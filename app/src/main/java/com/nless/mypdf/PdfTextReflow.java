package com.nless.mypdf;

import android.graphics.RectF;

import com.nless.pdf_search_engine.ocr.OcrPageResult;
import com.nless.pdf_search_engine.ocr.OcrTextBlock;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 将 PDF/OCR 的视觉换行还原成更适合复制和导出的段落文本。
 *
 * <p>PDF 文本层通常只记录字形坐标，PDFTextStripper 会把页面上的每个视觉行都输出为换行。
 * 本类根据行宽、缩进、行距、标点和列表标记区分“排版自动换行”和“真实段落换行”。</p>
 */
final class PdfTextReflow {

    private static final int BREAK_SOFT = 0;
    private static final int BREAK_PARAGRAPH = 1;
    private static final int BREAK_BLANK_LINE = 2;

    private PdfTextReflow() {
    }

    static String reflowPage(PdfTextPage page) {
        if (page == null || page.glyphs.isEmpty()) return "";
        return reflowSelection(page, 0, page.glyphs.size() - 1);
    }

    static String reflowSelection(PdfTextPage page, int selectionStart, int selectionEnd) {
        if (page == null || page.glyphs.isEmpty()) return "";
        int start = Math.max(0, Math.min(selectionStart, page.glyphs.size() - 1));
        int end = Math.max(start, Math.min(selectionEnd, page.glyphs.size() - 1));

        List<VisualLine> allLines = buildPdfLines(page);
        if (allLines.isEmpty()) return "";
        Metrics metrics = Metrics.from(allLines);

        List<VisualLine> selectedLines = new ArrayList<>();
        for (VisualLine line : allLines) {
            if (line.lastGlyph < start || line.firstGlyph > end) continue;
            int from = Math.max(start, line.firstGlyph);
            int to = Math.min(end, line.lastGlyph);
            String selected = buildGlyphText(page, from, to);
            if (selected.trim().isEmpty()) continue;
            selectedLines.add(line.withOutputText(selected));
        }
        return reflowLines(selectedLines, metrics);
    }

    static String reflowOcrPage(OcrPageResult result) {
        if (result == null || result.blocks == null || result.blocks.isEmpty()) {
            return normalizeFallbackText(result == null ? null : result.fullText);
        }

        List<VisualLine> lines = new ArrayList<>();
        int sequence = 0;
        for (OcrTextBlock block : result.blocks) {
            if (block == null || block.text == null || block.rectInBitmap == null) continue;
            String normalized = normalizeSingleLine(block.text);
            if (normalized.isEmpty()) continue;

            RectF rect = new RectF(block.rectInBitmap);
            if (result.bitmapWidth > 0 && result.bitmapHeight > 0) {
                rect.left /= result.bitmapWidth;
                rect.right /= result.bitmapWidth;
                rect.top /= result.bitmapHeight;
                rect.bottom /= result.bitmapHeight;
            }
            normalizeRect(rect);
            lines.add(new VisualLine(
                    sequence,
                    sequence,
                    sequence,
                    normalized,
                    normalized,
                    rect,
                    0,
                    sequence
            ));
            sequence++;
        }
        if (lines.isEmpty()) return normalizeFallbackText(result.fullText);

        // OCR 引擎通常已按阅读顺序返回 block。仅在顺序明显混乱时才按视觉位置排序，
        // 避免破坏引擎已经完成的双栏阅读顺序判断。
        if (!isMostlyTopToBottom(lines)) {
            Collections.sort(lines, Comparator
                    .comparingDouble((VisualLine line) -> line.bounds.top)
                    .thenComparingDouble(line -> line.bounds.left));
            for (int i = 0; i < lines.size(); i++) lines.get(i).order = i;
        }
        return reflowLines(lines, Metrics.from(lines));
    }

