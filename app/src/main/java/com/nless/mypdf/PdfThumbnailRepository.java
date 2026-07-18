package com.nless.mypdf;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.util.LruCache;

import java.io.Closeable;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 使用 Android PdfRenderer 按需生成页面缩略图。
 *
 * 只缓存少量最近页面，避免几百页、上千页 PDF 在页面管理界面中占用过多内存。
 */
final class PdfThumbnailRepository implements Closeable {

    interface Callback {
        void onLoaded(int pageIndex, Bitmap bitmap);
        void onFailed(int pageIndex);
    }

    private static final int CACHE_BYTES = 18 * 1024 * 1024;

    private final ParcelFileDescriptor descriptor;
    private final PdfRenderer renderer;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Object rendererLock = new Object();
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(CACHE_BYTES) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value == null ? 0 : value.getAllocationByteCount();
        }
    };

    PdfThumbnailRepository(Context context, Uri uri) throws IOException {
        descriptor = context.getContentResolver().openFileDescriptor(uri, "r");
        if (descriptor == null) throw new IOException("无法打开 PDF 文件");
        try {
            renderer = new PdfRenderer(descriptor);
        } catch (Throwable error) {
            try { descriptor.close(); } catch (Throwable ignore) { }
            throw new IOException("无法读取 PDF，文件可能已加密、损坏或格式不受支持", error);
        }
    }

    int getPageCount() {
        synchronized (rendererLock) {
            return renderer.getPageCount();
        }
    }

    static int countPages(Context context, Uri uri) throws IOException {
        try (PdfThumbnailRepository repository = new PdfThumbnailRepository(context, uri)) {
            return repository.getPageCount();
        }
    }

    void request(int pageIndex, int maxWidth, int maxHeight, Callback callback) {
        if (callback == null || closed.get()) return;
        int safeWidth = Math.max(80, maxWidth);
        int safeHeight = Math.max(100, maxHeight);
        String key = pageIndex + "@" + safeWidth + "x" + safeHeight;
        Bitmap cached = cache.get(key);
        if (cached != null && !cached.isRecycled()) {
            callback.onLoaded(pageIndex, cached);
            return;
        }

        executor.execute(() -> {
            if (closed.get()) return;
            Bitmap bitmap = null;
            try {
                synchronized (rendererLock) {
                    if (closed.get()) return;
                    if (pageIndex < 0 || pageIndex >= renderer.getPageCount()) {
                        throw new IOException("页码超出范围");
                    }
                    try (PdfRenderer.Page page = renderer.openPage(pageIndex)) {
                        int sourceWidth = Math.max(1, page.getWidth());
                        int sourceHeight = Math.max(1, page.getHeight());
                        float aspect = sourceHeight / (float) sourceWidth;

                        int outputWidth;
                        int outputHeight;
                        float scale;
                        if (aspect > 3.2f) {
                            // 超长页若完整缩放会变成一条细线，因此展示页面顶部的可读预览。
                            outputWidth = safeWidth;
                            outputHeight = safeHeight;
                            scale = outputWidth / (float) sourceWidth;
                        } else {
                            scale = Math.min(
                                    safeWidth / (float) sourceWidth,
                                    safeHeight / (float) sourceHeight
                            );
                            outputWidth = Math.max(1, Math.round(sourceWidth * scale));
                            outputHeight = Math.max(1, Math.round(sourceHeight * scale));
                        }

                        bitmap = Bitmap.createBitmap(
                                outputWidth,
                                outputHeight,
                                Bitmap.Config.ARGB_8888
                        );
                        bitmap.eraseColor(Color.WHITE);
                        Matrix matrix = new Matrix();
                        matrix.setScale(scale, scale);
                        page.render(
                                bitmap,
                                new Rect(0, 0, outputWidth, outputHeight),
                                matrix,
                                PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY
                        );
                    }
                }
                if (closed.get()) return;
                cache.put(key, bitmap);
                Bitmap result = bitmap;
                mainHandler.post(() -> {
                    if (!closed.get()) callback.onLoaded(pageIndex, result);
                });
            } catch (Throwable error) {
                mainHandler.post(() -> {
                    if (!closed.get()) callback.onFailed(pageIndex);
                });
            }
        });
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        executor.shutdownNow();
        synchronized (rendererLock) {
            try { renderer.close(); } catch (Throwable ignore) { }
            try { descriptor.close(); } catch (Throwable ignore) { }
        }
        cache.evictAll();
    }
}
