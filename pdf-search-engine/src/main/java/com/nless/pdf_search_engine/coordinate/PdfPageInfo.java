package com.nless.pdf_search_engine.coordinate;

public class PdfPageInfo {

    public final int pageIndex;

    /**
     * PDFView 内部页面宽度。
     */
    public final float viewPageWidth;

    /**
     * PDFView 内部页面高度。
     */
    public final float viewPageHeight;

    /**
     * 搜索结果来源页面宽度。
     * PDFium 文本搜索一般是 PDF point 宽度。
     * OCR 搜索第一版可能是 bitmap 宽度。
     */
    public final float sourcePageWidth;

    /**
     * 搜索结果来源页面高度。
     */
    public final float sourcePageHeight;

    /**
     * AndroidPdfViewer 页面间距。
     */
    public final float spacing;

    /**
     * 当前页在 PDFView 文档坐标中的起始 Y。
     */
    public final float pageStartY;

    public PdfPageInfo(
            int pageIndex,
            float viewPageWidth,
            float viewPageHeight,
            float sourcePageWidth,
            float sourcePageHeight,
            float spacing,
            float pageStartY
    ) {
        this.pageIndex = pageIndex;
        this.viewPageWidth = viewPageWidth;
        this.viewPageHeight = viewPageHeight;
        this.sourcePageWidth = sourcePageWidth;
        this.sourcePageHeight = sourcePageHeight;
        this.spacing = spacing;
        this.pageStartY = pageStartY;
    }
}
