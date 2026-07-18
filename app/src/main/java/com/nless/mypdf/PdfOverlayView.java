package com.nless.mypdf;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import com.github.barteksc.pdfviewer.PDFView;
import com.shockwave.pdfium.util.SizeF;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Stack;

/**
 * PDF 批注覆盖层。
 *
 * <p>批注以“页内坐标”保存，而不是保存成一张整页位图。这样导出时可以把：
 * <ul>
 *     <li>涂鸦、箭头、矩形、圆形写成 PDF 矢量图形；</li>
 *     <li>文本批注写成真正的 PDF 文本内容。</li>
 * </ul>
 * 页面缩放、滚动只影响预览，不会改变已经记录的页内坐标。</p>
 */
public class PdfOverlayView extends View {

    public enum Mode { DRAG, DOODLE, TEXT }

    public enum ToolType {
        PENCIL,
        HIGHLIGHTER,
        ARROW_ONE,
        ARROW_TWO,
        RECT_OUTLINE,
        RECT_FILL,
        CIRCLE
    }

    public enum ActionCategory { FREEHAND, SHAPE, TEXT }

    private Mode currentMode = Mode.DRAG;
    private ToolType currentTool = ToolType.PENCIL;

    private int currentColor = 0xFF000000;
    private float currentStrokeWidth = 5f;

    private final Stack<AnnotationAction> actionStack = new Stack<>();

    private boolean isDrawing = false;
    private int activePageIndex = -1;
    private final List<PointF> activePoints = new ArrayList<>();
    private float activeStartX;
    private float activeStartY;
    private float activeCurrentX;
    private float activeCurrentY;
    private float activeStrokeWidth;

    private PDFView pdfView;

    private final List<SearchHighlight> searchHighlights = new ArrayList<>();
    private final Paint searchPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint currentSearchPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int currentSearchIndex = -1;

    private final Paint textSelectionPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textSelectionHandlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textSelectionHandleStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private PdfTextPage selectedTextPage;
    private int textSelectionStart = -1;
    private int textSelectionEnd = -1;
    // 两个端点独立保存。显示和复制时再取 min/max，避免某个手柄越过另一端时
    // 被 Math.min/Math.max 直接压缩到单个字形。
    private int textSelectionAnchorA = -1;
    private int textSelectionAnchorB = -1;
    private SelectionHandle activeSelectionHandle = SelectionHandle.NONE;
    private SelectionSide activeSelectionSide = SelectionSide.NONE;
    private float selectionDragOffsetX;
    private float selectionDragOffsetY;
    private float selectionHandleTouchOffsetX;
    private float selectionHandleTouchOffsetY;
    private float activeSelectionHandleCenterViewX = Float.NaN;
    private float activeSelectionHandleCenterViewY = Float.NaN;
    private final float selectionHandleRadiusPx;
    private final float selectionHandleTouchRadiusPx;
    private final float selectionHandleOutwardOffsetPx;

    // AndroidPdfViewer 把所有页面排成一条长画布。几百页文档中不能在每次绘制时
    // 重复遍历全部页面，因此缓存每一页在这条画布中的位置。
    private List<PageGeometry> pageGeometryCache = Collections.emptyList();
    private int cachedPageCount = -1;
    private float cachedSpacingPx = Float.NaN;

    public interface OnTextRequestListener {
        void onRequestText(int pageIndex, float pageX, float pageY);
    }

    private OnTextRequestListener textRequestListener;

    public interface OnTextSelectionChangedListener {
        void onTextSelectionChanged(boolean active, String selectedText);
    }

    private OnTextSelectionChangedListener textSelectionChangedListener;

    public PdfOverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
        searchPaint.setColor(0x66FFEB3B);
        searchPaint.setStyle(Paint.Style.FILL);
        currentSearchPaint.setColor(0x99FF9800);
        currentSearchPaint.setStyle(Paint.Style.FILL);

        textSelectionPaint.setColor(0x6633A1FD);
        textSelectionPaint.setStyle(Paint.Style.FILL);

        textSelectionHandlePaint.setColor(0xFF1976D2);
        textSelectionHandlePaint.setStyle(Paint.Style.FILL);

        textSelectionHandleStrokePaint.setColor(0xFFFFFFFF);
        textSelectionHandleStrokePaint.setStyle(Paint.Style.STROKE);
        textSelectionHandleStrokePaint.setStrokeWidth(dp(1.5f));

