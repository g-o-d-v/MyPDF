package com.nless.pdf_search_engine.core;

import java.util.List;

public class PdfSearchResult {

    public final String keyword;

    /**
     * 页码，0-based。
     */
    public final int pageIndex;

    /**
     * 来源：PDF 文本层 / OCR。
     */
    public final PdfSearchSource source;

    /**
     * 一个命中结果可能有多个矩形。
     * 例如关键词跨行时。
     */
    public final List<PdfSearchRect> rects;

    /**
     * 命中的文本。
     * 第一版可以直接填 keyword。
     */
    public final String matchedText;

    public PdfSearchResult(
            String keyword,
            int pageIndex,
            PdfSearchSource source,
            List<PdfSearchRect> rects,
            String matchedText
    ) {
        this.keyword = keyword;
        this.pageIndex = pageIndex;
        this.source = source;
        this.rects = rects;
        this.matchedText = matchedText;
    }
}
