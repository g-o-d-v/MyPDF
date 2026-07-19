package com.nless.mypdf.feature.manage;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.text.TextUtils;
import android.util.LruCache;

import com.shockwave.pdfium.PdfDocument;
import com.shockwave.pdfium.PdfiumCore;

import java.io.Closeable;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 使用 Pdfium 按需生成页面缩略图。
 *
 * <p>Pdfium 可以直接接收 PDF 密码，因此加密文件不需要先由 PDFBox 完整解密、
 * 重写成临时 PDF 后再交给缩略图渲染器。这样可显著降低加密文件进入页面管理时
 * 的等待时间。</p>
 */
final class PdfThumbnailRepository implements Closeable {

    interface Callback {
        void onLoaded(int pageIndex, Bitmap bitmap);
        void onFailed(int pageIndex);
    }

    private static final int CACHE_BYTES = 18 * 1024 * 1024;

    private final PdfiumCore pdfiumCore;
    private final PdfDocument document;
    private final int pageCount;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Object rendererLock = new Object();
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final Set<Integer> openedPages = new HashSet<>();
    private final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(CACHE_BYTES) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value == null ? 0 : value.getAllocationByteCount();
        }
    };

    PdfThumbnailRepository(Context context, Uri uri) throws IOException {
        this(context, uri, "");
    }

    PdfThumbnailRepository(Context context, Uri uri, String password) throws IOException {
        ParcelFileDescriptor descriptor = context.getContentResolver().openFileDescriptor(uri, "r");
        if (descriptor == null) throw new IOException("无法打开 PDF 文件");
        PdfiumCore core = new PdfiumCore(context.getApplicationContext());
        PdfDocument openedDocument = null;
        try {
            openedDocument = core.newDocument(
                    descriptor,
                    TextUtils.isEmpty(password) ? null : password
            );
            int count = core.getPageCount(openedDocument);
            if (count <= 0) throw new IOException("PDF 没有可读取的页面");
            pdfiumCore = core;
            document = openedDocument;
            pageCount = count;
        } catch (Throwable error) {
            if (openedDocument != null) {
                try {
                    core.closeDocument(openedDocument);
                } catch (Throwable ignore) {
                }
            } else {
                try {
                    descriptor.close();
                } catch (Throwable ignore) {
                }
            }
            if (error instanceof IOException) throw (IOException) error;
            throw new IOException("无法读取 PDF，密码错误、文件损坏或格式不受支持", error);
        }
    }

    int getPageCount() {
        return pageCount;
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
                    if (pageIndex < 0 || pageIndex >= pageCount) {
                        throw new IOException("页码超出范围");
                    }
                    if (openedPages.add(pageIndex)) {
                        pdfiumCore.openPage(document, pageIndex);
                    }

                    int sourceWidth = Math.max(1, pdfiumCore.getPageWidthPoint(document, pageIndex));
                    int sourceHeight = Math.max(1, pdfiumCore.getPageHeightPoint(document, pageIndex));
                    float aspect = sourceHeight / (float) sourceWidth;

                    int outputWidth;
                    int outputHeight;
                    int renderedPageWidth;
                    int renderedPageHeight;
                    if (aspect > 3.2f) {
                        // 超长页只在缩略图中显示顶部预览，但仍按页面原比例渲染后裁切，
                        // 不把整页强行压扁成一条细线。
                        outputWidth = safeWidth;
                        outputHeight = safeHeight;
                        float scale = outputWidth / (float) sourceWidth;
                        renderedPageWidth = outputWidth;
                        renderedPageHeight = Math.max(outputHeight, Math.round(sourceHeight * scale));
                    } else {
                        float scale = Math.min(
                                safeWidth / (float) sourceWidth,
                                safeHeight / (float) sourceHeight
                        );
                        outputWidth = Math.max(1, Math.round(sourceWidth * scale));
                        outputHeight = Math.max(1, Math.round(sourceHeight * scale));
                        renderedPageWidth = outputWidth;
                        renderedPageHeight = outputHeight;
                    }

                    // 页面管理只需要缩略图，RGB_565 的内存和像素写入量约为 ARGB_8888 的一半。
                    bitmap = Bitmap.createBitmap(
                            outputWidth,
                            outputHeight,
                            Bitmap.Config.RGB_565
                    );
                    bitmap.eraseColor(Color.WHITE);
                    pdfiumCore.renderPageBitmap(
                            document,
                            bitmap,
                            pageIndex,
                            0,
                            0,
                            renderedPageWidth,
                            renderedPageHeight,
                            true
                    );
                }
                if (closed.get()) {
                    if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
                    return;
                }
                cache.put(key, bitmap);
                Bitmap result = bitmap;
                mainHandler.post(() -> {
                    if (!closed.get()) callback.onLoaded(pageIndex, result);
                });
            } catch (Throwable error) {
                if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
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
            try {
                pdfiumCore.closeDocument(document);
            } catch (Throwable ignore) {
            }
            openedPages.clear();
        }
        cache.evictAll();
    }
}
