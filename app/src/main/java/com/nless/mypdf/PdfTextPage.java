package com.nless.mypdf;

import android.graphics.RectF;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 单个 PDF 页面的可选择文本模型。
 *
 * <p>字形矩形使用 0..1 的页面归一化坐标，因此 PDFView 重新布局、缩放或旋转屏幕后，
 * 不需要重新解析 PDF 文本层。</p>
 */
public final class PdfTextPage {

    public final int pageIndex;
    public final List<Glyph> glyphs;

    public PdfTextPage(int pageIndex, List<Glyph> glyphs) {
        this.pageIndex = pageIndex;
        this.glyphs = Collections.unmodifiableList(new ArrayList<>(glyphs));
    }

    public boolean isEmpty() {
        return glyphs.isEmpty();
    }

    public static final class Glyph {
        public final String text;
        public final RectF normalizedBounds;
        public final String separatorBefore;
        public final int lineIndex;

        public Glyph(String text, RectF normalizedBounds, String separatorBefore, int lineIndex) {
            this.text = text == null ? "" : text;
            this.normalizedBounds = normalizedBounds == null
                    ? new RectF()
                    : new RectF(normalizedBounds);
            this.separatorBefore = separatorBefore == null ? "" : separatorBefore;
            this.lineIndex = lineIndex;
        }
    }
}
