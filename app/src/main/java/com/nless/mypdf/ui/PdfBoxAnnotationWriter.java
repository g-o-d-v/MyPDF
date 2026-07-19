package com.nless.mypdf.ui;


import com.nless.mypdf.core.PdfSecurityContext;
import com.nless.mypdf.core.PdfSystemFontResolver;
import android.content.Context;
import android.graphics.Color;
import android.graphics.PointF;
import android.net.Uri;

import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.PDPage;
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream;
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle;
import com.tom_roush.pdfbox.pdmodel.font.PDFont;
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission;
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import com.tom_roush.pdfbox.util.Matrix;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

/** 将屏幕上的页内批注写入 PDF 内容流。涂鸦/图形为矢量路径，文本为真实文本。 */
final class PdfBoxAnnotationWriter {

    private PdfBoxAnnotationWriter() {}

    static void write(
            Context context,
            Uri sourceUri,
            String fallbackPath,
            OutputStream target,
            List<PdfOverlayView.AnnotationAction> actions,
            List<PdfOverlayView.PageMetrics> pageMetrics,
            String openPassword,
            String managementPassword,
            PdfSecurityContext.Info securityInfo
    ) throws Exception {
        if (actions == null || actions.isEmpty()) {
            try (InputStream source = PdfSecurityContext.openInput(context, sourceUri, fallbackPath)) {
                copy(source, target);
            }
            return;
        }
        if (pageMetrics == null || pageMetrics.isEmpty()) {
            throw new IllegalStateException("PDF 页面尺寸尚未初始化");
        }

        String effectiveOpenPassword = openPassword == null ? "" : openPassword;
        String effectiveManagementPassword = managementPassword == null ? "" : managementPassword;
        boolean restricted = securityInfo != null && securityInfo.encrypted && securityInfo.hasRestrictions();
        String loadPassword = restricted ? effectiveManagementPassword : effectiveOpenPassword;
        if (restricted && effectiveManagementPassword.isEmpty()) {
            throw new IllegalStateException("需要权限管理密码才能保存批注并保留原有权限");
        }

        try (PDDocument document = PdfSecurityContext.loadDocument(
                context,
                sourceUri,
                fallbackPath,
                loadPassword,
                "pdfbox-annotation-write"
        );
             PdfSystemFontResolver fontResolver = new PdfSystemFontResolver(document, "PdfAnnotationFont")) {
            boolean encrypted = document.isEncrypted();
            AccessPermission declaredPermission = encrypted && document.getEncryption() != null
                    ? new AccessPermission(document.getEncryption().getPermissions())
                    : AccessPermission.getOwnerAccessPermission();
            // 始终按加密字典中声明的“批注权限”判断，而不是按当前密码身份
            // 或“允许修改内容”兜底。这样禁止批注时，即使使用所有者密码打开，
            // MyPDF 也不会绕过用户设置。
            if (encrypted && !declaredPermission.canModifyAnnotations()) {
                throw new IllegalStateException("文档权限禁止添加或修改批注");
            }

            for (PdfOverlayView.AnnotationAction action : actions) {
                if (action.pageIndex < 0 || action.pageIndex >= document.getNumberOfPages()) continue;
                if (action.pageIndex >= pageMetrics.size()) continue;

                PdfOverlayView.PageMetrics shown = pageMetrics.get(action.pageIndex);
                if (shown.width <= 0 || shown.height <= 0) continue;

                PDPage page = document.getPage(action.pageIndex);
                PDRectangle media = page.getCropBox() != null ? page.getCropBox() : page.getMediaBox();
                CoordinateMap map = new CoordinateMap(shown.width, shown.height, media.getWidth(), media.getHeight());

                try (PDPageContentStream out = new PDPageContentStream(
                        document, page, PDPageContentStream.AppendMode.APPEND, true, true)) {
                    switch (action.category) {
                        case FREEHAND:
                            writeFreehand(document, out, action, map);
                            break;
                        case SHAPE:
                            writeShape(document, out, action, map);
                            break;
                        case TEXT:
                            writeText(out, action, map, fontResolver);
                            break;
                    }
                }
            }
            if (encrypted) {
                String ownerPassword = restricted
                        ? effectiveManagementPassword
                        : effectiveOpenPassword;
                StandardProtectionPolicy policy = new StandardProtectionPolicy(
                        ownerPassword,
                        effectiveOpenPassword,
                        declaredPermission
                );
                policy.setPermissions(declaredPermission);
                policy.setEncryptionKeyLength(128);
                policy.setPreferAES(true);
                if (document.getVersion() < 1.6f) document.setVersion(1.6f);
                document.protect(policy);
            }
            // 子集字体必须在 document.save() 时仍保持底层字体文件打开。
            document.save(target);
        }
    }

