package com.nless.pdf_search_engine.ocr;

import android.content.Context;
import android.graphics.RectF;
import android.net.Uri;

import com.nless.pdf_search_engine.cache.OcrMemoryCache;
import com.nless.pdf_search_engine.cache.PdfSearchCacheKey;
import com.nless.pdf_search_engine.core.PdfSearchCancelChecker;
import com.nless.pdf_search_engine.core.PdfSearchProgressListener;
import com.nless.pdf_search_engine.core.PdfSearchRect;
import com.nless.pdf_search_engine.core.PdfSearchResult;
import com.nless.pdf_search_engine.core.PdfSearchSource;
import com.nless.pdf_search_engine.paddle.PaddleOcrEngine;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class OcrSearchEngine {

    private final Context appContext;
    private final OcrPageRenderer pageRenderer;
    private final OcrMemoryCache memoryCache;

    public OcrSearchEngine(Context context) {
        this(context, new OcrMemoryCache(8));
    }

    public OcrSearchEngine(Context context, OcrMemoryCache memoryCache) {
        this.appContext = context.getApplicationContext();
        this.pageRenderer = new OcrPageRenderer();
        this.memoryCache = memoryCache != null ? memoryCache : new OcrMemoryCache(8);
    }

    public List<PdfSearchResult> searchCurrentPage(
            Uri pdfUri,
            String keyword,
            int pageIndex,
            int renderWidth
    ) {
        return searchRange(pdfUri, keyword, pageIndex, pageIndex, renderWidth);
    }

    public List<PdfSearchResult> searchRange(
            Uri pdfUri,
            String keyword,
            int startPage,
            int endPage,
            int renderWidth
    ) {
        return searchRange(
                pdfUri,
                keyword,
                startPage,
                endPage,
                renderWidth,
                null,
                null
        );
    }


    public List<PdfSearchResult> searchRange(
            Uri pdfUri,
            String keyword,
            int startPage,
            int endPage,
            int renderWidth,
            PdfSearchProgressListener progressListener,
            PdfSearchCancelChecker cancelChecker
    ) {
        List<PdfSearchResult> results = new ArrayList<>();

        if (pdfUri == null || keyword == null || keyword.trim().isEmpty()) {
            return results;
        }

        if (startPage < 0) startPage = 0;
        if (endPage < startPage) endPage = startPage;
        if (renderWidth <= 0) renderWidth = 1280;

        int pageCount = pageRenderer.getPageCount(appContext, pdfUri);
        if (pageCount <= 0) return results;

        if (endPage >= pageCount) {
            endPage = pageCount - 1;
        }

        for (int pageIndex = startPage; pageIndex <= endPage; pageIndex++) {
            if (cancelChecker != null && cancelChecker.isCancelled()) {
                return results;
            }

            if (progressListener != null) {
                progressListener.onProgress(pageIndex, pageCount, PdfSearchSource.OCR);
            }

            OcrPageResult pageResult = getOrRecognizePage(
                    pdfUri,
                    pageIndex,
                    renderWidth
            );

            if (pageResult == null) continue;

            results.addAll(buildSearchResultsFromOcrPage(
                    pageIndex,
                    keyword,
                    pageResult
            ));
        }

        return results;
    }

    private OcrPageResult getOrRecognizePage(
            Uri pdfUri,
            int pageIndex,
            int renderWidth
    ) {
        String key = PdfSearchCacheKey.buildOcrPageKey(
                pdfUri,
                pageIndex,
                renderWidth
        );

        OcrPageResult cached = memoryCache.get(key);
        if (cached != null) {
            return cached;
        }

        OcrRenderedPage renderedPage = null;

        try {
            renderedPage = pageRenderer.renderPage(
                    appContext,
                    pdfUri,
                    pageIndex,
                    renderWidth
            );

            if (renderedPage == null || renderedPage.bitmap == null) {
                return null;
            }

            OcrPageResult result = PaddleOcrEngine.getInstance()
                    .recognizePage(
                            appContext,
                            renderedPage.bitmap,
                            renderedPage.pageWidth,
                            renderedPage.pageHeight
                    );

            if (result != null) {
                memoryCache.put(key, result);
            }

            return result;

        } catch (Exception e) {
            e.printStackTrace();
            return null;

        } finally {
            if (renderedPage != null) {
                renderedPage.recycle();
            }
        }
    }

    private List<PdfSearchResult> buildSearchResultsFromOcrPage(
            int pageIndex,
            String keyword,
            OcrPageResult pageResult
    ) {
        List<PdfSearchResult> results = new ArrayList<>();

        if (pageResult == null || pageResult.blocks == null) {
            return results;
        }

        if (keyword == null || keyword.trim().isEmpty()) {
            return results;
        }

        String lowerKeyword = keyword.toLowerCase(Locale.getDefault());

        for (OcrTextBlock block : pageResult.blocks) {
            if (block == null || block.text == null || block.rectInBitmap == null) {
                continue;
            }

            String originalText = block.text;
            String lowerText = originalText.toLowerCase(Locale.getDefault());

            int fromIndex = 0;

            while (true) {
                int matchIndex = lowerText.indexOf(lowerKeyword, fromIndex);
                if (matchIndex < 0) {
                    break;
                }

                RectF keywordBitmapRect = estimateKeywordRectInBlock(
                        block.rectInBitmap,
                        originalText,
                        matchIndex,
                        keyword.length()
                );

                RectF pdfPointRect = bitmapRectToPdfPointRect(
                        keywordBitmapRect,
                        pageResult.bitmapWidth,
                        pageResult.bitmapHeight,
                        pageResult.pageWidth,
                        pageResult.pageHeight
                );

                if (pdfPointRect != null) {
                    List<PdfSearchRect> rects = new ArrayList<>();

                    rects.add(new PdfSearchRect(
                            pageIndex,
                            pdfPointRect,
                            pageResult.pageWidth,
                            pageResult.pageHeight
                    ));

                    results.add(new PdfSearchResult(
                            keyword,
                            pageIndex,
                            PdfSearchSource.OCR,
                            rects,
                            block.text
                    ));
                }

                fromIndex = matchIndex + Math.max(1, lowerKeyword.length());
            }
        }

        return results;
    }

    private RectF estimateKeywordRectInBlock(
            RectF blockRect,
            String blockText,
            int matchStart,
            int keywordLength
    ) {
        if (blockRect == null || blockText == null || blockText.isEmpty()) {
            return blockRect;
        }

        int totalLength = blockText.length();
        if (totalLength <= 0) {
            return blockRect;
        }

        int matchEnd = Math.min(totalLength, matchStart + keywordLength);

        float blockWidth = blockRect.width();
        float blockHeight = blockRect.height();

        float startRatio = matchStart / (float) totalLength;
        float endRatio = matchEnd / (float) totalLength;

        float left = blockRect.left + blockWidth * startRatio;
        float right = blockRect.left + blockWidth * endRatio;

        /*
         * OCR 检测框一般是整行框。
         * 这里按字符比例估算关键词所在区域。
         */
        float centerY = blockRect.centerY();
        float refinedHeight = blockHeight * 0.78f;

        float top = centerY - refinedHeight / 2f;
        float bottom = centerY + refinedHeight / 2f;

        /*
         * 左右加一点 padding，避免关键词边缘被裁掉。
         */
        float paddingX = Math.max(
                2f,
                blockWidth / Math.max(1, totalLength) * 0.18f
        );

        left -= paddingX;
        right += paddingX;

        if (left < blockRect.left) {
            left = blockRect.left;
        }

        if (right > blockRect.right) {
            right = blockRect.right;
        }

        return new RectF(left, top, right, bottom);
    }

    /**
     * OCR Bitmap 坐标转换成真实 PDF point 坐标。
     *
     * OCR Bitmap 坐标：
     * - 原点左上
     * - Y 向下
     *
     * PDF point 坐标：
     * - 原点左下
     * - Y 向上
     */
    private RectF bitmapRectToPdfPointRect(
            RectF bitmapRect,
            int bitmapWidth,
            int bitmapHeight,
            float pageWidth,
            float pageHeight
    ) {
        if (bitmapRect == null) {
            return null;
        }

        if (bitmapWidth <= 0 || bitmapHeight <= 0 || pageWidth <= 0 || pageHeight <= 0) {
            return null;
        }

        float left = bitmapRect.left / bitmapWidth * pageWidth;
        float right = bitmapRect.right / bitmapWidth * pageWidth;

        float bottom = pageHeight - (bitmapRect.bottom / bitmapHeight * pageHeight);
        float top = pageHeight - (bitmapRect.top / bitmapHeight * pageHeight);

        if (right <= left || top <= bottom) {
            return null;
        }

        return new RectF(left, bottom, right, top);
    }

    public void clearCache() {
        memoryCache.clear();
    }
}
