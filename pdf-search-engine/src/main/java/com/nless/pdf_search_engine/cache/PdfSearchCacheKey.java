package com.nless.pdf_search_engine.cache;

import android.net.Uri;

public class PdfSearchCacheKey {

    public static String buildOcrPageKey(
            Uri pdfUri,
            int pageIndex,
            int renderWidth
    ) {
        String fileKey = pdfUri != null ? pdfUri.toString() : "";
        return fileKey + "#page=" + pageIndex + "#ocrWidth=" + renderWidth;
    }

    public static String buildTextSearchKey(
            Uri pdfUri,
            String keyword,
            int startPage,
            int endPage
    ) {
        String fileKey = pdfUri != null ? pdfUri.toString() : "";
        String kw = keyword != null ? keyword : "";
        return fileKey + "#text#" + kw + "#start=" + startPage + "#end=" + endPage;
    }
}
