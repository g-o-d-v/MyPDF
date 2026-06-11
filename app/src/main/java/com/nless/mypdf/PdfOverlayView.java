package com.nless.mypdf;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import com.github.barteksc.pdfviewer.PDFView;

import java.util.Stack;

public class PdfOverlayView extends View {

    public enum Mode { DRAG, DOODLE, TEXT }
    private Mode currentMode = Mode.DRAG;

    public enum ToolType { PENCIL, HIGHLIGHTER, ARROW_ONE, ARROW_TWO, RECT_OUTLINE, RECT_FILL, CIRCLE }
    private ToolType currentTool = ToolType.PENCIL;

    private int currentColor = 0xFF000000;
    private float currentStrokeWidth = 5f;

    private final Stack<DrawAction> actionStack = new Stack<>();

    private boolean isDrawing = false;
    private Path currentPath;
    private Paint currentPaint;
    private float startX, startY, currentX, currentY;

    private PDFView pdfView;

    public interface OnTextRequestListener {
        void onRequestText(float docX, float docY);
    }
    private OnTextRequestListener textRequestListener;

    public PdfOverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public void setPdfView(PDFView pdfView) {
        this.pdfView = pdfView;
    }

    public void clearActions() {
        actionStack.clear();
        invalidate();
    }

    public boolean isActionStackEmpty() {
        return actionStack.isEmpty();
    }

    public void setMode(Mode mode) {
        this.currentMode = mode;
    }

    public void setDrawColor(int color) {
        this.currentColor = color;
    }

    public void setStrokeWidth(float width) {
        this.currentStrokeWidth = width;
    }

    public void setToolType(String toolName) {
        switch(toolName) {
            case "荧光笔": this.currentTool = ToolType.HIGHLIGHTER; break;
            case "单向箭头": this.currentTool = ToolType.ARROW_ONE; break;
            case "双向箭头": this.currentTool = ToolType.ARROW_TWO; break;
            case "矩形框": this.currentTool = ToolType.RECT_OUTLINE; break;
            case "实心矩形": this.currentTool = ToolType.RECT_FILL; break;
            case "圆形": this.currentTool = ToolType.CIRCLE; break;
            case "铅笔":
            default: this.currentTool = ToolType.PENCIL; break;
        }
    }

    public void setOnTextRequestListener(OnTextRequestListener listener) {
        this.textRequestListener = listener;
    }

    public void undo() {
        if (!actionStack.isEmpty()) {
            actionStack.pop();
            invalidate();
        }
    }