        selectionHandleRadiusPx = dp(9f);
        selectionHandleTouchRadiusPx = dp(28f);
        // 两个手柄始终分别向选区外侧偏移，而不是只在“距离不足”时临时展开。
        // 这样选区从 1 个字扩展到几个字时，手柄不会在阈值处突然吸回文字边缘。
        selectionHandleOutwardOffsetPx = dp(28f);
    }

    public void setSearchHighlights(List<SearchHighlight> highlights) {
        searchHighlights.clear();
        if (highlights != null) searchHighlights.addAll(highlights);
        currentSearchIndex = searchHighlights.isEmpty() ? -1 : 0;
        invalidate();
    }

    public void clearSearchHighlights() {
        searchHighlights.clear();
        currentSearchIndex = -1;
        invalidate();
    }

    public void setCurrentSearchIndex(int index) {
        if (index < 0) {
            currentSearchIndex = -1;
        } else {
            boolean exists = false;
            for (SearchHighlight highlight : searchHighlights) {
                if (highlight != null && highlight.matchIndex == index) {
                    exists = true;
                    break;
                }
            }
            currentSearchIndex = exists ? index : -1;
        }
        invalidate();
    }

    /**
     * 返回某个逻辑搜索结果在整份 PDF 长画布中的包围框（未乘当前 zoom）。
     * 跨行结果可能包含多个矩形，这里返回它们的并集，供阅读器精确居中定位。
     */
    public boolean getSearchMatchBoundsInDocument(int matchIndex, RectF outRect) {
        if (outRect == null || matchIndex < 0) return false;
        boolean found = false;
        RectF temp = new RectF();
        for (SearchHighlight highlight : searchHighlights) {
            if (highlight == null || highlight.matchIndex != matchIndex) continue;
            if (!resolveSearchHighlightRect(highlight, temp)) continue;
            if (!found) {
                outRect.set(temp);
                found = true;
            } else {
                outRect.union(temp);
            }
        }
        return found;
    }

    public void setPdfView(PDFView pdfView) {
        this.pdfView = pdfView;
        refreshPageGeometry();
    }

    /**
     * PDF 重新加载、页面尺寸变化或屏幕旋转后调用。
     */
    public void refreshPageGeometry() {
        pageGeometryCache = Collections.emptyList();
        cachedPageCount = -1;
        cachedSpacingPx = Float.NaN;
        invalidate();
    }

    public void clearActions() {
        actionStack.clear();
        cancelActiveDrawing();
        invalidate();
    }

    public boolean isActionStackEmpty() {
        return actionStack.isEmpty();
    }

    public int getActionCount() {
        return actionStack.size();
    }

    public List<AnnotationAction> getActionsSnapshot() {
        return new ArrayList<>(actionStack);
    }

    /**
     * 必须在 UI 线程、PDF 已加载后调用。
     */
    public List<PageMetrics> getPageMetricsSnapshot() {
        if (pdfView == null || pdfView.getPageCount() <= 0) {
            return Collections.emptyList();
        }

        List<PageMetrics> result = new ArrayList<>(pdfView.getPageCount());
        for (int i = 0; i < pdfView.getPageCount(); i++) {
            SizeF size = pdfView.getPageSize(i);
            result.add(new PageMetrics(size.getWidth(), size.getHeight()));
        }
        return result;
    }

    public void setMode(Mode mode) {
        currentMode = mode;
        if (mode != Mode.DOODLE) {
            cancelActiveDrawing();
        }
    }

    public void setDrawColor(int color) {
        currentColor = color;
    }

    public void setStrokeWidth(float width) {
        currentStrokeWidth = Math.max(1f, width);
    }

    public void setToolType(String toolName) {
        switch (toolName) {
            case "荧光笔":
                currentTool = ToolType.HIGHLIGHTER;
                break;
            case "单向箭头":
                currentTool = ToolType.ARROW_ONE;
                break;
            case "双向箭头":
                currentTool = ToolType.ARROW_TWO;
                break;
            case "矩形框":
                currentTool = ToolType.RECT_OUTLINE;
                break;
            case "实心矩形":
                currentTool = ToolType.RECT_FILL;
                break;
            case "圆形":
                currentTool = ToolType.CIRCLE;
                break;
            case "铅笔":
            default:
                currentTool = ToolType.PENCIL;
                break;
        }
    }

    public void setOnTextRequestListener(OnTextRequestListener listener) {
        textRequestListener = listener;
    }

    public void setOnTextSelectionChangedListener(OnTextSelectionChangedListener listener) {
        textSelectionChangedListener = listener;
    }

    public void undo() {
        if (!actionStack.isEmpty()) {
            actionStack.pop();
            invalidate();
        }
    }

    public void addTextAction(
            String text,
            int pageIndex,
            float pageX,
            float pageY,
            int color,
            int size,
            boolean isBold,
            boolean isItalic,
            boolean isUnderline
    ) {
        if (text == null || text.trim().isEmpty()) {
            return;
        }

        float displayTextSize = Math.max(8f, size * 1.5f);
        Paint textPaint = createTextPaint(color, displayTextSize, isBold, isItalic, isUnderline);

        String[] lines = text.split("\\R", -1);
        float maxWidth = 0f;
        for (String line : lines) {
            maxWidth = Math.max(maxWidth, textPaint.measureText(line));
        }
        float lineHeight = displayTextSize * 1.25f;
        RectF bounds = new RectF(
                pageX,
                pageY - displayTextSize,
                pageX + maxWidth,
                pageY + Math.max(0, lines.length - 1) * lineHeight + displayTextSize * 0.3f
        );

        actionStack.push(AnnotationAction.text(
                pageIndex,
                text,
                pageX,
                pageY,
                color,
                displayTextSize,
                isBold,
                isItalic,
                isUnderline,
                bounds
        ));
        invalidate();
    }

    /**
     * 将覆盖层坐标转换为 PDF 页内坐标。页内坐标与 PDFView 在 zoom=1 时的页面尺寸一致。
     */
    public PageHit locateViewPoint(float viewX, float viewY) {
        if (pdfView == null || pdfView.getZoom() <= 0f) return null;
        float zoom = pdfView.getZoom();
        float documentX = (viewX - pdfView.getCurrentXOffset()) / zoom;
        float documentY = (viewY - pdfView.getCurrentYOffset()) / zoom;
        PagePoint point = locatePagePoint(documentX, documentY);
        if (point == null) return null;
        return new PageHit(point.pageIndex, point.x, point.y);
    }

    /**
     * 在长按位置开始文本选择。初始选择会尽量扩展到当前英文/数字单词；
     * 中日韩文字按单个字形开始，之后可拖动手柄扩大范围。
     */
    public boolean beginTextSelection(PdfTextPage page, float pageX, float pageY) {
        if (page == null || page.isEmpty()) return false;
        PageGeometry geometry = getPageGeometry(page.pageIndex);
        if (geometry == null || geometry.width <= 0f || geometry.height <= 0f) return false;

        int hitIndex = findNearestGlyph(page, pageX, pageY, geometry, true);
        if (hitIndex < 0) return false;

        selectedTextPage = page;
        int[] wordRange = findInitialWordRange(page, hitIndex);
        setTextSelectionAnchors(wordRange[0], wordRange[1]);
        resetSelectionDragState();
        notifyTextSelectionChanged();
        invalidate();
        return true;
    }

    public boolean hasTextSelection() {
        return selectedTextPage != null
                && textSelectionAnchorA >= 0
                && textSelectionAnchorB >= 0
                && textSelectionStart >= 0
                && textSelectionEnd >= textSelectionStart
                && textSelectionEnd < selectedTextPage.glyphs.size();
    }

    public void clearTextSelection() {
        if (pdfView != null) pdfView.setSwipeEnabled(true);
        selectedTextPage = null;
        textSelectionStart = -1;
        textSelectionEnd = -1;
        textSelectionAnchorA = -1;
        textSelectionAnchorB = -1;
        resetSelectionDragState();
        notifyTextSelectionChanged();
        invalidate();
    }

    public void selectAllTextOnPage() {
        if (selectedTextPage == null || selectedTextPage.glyphs.isEmpty()) return;
        resetSelectionDragState();
        setTextSelectionAnchors(0, selectedTextPage.glyphs.size() - 1);
        notifyTextSelectionChanged();
        invalidate();
    }

    private void setTextSelectionAnchors(int anchorA, int anchorB) {
        textSelectionAnchorA = anchorA;
        textSelectionAnchorB = anchorB;
        textSelectionStart = Math.min(anchorA, anchorB);
        textSelectionEnd = Math.max(anchorA, anchorB);
    }

    private void resetSelectionDragState() {
        activeSelectionHandle = SelectionHandle.NONE;
        activeSelectionSide = SelectionSide.NONE;
        selectionDragOffsetX = 0f;
        selectionDragOffsetY = 0f;
        selectionHandleTouchOffsetX = 0f;
        selectionHandleTouchOffsetY = 0f;
        activeSelectionHandleCenterViewX = Float.NaN;
        activeSelectionHandleCenterViewY = Float.NaN;
    }

    public String getSelectedText() {
        if (!hasTextSelection()) return "";
        return PdfTextReflow.reflowSelection(
                selectedTextPage,
                textSelectionStart,
                textSelectionEnd
        );
    }

    /**
     * 返回当前选择区域在覆盖层 View 坐标中的外接矩形，供 Android 浮动复制菜单定位。
     */
    public boolean getTextSelectionBoundsInView(Rect outRect) {
        if (outRect == null || !hasTextSelection() || pdfView == null) return false;
        PageGeometry geometry = getPageGeometry(selectedTextPage.pageIndex);
        if (geometry == null) return false;

        RectF union = null;
        for (int i = textSelectionStart; i <= textSelectionEnd; i++) {
            RectF rect = toPageRect(selectedTextPage.glyphs.get(i).normalizedBounds, geometry);
            if (union == null) union = new RectF(rect);
            else union.union(rect);
        }
        if (union == null) return false;

        float zoom = Math.max(0.01f, pdfView.getZoom());
        float left = pdfView.getCurrentXOffset() + (geometry.left + union.left) * zoom;
        float top = pdfView.getCurrentYOffset() + (geometry.top + union.top) * zoom;
        float right = pdfView.getCurrentXOffset() + (geometry.left + union.right) * zoom;
        float bottom = pdfView.getCurrentYOffset() + (geometry.top + union.bottom) * zoom;

        int padding = Math.round(dp(6f));
        outRect.set(
                Math.round(left) - padding,
                Math.round(top) - padding,
                Math.round(right) + padding,
                Math.round(bottom) + padding
        );
        return true;
    }

    private void notifyTextSelectionChanged() {
        if (textSelectionChangedListener != null) {
            textSelectionChangedListener.onTextSelectionChanged(hasTextSelection(), getSelectedText());
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (pdfView == null || pdfView.getZoom() <= 0f) return false;

        // 普通阅读状态下覆盖层只接管两个文本选择手柄，其余手势继续交给 PDFView。
        if (currentMode == Mode.DRAG) {
            if (!hasTextSelection()) return false;

            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    activeSelectionHandle = findTouchedSelectionHandle(event.getX(), event.getY());
                    if (activeSelectionHandle == SelectionHandle.NONE) return false;

                    SelectionHandleInfo handleInfo = getSelectionHandleInfo(activeSelectionHandle);
                    if (handleInfo == null) {
                        resetSelectionDragState();
                        return false;
                    }
                    activeSelectionSide = handleInfo.side;

                    PointF logicalAnchor = getSelectionHandleAnchorInView(activeSelectionHandle);
                    PointF visualCenter = getSelectionHandleCenterInView(activeSelectionHandle);
                    if (logicalAnchor != null) {
                        // 手柄始终绘制在文字边界外侧。这里保存“手指到真实文字端点”的固定偏移，
                        // 后续选区扩大时不重新计算，防止端点在几个字宽度处突然跳回原位。
                        selectionDragOffsetX = logicalAnchor.x - event.getX();
                        selectionDragOffsetY = logicalAnchor.y - event.getY();
                    } else {
                        selectionDragOffsetX = 0f;
                        selectionDragOffsetY = 0f;
                    }
                    if (visualCenter != null) {
                        // 拖动期间视觉手柄跟随手指，而不是每一帧按新选区重新布局。
                        selectionHandleTouchOffsetX = visualCenter.x - event.getX();
                        selectionHandleTouchOffsetY = visualCenter.y - event.getY();
                        activeSelectionHandleCenterViewX = visualCenter.x;
                        activeSelectionHandleCenterViewY = visualCenter.y;
                    } else {
                        selectionHandleTouchOffsetX = 0f;
                        selectionHandleTouchOffsetY = 0f;
                        activeSelectionHandleCenterViewX = event.getX();
                        activeSelectionHandleCenterViewY = event.getY();
                    }

                    pdfView.stopFling();
                    pdfView.setSwipeEnabled(false);
                    getParent().requestDisallowInterceptTouchEvent(true);
                    invalidate();
                    return true;
                }

                case MotionEvent.ACTION_MOVE:
                    if (activeSelectionHandle == SelectionHandle.NONE) return false;
                    activeSelectionHandleCenterViewX = event.getX() + selectionHandleTouchOffsetX;
                    activeSelectionHandleCenterViewY = event.getY() + selectionHandleTouchOffsetY;
                    updateSelectionHandle(event.getX(), event.getY());
                    return true;

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (activeSelectionHandle == SelectionHandle.NONE) return false;
                    activeSelectionHandleCenterViewX = event.getX() + selectionHandleTouchOffsetX;
                    activeSelectionHandleCenterViewY = event.getY() + selectionHandleTouchOffsetY;
                    updateSelectionHandle(event.getX(), event.getY());
                    resetSelectionDragState();
                    pdfView.setSwipeEnabled(true);
                    getParent().requestDisallowInterceptTouchEvent(false);
                    invalidate();
                    return true;

                default:
                    return activeSelectionHandle != SelectionHandle.NONE;
            }
        }

        float zoom = pdfView.getZoom();
        float documentX = (event.getX() - pdfView.getCurrentXOffset()) / zoom;
        float documentY = (event.getY() - pdfView.getCurrentYOffset()) / zoom;

        if (currentMode == Mode.TEXT && event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            PagePoint point = locatePagePoint(documentX, documentY);
            if (point != null && textRequestListener != null) {
                textRequestListener.onRequestText(point.pageIndex, point.x, point.y);
            }
            return true;
        }

        if (currentMode != Mode.DOODLE) return true;

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                PagePoint point = locatePagePoint(documentX, documentY);
                if (point == null) return true;

                isDrawing = true;
                activePageIndex = point.pageIndex;
                activeStartX = point.x;
                activeStartY = point.y;
                activeCurrentX = point.x;
                activeCurrentY = point.y;
                activeStrokeWidth = Math.max(0.5f, currentStrokeWidth / zoom);
                activePoints.clear();
                activePoints.add(new PointF(point.x, point.y));
                invalidate();
                break;
            }

            case MotionEvent.ACTION_MOVE: {
                if (!isDrawing) break;
                PagePoint point = locatePointOnPage(activePageIndex, documentX, documentY, true);
                if (point == null) break;

                activeCurrentX = point.x;
                activeCurrentY = point.y;
                if (currentTool == ToolType.PENCIL || currentTool == ToolType.HIGHLIGHTER) {
                    PointF previous = activePoints.get(activePoints.size() - 1);
                    if (distance(previous.x, previous.y, point.x, point.y) >= 0.5f) {
                        activePoints.add(new PointF(point.x, point.y));
                    }
                }
                invalidate();
                break;
            }

            case MotionEvent.ACTION_UP: {
                if (!isDrawing) break;
                PagePoint point = locatePointOnPage(activePageIndex, documentX, documentY, true);
                if (point != null) {
                    activeCurrentX = point.x;
                    activeCurrentY = point.y;
                    if ((currentTool == ToolType.PENCIL || currentTool == ToolType.HIGHLIGHTER)
                            && distance(
                            activePoints.get(activePoints.size() - 1).x,
                            activePoints.get(activePoints.size() - 1).y,
                            point.x,
                            point.y
                    ) >= 0.5f) {
                        activePoints.add(new PointF(point.x, point.y));
                    }
                }
                commitActiveDrawing();
                cancelActiveDrawing();
                invalidate();
                break;
            }

            case MotionEvent.ACTION_CANCEL:
                cancelActiveDrawing();
                invalidate();
                break;

            default:
                break;
        }
        return true;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (pdfView == null || pdfView.getPageCount() <= 0) {
            return;
        }

        canvas.save();
        canvas.translate(pdfView.getCurrentXOffset(), pdfView.getCurrentYOffset());
        canvas.scale(pdfView.getZoom(), pdfView.getZoom());

        RectF resolvedSearchRect = new RectF();
        for (SearchHighlight highlight : searchHighlights) {
            if (!resolveSearchHighlightRect(highlight, resolvedSearchRect)) continue;
            canvas.drawRect(
                    resolvedSearchRect,
                    highlight.matchIndex == currentSearchIndex ? currentSearchPaint : searchPaint
            );
        }

        drawTextSelection(canvas);

        for (AnnotationAction action : actionStack) {
            drawAction(canvas, action);
        }

        if (isDrawing && activePageIndex >= 0) {
            AnnotationAction preview = createPreviewAction();
            if (preview != null) {
                drawAction(canvas, preview);
            }
        }

        canvas.restore();
    }

    private void drawTextSelection(Canvas canvas) {
        if (!hasTextSelection()) return;
        PageGeometry geometry = getPageGeometry(selectedTextPage.pageIndex);
        if (geometry == null) return;

        // 字形坐标是页内坐标，而当前 Canvas 已处于整份文档坐标系。
        // 先移动到页面左上角，保证高亮与实际文字位置一致。
        canvas.save();
        canvas.translate(geometry.left, geometry.top);

        RectF merged = null;
        int mergedLine = Integer.MIN_VALUE;
        for (int i = textSelectionStart; i <= textSelectionEnd; i++) {
            PdfTextPage.Glyph glyph = selectedTextPage.glyphs.get(i);
            RectF rect = toPageRect(glyph.normalizedBounds, geometry);
            if (merged == null) {
                merged = new RectF(rect);
                mergedLine = glyph.lineIndex;
                continue;
            }

            float allowedGap = Math.max(1.5f, Math.max(merged.height(), rect.height()) * 0.65f);
            if (glyph.lineIndex == mergedLine && rect.left <= merged.right + allowedGap) {
                merged.union(rect);
            } else {
                canvas.drawRect(merged, textSelectionPaint);
                merged.set(rect);
                mergedLine = glyph.lineIndex;
            }
        }
        if (merged != null) canvas.drawRect(merged, textSelectionPaint);

        SelectionHandleLayout handleLayout = buildSelectionHandleLayout(geometry);
        if (handleLayout != null) {
            drawSelectionHandle(canvas, handleLayout.anchorA);
            drawSelectionHandle(canvas, handleLayout.anchorB);
        }
        canvas.restore();
    }

    private void drawSelectionHandle(Canvas canvas, SelectionHandleInfo handle) {
        if (handle == null) return;
        float zoom = Math.max(0.01f, pdfView.getZoom());
        float radius = selectionHandleRadiusPx / zoom;
        Paint stemPaint = textSelectionHandlePaint;
        float oldStroke = stemPaint.getStrokeWidth();
        Paint.Style oldStyle = stemPaint.getStyle();
        stemPaint.setStyle(Paint.Style.STROKE);
        stemPaint.setStrokeWidth(Math.max(1f / zoom, radius * 0.35f));
        canvas.drawLine(handle.anchorX, handle.anchorY, handle.centerX, handle.centerY, stemPaint);
        stemPaint.setStyle(oldStyle);
        stemPaint.setStrokeWidth(oldStroke);

        canvas.drawCircle(handle.centerX, handle.centerY, radius, textSelectionHandlePaint);
        textSelectionHandleStrokePaint.setStrokeWidth(Math.max(1f / zoom, dp(1.5f) / zoom));
        canvas.drawCircle(handle.centerX, handle.centerY, radius, textSelectionHandleStrokePaint);
    }

    /**
     * 两个端点使用固定的向外偏移，不再在“选区宽度小于 56dp”时才临时展开。
     * 旧逻辑跨过阈值后会立刻恢复到文字边缘，看起来像系统把手柄拉回原位。
     */
    private SelectionHandleLayout buildSelectionHandleLayout(PageGeometry geometry) {
        if (!hasTextSelection() || geometry == null) return null;

        boolean anchorAIsStart = textSelectionAnchorA <= textSelectionAnchorB;
        SelectionHandleInfo anchorA = buildSelectionHandleInfo(
                geometry,
                SelectionHandle.ANCHOR_A,
                textSelectionAnchorA,
                anchorAIsStart ? SelectionSide.START : SelectionSide.END
        );
        SelectionHandleInfo anchorB = buildSelectionHandleInfo(
                geometry,
                SelectionHandle.ANCHOR_B,
                textSelectionAnchorB,
                anchorAIsStart ? SelectionSide.END : SelectionSide.START
        );
        if (anchorA == null || anchorB == null) return null;
        return new SelectionHandleLayout(anchorA, anchorB);
    }

    private SelectionHandleInfo buildSelectionHandleInfo(
            PageGeometry geometry,
            SelectionHandle handle,
            int glyphIndex,
            SelectionSide naturalSide
    ) {
        if (glyphIndex < 0 || glyphIndex >= selectedTextPage.glyphs.size()) return null;
        PdfTextPage.Glyph glyph = selectedTextPage.glyphs.get(glyphIndex);
        RectF rect = toPageRect(glyph.normalizedBounds, geometry);

        // 一个拖动手势内锁定起始侧，端点即使越过另一端也不会在手指下方突然换边。
        SelectionSide side = handle == activeSelectionHandle && activeSelectionSide != SelectionSide.NONE
                ? activeSelectionSide
                : naturalSide;

        float zoom = Math.max(0.01f, pdfView.getZoom());
        float radius = selectionHandleRadiusPx / zoom;
        float outward = selectionHandleOutwardOffsetPx / zoom;
        float stem = radius * 1.6f;

        float anchorX = side == SelectionSide.START ? rect.left : rect.right;
        float anchorY = rect.bottom;
        float centerX = anchorX + (side == SelectionSide.START ? -outward : outward);
        float centerY = anchorY + stem + radius * 0.75f;

        // 拖动期间手柄中心由手指位置驱动。选区变化只更新文字锚点和连接线，
        // 不会重新计算视觉中心并将它吸回文字边缘。
        if (handle == activeSelectionHandle
                && !Float.isNaN(activeSelectionHandleCenterViewX)
                && !Float.isNaN(activeSelectionHandleCenterViewY)) {
            centerX = (activeSelectionHandleCenterViewX - pdfView.getCurrentXOffset()) / zoom
                    - geometry.left;
            centerY = (activeSelectionHandleCenterViewY - pdfView.getCurrentYOffset()) / zoom
                    - geometry.top;
        }

        return new SelectionHandleInfo(handle, side, anchorX, anchorY, centerX, centerY);
    }

    private SelectionHandle findTouchedSelectionHandle(float viewX, float viewY) {
        if (!hasTextSelection()) return SelectionHandle.NONE;
        SelectionHandleLayout layout = buildSelectionHandleLayout(
                getPageGeometry(selectedTextPage.pageIndex)
        );
        if (layout == null) return SelectionHandle.NONE;

        PointF anchorA = toViewPoint(layout.anchorA.centerX, layout.anchorA.centerY);
        PointF anchorB = toViewPoint(layout.anchorB.centerX, layout.anchorB.centerY);
        if (anchorA == null || anchorB == null) return SelectionHandle.NONE;

        float distanceA = distance(viewX, viewY, anchorA.x, anchorA.y);
        float distanceB = distance(viewX, viewY, anchorB.x, anchorB.y);
        boolean insideA = distanceA <= selectionHandleTouchRadiusPx;
        boolean insideB = distanceB <= selectionHandleTouchRadiusPx;

        if (insideA && insideB) {
            float difference = Math.abs(distanceA - distanceB);
            if (difference > dp(1.5f)) {
                return distanceA < distanceB ? SelectionHandle.ANCHOR_A : SelectionHandle.ANCHOR_B;
            }
            // 极少数完全等距情况，按两个手柄中心距离触点的主方向分区。
            float dx = Math.abs(anchorA.x - anchorB.x);
            float dy = Math.abs(anchorA.y - anchorB.y);
            if (dx >= dy) {
                boolean aOnLeft = anchorA.x <= anchorB.x;
                boolean touchLeft = viewX <= (anchorA.x + anchorB.x) * 0.5f;
                return touchLeft == aOnLeft ? SelectionHandle.ANCHOR_A : SelectionHandle.ANCHOR_B;
            }
            boolean aOnTop = anchorA.y <= anchorB.y;
            boolean touchTop = viewY <= (anchorA.y + anchorB.y) * 0.5f;
            return touchTop == aOnTop ? SelectionHandle.ANCHOR_A : SelectionHandle.ANCHOR_B;
        }
        if (insideA) return SelectionHandle.ANCHOR_A;
        if (insideB) return SelectionHandle.ANCHOR_B;
        return SelectionHandle.NONE;
    }

    private SelectionHandleInfo getSelectionHandleInfo(SelectionHandle handle) {
        if (!hasTextSelection() || handle == SelectionHandle.NONE) return null;
        PageGeometry geometry = getPageGeometry(selectedTextPage.pageIndex);
        if (geometry == null) return null;
        SelectionHandleLayout layout = buildSelectionHandleLayout(geometry);
        return layout == null ? null : layout.forHandle(handle);
    }

    private PointF getSelectionHandleCenterInView(SelectionHandle handle) {
        SelectionHandleInfo info = getSelectionHandleInfo(handle);
        return info == null ? null : toViewPoint(info.centerX, info.centerY);
    }

    private PointF getSelectionHandleAnchorInView(SelectionHandle handle) {
        SelectionHandleInfo info = getSelectionHandleInfo(handle);
        return info == null ? null : toViewPoint(info.anchorX, info.anchorY);
    }

    private PointF toViewPoint(float pageX, float pageY) {
        if (!hasTextSelection() || pdfView == null) return null;
        PageGeometry geometry = getPageGeometry(selectedTextPage.pageIndex);
        if (geometry == null) return null;
        float zoom = Math.max(0.01f, pdfView.getZoom());
        return new PointF(
                pdfView.getCurrentXOffset() + (geometry.left + pageX) * zoom,
                pdfView.getCurrentYOffset() + (geometry.top + pageY) * zoom
        );
    }

    private void updateSelectionHandle(float viewX, float viewY) {
        if (!hasTextSelection() || activeSelectionHandle == SelectionHandle.NONE) return;
        PageGeometry geometry = getPageGeometry(selectedTextPage.pageIndex);
        if (geometry == null) return;

        float zoom = pdfView.getZoom();
        float adjustedViewX = viewX + selectionDragOffsetX;
        float adjustedViewY = viewY + selectionDragOffsetY;
        float documentX = (adjustedViewX - pdfView.getCurrentXOffset()) / zoom;
        float documentY = (adjustedViewY - pdfView.getCurrentYOffset()) / zoom;
        PagePoint point = locatePointOnPage(
                selectedTextPage.pageIndex,
                documentX,
                documentY,
                true
        );
        if (point == null) return;

        int index = findNearestGlyph(selectedTextPage, point.x, point.y, geometry, false);
        if (index < 0) return;

        // 不再把 START 强制限制为 <= END。两个端点可独立移动并相互越过，
        // 选区始终由 min(anchorA, anchorB) 到 max(anchorA, anchorB) 构成。
        // 这样调整左端点时不会突然坍缩成最右侧的一个字。
        if (activeSelectionHandle == SelectionHandle.ANCHOR_A) {
            setTextSelectionAnchors(index, textSelectionAnchorB);
        } else {
            setTextSelectionAnchors(textSelectionAnchorA, index);
        }
        notifyTextSelectionChanged();
        invalidate();
    }

    private int findNearestGlyph(
            PdfTextPage page,
            float pageX,
            float pageY,
            PageGeometry geometry,
            boolean requireReasonableDistance
    ) {
        float bestDistance = Float.MAX_VALUE;
        int bestIndex = -1;
        float typicalHeight = 0f;

        for (int i = 0; i < page.glyphs.size(); i++) {
            RectF rect = toPageRect(page.glyphs.get(i).normalizedBounds, geometry);
            typicalHeight = Math.max(typicalHeight, Math.min(rect.height(), geometry.height * 0.08f));
            float dx = pageX < rect.left ? rect.left - pageX : (pageX > rect.right ? pageX - rect.right : 0f);
            float dy = pageY < rect.top ? rect.top - pageY : (pageY > rect.bottom ? pageY - rect.bottom : 0f);
            float distance = (float) Math.sqrt(dx * dx + dy * dy);
            if (distance < bestDistance) {
                bestDistance = distance;
                bestIndex = i;
                if (distance == 0f) break;
            }
        }

        if (bestIndex < 0) return -1;
        if (requireReasonableDistance) {
            float maxDistance = Math.max(dp(22f) / Math.max(0.01f, pdfView.getZoom()), typicalHeight * 1.2f);
            if (bestDistance > maxDistance) return -1;
        }
        return bestIndex;
    }

    private int[] findInitialWordRange(PdfTextPage page, int hitIndex) {
        int start = hitIndex;
        int end = hitIndex;
        PdfTextPage.Glyph hit = page.glyphs.get(hitIndex);
        if (!isLatinWordGlyph(hit.text)) return new int[]{start, end};

        while (start > 0) {
            PdfTextPage.Glyph current = page.glyphs.get(start);
            PdfTextPage.Glyph previous = page.glyphs.get(start - 1);
            if (previous.lineIndex != current.lineIndex
                    || containsBoundary(current.separatorBefore)
                    || !isLatinWordGlyph(previous.text)) {
                break;
            }
            start--;
        }
        while (end + 1 < page.glyphs.size()) {
            PdfTextPage.Glyph next = page.glyphs.get(end + 1);
            PdfTextPage.Glyph current = page.glyphs.get(end);
            if (next.lineIndex != current.lineIndex
                    || containsBoundary(next.separatorBefore)
                    || !isLatinWordGlyph(next.text)) {
                break;
            }
            end++;
        }
        return new int[]{start, end};
    }

    private static boolean isLatinWordGlyph(String text) {
        if (text == null || text.isEmpty()) return false;
        for (int offset = 0; offset < text.length(); ) {
            int codePoint = text.codePointAt(offset);
            boolean latinLetterOrDigit = codePoint < 0x0250 && Character.isLetterOrDigit(codePoint);
            if (!(latinLetterOrDigit
                    || codePoint == '_'
                    || codePoint == '\''
                    || codePoint == 0x2019)) {
                return false;
            }
            offset += Character.charCount(codePoint);
        }
        return true;
    }

    private static boolean containsBoundary(String separator) {
        if (separator == null || separator.isEmpty()) return false;
        for (int i = 0; i < separator.length(); i++) {
            if (Character.isWhitespace(separator.charAt(i))) return true;
        }
        return false;
    }

    private static RectF toPageRect(RectF normalized, PageGeometry geometry) {
        return new RectF(
                normalized.left * geometry.width,
                normalized.top * geometry.height,
                normalized.right * geometry.width,
                normalized.bottom * geometry.height
        );
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    private void commitActiveDrawing() {
        if (activePageIndex < 0) {
            return;
        }

        int alpha = currentTool == ToolType.HIGHLIGHTER ? 110 : 255;
        if (currentTool == ToolType.PENCIL || currentTool == ToolType.HIGHLIGHTER) {
            if (activePoints.size() < 2) {
                return;
            }
            RectF bounds = boundsForPoints(activePoints, activeStrokeWidth);
            actionStack.push(AnnotationAction.freehand(
                    activePageIndex,
                    currentTool,
                    activePoints,
                    currentColor,
                    activeStrokeWidth,
                    alpha,
                    bounds
            ));
        } else {
            RectF bounds = new RectF(
                    Math.min(activeStartX, activeCurrentX),
                    Math.min(activeStartY, activeCurrentY),
                    Math.max(activeStartX, activeCurrentX),
                    Math.max(activeStartY, activeCurrentY)
            );
            bounds.inset(-activeStrokeWidth, -activeStrokeWidth);
            actionStack.push(AnnotationAction.shape(
                    activePageIndex,
                    currentTool,
                    activeStartX,
                    activeStartY,
                    activeCurrentX,
                    activeCurrentY,
                    currentColor,
                    activeStrokeWidth,
                    alpha,
                    bounds
            ));
        }
    }

    private AnnotationAction createPreviewAction() {
        int alpha = currentTool == ToolType.HIGHLIGHTER ? 110 : 255;
        if (currentTool == ToolType.PENCIL || currentTool == ToolType.HIGHLIGHTER) {
            if (activePoints.isEmpty()) {
                return null;
            }
            return AnnotationAction.freehand(
                    activePageIndex,
                    currentTool,
                    activePoints,
                    currentColor,
                    activeStrokeWidth,
                    alpha,
                    boundsForPoints(activePoints, activeStrokeWidth)
            );
        }
        return AnnotationAction.shape(
                activePageIndex,
                currentTool,
                activeStartX,
                activeStartY,
                activeCurrentX,
                activeCurrentY,
                currentColor,
                activeStrokeWidth,
                alpha,
                new RectF()
        );
    }

    private void drawAction(Canvas canvas, AnnotationAction action) {
        PageGeometry geometry = getPageGeometry(action.pageIndex);
        if (geometry == null) {
            return;
        }

        canvas.save();
        canvas.translate(geometry.left, geometry.top);
        // PDF 的每一页都是独立画布。预览也按页面边界裁剪，
        // 避免屏幕上看似跨页、保存后却被 PDF 页面截断。
        canvas.clipRect(0f, 0f, geometry.width, geometry.height);

        switch (action.category) {
            case FREEHAND:
                drawFreehand(canvas, action);
                break;
            case SHAPE:
                drawShape(canvas, action.toolType, action.startX, action.startY, action.endX, action.endY, createDrawPaint(action));
                break;
            case TEXT:
                drawText(canvas, action);
                break;
        }

        canvas.restore();
    }

    private void drawFreehand(Canvas canvas, AnnotationAction action) {
        if (action.points == null || action.points.isEmpty()) {
            return;
        }

        Path path = new Path();
        PointF first = action.points.get(0);
        path.moveTo(first.x, first.y);
        for (int i = 1; i < action.points.size(); i++) {
            PointF point = action.points.get(i);
            path.lineTo(point.x, point.y);
        }
        canvas.drawPath(path, createDrawPaint(action));
    }

    private void drawText(Canvas canvas, AnnotationAction action) {
        Paint paint = createTextPaint(
                action.color,
                action.textSize,
                action.bold,
                action.italic,
                action.underline
        );
        float lineHeight = action.textSize * 1.25f;
        String[] lines = action.text.split("\\R", -1);
        for (int i = 0; i < lines.length; i++) {
            canvas.drawText(lines[i], action.x, action.y + i * lineHeight, paint);
        }
    }

    private Paint createDrawPaint(AnnotationAction action) {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(action.color);
        paint.setAlpha(action.alpha);
        paint.setStrokeWidth(action.strokeWidth);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStyle(action.toolType == ToolType.RECT_FILL ? Paint.Style.FILL : Paint.Style.STROKE);
        return paint;
    }

    private Paint createTextPaint(int color, float size, boolean bold, boolean italic, boolean underline) {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(color);
        paint.setTextSize(size);
        paint.setUnderlineText(underline);

        if (bold && italic) {
            paint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD_ITALIC));
        } else if (bold) {
            paint.setTypeface(Typeface.defaultFromStyle(Typeface.BOLD));
        } else if (italic) {
            paint.setTypeface(Typeface.defaultFromStyle(Typeface.ITALIC));
        } else {
            paint.setTypeface(Typeface.DEFAULT);
        }
        return paint;
    }

    private void drawShape(Canvas canvas, ToolType type, float x1, float y1, float x2, float y2, Paint paint) {
        switch (type) {
            case ARROW_ONE:
                drawArrow(canvas, x1, y1, x2, y2, paint, false);
                break;
            case ARROW_TWO:
                drawArrow(canvas, x1, y1, x2, y2, paint, true);
                break;
            case RECT_OUTLINE:
            case RECT_FILL:
                canvas.drawRect(
                        Math.min(x1, x2),
                        Math.min(y1, y2),
                        Math.max(x1, x2),
                        Math.max(y1, y2),
                        paint
                );
                break;
            case CIRCLE:
                float centerX = (x1 + x2) / 2f;
                float centerY = (y1 + y2) / 2f;
                float radius = distance(x1, y1, x2, y2) / 2f;
                canvas.drawCircle(centerX, centerY, radius, paint);
                break;
            default:
                break;
        }
    }

    private void drawArrow(Canvas canvas, float x1, float y1, float x2, float y2, Paint paint, boolean doubleSided) {
        canvas.drawLine(x1, y1, x2, y2, paint);
        drawArrowHead(canvas, x1, y1, x2, y2, paint);
        if (doubleSided) {
            drawArrowHead(canvas, x2, y2, x1, y1, paint);
        }
    }

    private void drawArrowHead(Canvas canvas, float x1, float y1, float x2, float y2, Paint paint) {
        float angle = (float) Math.atan2(y2 - y1, x2 - x1);
        float arrowLength = Math.max(8f, paint.getStrokeWidth() * 3.5f);
        float arrowAngle = (float) Math.PI / 6f;

        float x3 = (float) (x2 - arrowLength * Math.cos(angle - arrowAngle));
        float y3 = (float) (y2 - arrowLength * Math.sin(angle - arrowAngle));
        float x4 = (float) (x2 - arrowLength * Math.cos(angle + arrowAngle));
        float y4 = (float) (y2 - arrowLength * Math.sin(angle + arrowAngle));

        Path head = new Path();
        head.moveTo(x2, y2);
        head.lineTo(x3, y3);
        head.moveTo(x2, y2);
        head.lineTo(x4, y4);
        canvas.drawPath(head, paint);
    }

    private PagePoint locatePagePoint(float documentX, float documentY) {
        ensurePageGeometryCache();
        if (pageGeometryCache.isEmpty()) {
            return null;
        }

        // 页面按 top 递增排列，二分定位可避免长文档每次点击都线性扫描。
        int low = 0;
        int high = pageGeometryCache.size() - 1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            PageGeometry geometry = pageGeometryCache.get(mid);
            if (documentY < geometry.top) {
                high = mid - 1;
            } else if (documentY > geometry.bottom) {
                low = mid + 1;
            } else {
                if (documentX >= geometry.left && documentX <= geometry.right) {
                    return new PagePoint(mid, documentX - geometry.left, documentY - geometry.top);
                }
                return null;
            }
        }
        return null;
    }

    private boolean resolveSearchHighlightRect(SearchHighlight highlight, RectF outRect) {
        if (highlight == null || highlight.rectInPageRatio == null || outRect == null) {
            return false;
        }
        PageGeometry geometry = getPageGeometry(highlight.pageIndex);
        if (geometry == null) return false;
        RectF ratio = highlight.rectInPageRatio;
        outRect.set(
                geometry.left + ratio.left * geometry.width,
                geometry.top + ratio.top * geometry.height,
                geometry.left + ratio.right * geometry.width,
                geometry.top + ratio.bottom * geometry.height
        );
        return true;
    }

    private PagePoint locatePointOnPage(int pageIndex, float documentX, float documentY, boolean clamp) {
        PageGeometry geometry = getPageGeometry(pageIndex);
        if (geometry == null) {
            return null;
        }

        float x = documentX - geometry.left;
        float y = documentY - geometry.top;
        if (!clamp && (x < 0 || y < 0 || x > geometry.width || y > geometry.height)) {
            return null;
        }
        x = Math.max(0, Math.min(geometry.width, x));
        y = Math.max(0, Math.min(geometry.height, y));
        return new PagePoint(pageIndex, x, y);
    }

    private PageGeometry getPageGeometry(int pageIndex) {
        ensurePageGeometryCache();
        if (pageIndex < 0 || pageIndex >= pageGeometryCache.size()) {
            return null;
        }
        return pageGeometryCache.get(pageIndex);
    }

    private void ensurePageGeometryCache() {
        if (pdfView == null || pdfView.getPageCount() <= 0) {
            pageGeometryCache = Collections.emptyList();
            cachedPageCount = 0;
            return;
        }

        int pageCount = pdfView.getPageCount();
        float spacingPx = pdfView.getSpacingPx();
        if (cachedPageCount == pageCount
                && Float.compare(cachedSpacingPx, spacingPx) == 0
                && pageGeometryCache.size() == pageCount) {
            return;
        }

        List<SizeF> sizes = new ArrayList<>(pageCount);
        float maxWidth = 0f;
        for (int i = 0; i < pageCount; i++) {
            SizeF size = pdfView.getPageSize(i);
            sizes.add(size);
            maxWidth = Math.max(maxWidth, size.getWidth());
        }

        List<PageGeometry> geometries = new ArrayList<>(pageCount);
        float top = 0f;
        for (int i = 0; i < pageCount; i++) {
            SizeF size = sizes.get(i);
            float left = (maxWidth - size.getWidth()) / 2f;
            geometries.add(new PageGeometry(left, top, size.getWidth(), size.getHeight()));
            top += size.getHeight() + spacingPx;
        }

        pageGeometryCache = Collections.unmodifiableList(geometries);
        cachedPageCount = pageCount;
        cachedSpacingPx = spacingPx;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w != oldw || h != oldh) {
            refreshPageGeometry();
        }
    }

    private void cancelActiveDrawing() {
        isDrawing = false;
        activePageIndex = -1;
        activePoints.clear();
    }

    private RectF boundsForPoints(List<PointF> points, float padding) {
        if (points == null || points.isEmpty()) {
            return new RectF();
        }
        float left = points.get(0).x;
        float right = left;
        float top = points.get(0).y;
        float bottom = top;
        for (PointF point : points) {
            left = Math.min(left, point.x);
            right = Math.max(right, point.x);
            top = Math.min(top, point.y);
            bottom = Math.max(bottom, point.y);
        }
        RectF bounds = new RectF(left, top, right, bottom);
        bounds.inset(-padding, -padding);
        return bounds;
    }

    private static float distance(float x1, float y1, float x2, float y2) {
        float dx = x2 - x1;
        float dy = y2 - y1;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    public static final class SearchHighlight {
        public final int pageIndex;
        public final RectF rectInPageRatio;
        public final int matchIndex;

        public SearchHighlight(int pageIndex, RectF rectInPageRatio, int matchIndex) {
            this.pageIndex = pageIndex;
            this.rectInPageRatio = rectInPageRatio == null ? null : new RectF(rectInPageRatio);
            this.matchIndex = matchIndex;
        }
    }

    public static final class PageMetrics {
        public final float width;
        public final float height;

        public PageMetrics(float width, float height) {
            this.width = width;
            this.height = height;
        }
    }

    public static final class AnnotationAction {
        public final ActionCategory category;
        public final int pageIndex;
        public final ToolType toolType;
        public final List<PointF> points;
        public final float startX;
        public final float startY;
        public final float endX;
        public final float endY;
        public final int color;
        public final float strokeWidth;
        public final int alpha;
        public final String text;
        public final float x;
        public final float y;
        public final float textSize;
        public final boolean bold;
        public final boolean italic;
        public final boolean underline;
        public final RectF bounds;

        private AnnotationAction(
                ActionCategory category,
                int pageIndex,
                ToolType toolType,
                List<PointF> points,
                float startX,
                float startY,
                float endX,
                float endY,
                int color,
                float strokeWidth,
                int alpha,
                String text,
                float x,
                float y,
                float textSize,
                boolean bold,
                boolean italic,
                boolean underline,
                RectF bounds
        ) {
            this.category = category;
            this.pageIndex = pageIndex;
            this.toolType = toolType;
            this.points = points;
            this.startX = startX;
            this.startY = startY;
            this.endX = endX;
            this.endY = endY;
            this.color = color;
            this.strokeWidth = strokeWidth;
            this.alpha = alpha;
            this.text = text;
            this.x = x;
            this.y = y;
            this.textSize = textSize;
            this.bold = bold;
            this.italic = italic;
            this.underline = underline;
            this.bounds = bounds == null ? new RectF() : new RectF(bounds);
        }

        static AnnotationAction freehand(
                int pageIndex,
                ToolType toolType,
                List<PointF> points,
                int color,
                float strokeWidth,
                int alpha,
                RectF bounds
        ) {
            List<PointF> pointCopy = new ArrayList<>(points.size());
            for (PointF point : points) {
                pointCopy.add(new PointF(point.x, point.y));
            }
            return new AnnotationAction(
                    ActionCategory.FREEHAND,
                    pageIndex,
                    toolType,
                    Collections.unmodifiableList(pointCopy),
                    0,
                    0,
                    0,
                    0,
                    color,
                    strokeWidth,
                    alpha,
                    null,
                    0,
                    0,
                    0,
                    false,
                    false,
                    false,
                    bounds
            );
        }

        static AnnotationAction shape(
                int pageIndex,
                ToolType toolType,
                float startX,
                float startY,
                float endX,
                float endY,
                int color,
                float strokeWidth,
                int alpha,
                RectF bounds
        ) {
            return new AnnotationAction(
                    ActionCategory.SHAPE,
                    pageIndex,
                    toolType,
                    Collections.emptyList(),
                    startX,
                    startY,
                    endX,
                    endY,
                    color,
                    strokeWidth,
                    alpha,
                    null,
                    0,
                    0,
                    0,
                    false,
                    false,
                    false,
                    bounds
            );
        }

        static AnnotationAction text(
                int pageIndex,
                String text,
                float x,
                float y,
                int color,
                float textSize,
                boolean bold,
                boolean italic,
                boolean underline,
                RectF bounds
        ) {
            return new AnnotationAction(
                    ActionCategory.TEXT,
                    pageIndex,
                    null,
                    Collections.emptyList(),
                    0,
                    0,
                    0,
                    0,
                    color,
                    0,
                    255,
                    text,
                    x,
                    y,
                    textSize,
                    bold,
                    italic,
                    underline,
                    bounds
            );
        }
    }


    private static final class SelectionHandleLayout {
        final SelectionHandleInfo anchorA;
        final SelectionHandleInfo anchorB;

        SelectionHandleLayout(SelectionHandleInfo anchorA, SelectionHandleInfo anchorB) {
            this.anchorA = anchorA;
            this.anchorB = anchorB;
        }

        SelectionHandleInfo forHandle(SelectionHandle handle) {
            if (handle == SelectionHandle.ANCHOR_A) return anchorA;
            if (handle == SelectionHandle.ANCHOR_B) return anchorB;
            return null;
        }
    }

    private static final class SelectionHandleInfo {
        final SelectionHandle handle;
        final SelectionSide side;
        final float anchorX;
        final float anchorY;
        final float centerX;
        final float centerY;

        SelectionHandleInfo(
                SelectionHandle handle,
                SelectionSide side,
                float anchorX,
                float anchorY,
                float centerX,
                float centerY
        ) {
            this.handle = handle;
            this.side = side;
            this.anchorX = anchorX;
            this.anchorY = anchorY;
            this.centerX = centerX;
            this.centerY = centerY;
        }
    }

    private enum SelectionHandle { NONE, ANCHOR_A, ANCHOR_B }
    private enum SelectionSide { NONE, START, END }

    public static final class PageHit {
        public final int pageIndex;
        public final float pageX;
        public final float pageY;

        PageHit(int pageIndex, float pageX, float pageY) {
            this.pageIndex = pageIndex;
            this.pageX = pageX;
            this.pageY = pageY;
        }
    }

    private static final class PagePoint {
        final int pageIndex;
        final float x;
        final float y;

        PagePoint(int pageIndex, float x, float y) {
            this.pageIndex = pageIndex;
            this.x = x;
            this.y = y;
        }
    }

    private static final class PageGeometry {
        final float left;
        final float top;
        final float width;
        final float height;
        final float right;
        final float bottom;

        PageGeometry(float left, float top, float width, float height) {
            this.left = left;
            this.top = top;
            this.width = width;
            this.height = height;
            this.right = left + width;
            this.bottom = top + height;
        }
    }
}
