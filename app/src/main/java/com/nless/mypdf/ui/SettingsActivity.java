package com.nless.mypdf.ui;


import com.nless.mypdf.R;
import com.nless.mypdf.core.SearchPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.appcompat.widget.SwitchCompat;
import androidx.appcompat.widget.Toolbar;

import com.nless.pdf_search_engine.core.PdfSearchManager;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * 应用设置页。
 *
 * 当前先提供搜索设置，后续其他功能设置可以继续按“标题 + 分隔线 + 设置行”的
 * 结构追加，不需要重新设计页面。
 */
public class SettingsActivity extends AppCompatActivity {

    private static final int TEXT_PRIMARY = 0xFF202124;
    private static final int TEXT_SECONDARY = 0xFF6F7378;
    private static final int DIVIDER_COLOR = 0xFFE2E5E9;
    private static final int SECTION_BACKGROUND = 0xFFF4F5F7;

    private final ExecutorService cacheExecutor = Executors.newSingleThreadExecutor();

    private LinearLayout searchMatchingContainer;
    private LinearLayout ocrSettingsContainer;
    private TextView ocrPrecisionValue;
    private TextView clearCacheValue;
    private View clearCacheRow;
    private volatile boolean clearingCache = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        Toolbar toolbar = findViewById(R.id.settings_toolbar);
        toolbar.setTitle("设置");
        toolbar.setNavigationIcon(android.R.drawable.ic_menu_revert);
        toolbar.setNavigationOnClickListener(v -> finish());

        searchMatchingContainer = findViewById(R.id.search_matching_container);
        ocrSettingsContainer = findViewById(R.id.ocr_settings_container);

