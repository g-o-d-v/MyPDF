package com.nless.mypdf;

/**
 * PDF 文本选择框与 OCR 不可见文字层共用的垂直几何参数。
 *
 * <p>PdfTextSelectionRepository 会把 TextPosition 的基线以上高度作为主体，
 * 并在基线下方保留少量下行部件空间。OCR 文字层写入时必须使用同一比例，
 * 否则扫描页生成后的选择框高度会明显偏小或偏移。</p>
 */
final class PdfTextGeometry {

    /** 基线下方为英文 g、p、y 等下行部件预留的比例。 */
    static final float DESCENT_RATIO = 0.22f;

    /** 最终选择框总高度相对于 TextPosition.getHeight() 的比例。 */
    static final float SELECTION_SPAN_FACTOR = 1f + DESCENT_RATIO;

    private PdfTextGeometry() {
    }
}