    private static List<VisualLine> buildPdfLines(PdfTextPage page) {
        List<VisualLine> lines = new ArrayList<>();
        VisualLine current = null;
        PdfTextPage.Glyph previousGlyph = null;

        for (int index = 0; index < page.glyphs.size(); index++) {
            PdfTextPage.Glyph glyph = page.glyphs.get(index);
            boolean explicitNewLine = glyph.separatorBefore != null
                    && glyph.separatorBefore.indexOf('\n') >= 0;
            boolean newLine = current == null
                    || explicitNewLine
                    || glyph.lineIndex != current.sourceLineIndex;
            if (newLine) {
                if (current != null) lines.add(current.finish());
                current = new VisualLine(
                        index,
                        index,
                        glyph.lineIndex,
                        "",
                        "",
                        new RectF(glyph.normalizedBounds),
                        0,
                        lines.size()
                );
                previousGlyph = null;
            }

            if (current == null) continue;
            if (previousGlyph != null) {
                float previousWidth = Math.max(0.0005f, previousGlyph.normalizedBounds.width());
                float currentWidth = Math.max(0.0005f, glyph.normalizedBounds.width());
                float typicalGlyphWidth = Math.min(previousWidth, currentWidth);
                float gap = glyph.normalizedBounds.left - previousGlyph.normalizedBounds.right;
                if (gap > Math.max(0.012f, typicalGlyphWidth * 2.2f)) {
                    current.largeGapCount++;
                }
            }

            if (current.textBuilder.length() > 0
                    && glyph.separatorBefore != null
                    && glyph.separatorBefore.indexOf(' ') >= 0
                    && current.textBuilder.charAt(current.textBuilder.length() - 1) != ' ') {
                current.textBuilder.append(' ');
            }
            current.textBuilder.append(glyph.text);
            current.lastGlyph = index;
            current.bounds.union(glyph.normalizedBounds);
            previousGlyph = glyph;
        }
        if (current != null) lines.add(current.finish());
        return lines;
    }

    private static String buildGlyphText(PdfTextPage page, int start, int end) {
        StringBuilder text = new StringBuilder();
        for (int index = start; index <= end; index++) {
            PdfTextPage.Glyph glyph = page.glyphs.get(index);
            if (text.length() > 0
                    && glyph.separatorBefore != null
                    && glyph.separatorBefore.indexOf(' ') >= 0
                    && text.charAt(text.length() - 1) != ' ') {
                text.append(' ');
            }
            text.append(glyph.text);
        }
        return normalizeSingleLine(text.toString());
    }

    private static String reflowLines(List<VisualLine> lines, Metrics metrics) {
        if (lines == null || lines.isEmpty()) return "";
        StringBuilder output = new StringBuilder();
        VisualLine previous = null;

        for (VisualLine line : lines) {
            String text = normalizeSingleLine(line.outputText);
            if (text.isEmpty()) continue;
            if (previous == null) {
                output.append(text);
                previous = line;
                continue;
            }

            int breakType = classifyBoundary(previous, line, metrics);
            if (breakType == BREAK_BLANK_LINE) {
                trimTrailingSpaces(output);
                appendNewLines(output, 2);
                output.append(text);
            } else if (breakType == BREAK_PARAGRAPH) {
                trimTrailingSpaces(output);
                appendNewLines(output, 1);
                output.append(text);
            } else {
                appendSoftWrappedLine(output, text);
            }
            previous = line;
        }
        return output.toString().trim();
    }

    private static int classifyBoundary(VisualLine previous, VisualLine next, Metrics metrics) {
        if (previous == null || next == null) return BREAK_PARAGRAPH;

        int sourceLineGap = next.sourceLineIndex - previous.sourceLineIndex;
        if (sourceLineGap > 1) return BREAK_BLANK_LINE;

        float topStep = next.bounds.top - previous.bounds.top;
        if (metrics.typicalLineStep > 0f
                && topStep > metrics.typicalLineStep * 1.55f) {
            return topStep > metrics.typicalLineStep * 2.15f
                    ? BREAK_BLANK_LINE
                    : BREAK_PARAGRAPH;
        }

        String previousText = previous.fullText.trim();
        String nextText = next.fullText.trim();
        if (previousText.isEmpty() || nextText.isEmpty()) return BREAK_PARAGRAPH;

        if (previous.largeGapCount >= 2 || next.largeGapCount >= 2) {
            return BREAK_PARAGRAPH;
        }
        if (looksLikeListItem(nextText)) return BREAK_PARAGRAPH;

        float bodyWidth = Math.max(0.05f, metrics.bodyRight - metrics.bodyLeft);
        float indentThreshold = Math.max(0.018f, metrics.typicalHeight * 0.75f);
        boolean nextIndented = next.bounds.left > metrics.bodyLeft + indentThreshold;
        boolean previousShort = previous.bounds.right
                < metrics.bodyRight - Math.max(0.045f, bodyWidth * 0.11f);
        boolean previousVeryShort = previous.bounds.width() < bodyWidth * 0.56f;
        boolean previousCentered = Math.abs(previous.bounds.centerX() - metrics.bodyCenter)
                < Math.max(0.035f, bodyWidth * 0.08f)
                && previous.bounds.width() < bodyWidth * 0.78f;
        boolean nextCentered = Math.abs(next.bounds.centerX() - metrics.bodyCenter)
                < Math.max(0.035f, bodyWidth * 0.08f)
                && next.bounds.width() < bodyWidth * 0.78f;

        if (previousCentered || nextCentered) return BREAK_PARAGRAPH;
        if (nextIndented && (previousShort || endsSentence(previousText))) {
            return BREAK_PARAGRAPH;
        }
        if (previousShort && (previousVeryShort || endsSentence(previousText))) {
            return BREAK_PARAGRAPH;
        }

        // 标题、署名、日期等通常是很短的独立行，即使没有句号也应保留换行。
        if (previousText.codePointCount(0, previousText.length()) <= 16
                && previousVeryShort
                && next.bounds.width() > previous.bounds.width() * 1.35f) {
            return BREAK_PARAGRAPH;
        }
        return BREAK_SOFT;
    }

