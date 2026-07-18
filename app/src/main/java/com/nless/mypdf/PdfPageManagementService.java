package com.nless.mypdf;

import android.content.Context;
import android.net.Uri;

import com.tom_roush.pdfbox.cos.COSDictionary;
import com.tom_roush.pdfbox.io.MemoryUsageSetting;
import com.tom_roush.pdfbox.multipdf.PDFCloneUtility;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.PDPage;
import com.tom_roush.pdfbox.pdmodel.PDPageTree;
import com.tom_roush.pdfbox.pdmodel.PDResources;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** PDF 页面排序、删除、另存和合并的底层处理。 */
final class PdfPageManagementService {

    interface ProgressCallback {
        void onProgress(int current, int total, String message);
    }

    static final class ResultSummary {
        int sourcePages;
        int outputPages;
        int sourceFiles;
    }

    private PdfPageManagementService() {
    }

    static ResultSummary reorderPages(
            Context context,
            Uri sourceUri,
            File target,
            List<Integer> order,
            AtomicBoolean cancelled,
            ProgressCallback callback
    ) throws Exception {
        try (InputStream input = requireInput(context, sourceUri);
             PDDocument document = PDDocument.load(input)) {
            int pageCount = document.getNumberOfPages();
            validateCompleteOrder(order, pageCount);
            checkCancelled(cancelled);

            List<PDPage> originalPages = new ArrayList<>(pageCount);
            for (PDPage page : document.getPages()) originalPages.add(page);

            for (int i = pageCount - 1; i >= 0; i--) {
                checkCancelled(cancelled);
                document.removePage(i);
                notifyProgress(callback, pageCount - i, pageCount * 2,
                        "正在整理原页面结构");
            }
            for (int outputIndex = 0; outputIndex < order.size(); outputIndex++) {
                checkCancelled(cancelled);
                document.addPage(originalPages.get(order.get(outputIndex)));
                notifyProgress(callback, pageCount + outputIndex + 1, pageCount * 2,
                        "正在写入第 " + (outputIndex + 1) + " / " + pageCount + " 页");
            }
            checkCancelled(cancelled);
            notifyProgress(callback, 1, 1, "正在保存排序后的 PDF");
            document.save(target);

            ResultSummary summary = new ResultSummary();
            summary.sourcePages = pageCount;
            summary.outputPages = pageCount;
            summary.sourceFiles = 1;
            return summary;
        }
    }

    static ResultSummary deletePages(
            Context context,
            Uri sourceUri,
            File target,
            List<Integer> deletedPages,
            AtomicBoolean cancelled,
            ProgressCallback callback
    ) throws Exception {
        try (InputStream input = requireInput(context, sourceUri);
             PDDocument document = PDDocument.load(input)) {
            int pageCount = document.getNumberOfPages();
            Set<Integer> deleted = validatePageSet(deletedPages, pageCount, false);
            if (deleted.isEmpty()) throw new IOException("请先选择要删除的页面");
            if (deleted.size() >= pageCount) throw new IOException("PDF 至少需要保留一页");

            int handled = 0;
            for (int i = pageCount - 1; i >= 0; i--) {
                checkCancelled(cancelled);
                if (deleted.contains(i)) document.removePage(i);
                handled++;
                notifyProgress(callback, handled, pageCount,
                        "正在处理第 " + (pageCount - i) + " / " + pageCount + " 页");
            }
            checkCancelled(cancelled);
            notifyProgress(callback, 1, 1, "正在保存删除页面后的 PDF");
            document.save(target);

            ResultSummary summary = new ResultSummary();
            summary.sourcePages = pageCount;
            summary.outputPages = pageCount - deleted.size();
            summary.sourceFiles = 1;
            return summary;
        }
    }

    static ResultSummary saveSelectedPages(
            Context context,
            Uri sourceUri,
            File target,
            List<Integer> selectedPages,
            AtomicBoolean cancelled,
            ProgressCallback callback
    ) throws Exception {
        try (InputStream input = requireInput(context, sourceUri);
             PDDocument document = PDDocument.load(input)) {
            int pageCount = document.getNumberOfPages();
            Set<Integer> selected = validatePageSet(selectedPages, pageCount, true);
            if (selected.isEmpty()) throw new IOException("请先选择要另存的页面");

            int handled = 0;
            for (int i = pageCount - 1; i >= 0; i--) {
                checkCancelled(cancelled);
                if (!selected.contains(i)) document.removePage(i);
                handled++;
                notifyProgress(callback, handled, pageCount,
                        "正在筛选第 " + (pageCount - i) + " / " + pageCount + " 页");
            }
            checkCancelled(cancelled);
            notifyProgress(callback, 1, 1, "正在保存所选页面");
            document.save(target);

            ResultSummary summary = new ResultSummary();
            summary.sourcePages = pageCount;
            summary.outputPages = selected.size();
            summary.sourceFiles = 1;
            return summary;
        }
    }

