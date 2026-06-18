package com.nless.pdf_search_engine.core;

public interface PdfSearchProgressListener {
    void onProgress(int currentPage, int totalPage, PdfSearchSource source);
}