        buildSearchMatchingSettings();
        buildOcrSettings();
        buildActionSettings();
    }

    private void buildSearchMatchingSettings() {
        addSwitchSetting(
                searchMatchingContainer,
                "区分大小写",
                "开启后，英文大写和小写会被视为不同字符。例如 PDF 不再匹配 pdf。",
                SearchPreferences.isCaseSensitive(this),
                enabled -> SearchPreferences.setCaseSensitive(this, enabled)
        );
        addDivider(searchMatchingContainer);

        addSwitchSetting(
                searchMatchingContainer,
                "允许跨行匹配",
                "允许关键词跨 PDF 文本换行或 OCR 行边界匹配；同一行中的普通空格仍保留。",
                SearchPreferences.isCrossLineMatchEnabled(this),
                enabled -> SearchPreferences.setCrossLineMatchEnabled(this, enabled)
        );
        addDivider(searchMatchingContainer);

        addSwitchSetting(
                searchMatchingContainer,
                "忽略所有空白字符",
                "匹配时忽略空格、制表符和换行，适合文字之间被错误插入空格的 PDF；可能扩大匹配范围。",
                SearchPreferences.isIgnoreAllWhitespaceEnabled(this),
                enabled -> SearchPreferences.setIgnoreAllWhitespaceEnabled(this, enabled)
        );
        addDivider(searchMatchingContainer);

        addSwitchSetting(
                searchMatchingContainer,
                "合并英文断行连字符",
                "将英文行末连字符造成的断词合并，例如 search- 换行 engine 会按 searchengine 匹配。",
                SearchPreferences.isJoinHyphenatedLineBreaksEnabled(this),
                enabled -> SearchPreferences.setJoinHyphenatedLineBreaksEnabled(this, enabled)
        );
        addDivider(searchMatchingContainer);

        addSwitchSetting(
                searchMatchingContainer,
                "仅匹配完整单词",
                "只匹配完整英文或数字单词，避免 cat 命中 catalog；对中文等无空格语言作用有限。",
                SearchPreferences.isWholeWordEnabled(this),
                enabled -> SearchPreferences.setWholeWordEnabled(this, enabled)
        );
    }

    private void buildOcrSettings() {
        addSwitchSetting(
                ocrSettingsContainer,
                "智能模式增强 OCR",
                "关闭时只 OCR 没有可用文本层的页面；开启后，文本层可用但没有命中时也继续 OCR，结果更完整但搜索更慢。",
                SearchPreferences.isSmartEnhancedOcrEnabled(this),
                enabled -> SearchPreferences.setSmartEnhancedOcrEnabled(this, enabled)
        );
        addDivider(ocrSettingsContainer);

        addSwitchSetting(
                ocrSettingsContainer,
                "OCR O/0 易混淆容错",
                "仅对 OCR 结果生效，将字母 O/o 与数字 0 视为可以互相匹配；PDF 原生文本层仍保持精确。",
                SearchPreferences.isOcrOZeroToleranceEnabled(this),
                enabled -> SearchPreferences.setOcrOZeroToleranceEnabled(this, enabled)
        );
        addDivider(ocrSettingsContainer);

        addSwitchSetting(
                ocrSettingsContainer,
                "优先搜索当前阅读页",
                "全文 OCR 时先处理当前阅读页，再向前后页面扩散；关闭后按照页码顺序处理。",
                SearchPreferences.isCurrentPageFirstEnabled(this),
                enabled -> SearchPreferences.setCurrentPageFirstEnabled(this, enabled)
        );
    }

    private void buildActionSettings() {
        LinearLayout actionContainer = findViewById(R.id.search_action_settings_container);

        View precisionRow = createValueSetting(
                "OCR 识别精度",
                SearchPreferences.getOcrPrecisionLabel(this),
                this::showOcrPrecisionDialog
        );
        ocrPrecisionValue = precisionRow.findViewById(R.id.dynamic_setting_value);
        actionContainer.addView(precisionRow);
        addDivider(actionContainer);

        clearCacheRow = createValueSetting(
                "清除搜索缓存",
                "›",
                this::confirmClearSearchCache
        );
        clearCacheValue = clearCacheRow.findViewById(R.id.dynamic_setting_value);
        actionContainer.addView(clearCacheRow);
    }

    /**
     * 点击设置行会展开/收起说明；开关本身只负责修改值，避免用户查看说明时误切换设置。
     */
    private void addSwitchSetting(
            LinearLayout parent,
            String title,
            String description,
            boolean initialValue,
            Consumer<Boolean> onChanged
    ) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(12), dp(12), dp(12));
        root.setBackgroundColor(Color.WHITE);
        root.setClickable(true);
        root.setFocusable(true);

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTextColor(TEXT_PRIMARY);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        titleView.setGravity(Gravity.CENTER_VERTICAL);
        titleView.setCompoundDrawablePadding(dp(8));
        titleView.setLayoutParams(new LinearLayout.LayoutParams(0, dp(40), 1f));

        TextView infoIndicator = new TextView(this);
        infoIndicator.setText("▾");
        infoIndicator.setTextColor(TEXT_SECONDARY);
        infoIndicator.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        infoIndicator.setGravity(Gravity.CENTER);
        infoIndicator.setContentDescription("展开说明");
        infoIndicator.setLayoutParams(new LinearLayout.LayoutParams(dp(32), dp(40)));

        SwitchCompat switchView = new SwitchCompat(this);
        switchView.setChecked(initialValue);
        switchView.setShowText(false);
        switchView.setThumbTintList(
                AppCompatResources.getColorStateList(this, R.color.switch_thumb_tint)
        );
        switchView.setTrackTintList(
                AppCompatResources.getColorStateList(this, R.color.switch_track_tint)
        );
        switchView.setContentDescription(title);
        switchView.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        TextView descriptionView = new TextView(this);
        descriptionView.setText(description);
        descriptionView.setTextColor(TEXT_SECONDARY);
        descriptionView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        descriptionView.setLineSpacing(0f, 1.18f);
        descriptionView.setPadding(0, dp(2), dp(46), dp(2));
        descriptionView.setVisibility(View.GONE);

        titleRow.addView(titleView);
        titleRow.addView(infoIndicator);
        titleRow.addView(switchView);
        root.addView(titleRow);
        root.addView(descriptionView);

        View.OnClickListener toggleDescription = v -> {
            boolean showing = descriptionView.getVisibility() == View.VISIBLE;
            descriptionView.setVisibility(showing ? View.GONE : View.VISIBLE);
            infoIndicator.setText(showing ? "▾" : "▴");
            infoIndicator.setContentDescription(showing ? "展开说明" : "收起说明");
        };
        root.setOnClickListener(toggleDescription);
        titleView.setOnClickListener(toggleDescription);
        infoIndicator.setOnClickListener(toggleDescription);

        switchView.setOnCheckedChangeListener((buttonView, isChecked) -> onChanged.accept(isChecked));
        parent.addView(root);
    }

    private View createValueSetting(String title, String value, Runnable action) {
        LinearLayout row = new LinearLayout(this);
        row.setId(View.generateViewId());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(18), dp(12), dp(18), dp(12));
        row.setMinimumHeight(dp(64));
        row.setBackgroundColor(Color.WHITE);
        row.setClickable(true);
        row.setFocusable(true);

        TextView titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTextColor(TEXT_PRIMARY);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        titleView.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView valueView = new TextView(this);
        valueView.setId(R.id.dynamic_setting_value);
        valueView.setText(value);
        valueView.setTextColor(TEXT_SECONDARY);
        valueView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        valueView.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        valueView.setPadding(dp(12), 0, 0, 0);

        row.addView(titleView);
        row.addView(valueView);
        row.setOnClickListener(v -> action.run());
        return row;
    }

    private void showOcrPrecisionDialog() {
        String[] labels = {
                "快速（960）\n速度优先，适合大量页面；小字识别率可能下降。",
                "平衡（1280）\n速度和识别精度较均衡，推荐日常使用。",
                "高精度（1440）\n适合小字和复杂扫描件，但搜索更慢、内存占用更高。"
        };
        int currentWidth = SearchPreferences.getOcrRenderWidth(this);
        int selectedIndex = currentWidth == SearchPreferences.OCR_WIDTH_FAST
                ? 0
                : currentWidth == SearchPreferences.OCR_WIDTH_HIGH ? 2 : 1;

        new AlertDialog.Builder(this)
                .setTitle("OCR 识别精度")
                .setSingleChoiceItems(labels, selectedIndex, (dialog, which) -> {
                    int width = which == 0
                            ? SearchPreferences.OCR_WIDTH_FAST
                            : which == 2
                            ? SearchPreferences.OCR_WIDTH_HIGH
                            : SearchPreferences.OCR_WIDTH_BALANCED;
                    SearchPreferences.setOcrRenderWidth(this, width);
                    ocrPrecisionValue.setText(SearchPreferences.getOcrPrecisionLabel(this));
                    dialog.dismiss();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void confirmClearSearchCache() {
        if (clearingCache) return;
        new AlertDialog.Builder(this)
                .setTitle("清除搜索缓存")
                .setMessage("将删除 OCR 识别缓存和 PDF 文本页面索引。以后再次搜索这些文件时需要重新建立缓存。")
                .setPositiveButton("清除", (dialog, which) -> clearSearchCache())
                .setNegativeButton("取消", null)
                .show();
    }

    private void clearSearchCache() {
        if (clearingCache) return;
        clearingCache = true;
        clearCacheRow.setEnabled(false);
        clearCacheRow.setAlpha(0.6f);
        clearCacheValue.setText("清理中…");

        cacheExecutor.execute(() -> {
            Throwable failure = null;
            PdfSearchManager manager = null;
            try {
                manager = new PdfSearchManager(getApplicationContext());
                manager.clearCache();
                SearchPreferences.markSearchCacheCleared(getApplicationContext());
            } catch (Throwable error) {
                failure = error;
            } finally {
                if (manager != null) {
                    try {
                        manager.close();
                    } catch (Throwable ignored) {
                    }
                }
            }

            Throwable finalFailure = failure;
            runOnUiThread(() -> {
                clearingCache = false;
                clearCacheRow.setEnabled(true);
                clearCacheRow.setAlpha(1f);
                clearCacheValue.setText("›");
                if (finalFailure == null) {
                    Toast.makeText(this, "搜索缓存已清除", Toast.LENGTH_SHORT).show();
                } else {
                    String message = finalFailure.getMessage();
                    if (message == null || message.trim().isEmpty()) message = "未知错误";
                    Toast.makeText(this, "清除失败：" + message, Toast.LENGTH_LONG).show();
                }
            });
        });
    }

    private void addDivider(LinearLayout parent) {
        View divider = new View(this);
        divider.setBackgroundColor(DIVIDER_COLOR);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(1)
        );
        params.leftMargin = dp(18);
        parent.addView(divider, params);
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                value,
                getResources().getDisplayMetrics()
        ));
    }

    @Override
    protected void onDestroy() {
        cacheExecutor.shutdownNow();
        super.onDestroy();
    }
}