    private static void writeFreehand(PDDocument doc, PDPageContentStream out,
                                      PdfOverlayView.AnnotationAction action, CoordinateMap map) throws Exception {
        if (action.points == null || action.points.size() < 2) return;
        applyStroke(doc, out, action, map);
        PointF first = action.points.get(0);
        out.moveTo(map.x(first.x), map.y(first.y));
        for (int i = 1; i < action.points.size(); i++) {
            PointF p = action.points.get(i);
            out.lineTo(map.x(p.x), map.y(p.y));
        }
        out.stroke();
    }

    private static void writeShape(PDDocument doc, PDPageContentStream out,
                                   PdfOverlayView.AnnotationAction action, CoordinateMap map) throws Exception {
        applyStroke(doc, out, action, map);
        switch (action.toolType) {
            case ARROW_ONE:
                writeArrow(out, action, map, false);
                break;
            case ARROW_TWO:
                writeArrow(out, action, map, true);
                break;
            case RECT_OUTLINE:
            case RECT_FILL: {
                float left = map.x(Math.min(action.startX, action.endX));
                float bottom = map.y(Math.max(action.startY, action.endY));
                float width = Math.abs(map.x(action.endX) - map.x(action.startX));
                float height = Math.abs(map.y(action.endY) - map.y(action.startY));
                out.addRect(left, bottom, width, height);
                if (action.toolType == PdfOverlayView.ToolType.RECT_FILL) {
                    setNonStrokingColor(out, action.color);
                    out.fill();
                } else {
                    out.stroke();
                }
                break;
            }
            case CIRCLE: {
                float cx = map.x((action.startX + action.endX) / 2f);
                float cy = map.y((action.startY + action.endY) / 2f);
                float rx = Math.abs(map.x(action.endX) - map.x(action.startX)) / 2f;
                float ry = Math.abs(map.y(action.endY) - map.y(action.startY)) / 2f;
                addEllipse(out, cx, cy, rx, ry);
                out.stroke();
                break;
            }
            default:
                break;
        }
    }

    private static void writeText(PDPageContentStream out,
                                  PdfOverlayView.AnnotationAction action, CoordinateMap map,
                                  PdfSystemFontResolver fontResolver) throws Exception {
        if (action.text == null || action.text.isEmpty()) return;
        PDFont font = fontResolver.resolve(action.text, action.bold);
        float size = Math.max(4f, action.textSize * map.scaleY);
        float lineHeight = size * 1.25f;
        float x = map.x(action.x);
        float y = map.y(action.y);
        setNonStrokingColor(out, action.color);
        setStrokingColor(out, action.color);

        String[] lines = action.text.split("\\R", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            float baseline = y - i * lineHeight;
            out.beginText();
            out.setFont(font, size);
            Matrix matrix = action.italic
                    ? new Matrix(1f, 0f, 0.22f, 1f, x, baseline)
                    : Matrix.getTranslateInstance(x, baseline);
            out.setTextMatrix(matrix);
            out.showText(line);
            out.endText();

            if (action.underline && !line.isEmpty()) {
                float width;
                try { width = font.getStringWidth(line) / 1000f * size; }
                catch (Exception ignore) { width = line.length() * size * 0.55f; }
                out.setLineWidth(Math.max(0.5f, size / 18f));
                out.moveTo(x, baseline - size * 0.12f);
                out.lineTo(x + width, baseline - size * 0.12f);
                out.stroke();
            }
        }
    }

