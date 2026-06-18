package com.nless.pdf_search_engine.core;

import android.graphics.RectF;

public class PdfSearchRect {

    /**
     * 页码，0-based。
     */
    public final int pageIndex;

    /**
     * PDF 页面内坐标。
     *
     * 统一约定：
     * - 使用 PDF point 坐标
     * - 原点左下
     * - Y 轴向上
     *
     * UI 层需要根据具体 PDFView / Canvas 坐标系再转换。
     */
    public final RectF rectInPdfPoint;

    /**
     * 当前页 PDF point 宽度。
     */
    public final float pageWidth;

    /**
     * 当前页 PDF point 高度。
     */
    public final float pageHeight;

    public PdfSearchRect(
            int pageIndex,
            RectF rectInPdfPoint,
            float pageWidth,
            float pageHeight
    ) {
        this.pageIndex = pageIndex;
        this.rectInPdfPoint = rectInPdfPoint;
        this.pageWidth = pageWidth;
        this.pageHeight = pageHeight;
    }
}
