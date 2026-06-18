package com.nless.pdf_search_engine.androidpdfviewer;

import android.graphics.RectF;

import com.github.barteksc.pdfviewer.PDFView;
import com.nless.pdf_search_engine.coordinate.PdfCoordinateConverter;
import com.nless.pdf_search_engine.coordinate.PdfPageInfo;
import com.nless.pdf_search_engine.core.PdfSearchRect;
import com.nless.pdf_search_engine.core.PdfSearchResult;
import com.nless.pdf_search_engine.core.PdfSearchSource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class AndroidPdfViewerAdapter {

    private final PDFView pdfView;

    public AndroidPdfViewerAdapter(PDFView pdfView) {
        this.pdfView = pdfView;
    }

    public List<ViewerSearchHighlight> convertResults(
            List<PdfSearchResult> results
    ) {
        List<ViewerSearchHighlight> highlights = new ArrayList<>();

        if (pdfView == null || results == null || results.isEmpty()) {
            return highlights;
        }

        for (PdfSearchResult result : results) {
            if (result == null || result.rects == null) continue;

            PdfSearchSource source = result.source;

            for (PdfSearchRect rect : result.rects) {
                RectF docRect = convertRect(rect, source);

                if (docRect != null) {
                    highlights.add(new ViewerSearchHighlight(
                            rect.pageIndex,
                            docRect,
                            source,
                            result.keyword,
                            result.matchedText
                    ));
                }
            }
        }

        sortHighlights(highlights);

        return highlights;
    }

    public ViewerSearchHighlight convertSingle(
            PdfSearchRect rect,
            PdfSearchSource source,
            String keyword,
            String matchedText
    ) {
        RectF docRect = convertRect(rect, source);
        if (docRect == null) return null;

        return new ViewerSearchHighlight(
                rect.pageIndex,
                docRect,
                source,
                keyword,
                matchedText
        );
    }

    private RectF convertRect(PdfSearchRect rect, PdfSearchSource source) {
        if (rect == null || rect.rectInPdfPoint == null) return null;
        if (pdfView == null) return null;

        int pageIndex = rect.pageIndex;

        try {
            float viewPageWidth = pdfView.getPageSize(pageIndex).getWidth();
            float viewPageHeight = pdfView.getPageSize(pageIndex).getHeight();
            float spacing = pdfView.getSpacingPx();

            float pageStartY = pageIndex * (viewPageHeight + spacing);

            PdfPageInfo pageInfo = new PdfPageInfo(
                    pageIndex,
                    viewPageWidth,
                    viewPageHeight,
                    rect.pageWidth,
                    rect.pageHeight,
                    spacing,
                    pageStartY
            );

            return PdfCoordinateConverter.searchRectToAndroidPdfViewerDocRect(
                    rect,
                    pageInfo,
                    source
            );
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    public static void sortHighlights(List<ViewerSearchHighlight> highlights) {
        if (highlights == null) return;

        Collections.sort(highlights, (a, b) -> {
            if (a.pageIndex != b.pageIndex) {
                return Integer.compare(a.pageIndex, b.pageIndex);
            }

            if (a.rectInDoc == null || b.rectInDoc == null) {
                return 0;
            }

            int yCompare = Float.compare(a.rectInDoc.top, b.rectInDoc.top);
            if (yCompare != 0) return yCompare;

            return Float.compare(a.rectInDoc.left, b.rectInDoc.left);
        });
    }

    public static class ViewerSearchHighlight {

        public final int pageIndex;
        public final RectF rectInDoc;
        public final PdfSearchSource source;
        public final String keyword;
        public final String matchedText;

        public ViewerSearchHighlight(
                int pageIndex,
                RectF rectInDoc,
                PdfSearchSource source,
                String keyword,
                String matchedText
        ) {
            this.pageIndex = pageIndex;
            this.rectInDoc = rectInDoc;
            this.source = source;
            this.keyword = keyword;
            this.matchedText = matchedText;
        }
    }
}