    private static void appendSoftWrappedLine(StringBuilder output, String nextText) {
        if (output.length() == 0) {
            output.append(nextText);
            return;
        }
        trimTrailingSpaces(output);
        String normalizedNext = trimLeadingSpaces(nextText);
        if (normalizedNext.isEmpty()) return;

        int previousCodePoint = output.codePointBefore(output.length());
        int nextCodePoint = normalizedNext.codePointAt(0);
        if (isHyphen(previousCodePoint)
                && output.length() >= Character.charCount(previousCodePoint)
                && isLatinLetter(nextCodePoint)) {
            int beforeHyphenIndex = output.length() - Character.charCount(previousCodePoint);
            if (beforeHyphenIndex > 0) {
                int beforeHyphen = output.codePointBefore(beforeHyphenIndex);
                if (isLatinLetter(beforeHyphen)) {
                    output.delete(beforeHyphenIndex, output.length());
                    output.append(normalizedNext);
                    return;
                }
            }
        }

        if (needsSpace(previousCodePoint, nextCodePoint)) output.append(' ');
        output.append(normalizedNext);
    }

    private static boolean needsSpace(int previous, int next) {
        if (isCjk(previous) || isCjk(next)) return false;
        if (isOpeningPunctuation(previous) || isClosingPunctuation(next)) return false;
        if (Character.isWhitespace(previous) || Character.isWhitespace(next)) return false;
        return true;
    }

    private static boolean endsSentence(String value) {
        if (value == null || value.isEmpty()) return false;
        int cp = value.codePointBefore(value.length());
        return cp == '。' || cp == '！' || cp == '？' || cp == '；' || cp == '：'
                || cp == '.' || cp == '!' || cp == '?' || cp == ';' || cp == ':'
                || cp == '”' || cp == '’' || cp == '』' || cp == '」';
    }

    private static boolean looksLikeListItem(String value) {
        if (value == null) return false;
        String text = value.trim();
        if (text.isEmpty()) return false;
        int first = text.codePointAt(0);
        if (first == '•' || first == '●' || first == '▪' || first == '◦'
                || first == '·' || first == '-' || first == '*') return true;

        int cursor = 0;
        if (first == '(' || first == '（') cursor += Character.charCount(first);
        int count = 0;
        while (cursor < text.length() && count < 6) {
            int cp = text.codePointAt(cursor);
            if (!Character.isDigit(cp) && !isChineseNumber(cp) && !isLatinLetter(cp)) break;
            cursor += Character.charCount(cp);
            count++;
        }
        if (count == 0 || cursor >= text.length()) return false;
        int marker = text.codePointAt(cursor);
        return marker == '.' || marker == ')' || marker == '）' || marker == '、';
    }

    private static boolean isChineseNumber(int cp) {
        return "一二三四五六七八九十百千".indexOf(cp) >= 0;
    }

