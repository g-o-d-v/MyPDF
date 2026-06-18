package com.nless.pdf_search_engine.ocr;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

public class OcrPageRenderer {

    /**
     * 兼容旧调用：只返回 Bitmap。
     */
    public Bitmap renderPageForOcr(
            Context context,
            Uri pdfUri,
            int pageIndex,
            int targetWidthPx
    ) {
        OcrRenderedPage renderedPage = renderPage(
                context,
                pdfUri,
                pageIndex,
                targetWidthPx
        );

        return renderedPage != null ? renderedPage.bitmap : null;
    }

    /**
     * 新接口：返回 Bitmap + PDF 页面尺寸。
     */
    public OcrRenderedPage renderPage(
            Context context,
            Uri pdfUri,
            int pageIndex,
            int targetWidthPx
    ) {
        if (context == null || pdfUri == null) return null;
        if (pageIndex < 0 || targetWidthPx <= 0) return null;

        ParcelFileDescriptor pfd = null;
        PdfRenderer renderer = null;
        PdfRenderer.Page page = null;

        try {
            pfd = context.getContentResolver().openFileDescriptor(pdfUri, "r");
            if (pfd == null) return null;

            renderer = new PdfRenderer(pfd);

            if (pageIndex >= renderer.getPageCount()) {
                return null;
            }

            page = renderer.openPage(pageIndex);

            int pageWidth = page.getWidth();
            int pageHeight = page.getHeight();

            if (pageWidth <= 0 || pageHeight <= 0) {
                return null;
            }

            float scale = targetWidthPx / (float) pageWidth;
            int targetHeightPx = Math.max(1, Math.round(pageHeight * scale));

            Bitmap bitmap = Bitmap.createBitmap(
                    targetWidthPx,
                    targetHeightPx,
                    Bitmap.Config.ARGB_8888
            );

            Canvas canvas = new Canvas(bitmap);
            canvas.drawColor(android.graphics.Color.WHITE);

            page.render(
                    bitmap,
                    null,
                    null,
                    PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY
            );

            return new OcrRenderedPage(
                    pageIndex,
                    bitmap,
                    bitmap.getWidth(),
                    bitmap.getHeight(),
                    pageWidth,
                    pageHeight
            );

        } catch (Exception e) {
            e.printStackTrace();
            return null;

        } finally {
            try {
                if (page != null) page.close();
            } catch (Exception ignored) {
            }

            try {
                if (renderer != null) renderer.close();
            } catch (Exception ignored) {
            }

            try {
                if (pfd != null) pfd.close();
            } catch (Exception ignored) {
            }
        }
    }

    public int getPageCount(Context context, Uri pdfUri) {
        if (context == null || pdfUri == null) return -1;

        ParcelFileDescriptor pfd = null;
        PdfRenderer renderer = null;

        try {
            pfd = context.getContentResolver().openFileDescriptor(pdfUri, "r");
            if (pfd == null) return -1;

            renderer = new PdfRenderer(pfd);
            return renderer.getPageCount();

        } catch (Exception e) {
            e.printStackTrace();
            return -1;

        } finally {
            try {
                if (renderer != null) renderer.close();
            } catch (Exception ignored) {
            }

            try {
                if (pfd != null) pfd.close();
            } catch (Exception ignored) {
            }
        }
    }
}
