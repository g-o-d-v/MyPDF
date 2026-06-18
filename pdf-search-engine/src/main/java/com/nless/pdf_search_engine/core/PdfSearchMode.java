package com.nless.pdf_search_engine.core;

public enum PdfSearchMode {
    /**
     * 只搜索 PDF 文本层。
     * 适合普通 PDF。
     */
    TEXT_ONLY,

    /**
     * 只使用 OCR。
     * 适合扫描件。
     */
    OCR_ONLY,

    /**
     * 先搜索 PDF 文本层。
     * 如果没有结果，再走 OCR。
     */
    TEXT_THEN_OCR
}