    private static boolean isCjk(int cp) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(cp);
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
                || block == Character.UnicodeBlock.HIRAGANA
                || block == Character.UnicodeBlock.KATAKANA
                || block == Character.UnicodeBlock.HANGUL_SYLLABLES
                || block == Character.UnicodeBlock.HANGUL_JAMO
                || block == Character.UnicodeBlock.HANGUL_COMPATIBILITY_JAMO;
    }

    private static boolean isLatinLetter(int cp) {
        Character.UnicodeScript script = Character.UnicodeScript.of(cp);
        return Character.isLetter(cp) && script == Character.UnicodeScript.LATIN;
    }

    private static boolean isHyphen(int cp) {
        return cp == '-' || cp == 0x2010 || cp == 0x2011 || cp == 0x00AD;
    }

    private static boolean isOpeningPunctuation(int cp) {
        return cp == '(' || cp == '[' || cp == '{' || cp == '（' || cp == '【'
                || cp == '《' || cp == '“' || cp == '‘' || cp == '「' || cp == '『';
    }

    private static boolean isClosingPunctuation(int cp) {
        return cp == ',' || cp == '.' || cp == '!' || cp == '?' || cp == ';' || cp == ':'
                || cp == ')' || cp == ']' || cp == '}' || cp == '，' || cp == '。'
                || cp == '！' || cp == '？' || cp == '；' || cp == '：' || cp == '）'
                || cp == '】' || cp == '》' || cp == '”' || cp == '’' || cp == '」'
                || cp == '』';
    }

    private static boolean isMostlyTopToBottom(List<VisualLine> lines) {
        if (lines.size() < 3) return true;
        int backward = 0;
        for (int i = 1; i < lines.size(); i++) {
            if (lines.get(i).bounds.top + 0.02f < lines.get(i - 1).bounds.top) backward++;
        }
        return backward <= Math.max(1, lines.size() / 8);
    }

    private static String normalizeSingleLine(String value) {
        if (value == null) return "";
        StringBuilder output = new StringBuilder(value.length());
        boolean pendingSpace = false;
        for (int offset = 0; offset < value.length();) {
            int cp = value.codePointAt(offset);
            offset += Character.charCount(cp);
            if (cp == 0 || Character.isISOControl(cp) || Character.isWhitespace(cp)
                    || Character.isSpaceChar(cp)) {
                pendingSpace = output.length() > 0;
                continue;
            }
            if (pendingSpace && output.length() > 0) output.append(' ');
            output.appendCodePoint(cp);
            pendingSpace = false;
        }
        return output.toString().trim();
    }

    private static String normalizeFallbackText(String value) {
        if (value == null) return "";
        String normalized = value.replace("\r\n", "\n").replace('\r', '\n');
        String[] rawLines = normalized.split("\n", -1);
        List<VisualLine> lines = new ArrayList<>();
        int order = 0;
        int blankRun = 0;
        for (String raw : rawLines) {
            String line = normalizeSingleLine(raw);
            if (line.isEmpty()) {
                blankRun++;
                continue;
            }
            int sourceLine = order + blankRun;
            lines.add(new VisualLine(
                    order,
                    order,
                    sourceLine,
                    line,
                    line,
                    new RectF(0f, order, 1f, order + 1f),
                    0,
                    order
            ));
            order++;
            blankRun = 0;
        }
        return reflowLines(lines, Metrics.from(lines));
    }

    private static String trimLeadingSpaces(String value) {
        int index = 0;
        while (index < value.length()) {
            int cp = value.codePointAt(index);
            if (!Character.isWhitespace(cp) && !Character.isSpaceChar(cp)) break;
            index += Character.charCount(cp);
        }
        return value.substring(index);
    }

    private static void trimTrailingSpaces(StringBuilder value) {
        while (value.length() > 0) {
            int cp = value.codePointBefore(value.length());
            if (!Character.isWhitespace(cp) && !Character.isSpaceChar(cp)) break;
            value.delete(value.length() - Character.charCount(cp), value.length());
        }
    }

    private static void appendNewLines(StringBuilder output, int count) {
        int existing = 0;
        for (int i = output.length() - 1; i >= 0 && output.charAt(i) == '\n'; i--) existing++;
        for (int i = existing; i < count; i++) output.append('\n');
    }

    private static void normalizeRect(RectF rect) {
        if (rect.left > rect.right) {
            float value = rect.left;
            rect.left = rect.right;
            rect.right = value;
        }
        if (rect.top > rect.bottom) {
            float value = rect.top;
            rect.top = rect.bottom;
            rect.bottom = value;
        }
        if (rect.width() <= 0f) rect.right = rect.left + 0.001f;
        if (rect.height() <= 0f) rect.bottom = rect.top + 0.001f;
    }

    private static float median(List<Float> values, float fallback) {
        if (values == null || values.isEmpty()) return fallback;
        List<Float> copy = new ArrayList<>(values);
        Collections.sort(copy);
        int middle = copy.size() / 2;
        if ((copy.size() & 1) == 1) return copy.get(middle);
        return (copy.get(middle - 1) + copy.get(middle)) * 0.5f;
    }

    private static final class Metrics {
        final float typicalHeight;
        final float typicalLineStep;
        final float bodyLeft;
        final float bodyRight;
        final float bodyCenter;

        private Metrics(
                float typicalHeight,
                float typicalLineStep,
                float bodyLeft,
                float bodyRight
        ) {
            this.typicalHeight = typicalHeight;
            this.typicalLineStep = typicalLineStep;
            this.bodyLeft = bodyLeft;
            this.bodyRight = bodyRight;
            this.bodyCenter = (bodyLeft + bodyRight) * 0.5f;
        }

        static Metrics from(List<VisualLine> lines) {
            if (lines == null || lines.isEmpty()) return new Metrics(0.02f, 0.03f, 0f, 1f);
            List<Float> heights = new ArrayList<>();
            List<Float> widths = new ArrayList<>();
            List<Float> steps = new ArrayList<>();
            for (int i = 0; i < lines.size(); i++) {
                VisualLine line = lines.get(i);
                heights.add(Math.max(0.001f, line.bounds.height()));
                widths.add(Math.max(0.001f, line.bounds.width()));
                if (i > 0) {
                    float step = line.bounds.top - lines.get(i - 1).bounds.top;
                    if (step > 0f) steps.add(step);
                }
            }
            float typicalHeight = median(heights, 0.02f);
            float typicalWidth = median(widths, 0.65f);
            float typicalStep = median(steps, typicalHeight * 1.35f);

            List<Float> bodyLefts = new ArrayList<>();
            List<Float> bodyRights = new ArrayList<>();
            for (VisualLine line : lines) {
                if (line.bounds.width() >= typicalWidth * 0.58f) {
                    bodyLefts.add(line.bounds.left);
                    bodyRights.add(line.bounds.right);
                }
            }
            if (bodyLefts.isEmpty()) {
                for (VisualLine line : lines) {
                    bodyLefts.add(line.bounds.left);
                    bodyRights.add(line.bounds.right);
                }
            }
            float left = median(bodyLefts, 0f);
            float right = median(bodyRights, 1f);
            if (right - left < 0.08f) {
                left = 0f;
                right = 1f;
            }
            return new Metrics(typicalHeight, typicalStep, left, right);
        }
    }

    private static final class VisualLine {
        final int firstGlyph;
        int lastGlyph;
        final int sourceLineIndex;
        String fullText;
        final String outputText;
        final RectF bounds;
        int largeGapCount;
        int order;
        final StringBuilder textBuilder = new StringBuilder();

        VisualLine(
                int firstGlyph,
                int lastGlyph,
                int sourceLineIndex,
                String fullText,
                String outputText,
                RectF bounds,
                int largeGapCount,
                int order
        ) {
            this.firstGlyph = firstGlyph;
            this.lastGlyph = lastGlyph;
            this.sourceLineIndex = sourceLineIndex;
            this.fullText = fullText == null ? "" : fullText;
            this.outputText = outputText == null ? "" : outputText;
            this.bounds = bounds == null ? new RectF() : new RectF(bounds);
            this.largeGapCount = largeGapCount;
            this.order = order;
            if (!this.fullText.isEmpty()) textBuilder.append(this.fullText);
        }

        VisualLine finish() {
            fullText = normalizeSingleLine(textBuilder.toString());
            return new VisualLine(
                    firstGlyph,
                    lastGlyph,
                    sourceLineIndex,
                    fullText,
                    fullText,
                    bounds,
                    largeGapCount,
                    order
            );
        }

        VisualLine withOutputText(String selectedText) {
            return new VisualLine(
                    firstGlyph,
                    lastGlyph,
                    sourceLineIndex,
                    fullText,
                    selectedText,
                    bounds,
                    largeGapCount,
                    order
            );
        }

        @Override
        public String toString() {
            return String.format(Locale.ROOT, "Line{%d, %.3f..%.3f, %s}",
                    sourceLineIndex, bounds.left, bounds.right, fullText);
        }
    }
}
