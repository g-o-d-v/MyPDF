package com.nless.pdf_search_engine.coordinate;

import android.graphics.RectF;

import com.nless.pdf_search_engine.core.PdfSearchRect;
import com.nless.pdf_search_engine.core.PdfSearchSource;

public class PdfCoordinateConverter {

    /**
     * 将搜索结果坐标转换为 AndroidPdfViewer 的文档坐标。
     *
     * 输入坐标约定：
     * - rectInPdfPoint 原点左下，Y 向上。
     *
     * 输出坐标约定：
     * - AndroidPdfViewer 文档坐标
     * - 原点左上，Y 向下
     * - 已经加上 pageStartY
     */
    public static RectF searchRectToAndroidPdfViewerDocRect(
            PdfSearchRect searchRect,
            PdfPageInfo pageInfo,
            PdfSearchSource source
    ) {
        if (searchRect == null || searchRect.rectInPdfPoint == null) return null;
        if (pageInfo == null) return null;
        if (pageInfo.sourcePageWidth <= 0 || pageInfo.sourcePageHeight <= 0) return null;
        if (pageInfo.viewPageWidth <= 0 || pageInfo.viewPageHeight <= 0) return null;

        RectF src = searchRect.rectInPdfPoint;

        float left = src.left / pageInfo.sourcePageWidth * pageInfo.viewPageWidth;
        float right = src.right / pageInfo.sourcePageWidth * pageInfo.viewPageWidth;

        /*
         * 输入是 PDF 坐标：原点左下，Y 向上。
         * 输出是 Android 文档坐标：原点左上，Y 向下。
         */
        float top = (pageInfo.sourcePageHeight - src.bottom)
                / pageInfo.sourcePageHeight * pageInfo.viewPageHeight;
        float bottom = (pageInfo.sourcePageHeight - src.top)
                / pageInfo.sourcePageHeight * pageInfo.viewPageHeight;

        RectF raw = new RectF(
                left,
                pageInfo.pageStartY + top,
                right,
                pageInfo.pageStartY + bottom
        );

        if (source == PdfSearchSource.OCR) {
            return normalizeOcrHighlightRect(raw, pageInfo.viewPageHeight);
        } else {
            return normalizeTextHighlightRect(raw, pageInfo.viewPageHeight);
        }
    }

    public static RectF normalizeTextHighlightRect(RectF rect, float pageHeight) {
        if (rect == null) return null;

        float height = rect.height();

        float minHeight = pageHeight * 0.010f;
        float maxHeight = pageHeight * 0.045f;

        float targetHeight = height;

        if (targetHeight < minHeight) {
            targetHeight = minHeight;
        } else if (targetHeight > maxHeight) {
            targetHeight = maxHeight;
        }

        float cy = rect.centerY();
        float paddingX = Math.max(1f, rect.width() * 0.04f);

        return new RectF(
                rect.left - paddingX,
                cy - targetHeight / 2f,
                rect.right + paddingX,
                cy + targetHeight / 2f
        );
    }

    public static RectF normalizeOcrHighlightRect(RectF rect, float pageHeight) {
        if (rect == null) return null;

        float height = rect.height();

        float minHeight = pageHeight * 0.014f;
        float maxHeight = pageHeight * 0.040f;

        float targetHeight = height;

        if (targetHeight < minHeight) {
            targetHeight = minHeight;
        } else if (targetHeight > maxHeight) {
            targetHeight = maxHeight;
        }

        float cy = rect.centerY();

        /*
         * OCR 框通常略偏低，向上校正。
         * 后续可以做成配置项。
         */
        float shiftUp = targetHeight * 0.18f;
        cy -= shiftUp;

        float paddingX = Math.max(1f, rect.width() * 0.05f);

        return new RectF(
                rect.left - paddingX,
                cy - targetHeight / 2f,
                rect.right + paddingX,
                cy + targetHeight / 2f
        );
    }
}
