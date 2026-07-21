package com.nless.mypdf.core;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 阅读体验设置。
 */
public final class ReadingPreferences {

    public static final String ANIMATION_SLIDE = "slide";
    public static final String ANIMATION_NONE = "none";

    private static final String PREFS_NAME = "reading_experience_settings";
    private static final String KEY_SMOOTH_PAGE_TURN = "smooth_page_turn";
    private static final String KEY_HORIZONTAL_ANIMATION = "horizontal_page_animation";
    private static final String KEY_EXPERIMENTAL_READING_CONTROLS =
            "experimental_reading_controls";

    private ReadingPreferences() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(
                PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static boolean isSmoothPageTurnEnabled(Context context) {
        return prefs(context).getBoolean(KEY_SMOOTH_PAGE_TURN, true);
    }

    public static void setSmoothPageTurnEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_SMOOTH_PAGE_TURN, enabled).apply();
    }

    public static String getHorizontalAnimation(Context context) {
        return prefs(context).getString(KEY_HORIZONTAL_ANIMATION, ANIMATION_SLIDE);
    }

    public static void setHorizontalAnimation(Context context, String value) {
        String safeValue = ANIMATION_NONE.equals(value) ? ANIMATION_NONE : ANIMATION_SLIDE;
        prefs(context).edit().putString(KEY_HORIZONTAL_ANIMATION, safeValue).apply();
    }

    public static boolean isHorizontalPageAnimationEnabled(Context context) {
        return !ANIMATION_NONE.equals(getHorizontalAnimation(context));
    }

    /**
     * 页面内底部菜单使用的统一翻页动画开关。
     * 开启时，点击翻页和拖动进度条跳转都使用平滑过渡；关闭时直接跳转。
     */
    public static boolean isPageTurnAnimationEnabled(Context context) {
        return isHorizontalPageAnimationEnabled(context);
    }

    public static void setPageTurnAnimationEnabled(Context context, boolean enabled) {
        prefs(context).edit()
                .putBoolean(KEY_SMOOTH_PAGE_TURN, enabled)
                .putString(KEY_HORIZONTAL_ANIMATION,
                        enabled ? ANIMATION_SLIDE : ANIMATION_NONE)
                .apply();
    }

    /**
     * 是否启用 PDF 页面内的实验性阅读控制栏。
     *
     * <p>默认关闭，关闭时保持原有纵向阅读体验。</p>
     */
    public static boolean isExperimentalReadingControlsEnabled(Context context) {
        return prefs(context).getBoolean(KEY_EXPERIMENTAL_READING_CONTROLS, false);
    }

    public static void setExperimentalReadingControlsEnabled(
            Context context,
            boolean enabled
    ) {
        prefs(context).edit()
                .putBoolean(KEY_EXPERIMENTAL_READING_CONTROLS, enabled)
                .apply();
    }

    public static String getHorizontalAnimationLabel(Context context) {
        return isHorizontalPageAnimationEnabled(context) ? "滑动" : "关闭";
    }
}
