package com.nless.pdf_search_engine.ocr;

import android.graphics.Bitmap;

public class OcrRenderedPage {

    public final int pageIndex;

    /**
     * 渲染出来用于 OCR 的 Bitmap。
     */
    public final Bitmap bitmap;

    /**
     * Bitmap 宽度。
     */
    public final int bitmapWidth;

    /**
     * Bitmap 高度。
     */
    public final int bitmapHeight;

    /**
     * PDF 原始页面宽度。
     *
     * 注意：
     * Android PdfRenderer.Page#getWidth() / getHeight()
     * 返回的是 PDF 页面尺寸，单位近似 point。
     */
    public final float pageWidth;

    /**
     * PDF 原始页面高度。
     */
    public final float pageHeight;

    public OcrRenderedPage(
            int pageIndex,
            Bitmap bitmap,
            int bitmapWidth,
            int bitmapHeight,
            float pageWidth,
            float pageHeight
    ) {
        this.pageIndex = pageIndex;
        this.bitmap = bitmap;
        this.bitmapWidth = bitmapWidth;
        this.bitmapHeight = bitmapHeight;
        this.pageWidth = pageWidth;
        this.pageHeight = pageHeight;
    }

    public void recycle() {
        if (bitmap != null && !bitmap.isRecycled()) {
            bitmap.recycle();
        }
    }
}
