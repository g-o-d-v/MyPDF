package com.nless.pdf_search_engine.core;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import com.nless.pdf_search_engine.ocr.OcrSearchEngine;
import com.nless.pdf_search_engine.pdfium.PdfiumTextSearchEngine;
import com.nless.pdf_search_engine.pdfium.PdfiumTextSearchResult;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

public class PdfSearchManager implements PdfSearchEngine {

    private final Context appContext;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final PdfiumTextSearchEngine textSearchEngine;
    private final OcrSearchEngine ocrSearchEngine;

    private final AtomicLong tokenGenerator = new AtomicLong(0);

    public PdfSearchManager(Context context) {
        this.appContext = context.getApplicationContext();
        this.textSearchEngine = new PdfiumTextSearchEngine();
        this.ocrSearchEngine = new OcrSearchEngine(appContext);
    }

    @Override
    public long search(
            Uri pdfUri,
            String keyword,
            PdfSearchOptions options,
            PdfSearchCallback callback
    ) {
        if (options == null) {
            options = new PdfSearchOptions();
        }

        PdfSearchOptions finalOptions = options;
        long token = tokenGenerator.incrementAndGet();

        postStarted(callback, keyword);

        new Thread(() -> {
            try {
                if (pdfUri == null || keyword == null || keyword.trim().isEmpty()) {
                    throw new PdfSearchError(
                            PdfSearchError.ERROR_INVALID_ARGUMENT,
                            "pdfUri or keyword is empty"
                    );
                }

                List<PdfSearchResult> finalResults = new ArrayList<>();

                if (finalOptions.mode == PdfSearchMode.TEXT_ONLY
                        || finalOptions.mode == PdfSearchMode.TEXT_THEN_OCR) {

                    if (isCancelled(token)) {
                        postCancelled(callback);
                        return;
                    }

                    List<PdfSearchResult> textResults =
                            searchTextLayer(
                                    pdfUri,
                                    keyword,
                                    finalOptions,
                                    callback,
                                    token
                            );

                    if (isCancelled(token)) {
                        postCancelled(callback);
                        return;
                    }

                    finalResults.addAll(textResults);
                }

                boolean shouldRunOcr = false;

                if (finalOptions.mode == PdfSearchMode.OCR_ONLY) {
                    shouldRunOcr = true;
                } else if (finalOptions.mode == PdfSearchMode.TEXT_THEN_OCR
                        && finalResults.isEmpty()
                        && finalOptions.fallbackToOcrWhenTextNotFound) {
                    shouldRunOcr = true;
                }

                if (shouldRunOcr) {
                    if (isCancelled(token)) {
                        postCancelled(callback);
                        return;
                    }

                    List<PdfSearchResult> ocrResults =
                            searchOcr(
                                    pdfUri,
                                    keyword,
                                    finalOptions,
                                    callback,
                                    token
                            );

                    if (isCancelled(token)) {
                        postCancelled(callback);
                        return;
                    }

                    finalResults.addAll(ocrResults);
                }

                postCompleted(callback, finalResults);

            } catch (Throwable e) {
                if (isCancelled(token)) {
                    postCancelled(callback);
                } else {
                    postFailed(callback, e);
                }
            }
        }).start();

        return token;
    }

    @Override
    public void cancel() {
        tokenGenerator.incrementAndGet();
    }

    @Override
    public void clearCache() {
        ocrSearchEngine.clearCache();
    }

    private boolean isCancelled(long token) {
        return token != tokenGenerator.get();
    }

    private List<PdfSearchResult> searchTextLayer(
            Uri pdfUri,
            String keyword,
            PdfSearchOptions options,
            PdfSearchCallback callback,
            long token
    ) {
        int startPage;
        int endPage;

        if (options.currentPageOnly) {
            startPage = Math.max(0, options.currentPage);
            endPage = startPage;
        } else {
            startPage = Math.max(0, options.startPage);
            endPage = options.endPage;
        }

        List<PdfiumTextSearchResult> rawResults = textSearchEngine.searchWithProgress(
                appContext,
                pdfUri,
                keyword,
                startPage,
                endPage,
                (currentPage, totalPage, source) ->
                        postProgress(callback, currentPage, totalPage, source),
                () -> isCancelled(token)
        );

        List<PdfSearchResult> results = new ArrayList<>();

        if (rawResults == null || rawResults.isEmpty()) {
            return results;
        }

        for (PdfiumTextSearchResult item : rawResults) {
            if (item == null || item.rectInPdfPoint == null) {
                continue;
            }

            List<PdfSearchRect> rects = new ArrayList<>();
            rects.add(new PdfSearchRect(
                    item.pageIndex,
                    item.rectInPdfPoint,
                    item.pageWidth,
                    item.pageHeight
            ));

            results.add(new PdfSearchResult(
                    keyword,
                    item.pageIndex,
                    PdfSearchSource.PDF_TEXT_LAYER,
                    rects,
                    keyword
            ));
        }

        return results;
    }

    private List<PdfSearchResult> searchOcr(
            Uri pdfUri,
            String keyword,
            PdfSearchOptions options,
            PdfSearchCallback callback,
            long token
    ) {
        int renderWidth = options.ocrRenderWidth > 0
                ? options.ocrRenderWidth
                : 1280;

        if (options.currentPageOnly || !options.allowFullDocumentOcr) {
            int page = Math.max(0, options.currentPage);

            return ocrSearchEngine.searchRange(
                    pdfUri,
                    keyword,
                    page,
                    page,
                    renderWidth,
                    (currentPage, totalPage, source) ->
                            postProgress(callback, currentPage, totalPage, source),
                    () -> isCancelled(token)
            );
        }

        int startPage = Math.max(0, options.startPage);
        int endPage = options.endPage;

        if (endPage < 0) {
            endPage = startPage + Math.max(0, options.maxOcrPages - 1);
        } else {
            endPage = Math.min(
                    endPage,
                    startPage + Math.max(0, options.maxOcrPages - 1)
            );
        }

        return ocrSearchEngine.searchRange(
                pdfUri,
                keyword,
                startPage,
                endPage,
                renderWidth,
                (currentPage, totalPage, source) ->
                        postProgress(callback, currentPage, totalPage, source),
                () -> isCancelled(token)
        );
    }

    private void postStarted(PdfSearchCallback callback, String keyword) {
        if (callback == null) return;
        mainHandler.post(() -> callback.onSearchStarted(keyword));
    }

    private void postProgress(
            PdfSearchCallback callback,
            int currentPage,
            int totalPage,
            PdfSearchSource source
    ) {
        if (callback == null) return;

        mainHandler.post(() -> callback.onSearchProgress(
                currentPage,
                totalPage,
                source
        ));
    }

    private void postCompleted(PdfSearchCallback callback, List<PdfSearchResult> results) {
        if (callback == null) return;
        mainHandler.post(() -> callback.onSearchCompleted(results));
    }

    private void postFailed(PdfSearchCallback callback, Throwable error) {
        if (callback == null) return;
        mainHandler.post(() -> callback.onSearchFailed(error));
    }

    private void postCancelled(PdfSearchCallback callback) {
        if (callback == null) return;
        mainHandler.post(callback::onSearchCancelled);
    }
}
