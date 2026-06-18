package com.nless.pdf_search_engine.ocr;

import android.graphics.RectF;

public class OcrTextBlock {
    public final String text;
    public final RectF rectInBitmap;
    public final float score;

    public OcrTextBlock(String text, RectF rectInBitmap, float score) {
        this.text = text;
        this.rectInBitmap = rectInBitmap;
        this.score = score;
    }
}