    public void addTextAction(String text, float docX, float docY, int color, int size, boolean isBold, boolean isItalic, boolean isUnderline) {
        Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(color);
        textPaint.setTextSize(size * 1.5f);
        textPaint.setUnderlineText(isUnderline);

        if (isBold && isItalic) textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD_ITALIC));
        else if (isBold) textPaint.setTypeface(Typeface.defaultFromStyle(Typeface.BOLD));
        else if (isItalic) textPaint.setTypeface(Typeface.defaultFromStyle(Typeface.ITALIC));
        else textPaint.setTypeface(Typeface.DEFAULT);

        // 🌟 计算文字包围盒
        float textWidth = textPaint.measureText(text);
        RectF bounds = new RectF(docX, docY - size * 1.5f, docX + textWidth, docY + size * 0.5f);

        actionStack.push(new DrawAction(text, docX, docY, textPaint, bounds));
        invalidate();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (currentMode == Mode.DRAG || pdfView == null || pdfView.getZoom() == 0) {
            return false;
        }

        float zoom = pdfView.getZoom();
        float offsetX = pdfView.getCurrentXOffset();
        float offsetY = pdfView.getCurrentYOffset();

        float docX = (event.getX() - offsetX) / zoom;
        float docY = (event.getY() - offsetY) / zoom;

        if (currentMode == Mode.DOODLE) {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    isDrawing = true;
                    startX = docX; startY = docY; currentX = docX; currentY = docY;

                    currentPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                    currentPaint.setColor(currentColor);
                    currentPaint.setStrokeWidth(currentStrokeWidth / zoom);
                    currentPaint.setStrokeCap(Paint.Cap.ROUND);
                    currentPaint.setStrokeJoin(Paint.Join.ROUND);

                    if (currentTool == ToolType.HIGHLIGHTER) currentPaint.setAlpha(110);

                    if (currentTool == ToolType.PENCIL || currentTool == ToolType.HIGHLIGHTER) {
                        currentPaint.setStyle(Paint.Style.STROKE);
                        currentPath = new Path();
                        currentPath.moveTo(docX, docY);
                    } else if (currentTool == ToolType.RECT_FILL) {
                        currentPaint.setStyle(Paint.Style.FILL);
                    } else {
                        currentPaint.setStyle(Paint.Style.STROKE);
                    }
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (!isDrawing) break;
                    currentX = docX; currentY = docY;
                    if (currentTool == ToolType.PENCIL || currentTool == ToolType.HIGHLIGHTER) {
                        currentPath.lineTo(docX, docY);
                    }
                    invalidate();
                    break;
                case MotionEvent.ACTION_UP:
                    if (!isDrawing) break;
                    isDrawing = false;
                    currentX = docX; currentY = docY;

                    RectF bounds = new RectF();
                    if (currentTool == ToolType.PENCIL || currentTool == ToolType.HIGHLIGHTER) {
                        // 🌟 计算路径的物理包围盒
                        currentPath.computeBounds(bounds, true);
                        bounds.inset(-currentPaint.getStrokeWidth(), -currentPaint.getStrokeWidth());
                        actionStack.push(new DrawAction(currentPath, currentPaint, bounds));
                    } else {
                        // 🌟 计算图形的物理包围盒
                        bounds.set(Math.min(startX, currentX), Math.min(startY, currentY), Math.max(startX, currentX), Math.max(startY, currentY));
                        bounds.inset(-currentPaint.getStrokeWidth(), -currentPaint.getStrokeWidth());
                        actionStack.push(new DrawAction(currentTool, startX, startY, currentX, currentY, currentPaint, bounds));
                    }
                    currentPath = null;
                    invalidate();
                    break;
            }
            return true;
        }

        if (currentMode == Mode.TEXT && event.getAction() == MotionEvent.ACTION_DOWN) {
            if (textRequestListener != null) textRequestListener.onRequestText(docX, docY);
            return true;
        }
        return true;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (pdfView == null) return;

        canvas.save();
        canvas.translate(pdfView.getCurrentXOffset(), pdfView.getCurrentYOffset());
        canvas.scale(pdfView.getZoom(), pdfView.getZoom());

        for (DrawAction action : actionStack) {
            if (action.actionCategory == 0) canvas.drawPath(action.path, action.paint);
            else if (action.actionCategory == 1) drawShape(canvas, action.toolType, action.startX, action.startY, action.endX, action.endY, action.paint);
            else if (action.actionCategory == 2) canvas.drawText(action.text, action.x, action.y, action.paint);
        }

        if (isDrawing && currentPaint != null) {
            if (currentTool == ToolType.PENCIL || currentTool == ToolType.HIGHLIGHTER) {
                if (currentPath != null) canvas.drawPath(currentPath, currentPaint);
            } else {
                drawShape(canvas, currentTool, startX, startY, currentX, currentY, currentPaint);
            }
        }
        canvas.restore();
    }

    private void drawShape(Canvas canvas, ToolType type, float x1, float y1, float x2, float y2, Paint paint) {
        switch (type) {
            case ARROW_ONE: drawArrow(canvas, x1, y1, x2, y2, paint, false); break;
            case ARROW_TWO: drawArrow(canvas, x1, y1, x2, y2, paint, true); break;
            case RECT_OUTLINE:
            case RECT_FILL: canvas.drawRect(Math.min(x1, x2), Math.min(y1, y2), Math.max(x1, x2), Math.max(y1, y2), paint); break;
            case CIRCLE:
                float cx = (x1 + x2) / 2; float cy = (y1 + y2) / 2;
                float radius = (float) Math.sqrt(Math.pow(x2 - x1, 2) + Math.pow(y2 - y1, 2)) / 2;
                canvas.drawCircle(cx, cy, radius, paint); break;
        }
    }

    private void drawArrow(Canvas canvas, float x1, float y1, float x2, float y2, Paint paint, boolean doubleSided) {
        canvas.drawLine(x1, y1, x2, y2, paint);
        drawArrowHead(canvas, x1, y1, x2, y2, paint);
        if (doubleSided) drawArrowHead(canvas, x2, y2, x1, y1, paint);
    }

    private void drawArrowHead(Canvas canvas, float x1, float y1, float x2, float y2, Paint paint) {
        float angle = (float) Math.atan2(y2 - y1, x2 - x1);
        float arrowLen = 14f / (pdfView != null && pdfView.getZoom() > 0 ? pdfView.getZoom() : 1f);
        float arrowAngle = (float) Math.PI / 6;

        float x3 = (float) (x2 - arrowLen * Math.cos(angle - arrowAngle)); float y3 = (float) (y2 - arrowLen * Math.sin(angle - arrowAngle));
        float x4 = (float) (x2 - arrowLen * Math.cos(angle + arrowAngle)); float y4 = (float) (y2 - arrowLen * Math.sin(angle + arrowAngle));

        Path head = new Path(); head.moveTo(x2, y2); head.lineTo(x3, y3); head.moveTo(x2, y2); head.lineTo(x4, y4);
        Paint.Style oldStyle = paint.getStyle(); paint.setStyle(Paint.Style.STROKE);
        canvas.drawPath(head, paint); paint.setStyle(oldStyle);
    }

    // 🌟 终极提速算法：空间拦截器
    public android.graphics.Bitmap getPageAnnotationBitmap(int pageIndex, float pageWidth, float pageHeight, float spacing) {
        if (pageWidth <= 0 || pageHeight <= 0 || actionStack.isEmpty()) return null;

        float pageStartY = pageIndex * (pageHeight + spacing);
        float pageEndY = pageStartY + pageHeight;

        boolean hasContent = false;
        // 🌟 核心拦截：检测当前页面的 Y 轴是否与任何涂鸦包围盒有交集
        for (DrawAction action : actionStack) {
            if (action.bounds != null && action.bounds.bottom >= pageStartY && action.bounds.top <= pageEndY) {
                hasContent = true;
                break;
            }
        }

        // 🌟 如果这页没画任何东西，直接光速跳过，耗时 0 毫秒！
        if (!hasContent) return null;

        android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap((int) pageWidth, (int) pageHeight, android.graphics.Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);

        canvas.save();
        canvas.translate(0, -pageStartY);

        for (DrawAction action : actionStack) {
            // 只有落在这个页面范围内的动作才执行渲染
            if (action.bounds != null && action.bounds.bottom >= pageStartY && action.bounds.top <= pageEndY) {
                if (action.actionCategory == 0) canvas.drawPath(action.path, action.paint);
                else if (action.actionCategory == 1) drawShape(canvas, action.toolType, action.startX, action.startY, action.endX, action.endY, action.paint);
                else if (action.actionCategory == 2) canvas.drawText(action.text, action.x, action.y, action.paint);
            }
        }
        canvas.restore();
        return bitmap;
    }

    private static class DrawAction {
        int actionCategory; Paint paint; Path path;
        ToolType toolType; float startX, startY, endX, endY;
        String text; float x, y;
        RectF bounds; // 记录包围盒范围

        DrawAction(Path path, Paint paint, RectF bounds) {
            this.actionCategory = 0; this.path = path; this.paint = paint; this.bounds = bounds;
        }
        DrawAction(ToolType type, float startX, float startY, float endX, float endY, Paint paint, RectF bounds) {
            this.actionCategory = 1; this.toolType = type; this.startX = startX; this.startY = startY; this.endX = endX; this.endY = endY; this.paint = paint; this.bounds = bounds;
        }
        DrawAction(String text, float x, float y, Paint paint, RectF bounds) {
            this.actionCategory = 2; this.text = text; this.x = x; this.y = y; this.paint = paint; this.bounds = bounds;
        }
    }
}