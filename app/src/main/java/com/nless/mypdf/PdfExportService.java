package com.nless.mypdf;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import androidx.documentfile.provider.DocumentFile;

import com.nless.pdf_search_engine.core.PdfSearchCacheSource;
import com.nless.pdf_search_engine.core.PdfSearchMode;
import com.nless.pdf_search_engine.core.PdfSearchOptions;
import com.nless.pdf_search_engine.core.PdfSearchPageError;
import com.nless.pdf_search_engine.core.PdfSearchPageOrder;
import com.nless.pdf_search_engine.core.PdfSearchResult;
import com.nless.pdf_search_engine.ocr.OcrPageResult;
import com.nless.pdf_search_engine.ocr.OcrPageSearchListener;
import com.nless.pdf_search_engine.ocr.OcrSearchEngine;
import com.nless.pdf_search_engine.ocr.OcrTextBlock;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.PDPage;
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream;
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle;
import com.tom_roush.pdfbox.pdmodel.font.PDFont;
import com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode;
import com.tom_roush.pdfbox.text.PDFTextStripper;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** PDF 页面图片导出、文本导出和不可见 OCR 文字层写入。 */
final class PdfExportService {

    enum ImageFormat { PNG, JPEG }
    enum TextMode { AUTO, TEXT_ONLY, OCR_ONLY }
    enum SearchableScope { MISSING_TEXT_ONLY, ALL_SELECTED }

    static final class PageRange {
        final int startPage;
        final int endPage;

        PageRange(int startPage, int endPage) {
            this.startPage = Math.max(0, startPage);
            this.endPage = Math.max(this.startPage, endPage);
        }

        int count() {
            return endPage - startPage + 1;
        }
    }

    static final class ImageOptions {
        ImageFormat format = ImageFormat.PNG;
        int targetWidth = 1920;
        int jpegQuality = 90;
        String filePrefix = "page";
    }

    static final class TextOptions {
        TextMode mode = TextMode.AUTO;
        boolean includePageHeaders = true;
        int ocrRenderWidth = SearchPreferences.OCR_WIDTH_BALANCED;
    }

    static final class SearchableOptions {
        SearchableScope scope = SearchableScope.MISSING_TEXT_ONLY;
        int ocrRenderWidth = SearchPreferences.OCR_WIDTH_BALANCED;
    }

    static final class ResultSummary {
        int processedPages;
        int ocrPages;
        int failedPages;
        int outputFiles;
        int skippedPages;
    }

    interface ProgressCallback {
        void onProgress(int current, int total, String message);
    }

    private PdfExportService() {
    }

    static int readPageCount(Context context, Uri pdfUri) throws Exception {
        try (ParcelFileDescriptor descriptor = context.getContentResolver()
                .openFileDescriptor(pdfUri, "r")) {
            if (descriptor == null) throw new IOException("无法打开 PDF 文件");
            try (PdfRenderer renderer = new PdfRenderer(descriptor)) {
                return renderer.getPageCount();
            }
        }
    }

