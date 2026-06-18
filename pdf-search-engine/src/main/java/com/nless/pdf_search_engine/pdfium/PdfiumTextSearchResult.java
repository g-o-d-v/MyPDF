package com.nless.pdf_search_engine.pdfium;

import android.graphics.RectF;

public class PdfiumTextSearchResult {

    public final int pageIndex;
    public final RectF rectInPdfPoint;
    public final float pageWidth;
    public final float pageHeight;

    public PdfiumTextSearchResult(
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
