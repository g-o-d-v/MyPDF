package com.nless.mypdf;

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

/**
 * 工具分类页。
 *
 * 创建 PDF、导出与转换分类已接入实际处理能力，其他分类保留入口和说明 UI。
 */
public class ToolCategoryActivity extends AppCompatActivity {

    public static final String EXTRA_CATEGORY = "tool_category";

    public static final String CATEGORY_CREATE_PDF = "create_pdf";
    public static final String CATEGORY_EXPORT_CONVERT = "export_convert";
    public static final String CATEGORY_MANAGE_PAGES = "manage_pages";
    public static final String CATEGORY_PDF_PROTECTION = "pdf_protection";

    private static final int TEXT_PRIMARY = 0xFF202124;
    private static final int TEXT_SECONDARY = 0xFF6F7378;
    private static final int DIVIDER_COLOR = 0xFFE2E5E9;
    private static final int PAGE_BACKGROUND = 0xFFF4F5F7;
    private static final int INFO_BACKGROUND = 0xFFE8F4FD;
    private static final int INFO_TEXT = 0xFF1769AA;

    private LinearLayout toolListContainer;
    private TextView categoryHint;
    private View categoryHintGap;
    private TextView categoryFooter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_tool_category);

        Toolbar toolbar = findViewById(R.id.tool_category_toolbar);
        toolbar.setNavigationIcon(android.R.drawable.ic_menu_revert);
        toolbar.setNavigationOnClickListener(v -> finish());

        toolListContainer = findViewById(R.id.tool_list_container);
        categoryHint = findViewById(R.id.tool_category_hint);
        categoryHintGap = findViewById(R.id.tool_category_hint_gap);
        categoryFooter = findViewById(R.id.tool_category_footer);

        String category = getIntent().getStringExtra(EXTRA_CATEGORY);
        if (category == null) category = CATEGORY_CREATE_PDF;

        if (CATEGORY_EXPORT_CONVERT.equals(category)) {
            toolbar.setTitle("导出与转换");
            categoryFooter.setText("导出结果会保存到用户选择的位置；OCR 相关操作均在本机完成。");
            buildExportConvertTools();
        } else if (CATEGORY_MANAGE_PAGES.equals(category)) {
            toolbar.setTitle("管理 PDF 页面");
            categoryFooter.setText("当前仅开放功能入口，点击可查看规划说明。");
            buildManagePageTools();
        } else if (CATEGORY_PDF_PROTECTION.equals(category)) {
            toolbar.setTitle("PDF 保护");
            categoryFooter.setText("当前仅开放功能入口，点击可查看规划说明。");
            buildProtectionTools();
        } else {
            toolbar.setTitle("创建 PDF");
            categoryFooter.setText("创建完成后将另存为新的 PDF，并自动加入首页列表。");
            buildCreatePdfTools();
        }
    }

    private void buildCreatePdfTools() {
        // 创建 PDF 页面本身已经足够直观，不再展示额外的流程说明。
        categoryHint.setVisibility(View.GONE);
        categoryHintGap.setVisibility(View.GONE);
        addCreateToolRow(
                "图片转 PDF",
                "选择一张或多张图片，调整顺序后合成为 PDF。",
                CreatePdfActivity.MODE_IMAGES
        );
        addDivider();
        addCreateToolRow(
                "文本转 PDF",
                "输入纯文本并设置基础页面样式后生成 PDF。",
                CreatePdfActivity.MODE_TEXT
        );
        addDivider();
        addCreateToolRow(
                "创建空白 PDF",
                "选择页面尺寸、方向和页数，创建空白 PDF。",
                CreatePdfActivity.MODE_BLANK
        );
    }

    private void buildExportConvertTools() {
        setCategoryHint(
                "扫描件的文本导出和可搜索文字层需要逐页 OCR。页数越多、识别精度越高，处理时间和内存占用越大；识别结果也会受到清晰度、倾斜、字体和版式影响。",
                true
        );
        addExportToolRow(
                "PDF 转图片",
                "将指定页面或全部页面导出为 PNG/JPEG 图片。",
                ExportConvertActivity.MODE_PDF_TO_IMAGES,
                false
        );
        addDivider();
        addExportToolRow(
                "导出文本",
                "文本层 PDF 可直接导出；扫描件需要 OCR，耗时和准确率取决于文档质量。",
                ExportConvertActivity.MODE_EXPORT_TEXT,
                true
        );
        addDivider();
        addExportToolRow(
                "添加可搜索文字层",
                "保留扫描页面外观，通过 OCR 生成新的可搜索、可复制 PDF；结果需要人工检查。",
                ExportConvertActivity.MODE_SEARCHABLE_PDF,
                true
        );
    }

    private void buildManagePageTools() {
        setCategoryHint(
                "页面操作默认另存为新文件，避免处理中断或参数错误损坏原 PDF。",
                false
        );
        addToolRow(
                "页面排序",
                "通过缩略图拖动调整 PDF 页面顺序。",
                false
        );
        addDivider();
        addToolRow(
                "删除页面",
                "选择一个或多个页面并生成删除后的 PDF 副本。",
                false
        );
        addDivider();
        addToolRow(
                "另存所选页面",
                "把选中的页面保留为新的 PDF，继续保留文本层和矢量内容。",
                false
        );
        addDivider();
        addToolRow(
                "合并 PDF",
                "选择多个 PDF，调整文件顺序后合并为一个新文件。",
                false
        );
    }

    private void buildProtectionTools() {
        setCategoryHint(
                "PDF 权限限制需要其他阅读器主动遵守，并不等同于不可绕过的 DRM。所有操作应优先另存为副本。",
                true
        );
        addToolRow(
                "设置/移除密码",
                "设置打开密码，或在已知密码的情况下移除保护。",
                false
        );
        addDivider();
        addToolRow(
                "权限限制",
                "设置打印、复制、修改和添加批注等权限。",
                true
        );
        addDivider();
        addToolRow(
                "清除元数据",
                "清除标题、作者、主题、关键词、创建程序、日期和 XMP 等文档属性。",
                false
        );
    }

    private void setCategoryHint(String text, boolean warning) {
        categoryHint.setText(text);
        categoryHint.setTextColor(warning ? INFO_TEXT : TEXT_SECONDARY);
        categoryHint.setBackgroundColor(warning ? INFO_BACKGROUND : PAGE_BACKGROUND);
    }

    private void addCreateToolRow(String title, String description, String createMode) {
        addToolRow(title, description, false, () -> {
            Intent intent = new Intent(this, CreatePdfActivity.class);
            intent.putExtra(CreatePdfActivity.EXTRA_MODE, createMode);
            startActivity(intent);
        });
    }

    private void addExportToolRow(
            String title,
            String description,
            String exportMode,
            boolean showCaution
    ) {
        addToolRow(title, description, showCaution, () -> {
            Intent intent = new Intent(this, ExportConvertActivity.class);
            intent.putExtra(ExportConvertActivity.EXTRA_MODE, exportMode);
            startActivity(intent);
        });
    }

    private void addToolRow(String title, String description, boolean showCaution) {
        addToolRow(title, description, showCaution, null);
    }

    private void addToolRow(String title, String description, boolean showCaution, Runnable action) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(18), dp(14), dp(14), dp(14));
        row.setMinimumHeight(dp(76));
        row.setBackgroundColor(Color.WHITE);
        row.setClickable(true);
        row.setFocusable(true);

        LinearLayout textColumn = new LinearLayout(this);
        textColumn.setOrientation(LinearLayout.VERTICAL);
        textColumn.setLayoutParams(new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
        ));

        TextView titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTextColor(TEXT_PRIMARY);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        titleView.setTypeface(titleView.getTypeface(), android.graphics.Typeface.BOLD);

        TextView descriptionView = new TextView(this);
        descriptionView.setText(description);
        descriptionView.setTextColor(showCaution ? INFO_TEXT : TEXT_SECONDARY);
        descriptionView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        descriptionView.setLineSpacing(0f, 1.16f);
        descriptionView.setPadding(0, dp(5), dp(8), 0);

        TextView arrowView = new TextView(this);
        arrowView.setText("›");
        arrowView.setTextColor(0xFF8A8F95);
        arrowView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        arrowView.setGravity(Gravity.CENTER);
        arrowView.setLayoutParams(new LinearLayout.LayoutParams(dp(28), dp(48)));

        textColumn.addView(titleView);
        textColumn.addView(descriptionView);
        row.addView(textColumn);
        row.addView(arrowView);

        row.setOnClickListener(v -> {
            if (action != null) action.run();
            else showPendingDialog(title, description, showCaution);
        });
        toolListContainer.addView(row);
    }

    private void showPendingDialog(String title, String description, boolean caution) {
        StringBuilder message = new StringBuilder(description)
                .append("\n\n当前版本只完成了功能入口 UI，实际处理逻辑将在后续批次接入。");
        if (caution) {
            message.append("\n\n开发时会保留耗时、识别精度或权限限制提示，并优先采用另存为副本的流程。");
        }
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message.toString())
                .setPositiveButton("知道了", null)
                .show();
    }

    private void addDivider() {
        View divider = new View(this);
        divider.setBackgroundColor(DIVIDER_COLOR);
        divider.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(1)
        ));
        toolListContainer.addView(divider);
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                value,
                getResources().getDisplayMetrics()
        ));
    }
}
