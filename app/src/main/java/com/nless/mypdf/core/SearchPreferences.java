package com.nless.mypdf.core;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * PDF 搜索设置的统一读写入口。
 *
 * 所有默认值都集中在这里，避免设置页和阅读器使用不同的默认行为。
 */
public final class SearchPreferences {

    private static final String PREFS_NAME = "pdf_search_settings";

    private static final String KEY_CASE_SENSITIVE = "case_sensitive";
    private static final String KEY_ALLOW_CROSS_LINE = "allow_cross_line";
    private static final String KEY_IGNORE_ALL_WHITESPACE = "ignore_all_whitespace";
    private static final String KEY_JOIN_HYPHENATED_LINE_BREAKS = "join_hyphenated_line_breaks";
    private static final String KEY_WHOLE_WORD = "whole_word";

    private static final String KEY_SMART_ENHANCED_OCR = "smart_enhanced_ocr";
    private static final String KEY_OCR_O_ZERO_TOLERANCE = "ocr_o_zero_tolerance";
    private static final String KEY_CURRENT_PAGE_FIRST = "current_page_first";
    private static final String KEY_OCR_RENDER_WIDTH = "ocr_render_width";
    private static final String KEY_CACHE_CLEAR_GENERATION = "cache_clear_generation";

    public static final int OCR_WIDTH_FAST = 960;
    public static final int OCR_WIDTH_BALANCED = 1280;
    public static final int OCR_WIDTH_HIGH = 1440;

    private SearchPreferences() {
    }

    private static SharedPreferences preferences(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static boolean isCaseSensitive(Context context) {
        return preferences(context).getBoolean(KEY_CASE_SENSITIVE, false);
    }

    public static void setCaseSensitive(Context context, boolean enabled) {
        preferences(context).edit().putBoolean(KEY_CASE_SENSITIVE, enabled).apply();
    }

    public static boolean isCrossLineMatchEnabled(Context context) {
        return preferences(context).getBoolean(KEY_ALLOW_CROSS_LINE, true);
    }

    public static void setCrossLineMatchEnabled(Context context, boolean enabled) {
        preferences(context).edit().putBoolean(KEY_ALLOW_CROSS_LINE, enabled).apply();
    }

    public static boolean isIgnoreAllWhitespaceEnabled(Context context) {
        return preferences(context).getBoolean(KEY_IGNORE_ALL_WHITESPACE, true);
    }

    public static void setIgnoreAllWhitespaceEnabled(Context context, boolean enabled) {
        preferences(context).edit().putBoolean(KEY_IGNORE_ALL_WHITESPACE, enabled).apply();
    }

    public static boolean isJoinHyphenatedLineBreaksEnabled(Context context) {
        return preferences(context).getBoolean(KEY_JOIN_HYPHENATED_LINE_BREAKS, true);
    }

    public static void setJoinHyphenatedLineBreaksEnabled(Context context, boolean enabled) {
        preferences(context).edit().putBoolean(KEY_JOIN_HYPHENATED_LINE_BREAKS, enabled).apply();
    }

    public static boolean isWholeWordEnabled(Context context) {
        return preferences(context).getBoolean(KEY_WHOLE_WORD, false);
    }

    public static void setWholeWordEnabled(Context context, boolean enabled) {
        preferences(context).edit().putBoolean(KEY_WHOLE_WORD, enabled).apply();
    }

    public static boolean isSmartEnhancedOcrEnabled(Context context) {
        return preferences(context).getBoolean(KEY_SMART_ENHANCED_OCR, false);
    }

    public static void setSmartEnhancedOcrEnabled(Context context, boolean enabled) {
        preferences(context).edit().putBoolean(KEY_SMART_ENHANCED_OCR, enabled).apply();
    }

    public static boolean isOcrOZeroToleranceEnabled(Context context) {
        return preferences(context).getBoolean(KEY_OCR_O_ZERO_TOLERANCE, true);
    }

    public static void setOcrOZeroToleranceEnabled(Context context, boolean enabled) {
        preferences(context).edit().putBoolean(KEY_OCR_O_ZERO_TOLERANCE, enabled).apply();
    }

    public static boolean isCurrentPageFirstEnabled(Context context) {
        return preferences(context).getBoolean(KEY_CURRENT_PAGE_FIRST, true);
    }

    public static void setCurrentPageFirstEnabled(Context context, boolean enabled) {
        preferences(context).edit().putBoolean(KEY_CURRENT_PAGE_FIRST, enabled).apply();
    }

    public static int getOcrRenderWidth(Context context) {
        int value = preferences(context).getInt(KEY_OCR_RENDER_WIDTH, OCR_WIDTH_BALANCED);
        if (value == OCR_WIDTH_FAST || value == OCR_WIDTH_HIGH) {
            return value;
        }
        return OCR_WIDTH_BALANCED;
    }

    public static void setOcrRenderWidth(Context context, int width) {
        int safeWidth = width;
        if (safeWidth != OCR_WIDTH_FAST
                && safeWidth != OCR_WIDTH_BALANCED
                && safeWidth != OCR_WIDTH_HIGH) {
            safeWidth = OCR_WIDTH_BALANCED;
        }
        preferences(context).edit().putInt(KEY_OCR_RENDER_WIDTH, safeWidth).apply();
    }

    public static String getOcrPrecisionLabel(Context context) {
        int width = getOcrRenderWidth(context);
        if (width == OCR_WIDTH_FAST) return "快速";
        if (width == OCR_WIDTH_HIGH) return "高精度";
        return "平衡";
    }

    public static long getCacheClearGeneration(Context context) {
        return preferences(context).getLong(KEY_CACHE_CLEAR_GENERATION, 0L);
    }

    public static long markSearchCacheCleared(Context context) {
        long next = Math.max(System.currentTimeMillis(), getCacheClearGeneration(context) + 1L);
        preferences(context).edit().putLong(KEY_CACHE_CLEAR_GENERATION, next).apply();
        return next;
    }
}
