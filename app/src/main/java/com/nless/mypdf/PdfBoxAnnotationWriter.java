package com.nless.mypdf;

import android.graphics.Color;
import android.graphics.PointF;
import android.util.Log;

import com.tom_roush.fontbox.ttf.CmapLookup;
import com.tom_roush.fontbox.ttf.OTFParser;
import com.tom_roush.fontbox.ttf.OpenTypeFont;
import com.tom_roush.fontbox.ttf.TTFParser;
import com.tom_roush.fontbox.ttf.TrueTypeCollection;
import com.tom_roush.fontbox.ttf.TrueTypeFont;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.PDPage;
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream;
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle;
import com.tom_roush.pdfbox.pdmodel.font.PDFont;
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font;
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font;
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;
import com.tom_roush.pdfbox.util.Matrix;

import java.io.File;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 将屏幕上的页内批注写入 PDF 内容流。涂鸦/图形为矢量路径，文本为真实文本。 */
final class PdfBoxAnnotationWriter {

    private PdfBoxAnnotationWriter() {}

    static void write(
            InputStream source,
            OutputStream target,
            List<PdfOverlayView.AnnotationAction> actions,
            List<PdfOverlayView.PageMetrics> pageMetrics
    ) throws Exception {
        if (actions == null || actions.isEmpty()) {
            copy(source, target);
            return;
        }
        if (pageMetrics == null || pageMetrics.isEmpty()) {
            throw new IllegalStateException("PDF 页面尺寸尚未初始化");
        }

        try (PDDocument document = PDDocument.load(source);
             SystemFontResolver fontResolver = new SystemFontResolver(document)) {
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
                                  SystemFontResolver fontResolver) throws Exception {
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

    /**
     * Android 设备上的中日韩字体通常位于系统字体目录，并且经常以 TTC 字体集合存在。
     * 旧实现只尝试少数 OTF/TTF 固定路径，找不到时退回 Helvetica，并把非拉丁字符替换成 '?'.
     * 这里会扫描系统字体，验证实际字形覆盖后再嵌入 PDF；若设备没有可嵌入字体，则明确失败，
     * 避免生成看似成功但文本已经永久变成问号的文件。
     */
    private static final class SystemFontResolver implements Closeable {
        private static final String TAG = "PdfAnnotationFont";
        private static final List<String> FONT_DIRS = Arrays.asList(
                "/system/fonts",
                "/system_ext/fonts",
                "/product/fonts",
                "/system/product/fonts",
                "/vendor/fonts"
        );
        private static final List<String> PRIORITY_FONT_NAMES = Arrays.asList(
                "NotoSansCJK-Regular.ttc",
                "NotoSansCJKsc-Regular.otf",
                "NotoSansCJKkr-Regular.otf",
                "NotoSansCJKjp-Regular.otf",
                "NotoSansSC-Regular.otf",
                "NotoSansKR-Regular.otf",
                "NotoSansJP-Regular.otf",
                "DroidSansFallback.ttf",
                "DroidSansChinese.ttf",
                "HwChinese-medium.ttf"
        );

        private final PDDocument document;
        private final Map<String, ResolvedFont> cache = new HashMap<>();
        private final List<Closeable> openFontResources = new ArrayList<>();

        SystemFontResolver(PDDocument document) {
            this.document = document;
        }

        PDFont resolve(String text, boolean bold) throws IOException {
            if (isStandardAscii(text)) {
                return bold ? PDType1Font.HELVETICA_BOLD : PDType1Font.HELVETICA;
            }

            String script = detectScript(text);
            String key = script + (bold ? "-bold" : "-regular");
            ResolvedFont cached = cache.get(key);
            if (cached != null && supports(cached.trueTypeFont, text)) {
                return cached.pdfFont;
            }

            IOException lastError = null;
            for (File fontFile : findCandidateFonts(script, bold)) {
                try {
                    ResolvedFont font = loadMatchingFont(fontFile, text);
                    if (font != null) {
                        cache.put(key, font);
                        Log.i(TAG, "PDF 批注字体: " + fontFile.getAbsolutePath() + " -> " + font.pdfFont.getName());
                        return font.pdfFont;
                    }
                } catch (IOException e) {
                    lastError = e;
                    Log.w(TAG, "无法使用系统字体: " + fontFile.getAbsolutePath(), e);
                }
            }

            String message = "设备中未找到可嵌入且支持当前文本的系统字体，已取消保存，避免文字变成问号。"
                    + " 文本类型=" + script;
            if (lastError != null) throw new IOException(message, lastError);
            throw new IOException(message);
        }

        private ResolvedFont loadMatchingFont(File file, String text) throws IOException {
            String lower = file.getName().toLowerCase(Locale.ROOT);
            if (lower.endsWith(".ttc")) {
                return loadFromCollection(file, text);
            }
            if (!lower.endsWith(".ttf") && !lower.endsWith(".otf")) return null;

            TrueTypeFont ttf = null;
            try {
                if (lower.endsWith(".otf")) {
                    OpenTypeFont otf = new OTFParser().parse(file);
                    if (otf.isPostScript()) {
                        otf.close();
                        return null;
                    }
                    ttf = otf;
                } else {
                    ttf = new TTFParser().parse(file);
                }
                if (!isEmbeddableTrueType(ttf) || !supports(ttf, text)) {
                    ttf.close();
                    return null;
                }
                PDFont font = PDType0Font.load(document, ttf, true);
                openFontResources.add(ttf);
                return new ResolvedFont(font, ttf);
            } catch (Exception e) {
                if (ttf != null) {
                    try { ttf.close(); } catch (IOException ignore) {}
                }
                if (e instanceof IOException) throw (IOException) e;
                throw new IOException("字体载入失败: " + file.getAbsolutePath(), e);
            }
        }

        private ResolvedFont loadFromCollection(File file, String text) throws IOException {
            final TrueTypeCollection collection = new TrueTypeCollection(file);
            final TrueTypeFont[] selected = new TrueTypeFont[1];
            try {
                try {
                    collection.processAllFonts(ttf -> {
                        if (isEmbeddableTrueType(ttf) && supports(ttf, text)) {
                            selected[0] = ttf;
                            throw FontMatched.INSTANCE;
                        }
                    });
                } catch (FontMatched matched) {
                    // 用异常提前结束 TTC 遍历，避免解析集合中的所有字体。
                }

                if (selected[0] == null) {
                    collection.close();
                    return null;
                }

                PDFont font = PDType0Font.load(document, selected[0], true);
                // PDType0Font 在 document.save() 时才执行子集化，因此 TTC 必须保持打开。
                openFontResources.add(collection);
                return new ResolvedFont(font, selected[0]);
            } catch (Exception e) {
                try { collection.close(); } catch (IOException ignore) {}
                if (e instanceof IOException) throw (IOException) e;
                throw new IOException("TTC 字体载入失败: " + file.getAbsolutePath(), e);
            }
        }

        private static boolean isEmbeddableTrueType(TrueTypeFont font) {
            return !(font instanceof OpenTypeFont) || !((OpenTypeFont) font).isPostScript();
        }

        private static boolean supports(TrueTypeFont font, String text) {
            try {
                CmapLookup cmap = font.getUnicodeCmapLookup(false);
                if (cmap == null) return false;
                for (int offset = 0; offset < text.length();) {
                    int cp = text.codePointAt(offset);
                    offset += Character.charCount(cp);
                    if (shouldIgnoreForGlyphCheck(cp)) continue;
                    if (cmap.getGlyphId(cp) == 0) return false;
                }
                return true;
            } catch (IOException e) {
                return false;
            }
        }

        private static boolean shouldIgnoreForGlyphCheck(int cp) {
            return cp == '\n' || cp == '\r' || cp == '\t'
                    || Character.isWhitespace(cp)
                    || Character.getType(cp) == Character.FORMAT;
        }

        private static boolean isStandardAscii(String text) {
            for (int offset = 0; offset < text.length();) {
                int cp = text.codePointAt(offset);
                offset += Character.charCount(cp);
                if (shouldIgnoreForGlyphCheck(cp)) continue;
                if (cp < 0x20 || cp > 0x7E) return false;
            }
            return true;
        }

        private static String detectScript(String text) {
            boolean han = false, hangul = false, kana = false;
            for (int offset = 0; offset < text.length();) {
                int cp = text.codePointAt(offset);
                offset += Character.charCount(cp);
                Character.UnicodeScript script = Character.UnicodeScript.of(cp);
                if (script == Character.UnicodeScript.HAN) han = true;
                else if (script == Character.UnicodeScript.HANGUL) hangul = true;
                else if (script == Character.UnicodeScript.HIRAGANA
                        || script == Character.UnicodeScript.KATAKANA) kana = true;
            }
            if (hangul) return "korean";
            if (kana) return "japanese";
            if (han) return "chinese";
            return "unicode";
        }

        private static List<File> findCandidateFonts(String script, boolean bold) {
            Set<File> unique = new LinkedHashSet<>();

            for (String dirPath : FONT_DIRS) {
                File dir = new File(dirPath);
                for (String name : PRIORITY_FONT_NAMES) {
                    File file = new File(dir, name);
                    if (file.isFile() && file.canRead()) unique.add(file);
                }
            }

            List<File> discovered = new ArrayList<>();
            for (String dirPath : FONT_DIRS) {
                File dir = new File(dirPath);
                File[] files = dir.listFiles(file -> {
                    if (file == null || !file.isFile() || !file.canRead()) return false;
                    String name = file.getName().toLowerCase(Locale.ROOT);
                    return name.endsWith(".ttf") || name.endsWith(".ttc") || name.endsWith(".otf");
                });
                if (files != null) discovered.addAll(Arrays.asList(files));
            }

            discovered.sort(Comparator
                    .comparingInt((File file) -> fontScore(file.getName(), script, bold))
                    .reversed()
                    .thenComparing(File::getAbsolutePath));
            unique.addAll(discovered);
            return new ArrayList<>(unique);
        }

        private static int fontScore(String fileName, String script, boolean bold) {
            String name = fileName.toLowerCase(Locale.ROOT);
            if (name.contains("emoji") || name.contains("symbol") || name.contains("math")) return -1000;

            int score = 0;
            if (name.contains("cjk")) score += 100;
            if (name.contains("fallback")) score += 90;
            if (name.contains("sans")) score += 20;
            if (name.contains("regular")) score += 15;
            if (bold && name.contains("bold")) score += 20;
            if (!bold && name.contains("bold")) score -= 10;

            if ("chinese".equals(script)) {
                if (name.contains("sc") || name.contains("hans") || name.contains("chinese") || name.contains("zh")) score += 80;
            } else if ("korean".equals(script)) {
                if (name.contains("kr") || name.contains("korean") || name.contains("ko")) score += 80;
            } else if ("japanese".equals(script)) {
                if (name.contains("jp") || name.contains("japanese") || name.contains("ja")) score += 80;
            }
            return score;
        }


        private static final class ResolvedFont {
            final PDFont pdfFont;
            final TrueTypeFont trueTypeFont;

            ResolvedFont(PDFont pdfFont, TrueTypeFont trueTypeFont) {
                this.pdfFont = pdfFont;
                this.trueTypeFont = trueTypeFont;
            }
        }

        @Override
        public void close() {
            for (int i = openFontResources.size() - 1; i >= 0; i--) {
                try { openFontResources.get(i).close(); }
                catch (Exception e) { Log.w(TAG, "关闭字体资源失败", e); }
            }
            openFontResources.clear();
        }
    }

    private static final class FontMatched extends IOException {
        static final FontMatched INSTANCE = new FontMatched();
        private FontMatched() { super("font matched"); }
        @Override public synchronized Throwable fillInStackTrace() { return this; }
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
