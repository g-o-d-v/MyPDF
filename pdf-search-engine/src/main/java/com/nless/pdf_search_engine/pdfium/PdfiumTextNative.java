package com.nless.pdf_search_engine.pdfium;

public class PdfiumTextNative {

    static {
        /*
         * AndroidPdfViewer / pdfium-android 通常携带 libmodpdfium.so。
         * 这里先尝试加载它。
         */
        try {
            System.loadLibrary("modpdfium");
        } catch (Throwable ignored) {
        }

        /*
         * 有些 PDFium 封装可能叫 pdfium。
         * 这里做兜底。
         */
        try {
            System.loadLibrary("pdfium");
        } catch (Throwable ignored) {
        }

        /*
         * 你的 pdf-search-engine 自己编译出来的是 libNative.so。
         */
        System.loadLibrary("Native");
    }

    /**
     * @param pdfPath   Java 层复制后的真实 PDF 文件路径
     * @param keyword   搜索关键词
     * @param startPage 起始页，0-based
     * @param endPage   结束页，包含，0-based；传 -1 表示搜索到最后一页
     * @return 每 7 个 float 为一条结果：
     * pageIndex, left, top, right, bottom, pageWidth, pageHeight
     */
    public native float[] nativeSearch(
            String pdfPath,
            String keyword,
            int startPage,
            int endPage
    );
}
