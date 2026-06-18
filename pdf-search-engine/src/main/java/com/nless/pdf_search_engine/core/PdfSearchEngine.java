package com.nless.pdf_search_engine.core;

import android.net.Uri;

public interface PdfSearchEngine {

    long search(
            Uri pdfUri,
            String keyword,
            PdfSearchOptions options,
            PdfSearchCallback callback
    );

    void cancel();

    void clearCache();
}
