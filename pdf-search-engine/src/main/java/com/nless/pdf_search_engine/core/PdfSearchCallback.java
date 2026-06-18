package com.nless.pdf_search_engine.core;

import java.util.List;

public interface PdfSearchCallback {

    void onSearchStarted(String keyword);

    /**
     * @param currentPage 当前处理页，0-based
     * @param totalPage 总页数，未知时可传 -1
     * @param source 当前搜索来源
     */
    void onSearchProgress(int currentPage, int totalPage, PdfSearchSource source);

    void onSearchCompleted(List<PdfSearchResult> results);

    void onSearchFailed(Throwable error);

    void onSearchCancelled();
}
