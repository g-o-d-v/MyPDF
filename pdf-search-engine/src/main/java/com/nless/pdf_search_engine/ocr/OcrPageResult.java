package com.nless.pdf_search_engine.ocr;

import java.util.ArrayList;
import java.util.List;

public class OcrPageResult {

    public final int bitmapWidth;
    public final int bitmapHeight;

    /**
     * PDF 原始页面宽度。
     * 如果未知，可能等于 bitmapWidth。
     */
    public final float pageWidth;

    /**
     * PDF 原始页面高度。
     * 如果未知，可能等于 bitmapHeight。
     */
    public final float pageHeight;

    public final List<OcrTextBlock> blocks;
    public final String fullText;

    /**
     * 兼容旧调用。
     */
    public OcrPageResult(int bitmapWidth, int bitmapHeight, List<OcrTextBlock> blocks) {
        this(bitmapWidth, bitmapHeight, bitmapWidth, bitmapHeight, blocks);
    }

    public OcrPageResult(
            int bitmapWidth,
            int bitmapHeight,
            float pageWidth,
            float pageHeight,
            List<OcrTextBlock> blocks
    ) {
        this.bitmapWidth = bitmapWidth;
        this.bitmapHeight = bitmapHeight;
        this.pageWidth = pageWidth;
        this.pageHeight = pageHeight;
        this.blocks = blocks != null ? blocks : new ArrayList<>();

        StringBuilder sb = new StringBuilder();
        for (OcrTextBlock block : this.blocks) {
            if (block.text != null && !block.text.isEmpty()) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(block.text);
            }
        }
        this.fullText = sb.toString();
    }
}
