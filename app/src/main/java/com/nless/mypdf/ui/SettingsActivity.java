package com.nless.mypdf.ui;


import com.nless.mypdf.R;
import com.nless.mypdf.core.ReadingPreferences;
import com.nless.mypdf.core.SearchPreferences;
import com.nless.mypdf.diagnostics.CrashReportingManager;
import com.nless.mypdf.diagnostics.DiagnosticContext;
import com.nless.mypdf.feature.feedback.FeedbackActivity;
import com.nless.mypdf.feature.feedback.FeedbackLauncher;
import com.nless.mypdf.feature.manage.PdfMergeWorkerService;
import com.nless.mypdf.ui.dialog.PrivacyInfoDialogs;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
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

    private LinearLayout readingExperienceContainer;
    private LinearLayout searchMatchingContainer;
    private LinearLayout ocrSettingsContainer;
    private TextView ocrPrecisionValue;
    private TextView clearCacheValue;
    private View clearCacheRow;
    private volatile boolean clearingCache = false;
    private boolean changingCrashSwitch = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        Toolbar toolbar = findViewById(R.id.settings_toolbar);
        toolbar.setTitle("设置");
        toolbar.setNavigationIcon(android.R.drawable.ic_menu_revert);
        toolbar.setNavigationOnClickListener(v -> finish());

        readingExperienceContainer = findViewById(R.id.reading_experience_container);
        searchMatchingContainer = findViewById(R.id.search_matching_container);
        ocrSettingsContainer = findViewById(R.id.ocr_settings_container);

        buildReadingExperienceSettings();
        buildSearchMatchingSettings();
        buildOcrSettings();
        buildActionSettings();
        buildFeedbackSettings();
        buildPrivacySettings();
    }


    private void buildReadingExperienceSettings() {
        addSwitchSetting(
                readingExperienceContainer,
                "实验性阅读控制栏",
                "实验性选项，默认关闭。开启后，PDF 页面底部会显示翻页模式、翻页动画和观看进度，并可切换横向单页阅读。部分漫画或特殊比例 PDF 在横向模式下可能出现较多留白，观看体验可能不如纵向模式；关闭后使用原有纵向阅读界面。",
                ReadingPreferences.isExperimentalReadingControlsEnabled(this),
                enabled -> ReadingPreferences.setExperimentalReadingControlsEnabled(
                        this, enabled)
        );
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
                SearchPreferences.getOcrPrecisionLabel(this) + "  ›",
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


    private View createDetailedSetting(
            String title,
            String summary,
            String value,
            Runnable action
    ) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(12), dp(18), dp(12));
        root.setMinimumHeight(dp(74));
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
        titleView.setLayoutParams(new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
        ));

        TextView valueView = new TextView(this);
        valueView.setId(R.id.dynamic_setting_value);
        valueView.setText(value);
        valueView.setTextColor(TEXT_SECONDARY);
        valueView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        valueView.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        valueView.setPadding(dp(12), 0, 0, 0);

        TextView summaryView = new TextView(this);
        summaryView.setText(summary);
        summaryView.setTextColor(TEXT_SECONDARY);
        summaryView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        summaryView.setLineSpacing(0f, 1.16f);
        summaryView.setPadding(0, dp(5), dp(28), 0);

        titleRow.addView(titleView);
        titleRow.addView(valueView);
        root.addView(titleRow);
        root.addView(summaryView);
        root.setOnClickListener(v -> action.run());
        return root;
    }

    private void buildFeedbackSettings() {
        LinearLayout container = findViewById(R.id.feedback_container);

        container.addView(createDetailedSetting(
                "GitHub 问题反馈",
                "推荐用于可以公开讨论、需要持续跟踪的问题或功能建议。将预填应用版本、Android 版本、设备型号和 ABI。",
                "›",
                () -> FeedbackLauncher.openGitHub(this)
        ));
        addDivider(container);

        container.addView(createDetailedSetting(
                "应用内反馈",
                "填写问题后通过 FormSubmit 转发至开发者邮箱。将自动附带应用版本、Android 版本、设备型号和 ABI；联系邮箱选填。",
                "›",
                () -> startActivity(new Intent(this, FeedbackActivity.class))
        ));
    }


    private void buildPrivacySettings() {
        LinearLayout container = findViewById(R.id.privacy_diagnostics_container);
        addCrashReportingSwitch(container);
        addDivider(container);

        container.addView(createValueSetting(
                "隐私政策",
                "›",
                () -> PrivacyInfoDialogs.showPrivacyPolicy(this)
        ));
        addDivider(container);

        container.addView(createValueSetting(
                "诊断数据白名单",
                "›",
                () -> PrivacyInfoDialogs.showDiagnosticAllowlist(this)
        ));
        addDivider(container);

        container.addView(createValueSetting(
                "删除设备上的未发送报告",
                "›",
                () -> {
                    if (CrashReportingManager.isEnabled(this)) {
                        Toast.makeText(this, "请先关闭匿名崩溃报告并重新打开应用", Toast.LENGTH_LONG).show();
                        return;
                    }
                    new AlertDialog.Builder(this)
                            .setTitle("删除未发送报告")
                            .setMessage("将请求删除 Crashlytics 暂存在本设备且尚未发送的报告，不影响已经发送到 Firebase 的报告。")
                            .setNegativeButton("取消", null)
                            .setPositiveButton("删除", (dialog, which) -> {
                                CrashReportingManager.deleteUnsentReports();
                                Toast.makeText(this, "已请求删除未发送报告", Toast.LENGTH_SHORT).show();
                            })
                            .show();
                }
        ));
    }

    private void addCrashReportingSwitch(LinearLayout parent) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(12), dp(12), dp(12));
        root.setBackgroundColor(Color.WHITE);

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText("匿名崩溃报告");
        title.setTextColor(TEXT_PRIMARY);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        title.setLayoutParams(new LinearLayout.LayoutParams(0, dp(40), 1f));
        title.setGravity(Gravity.CENTER_VERTICAL);

        SwitchCompat toggle = new SwitchCompat(this);
        toggle.setChecked(CrashReportingManager.isEnabled(this));
        toggle.setThumbTintList(AppCompatResources.getColorStateList(this, R.color.switch_thumb_tint));
        toggle.setTrackTintList(AppCompatResources.getColorStateList(this, R.color.switch_track_tint));
        toggle.setContentDescription("匿名崩溃报告");

        TextView description = new TextView(this);
        description.setText("默认关闭。开启后，仅发送崩溃堆栈、应用与设备技术信息，以及白名单中的粗粒度操作状态；不会发送 PDF、文件名、路径、正文、OCR 结果、搜索词、批注内容或密码。关闭后应用会退出一次，以确保所有进程从下次启动起停止收集。");
        description.setTextColor(TEXT_SECONDARY);
        description.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        description.setLineSpacing(0f, 1.18f);
        description.setPadding(0, dp(4), dp(24), dp(2));

        titleRow.addView(title);
        titleRow.addView(toggle);
        root.addView(titleRow);
        root.addView(description);

        toggle.setOnCheckedChangeListener((button, checked) -> {
            if (changingCrashSwitch) return;
            changingCrashSwitch = true;
            toggle.setChecked(!checked);
            changingCrashSwitch = false;
            if (checked) {
                showCrashConsentDialog(toggle);
            } else {
                showCrashDisableDialog(toggle);
            }
        });
        parent.addView(root);
    }

    private void showCrashConsentDialog(SwitchCompat toggle) {
        new AlertDialog.Builder(this)
                .setTitle("开启匿名崩溃报告")
                .setMessage(getString(R.string.crash_consent_message))
                .setNegativeButton("取消", null)
                .setPositiveButton("同意并开启", (dialog, which) -> {
                    CrashReportingManager.enableAfterConsent(this);
                    changingCrashSwitch = true;
                    toggle.setChecked(true);
                    changingCrashSwitch = false;
                    Toast.makeText(this, "匿名崩溃报告已开启", Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    private void showCrashDisableDialog(SwitchCompat toggle) {
        new AlertDialog.Builder(this)
                .setTitle("关闭匿名崩溃报告")
                .setMessage("关闭后应用将退出一次，确保主进程和 PDF 合并进程从下次启动起不再自动发送报告。重新打开 MyPDF 即可继续使用。")
                .setNegativeButton("取消", null)
                .setPositiveButton("关闭并退出", (dialog, which) -> {
                    CrashReportingManager.disable(this);
                    stopService(new Intent(this, PdfMergeWorkerService.class));
                    changingCrashSwitch = true;
                    toggle.setChecked(false);
                    changingCrashSwitch = false;
                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        finishAffinity();
                        Process.killProcess(Process.myPid());
                    }, 250L);
                })
                .show();
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
                    ocrPrecisionValue.setText(SearchPreferences.getOcrPrecisionLabel(this) + "  ›");
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
        DiagnosticContext.startOperation(this, "clear_search_cache");
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
                    DiagnosticContext.finishOperation(this, "clear_search_cache", true);
                    Toast.makeText(this, "搜索缓存已清除", Toast.LENGTH_SHORT).show();
                } else {
                    DiagnosticContext.finishOperation(this, "clear_search_cache", false);
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
