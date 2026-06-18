package com.nless.pdf_search_engine.pdfium;

import android.content.Context;
import android.graphics.RectF;
import android.net.Uri;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import com.nless.pdf_search_engine.core.PdfSearchCancelChecker;
import com.nless.pdf_search_engine.core.PdfSearchProgressListener;
import com.nless.pdf_search_engine.core.PdfSearchSource;

public class PdfiumTextSearchEngine {

    private static final String TAG = "PdfiumTextSearchEngine";

    private final PdfiumTextNative nativeApi = new PdfiumTextNative();

    public List<PdfiumTextSearchResult> search(
            Context context,
            Uri pdfUri,
            String keyword
    ) {
        return search(context, pdfUri, keyword, 0, -1);
    }

    public List<PdfiumTextSearchResult> search(
            Context context,
            Uri pdfUri,
            String keyword,
            int startPage,
            int endPage
    ) {
        List<PdfiumTextSearchResult> results = new ArrayList<>();

        if (context == null || pdfUri == null || keyword == null || keyword.trim().isEmpty()) {
            return results;
        }

        try {
            File pdfFile = copyUriToSearchCache(context, pdfUri);
            if (pdfFile == null || !pdfFile.exists()) {
                Log.e(TAG, "copy pdf uri to cache failed");
                return results;
            }

            float[] arr = nativeApi.nativeSearch(
                    pdfFile.getAbsolutePath(),
                    keyword,
                    startPage,
                    endPage
            );

            if (arr == null || arr.length == 0) {
                return results;
            }

            int unit = 7;
            int count = arr.length / unit;

            for (int i = 0; i < count; i++) {
                int base = i * unit;

                int pageIndex = (int) arr[base];
                float left = arr[base + 1];
                float top = arr[base + 2];
                float right = arr[base + 3];
                float bottom = arr[base + 4];
                float pageWidth = arr[base + 5];
                float pageHeight = arr[base + 6];

                RectF rect = new RectF(left, top, right, bottom);

                results.add(new PdfiumTextSearchResult(
                        pageIndex,
                        rect,
                        pageWidth,
                        pageHeight
                ));
            }

        } catch (Throwable e) {
            Log.e(TAG, "PDFium text search failed", e);
        }

        return results;
    }

    private File copyUriToSearchCache(Context context, Uri uri) {
        File dir = new File(context.getCacheDir(), "pdfium_text_search");
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }

        String safeName = "search_" + Math.abs(uri.toString().hashCode()) + ".pdf";
        File outFile = new File(dir, safeName);

        /*
         * 第一版简单处理：
         * 如果缓存文件已存在并且大小大于 0，就复用。
         * 后面可以根据文件大小、lastModified、Uri 字符串做更严谨的缓存刷新。
         */
        if (outFile.exists() && outFile.length() > 0) {
            return outFile;
        }

        try (InputStream is = context.getContentResolver().openInputStream(uri);
             FileOutputStream fos = new FileOutputStream(outFile)) {

            if (is == null) return null;

            byte[] buffer = new byte[1024 * 64];
            int len;

            while ((len = is.read(buffer)) > 0) {
                fos.write(buffer, 0, len);
            }

            fos.flush();
            return outFile;

        } catch (Exception e) {
            Log.e(TAG, "copy uri failed", e);
            if (outFile.exists()) {
                //noinspection ResultOfMethodCallIgnored
                outFile.delete();
            }
            return null;
        }
    }

    public List<PdfiumTextSearchResult> searchWithProgress(
            Context context,
            Uri pdfUri,
            String keyword,
            int startPage,
            int endPage,
            PdfSearchProgressListener progressListener,
            PdfSearchCancelChecker cancelChecker
    ) {
        List<PdfiumTextSearchResult> results = new ArrayList<>();

        if (context == null || pdfUri == null || keyword == null || keyword.trim().isEmpty()) {
            return results;
        }

        try {
            /*
             * 第一版没有直接从 PDFiumTextSearchEngine 获取 pageCount 的方法。
             * 所以这里如果 endPage < 0，仍然走原来的全文搜索。
             * 后续我们可以给 native 增加 getPageCount。
             */
            if (endPage < 0) {
                if (progressListener != null) {
                    progressListener.onProgress(startPage, -1, PdfSearchSource.PDF_TEXT_LAYER);
                }

                if (cancelChecker != null && cancelChecker.isCancelled()) {
                    return results;
                }

                return search(context, pdfUri, keyword, startPage, endPage);
            }

            for (int page = startPage; page <= endPage; page++) {
                if (cancelChecker != null && cancelChecker.isCancelled()) {
                    return results;
                }

                if (progressListener != null) {
                    progressListener.onProgress(page, endPage + 1, PdfSearchSource.PDF_TEXT_LAYER);
                }

                List<PdfiumTextSearchResult> pageResults = search(
                        context,
                        pdfUri,
                        keyword,
                        page,
                        page
                );

                if (pageResults != null && !pageResults.isEmpty()) {
                    results.addAll(pageResults);
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
        }

        return results;
    }

}