    static ResultSummary exportPagesToImages(
            Context context,
            Uri pdfUri,
            DocumentFile outputDirectory,
            PageRange range,
            ImageOptions options,
            AtomicBoolean cancelled,
            ProgressCallback callback
    ) throws Exception {
        if (outputDirectory == null || !outputDirectory.isDirectory()) {
            throw new IllegalArgumentException("请选择有效的输出文件夹");
        }
        ResultSummary summary = new ResultSummary();
        List<DocumentFile> createdFiles = new ArrayList<>();
        ContentResolver resolver = context.getContentResolver();
        try (ParcelFileDescriptor descriptor = resolver.openFileDescriptor(pdfUri, "r")) {
            if (descriptor == null) throw new IOException("无法打开 PDF 文件");
            try (PdfRenderer renderer = new PdfRenderer(descriptor)) {
                validateRange(range, renderer.getPageCount());
                int pageDigits = Math.max(3, String.valueOf(range.endPage + 1).length());
                int progressUnits = range.count() * 1000;
                for (int pageIndex = range.startPage; pageIndex <= range.endPage; pageIndex++) {
                    checkCancelled(cancelled);
                    PdfRenderer.Page page = null;
                    try {
                        page = renderer.openPage(pageIndex);
                        int renderWidth = Math.max(480, Math.min(4096, options.targetWidth));
                        float scale = renderWidth / (float) Math.max(1, page.getWidth());
                        int totalHeight = Math.max(1, (int) Math.ceil(page.getHeight() * (double) scale));
                        int segmentHeightLimit = resolveImageSegmentHeight(renderWidth);
                        int segmentCount = Math.max(1,
                                (int) Math.ceil(totalHeight / (double) segmentHeightLimit));
                        int segmentDigits = Math.max(2, String.valueOf(segmentCount).length());

                        for (int segment = 0; segment < segmentCount; segment++) {
                            checkCancelled(cancelled);
                            int segmentTop = segment * segmentHeightLimit;
                            int segmentHeight = Math.min(segmentHeightLimit, totalHeight - segmentTop);
                            int progress = (pageIndex - range.startPage) * 1000
                                    + Math.round(segment * 1000f / segmentCount);
                            String segmentMessage = segmentCount == 1
                                    ? "正在渲染第 " + (pageIndex + 1) + " 页"
                                    : "正在渲染第 " + (pageIndex + 1) + " 页，分段 "
                                            + (segment + 1) + " / " + segmentCount;
                            notifyProgress(callback, progress, progressUnits, segmentMessage);

                            Bitmap bitmap = null;
                            try {
                                bitmap = Bitmap.createBitmap(
                                        renderWidth,
                                        segmentHeight,
                                        Bitmap.Config.ARGB_8888
                                );
                                Canvas canvas = new Canvas(bitmap);
                                canvas.drawColor(Color.WHITE);
                                Matrix transform = new Matrix();
                                transform.setScale(scale, scale);
                                transform.postTranslate(0f, -segmentTop);
                                page.render(
                                        bitmap,
                                        null,
                                        transform,
                                        PdfRenderer.Page.RENDER_MODE_FOR_PRINT
                                );

                                String extension = options.format == ImageFormat.JPEG ? "jpg" : "png";
                                String mime = options.format == ImageFormat.JPEG
                                        ? "image/jpeg"
                                        : "image/png";
                                String pageNumber = String.format(
                                        Locale.ROOT,
                                        "%0" + pageDigits + "d",
                                        pageIndex + 1
                                );
                                String part = segmentCount == 1
                                        ? ""
                                        : "-part-" + String.format(
                                                Locale.ROOT,
                                                "%0" + segmentDigits + "d",
                                                segment + 1
                                        );
                                String safePrefix = sanitizeFileName(options.filePrefix, "page");
                                DocumentFile output = outputDirectory.createFile(
                                        mime,
                                        safePrefix + "-" + pageNumber + part + "." + extension
                                );
                                if (output == null) throw new IOException("无法在目标文件夹创建图片");
                                createdFiles.add(output);
                                try (OutputStream stream = resolver.openOutputStream(output.getUri(), "w")) {
                                    if (stream == null) throw new IOException("无法写入导出图片");
                                    Bitmap.CompressFormat format = options.format == ImageFormat.JPEG
                                            ? Bitmap.CompressFormat.JPEG
                                            : Bitmap.CompressFormat.PNG;
                                    int quality = options.format == ImageFormat.JPEG
                                            ? Math.max(50, Math.min(100, options.jpegQuality))
                                            : 100;
                                    if (!bitmap.compress(format, quality, stream)) {
                                        throw new IOException("图片编码失败");
                                    }
                                }
                                summary.outputFiles++;
                            } finally {
                                if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
                            }
                        }
                        summary.processedPages++;
                    } finally {
                        if (page != null) page.close();
                    }
                    int completed = (pageIndex - range.startPage + 1) * 1000;
                    notifyProgress(callback, completed, progressUnits,
                            "已导出第 " + (pageIndex + 1) + " 页");
                }
            }
        } catch (Exception error) {
            for (DocumentFile file : createdFiles) {
                try { file.delete(); } catch (Throwable ignore) { }
            }
            throw error;
        }
        return summary;
    }

