package com.nless.pdf_search_engine.androidpdfviewer;

import java.util.ArrayList;
import java.util.List;

public class PdfOverlaySearchHighlighter {

    private final List<AndroidPdfViewerAdapter.ViewerSearchHighlight> highlights =
            new ArrayList<>();

    private int currentIndex = -1;

    public void setHighlights(
            List<AndroidPdfViewerAdapter.ViewerSearchHighlight> newHighlights
    ) {
        highlights.clear();

        if (newHighlights != null) {
            highlights.addAll(newHighlights);
        }

        currentIndex = highlights.isEmpty() ? -1 : 0;
    }

    public void clear() {
        highlights.clear();
        currentIndex = -1;
    }

    public boolean isEmpty() {
        return highlights.isEmpty();
    }

    public int size() {
        return highlights.size();
    }

    public int getCurrentIndex() {
        return currentIndex;
    }

    public AndroidPdfViewerAdapter.ViewerSearchHighlight getCurrent() {
        if (currentIndex < 0 || currentIndex >= highlights.size()) {
            return null;
        }
        return highlights.get(currentIndex);
    }

    public AndroidPdfViewerAdapter.ViewerSearchHighlight get(int index) {
        if (index < 0 || index >= highlights.size()) {
            return null;
        }
        return highlights.get(index);
    }

    public List<AndroidPdfViewerAdapter.ViewerSearchHighlight> getHighlights() {
        return new ArrayList<>(highlights);
    }

    public AndroidPdfViewerAdapter.ViewerSearchHighlight next() {
        if (highlights.isEmpty()) {
            currentIndex = -1;
            return null;
        }

        currentIndex++;

        if (currentIndex >= highlights.size()) {
            currentIndex = 0;
        }

        return highlights.get(currentIndex);
    }

    public AndroidPdfViewerAdapter.ViewerSearchHighlight previous() {
        if (highlights.isEmpty()) {
            currentIndex = -1;
            return null;
        }

        currentIndex--;

        if (currentIndex < 0) {
            currentIndex = highlights.size() - 1;
        }

        return highlights.get(currentIndex);
    }

    public void setCurrentIndex(int index) {
        if (index >= 0 && index < highlights.size()) {
            currentIndex = index;
        } else {
            currentIndex = -1;
        }
    }
}
