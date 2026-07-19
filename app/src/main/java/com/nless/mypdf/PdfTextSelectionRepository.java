package com.nless.mypdf;

import android.content.Context;
import android.graphics.RectF;
import android.net.Uri;

import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.PDPage;
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle;
import com.tom_roush.pdfbox.text.PDFTextStripper;
import com.tom_roush.pdfbox.text.TextPosition;

import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 使用 PdfBox-Android 按页解析文本层，并缓存少量最近页面。
 *
 * <p>所有 PDDocument 操作都固定在一个后台线程，避免 PDFBox 文档对象被并发访问。</p>
 */
public final class PdfTextSelectionRepository implements Closeable {

    public interface Callback {
        void onLoaded(PdfTextPage page);
        void onError(Throwable error);
    }

    private static final int MAX_CACHED_PAGES = 8;

    private final Context appContext;
    private final Uri pdfUri;
    private final String fallbackPath;
    private final String password;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Map<Integer, PdfTextPage> cache = new LinkedHashMap<Integer, PdfTextPage>(
            MAX_CACHED_PAGES,
            0.75f,
            true
    ) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Integer, PdfTextPage> eldest) {
            return size() > MAX_CACHED_PAGES;
        }
    };

    private volatile boolean closed;
    private PDDocument document;

    public PdfTextSelectionRepository(Context context, Uri pdfUri, String fallbackPath) {
        this(context, pdfUri, fallbackPath, "");
    }

    public PdfTextSelectionRepository(Context context, Uri pdfUri, String fallbackPath, String password) {
        this.appContext = context.getApplicationContext();
        this.pdfUri = pdfUri;
        this.fallbackPath = fallbackPath;
        this.password = password == null ? "" : password;
    }

    public void requestPage(int pageIndex, Callback callback) {
        if (callback == null) return;

        synchronized (cache) {
            PdfTextPage cached = cache.get(pageIndex);
            if (cached != null) {
                callback.onLoaded(cached);
                return;
            }
        }

        executor.execute(() -> {
            if (closed) return;
            try {
                PDDocument doc = ensureDocument();
                if (pageIndex < 0 || pageIndex >= doc.getNumberOfPages()) {
                    throw new IOException("页码超出范围");
                }

                PdfTextPage page = extractPage(doc, pageIndex);
                synchronized (cache) {
                    cache.put(pageIndex, page);
                }
                if (!closed) callback.onLoaded(page);
            } catch (Throwable error) {
                if (!closed) callback.onError(error);
            }
        });
    }

    private PDDocument ensureDocument() throws IOException {
        if (document != null) return document;
        try (InputStream input = openInputStream()) {
            document = password.isEmpty() ? PDDocument.load(input) : PDDocument.load(input, password);
            return document;
        }
    }

    private InputStream openInputStream() throws IOException {
        InputStream input = null;
        if (pdfUri != null && "content".equalsIgnoreCase(pdfUri.getScheme())) {
            input = appContext.getContentResolver().openInputStream(pdfUri);
        } else if (pdfUri != null && pdfUri.getPath() != null) {
            File file = new File(pdfUri.getPath());
            if (file.isFile()) input = new FileInputStream(file);
        }

        if (input == null && fallbackPath != null && !fallbackPath.trim().isEmpty()) {
            File file = new File(fallbackPath);
            if (file.isFile()) input = new FileInputStream(file);
        }
        if (input == null) throw new IOException("无法读取源 PDF");
        return input;
    }

    static PdfTextPage extractPage(PDDocument document, int pageIndex) throws IOException {
        PDPage page = document.getPage(pageIndex);
        PageTextStripper stripper = new PageTextStripper(pageIndex, page);
        stripper.setStartPage(pageIndex + 1);
        stripper.setEndPage(pageIndex + 1);
        stripper.setSortByPosition(true);
        stripper.setShouldSeparateByBeads(false);
        stripper.getText(document);
        return stripper.build();
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        synchronized (cache) {
            cache.clear();
        }

        // PDDocument 的解析和关闭必须在同一后台线程串行执行，
        // 避免页面提取尚未结束时由 UI 线程并发 close。
        executor.execute(() -> {
            if (document != null) {
                try {
                    document.close();
                } catch (IOException ignored) {
                }
                document = null;
            }
        });
        executor.shutdown();
    }

    private static final class PageTextStripper extends PDFTextStripper {
        private final int pageIndex;
        private final float sourceWidth;
        private final float sourceHeight;
        private final List<PdfTextPage.Glyph> glyphs = new ArrayList<>();

        private String pendingSeparator = "";
        private int lineIndex = 0;

        PageTextStripper(int pageIndex, PDPage page) throws IOException {
            this.pageIndex = pageIndex;
            PDRectangle cropBox = page.getCropBox();
            int rotation = normalizeRotation(page.getRotation());
            if (rotation == 90 || rotation == 270) {
                sourceWidth = Math.max(1f, cropBox.getHeight());
                sourceHeight = Math.max(1f, cropBox.getWidth());
            } else {
                sourceWidth = Math.max(1f, cropBox.getWidth());
                sourceHeight = Math.max(1f, cropBox.getHeight());
            }
        }

        @Override
        protected void writeWordSeparator() {
            if (!pendingSeparator.contains("\n")) pendingSeparator = " ";
        }

        @Override
        protected void writeLineSeparator() {
            pendingSeparator = "\n";
            lineIndex++;
        }

        @Override
        protected void writeString(String text, List<TextPosition> textPositions) {
            if (textPositions == null || textPositions.isEmpty()) return;

            boolean firstVisiblePosition = true;
            for (TextPosition position : textPositions) {
                if (position == null) continue;
                String unicode = position.getUnicode();
                if (unicode == null || unicode.isEmpty()) continue;

                if (isOnlyWhitespace(unicode)) {
                    if (!pendingSeparator.contains("\n")) pendingSeparator = " ";
                    continue;
                }

                float x = position.getX();
                float baselineY = position.getY();
                float width = Math.max(0.1f, position.getWidth());
                float height = Math.max(0.1f, position.getHeight());

                // TextPosition 的 y 是以页面左上角为原点的基线坐标。
                float left = clamp01(x / sourceWidth);
                float right = clamp01((x + width) / sourceWidth);
                float top = clamp01((baselineY - height) / sourceHeight);
                // 给下行部件留少量空间，视觉上更接近系统文本选择高亮。
                float bottom = clamp01((baselineY
                        + height * PdfTextGeometry.DESCENT_RATIO) / sourceHeight);

                if (right <= left) right = clamp01(left + 0.001f);
                if (bottom <= top) bottom = clamp01(top + 0.001f);

                String separator = firstVisiblePosition ? pendingSeparator : "";
                glyphs.add(new PdfTextPage.Glyph(
                        unicode,
                        new RectF(left, top, right, bottom),
                        separator,
                        lineIndex
                ));
                firstVisiblePosition = false;
                pendingSeparator = "";
            }
        }

        PdfTextPage build() {
            return new PdfTextPage(pageIndex, glyphs);
        }

        private static boolean isOnlyWhitespace(String value) {
            for (int offset = 0; offset < value.length(); ) {
                int codePoint = value.codePointAt(offset);
                if (!Character.isWhitespace(codePoint) && !Character.isSpaceChar(codePoint)) {
                    return false;
                }
                offset += Character.charCount(codePoint);
            }
            return true;
        }

        private static int normalizeRotation(int rotation) {
            int normalized = rotation % 360;
            return normalized < 0 ? normalized + 360 : normalized;
        }

        private static float clamp01(float value) {
            return Math.max(0f, Math.min(1f, value));
        }
    }
}