    static ResultSummary exportText(
            Context context,
            Uri pdfUri,
            File target,
            PageRange range,
            TextOptions options,
            AtomicBoolean cancelled,
            ProgressCallback callback
    ) throws Exception {
        ResultSummary summary = new ResultSummary();
        String[] pageTexts = new String[range.count()];
        boolean[] needOcr = new boolean[range.count()];

        if (options.mode != TextMode.OCR_ONLY) {
            notifyProgress(callback, 0, range.count(), "正在读取 PDF 文本层");
            try (InputStream input = context.getContentResolver().openInputStream(pdfUri);
                 PDDocument document = PDDocument.load(requireInput(input))) {
                validateRange(range, document.getNumberOfPages());
                PDFTextStripper stripper = new PDFTextStripper();
                stripper.setSortByPosition(true);
                for (int pageIndex = range.startPage; pageIndex <= range.endPage; pageIndex++) {
                    checkCancelled(cancelled);
                    int local = pageIndex - range.startPage;
                    stripper.setStartPage(pageIndex + 1);
                    stripper.setEndPage(pageIndex + 1);
                    String text = normalizeExtractedText(stripper.getText(document));
                    pageTexts[local] = text;
                    needOcr[local] = options.mode == TextMode.AUTO && !hasMeaningfulText(text);
                    notifyProgress(callback, local + 1, range.count(),
                            "正在读取文本层 " + (local + 1) + " / " + range.count());
                }
            }
        } else {
            for (int i = 0; i < needOcr.length; i++) needOcr[i] = true;
        }

        if (options.mode != TextMode.TEXT_ONLY && containsTrue(needOcr)) {
            extractOcrForRequiredPages(
                    context,
                    pdfUri,
                    range,
                    needOcr,
                    options.ocrRenderWidth,
                    pageTexts,
                    summary,
                    cancelled,
                    callback
            );
        }

        checkCancelled(cancelled);
        int nonEmptyPages = 0;
        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(target), StandardCharsets.UTF_8))) {
            for (int pageIndex = range.startPage; pageIndex <= range.endPage; pageIndex++) {
                checkCancelled(cancelled);
                int local = pageIndex - range.startPage;
                String text = pageTexts[local] == null ? "" : pageTexts[local].trim();
                if (!text.isEmpty()) nonEmptyPages++;
                if (options.includePageHeaders) {
                    if (local > 0) writer.newLine();
                    writer.write("===== 第 " + (pageIndex + 1) + " 页 =====");
                    writer.newLine();
                    writer.newLine();
                } else if (local > 0) {
                    writer.newLine();
                    writer.newLine();
                }
                if (text.isEmpty()) {
                    writer.write("[本页未能提取到文字]");
                } else {
                    writer.write(text);
                }
                writer.newLine();
                summary.processedPages++;
            }
        }
        if (nonEmptyPages == 0) {
            throw new IOException(options.mode == TextMode.TEXT_ONLY
                    ? "所选页面没有可导出的文本层，可改用“自动”或“仅 OCR”方式。"
                    : "所选页面未能识别出文字，请尝试提高 OCR 精度或检查扫描清晰度。");
        }
        return summary;
    }

    static ResultSummary createSearchablePdf(
            Context context,
            Uri pdfUri,
            File target,
            PageRange range,
            SearchableOptions options,
            AtomicBoolean cancelled,
            ProgressCallback callback
    ) throws Exception {
        ResultSummary summary = new ResultSummary();
        Map<Integer, OcrPageResult> ocrResults = new HashMap<>();
        boolean[] needOcr = new boolean[range.count()];

        // 第一阶段只判断哪些页面需要 OCR，随后立即关闭文档，降低 OCR 期间的内存占用。
        try (InputStream input = context.getContentResolver().openInputStream(pdfUri);
             PDDocument inspectionDocument = PDDocument.load(requireInput(input))) {
            validateRange(range, inspectionDocument.getNumberOfPages());
            if (options.scope == SearchableScope.ALL_SELECTED) {
                for (int i = 0; i < needOcr.length; i++) needOcr[i] = true;
            } else {
                PDFTextStripper stripper = new PDFTextStripper();
                stripper.setSortByPosition(true);
                for (int pageIndex = range.startPage; pageIndex <= range.endPage; pageIndex++) {
                    checkCancelled(cancelled);
                    int local = pageIndex - range.startPage;
                    stripper.setStartPage(pageIndex + 1);
                    stripper.setEndPage(pageIndex + 1);
                    String text = normalizeExtractedText(stripper.getText(inspectionDocument));
                    needOcr[local] = !hasMeaningfulText(text);
                    if (!needOcr[local]) summary.skippedPages++;
                    notifyProgress(callback, local + 1, range.count(),
                            "正在检查第 " + (pageIndex + 1) + " 页的文本层");
                }
            }
        }

        if (containsTrue(needOcr)) {
            extractOcrForSearchablePdf(
                    context,
                    pdfUri,
                    range,
                    needOcr,
                    options.ocrRenderWidth,
                    ocrResults,
                    summary,
                    cancelled,
                    callback
            );
        }

        // 第二阶段重新加载原 PDF，将 OCR 文字作为不可见内容流写入，然后另存完整副本。
        checkCancelled(cancelled);
        try (InputStream input = context.getContentResolver().openInputStream(pdfUri);
             PDDocument document = PDDocument.load(requireInput(input));
             PdfSystemFontResolver fontResolver =
                     new PdfSystemFontResolver(document, "PdfSearchableLayerFont")) {
            validateRange(range, document.getNumberOfPages());
            notifyProgress(callback, 0, Math.max(1, ocrResults.size()), "正在写入不可见文字层");
            int handled = 0;
            for (int pageIndex = range.startPage; pageIndex <= range.endPage; pageIndex++) {
                checkCancelled(cancelled);
                OcrPageResult result = ocrResults.get(pageIndex);
                if (result == null || result.blocks == null || result.blocks.isEmpty()) continue;
                handled++;
                PDPage page = document.getPage(pageIndex);
                if (normalizeRotation(page.getRotation()) != 0) {
                    summary.failedPages++;
                    notifyProgress(callback, handled, Math.max(1, ocrResults.size()),
                            "第 " + (pageIndex + 1) + " 页为旋转页面，首版暂未写入");
                    continue;
                }
                int tokens = appendInvisibleTextLayer(document, page, result, fontResolver);
                if (tokens > 0) {
                    summary.processedPages++;
                } else {
                    summary.failedPages++;
                }
                notifyProgress(callback, handled, Math.max(1, ocrResults.size()),
                        "正在写入第 " + (pageIndex + 1) + " 页文字层");
            }
            checkCancelled(cancelled);
            notifyProgress(callback, 1, 1, "正在保存可搜索 PDF");
            document.save(target);
        }
        return summary;
    }

    private static void extractOcrForRequiredPages(
            Context context,
            Uri pdfUri,
            PageRange range,
            boolean[] needOcr,
            int renderWidth,
            String[] pageTexts,
            ResultSummary summary,
            AtomicBoolean cancelled,
            ProgressCallback callback
    ) throws Exception {
        OcrSearchEngine engine = new OcrSearchEngine(context);
        Set<Integer> completed = new HashSet<>();
        int totalOcrPages = countTrue(needOcr);
        int[] completedCount = {0};
        for (int[] run : contiguousRuns(range, needOcr)) {
            PdfSearchOptions searchOptions = buildOcrOptions(run[0], run[1], renderWidth);
            engine.extractPages(
                    pdfUri,
                    searchOptions,
                    () -> cancelled != null && cancelled.get(),
                    null,
                    new OcrPageSearchListener() {
                        @Override
                        public void onPageCompleted(
                                int pageIndex,
                                int documentPageCount,
                                int processedPages,
                                int targetPages,
                                List<PdfSearchResult> pageResults,
                                PdfSearchCacheSource cacheSource,
                                long elapsedMillis,
                                int cumulativeMatchCount
                        ) {
                            markOcrProgress(pageIndex, completed, completedCount, totalOcrPages,
                                    callback, "正在 OCR 第 " + (pageIndex + 1) + " 页");
                        }

                        @Override
                        public void onPageExtracted(
                                int pageIndex,
                                OcrPageResult pageResult,
                                PdfSearchCacheSource cacheSource
                        ) {
                            int local = pageIndex - range.startPage;
                            if (local >= 0 && local < pageTexts.length && pageResult != null) {
                                pageTexts[local] = normalizeExtractedText(pageResult.fullText);
                                summary.ocrPages++;
                            }
                        }

                        @Override
                        public void onPageFailed(PdfSearchPageError error) {
                            if (error != null) {
                                summary.failedPages++;
                                markOcrProgress(error.pageIndex, completed, completedCount,
                                        totalOcrPages, callback,
                                        "第 " + (error.pageIndex + 1) + " 页 OCR 失败，继续处理");
                            }
                        }
                    }
            );
            checkCancelled(cancelled);
        }
    }

    private static void extractOcrForSearchablePdf(
            Context context,
            Uri pdfUri,
            PageRange range,
            boolean[] needOcr,
            int renderWidth,
            Map<Integer, OcrPageResult> output,
            ResultSummary summary,
            AtomicBoolean cancelled,
            ProgressCallback callback
    ) throws Exception {
        OcrSearchEngine engine = new OcrSearchEngine(context);
        Set<Integer> completed = new HashSet<>();
        int totalOcrPages = countTrue(needOcr);
        int[] completedCount = {0};
        for (int[] run : contiguousRuns(range, needOcr)) {
            PdfSearchOptions searchOptions = buildOcrOptions(run[0], run[1], renderWidth);
            engine.extractPages(
                    pdfUri,
                    searchOptions,
                    () -> cancelled != null && cancelled.get(),
                    null,
                    new OcrPageSearchListener() {
                        @Override
                        public void onPageCompleted(
                                int pageIndex,
                                int documentPageCount,
                                int processedPages,
                                int targetPages,
                                List<PdfSearchResult> pageResults,
                                PdfSearchCacheSource cacheSource,
                                long elapsedMillis,
                                int cumulativeMatchCount
                        ) {
                            markOcrProgress(pageIndex, completed, completedCount, totalOcrPages,
                                    callback, "正在 OCR 第 " + (pageIndex + 1) + " 页");
                        }

                        @Override
                        public void onPageExtracted(
                                int pageIndex,
                                OcrPageResult pageResult,
                                PdfSearchCacheSource cacheSource
                        ) {
                            if (pageResult != null) {
                                output.put(pageIndex, pageResult);
                                summary.ocrPages++;
                            }
                        }

                        @Override
                        public void onPageFailed(PdfSearchPageError error) {
                            if (error != null) {
                                summary.failedPages++;
                                markOcrProgress(error.pageIndex, completed, completedCount,
                                        totalOcrPages, callback,
                                        "第 " + (error.pageIndex + 1) + " 页 OCR 失败，继续处理");
                            }
                        }
                    }
            );
            checkCancelled(cancelled);
        }
    }

    private static PdfSearchOptions buildOcrOptions(int startPage, int endPage, int renderWidth) {
        PdfSearchOptions options = new PdfSearchOptions();
        options.mode = PdfSearchMode.OCR_ONLY;
        options.currentPageOnly = false;
        options.currentPage = startPage;
        options.startPage = startPage;
        options.endPage = endPage;
        options.allowFullDocumentOcr = true;
        options.maxOcrPages = 0;
        options.ocrRenderWidth = renderWidth > 0
                ? renderWidth
                : SearchPreferences.OCR_WIDTH_BALANCED;
        options.ocrPageOrder = PdfSearchPageOrder.NATURAL;
        options.enableOcrPipeline = true;
        options.ocrPrefetchPages = 1;
        options.usePersistentOcrCache = true;
        options.enableDocumentIndex = false;
        options.usePersistentPageIndexCache = false;
        options.detectMultiColumnLayout = true;
        return options;
    }

    private static int appendInvisibleTextLayer(
            PDDocument document,
            PDPage page,
            OcrPageResult result,
            PdfSystemFontResolver fontResolver
    ) throws Exception {
        PDRectangle crop = page.getCropBox() != null ? page.getCropBox() : page.getMediaBox();
        if (crop == null || crop.getWidth() <= 0 || crop.getHeight() <= 0
                || result.bitmapWidth <= 0 || result.bitmapHeight <= 0) {
            return 0;
        }
        int written = 0;
        try (PDPageContentStream out = new PDPageContentStream(
                document,
                page,
                PDPageContentStream.AppendMode.APPEND,
                true,
                true
        )) {
            for (OcrTextBlock block : result.blocks) {
                if (block == null || block.text == null || block.text.trim().isEmpty()
                        || block.rectInBitmap == null) continue;
                if (block.hasTokenBoxes()) {
                    int count = block.getTokenCount();
                    for (int i = 0; i < count; i++) {
                        int start = block.tokenUtf16Starts[i];
                        int end = block.tokenUtf16Ends[i];
                        if (start < 0 || end <= start || end > block.text.length()) continue;
                        String token = sanitizePdfText(block.text.substring(start, end));
                        if (token.isEmpty()) continue;
                        int boxOffset = i * 4;
                        RectF tokenRect = tokenRect(block.rectInBitmap, block.tokenBoxesInLine,
                                boxOffset);
                        if (writeInvisibleToken(out, token, tokenRect, result, crop, fontResolver)) {
                            written++;
                        }
                    }
                } else {
                    String text = sanitizePdfText(block.text);
                    if (!text.isEmpty() && writeInvisibleToken(
                            out, text, block.rectInBitmap, result, crop, fontResolver)) {
                        written++;
                    }
                }
            }
        }
        return written;
    }

    private static RectF tokenRect(RectF lineRect, float[] boxes, int offset) {
        float width = Math.max(1f, lineRect.width());
        float height = Math.max(1f, lineRect.height());
        return new RectF(
                lineRect.left + clamp01(boxes[offset]) * width,
                lineRect.top + clamp01(boxes[offset + 1]) * height,
                lineRect.left + clamp01(boxes[offset + 2]) * width,
                lineRect.top + clamp01(boxes[offset + 3]) * height
        );
    }

    private static boolean writeInvisibleToken(
            PDPageContentStream out,
            String text,
            RectF bitmapRect,
            OcrPageResult result,
            PDRectangle crop,
            PdfSystemFontResolver fontResolver
    ) {
        try {
            float leftRatio = clamp01(bitmapRect.left / result.bitmapWidth);
            float rightRatio = clamp01(bitmapRect.right / result.bitmapWidth);
            float topRatio = clamp01(bitmapRect.top / result.bitmapHeight);
            float bottomRatio = clamp01(bitmapRect.bottom / result.bitmapHeight);
            float boxWidth = Math.max(0.5f, (rightRatio - leftRatio) * crop.getWidth());
            float boxHeight = Math.max(1f, (bottomRatio - topRatio) * crop.getHeight());
            float x = crop.getLowerLeftX() + leftRatio * crop.getWidth();
            float bottom = crop.getLowerLeftY() + crop.getHeight()
                    - bottomRatio * crop.getHeight();

            PDFont font = fontResolver.resolve(text, false);
            float fontSize = Math.max(1f, boxHeight * 0.82f);
            float naturalWidth = font.getStringWidth(text) / 1000f * fontSize;
            if (naturalWidth <= 0f) return false;
            float horizontalScale = Math.max(10f, Math.min(500f,
                    boxWidth / naturalWidth * 100f));
            float baseline = bottom + Math.max(0f, (boxHeight - fontSize) * 0.20f);

            boolean beganText = false;
            try {
                out.beginText();
                beganText = true;
                out.setRenderingMode(RenderingMode.NEITHER);
                out.setFont(font, fontSize);
                out.setHorizontalScaling(horizontalScale);
                out.newLineAtOffset(x, baseline);
                out.showText(text);
                out.endText();
                beganText = false;
                return true;
            } finally {
                if (beganText) {
                    try { out.endText(); } catch (Throwable ignore) { }
                }
            }
        } catch (Throwable ignore) {
            return false;
        }
    }

    private static List<int[]> contiguousRuns(PageRange range, boolean[] required) {
        List<int[]> runs = new ArrayList<>();
        int index = 0;
        while (index < required.length) {
            while (index < required.length && !required[index]) index++;
            if (index >= required.length) break;
            int start = index;
            while (index + 1 < required.length && required[index + 1]) index++;
            int end = index;
            runs.add(new int[]{range.startPage + start, range.startPage + end});
            index++;
        }
        return runs;
    }

    private static void markOcrProgress(
            int pageIndex,
            Set<Integer> completed,
            int[] completedCount,
            int total,
            ProgressCallback callback,
            String message
    ) {
        if (completed.add(pageIndex)) completedCount[0]++;
        notifyProgress(callback, completedCount[0], Math.max(1, total), message);
    }

    private static String normalizeExtractedText(String text) {
        if (text == null) return "";
        return text.replace("\r\n", "\n")
                .replace('\r', '\n')
                .replace("\u0000", "")
                .trim();
    }

    private static boolean hasMeaningfulText(String text) {
        if (text == null) return false;
        int visible = 0;
        for (int offset = 0; offset < text.length();) {
            int cp = text.codePointAt(offset);
            offset += Character.charCount(cp);
            if (!Character.isWhitespace(cp) && !Character.isISOControl(cp)) visible++;
            if (visible >= 8) return true;
        }
        return false;
    }

    private static int resolveImageSegmentHeight(int width) {
        // 限制单个 ARGB 位图约 12M 像素；超长 PDF 页面按纵向分段，避免缩窄后失去可读性。
        int byPixels = Math.max(512, 12_000_000 / Math.max(1, width));
        return Math.max(512, Math.min(8192, byPixels));
    }

    private static void validateRange(PageRange range, int pageCount) {
        if (pageCount <= 0) throw new IllegalArgumentException("PDF 没有可处理的页面");
        if (range == null || range.startPage < 0 || range.endPage >= pageCount
                || range.startPage > range.endPage) {
            throw new IllegalArgumentException("页面范围无效");
        }
    }

    private static InputStream requireInput(InputStream input) throws IOException {
        if (input == null) throw new IOException("无法打开 PDF 文件");
        return input;
    }

    private static void checkCancelled(AtomicBoolean cancelled) throws IOException {
        if (cancelled != null && cancelled.get()) throw new IOException("操作已取消");
    }

    private static void notifyProgress(
            ProgressCallback callback,
            int current,
            int total,
            String message
    ) {
        if (callback != null) callback.onProgress(current, Math.max(1, total), message);
    }

    private static boolean containsTrue(boolean[] values) {
        for (boolean value : values) if (value) return true;
        return false;
    }

    private static int countTrue(boolean[] values) {
        int count = 0;
        for (boolean value : values) if (value) count++;
        return count;
    }

    private static int normalizeRotation(int rotation) {
        int value = rotation % 360;
        if (value < 0) value += 360;
        return value;
    }

    private static float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    private static String sanitizePdfText(String text) {
        if (text == null) return "";
        StringBuilder out = new StringBuilder(text.length());
        for (int offset = 0; offset < text.length();) {
            int cp = text.codePointAt(offset);
            offset += Character.charCount(cp);
            if (cp == '\n' || cp == '\r' || cp == '\t') {
                out.append(' ');
            } else if (!Character.isISOControl(cp)) {
                out.appendCodePoint(cp);
            }
        }
        return out.toString().trim();
    }

    private static String sanitizeFileName(String value, String fallback) {
        String text = value == null ? "" : value.trim();
        text = text.replaceAll("[\\\\/:*?\"<>|]", "_");
        while (text.endsWith(".")) text = text.substring(0, text.length() - 1);
        return text.isEmpty() ? fallback : text;
    }
}