    static ResultSummary mergePdfs(
            Context context,
            List<Uri> sourceUris,
            File target,
            AtomicBoolean cancelled,
            ProgressCallback callback
    ) throws Exception {
        if (sourceUris == null || sourceUris.size() < 2) {
            throw new IOException("请至少添加两个 PDF 文件");
        }

        File scratchDir = new File(context.getCacheDir(), "pdfbox-merge-scratch");
        deleteDirectoryContents(scratchDir);
        if (!scratchDir.exists() && !scratchDir.mkdirs()) {
            throw new IOException("无法创建 PDF 合并缓存目录");
        }

        int totalPages = 0;
        MemoryUsageSetting destinationMemory = MemoryUsageSetting
                .setupMixed(32L * 1024L * 1024L)
                .setTempDir(scratchDir);
        try (PDDocument destination = new PDDocument(destinationMemory)) {
            PDPageTree destinationPages = destination.getPages();

            for (int fileIndex = 0; fileIndex < sourceUris.size(); fileIndex++) {
                checkCancelled(cancelled);
                Uri uri = sourceUris.get(fileIndex);
                int displayedFileIndex = fileIndex + 1;
                notifyProgress(callback, 0, 1,
                        "正在检查第 " + displayedFileIndex + " / " + sourceUris.size()
                                + " 个 PDF 的文件结构");

                MemoryUsageSetting sourceMemory = MemoryUsageSetting
                        .setupMixed(16L * 1024L * 1024L)
                        .setTempDir(scratchDir);
                try (InputStream input = requireInput(context, uri);
                     PDDocument source = PDDocument.load(input, sourceMemory)) {
                    int pages = source.getNumberOfPages();
                    if (pages <= 0) {
                        throw new IOException("第 " + displayedFileIndex + " 个 PDF 没有页面");
                    }

                    // 每个源文件使用独立克隆器。克隆器内部会缓存源对象映射，
                    // 若跨文件复用会长期持有前面源文档的对象，明显抬高峰值内存。
                    PDFCloneUtility cloner = new PDFCloneUtility(destination);
                    int pageIndex = 0;
                    for (PDPage page : source.getPages()) {
                        checkCancelled(cancelled);
                        destinationPages.add(clonePageForMerge(cloner, page));
                        pageIndex++;
                        totalPages++;
                        notifyProgress(callback, pageIndex, pages,
                                "正在合并第 " + displayedFileIndex + " / " + sourceUris.size()
                                        + " 个 PDF，第 " + pageIndex + " / " + pages + " 页");
                    }
                } catch (IOException error) {
                    throw new IOException(
                            "第 " + displayedFileIndex + " 个 PDF 无法合并："
                                    + safeMessage(error),
                            error
                    );
                }
            }
            checkCancelled(cancelled);
            notifyProgress(callback, 1, 1, "正在保存合并后的 PDF");
            destination.save(target);
        } finally {
            deleteDirectoryContents(scratchDir);
        }

        ResultSummary summary = new ResultSummary();
        summary.sourceFiles = sourceUris.size();
        summary.sourcePages = totalPages;
        summary.outputPages = totalPages;
        return summary;
    }


    /**
     * 深度复制页面内容到目标文档。不能直接把 source 的 PDPage 或资源对象挂到 destination：
     * source 关闭后，延迟读取的 COSStream 会同时关闭，保存目标文档时就会出现
     * “COSStream has been closed”。这里采用 PDFBox 合并器优化模式相同的克隆方式，
     * 每个源文档可以在本文件复制完成后立即安全关闭。
     */
    private static PDPage clonePageForMerge(
            PDFCloneUtility cloner,
            PDPage sourcePage
    ) throws IOException {
        PDPage clonedPage = new PDPage((COSDictionary) cloner.cloneForNewDocument(
                sourcePage.getCOSObject()
        ));
        clonedPage.setCropBox(sourcePage.getCropBox());
        clonedPage.setMediaBox(sourcePage.getMediaBox());
        clonedPage.setRotation(sourcePage.getRotation());

        PDResources resources = sourcePage.getResources();
        if (resources != null) {
            COSDictionary clonedResources = (COSDictionary) cloner.cloneForNewDocument(resources);
            clonedPage.setResources(new PDResources(clonedResources));
        } else {
            clonedPage.setResources(new PDResources());
        }
        return clonedPage;
    }

    private static void deleteDirectoryContents(File directory) {
        if (directory == null || !directory.exists()) return;
        File[] children = directory.listFiles();
        if (children != null) {
            for (File child : children) {
                if (child.isDirectory()) deleteDirectoryContents(child);
                if (!child.delete()) child.deleteOnExit();
            }
        }
        if (!directory.delete()) directory.deleteOnExit();
    }

    private static String safeMessage(Throwable error) {
        if (error == null) return "文件结构异常";
        String message = error.getMessage();
        if (message == null || message.trim().isEmpty()) return "文件结构异常";
        return message.trim();
    }

    private static InputStream requireInput(Context context, Uri uri) throws IOException {
        InputStream input = context.getContentResolver().openInputStream(uri);
        if (input == null) throw new IOException("无法打开 PDF 文件");
        return input;
    }

    private static void validateCompleteOrder(List<Integer> order, int pageCount) throws IOException {
        if (order == null || order.size() != pageCount) {
            throw new IOException("页面顺序数据不完整");
        }
        Set<Integer> unique = new HashSet<>(order);
        if (unique.size() != pageCount) throw new IOException("页面顺序中存在重复页面");
        for (int value : unique) {
            if (value < 0 || value >= pageCount) throw new IOException("页面顺序包含无效页码");
        }
    }

    private static Set<Integer> validatePageSet(
            List<Integer> pages,
            int pageCount,
            boolean allowAll
    ) throws IOException {
        Set<Integer> result = new HashSet<>();
        if (pages != null) result.addAll(pages);
        for (int value : result) {
            if (value < 0 || value >= pageCount) throw new IOException("选择中包含无效页码");
        }
        if (!allowAll && result.size() == pageCount) {
            throw new IOException("PDF 至少需要保留一页");
        }
        return result;
    }

    private static void checkCancelled(AtomicBoolean cancelled) throws IOException {
        if (cancelled != null && cancelled.get()) throw new IOException("操作已取消");
        if (Thread.currentThread().isInterrupted()) throw new IOException("操作已取消");
    }

    private static void notifyProgress(
            ProgressCallback callback,
            int current,
            int total,
            String message
    ) {
        if (callback != null) callback.onProgress(current, total, message);
    }
}
