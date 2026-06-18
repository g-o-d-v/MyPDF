package com.nless.pdf_search_engine.core;

public class PdfSearchOptions {

    /**
     * 搜索模式。
     */
    public PdfSearchMode mode = PdfSearchMode.TEXT_THEN_OCR;

    /**
     * 是否只搜索当前页。
     */
    public boolean currentPageOnly = false;

    /**
     * 当前页页码，0-based。
     * currentPageOnly = true 时生效。
     */
    public int currentPage = 0;

    /**
     * 起始页，0-based。
     */
    public int startPage = 0;

    /**
     * 结束页，包含。
     * -1 表示最后一页。
     */
    public int endPage = -1;

    /**
     * OCR 渲染宽度。
     * 960：快，准确率一般
     * 1280：推荐平衡值
     * 1440：更准但更慢
     */
    public int ocrRenderWidth = 1280;

    /**
     * TEXT_THEN_OCR 模式下，文本层没有结果时是否 fallback 到 OCR。
     */
    public boolean fallbackToOcrWhenTextNotFound = true;

    /**
     * 是否忽略大小写。
     * 当前 PDFium native 第一版仅对 ASCII 做大小写处理。
     */
    public boolean ignoreCase = true;

    /**
     * 是否允许全文 OCR。
     *
     * 警告：
     * 扫描件全文 OCR 很耗时，第一版默认关闭。
     * 默认只 OCR 当前页。
     */
    public boolean allowFullDocumentOcr = false;

    /**
     * 如果开启全文 OCR，最多 OCR 多少页。
     * 防止大文件把手机跑爆。
     */
    public int maxOcrPages = 5;
}