    private static void applyStroke(PDDocument doc, PDPageContentStream out,
                                    PdfOverlayView.AnnotationAction action, CoordinateMap map) throws Exception {
        setStrokingColor(out, action.color);
        out.setLineWidth(Math.max(0.5f, action.strokeWidth * map.averageScale));
        out.setLineCapStyle(1);
        out.setLineJoinStyle(1);
        int alpha = Color.alpha(action.color);
        if (alpha < 255 || action.toolType == PdfOverlayView.ToolType.HIGHLIGHTER) {
            float opacity = action.toolType == PdfOverlayView.ToolType.HIGHLIGHTER
                    ? Math.min(0.35f, alpha / 255f) : alpha / 255f;
            PDExtendedGraphicsState gs = new PDExtendedGraphicsState();
            gs.setStrokingAlphaConstant(opacity);
            gs.setNonStrokingAlphaConstant(opacity);
            out.setGraphicsStateParameters(gs);
        }
    }

    private static void writeArrow(PDPageContentStream out, PdfOverlayView.AnnotationAction action,
                                   CoordinateMap map, boolean both) throws Exception {
        float x1 = map.x(action.startX), y1 = map.y(action.startY);
        float x2 = map.x(action.endX), y2 = map.y(action.endY);
        out.moveTo(x1, y1); out.lineTo(x2, y2);
        arrowHead(out, x1, y1, x2, y2, Math.max(7f, action.strokeWidth * map.averageScale * 3.5f));
        if (both) arrowHead(out, x2, y2, x1, y1, Math.max(7f, action.strokeWidth * map.averageScale * 3.5f));
        out.stroke();
    }

    private static void arrowHead(PDPageContentStream out, float x1, float y1, float x2, float y2, float len) throws Exception {
        double angle = Math.atan2(y2 - y1, x2 - x1);
        double spread = Math.PI / 6d;
        out.moveTo(x2, y2);
        out.lineTo((float)(x2 - len * Math.cos(angle - spread)), (float)(y2 - len * Math.sin(angle - spread)));
        out.moveTo(x2, y2);
        out.lineTo((float)(x2 - len * Math.cos(angle + spread)), (float)(y2 - len * Math.sin(angle + spread)));
    }

    private static void addEllipse(PDPageContentStream out, float cx, float cy, float rx, float ry) throws Exception {
        float k = 0.55228475f;
        out.moveTo(cx + rx, cy);
        out.curveTo(cx + rx, cy + k * ry, cx + k * rx, cy + ry, cx, cy + ry);
        out.curveTo(cx - k * rx, cy + ry, cx - rx, cy + k * ry, cx - rx, cy);
        out.curveTo(cx - rx, cy - k * ry, cx - k * rx, cy - ry, cx, cy - ry);
        out.curveTo(cx + k * rx, cy - ry, cx + rx, cy - k * ry, cx + rx, cy);
        out.closePath();
    }

    private static void setStrokingColor(PDPageContentStream out, int color) throws Exception {
        out.setStrokingColor(Color.red(color), Color.green(color), Color.blue(color));
    }

    private static void setNonStrokingColor(PDPageContentStream out, int color) throws Exception {
        out.setNonStrokingColor(Color.red(color), Color.green(color), Color.blue(color));
    }

    private static void copy(InputStream in, OutputStream out) throws Exception {
        byte[] buffer = new byte[64 * 1024];
        int read;
        while ((read = in.read(buffer)) >= 0) out.write(buffer, 0, read);
    }

    private static final class CoordinateMap {
        final float scaleX, scaleY, averageScale, pageHeight;
        CoordinateMap(float displayW, float displayH, float pdfW, float pdfH) {
            scaleX = pdfW / displayW;
            scaleY = pdfH / displayH;
            averageScale = (scaleX + scaleY) / 2f;
            pageHeight = pdfH;
        }
        float x(float localX) { return localX * scaleX; }
        float y(float localY) { return pageHeight - localY * scaleY; }
    }
}
