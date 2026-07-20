package com.nless.mypdf.ui.dialog;

import android.app.Activity;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.StyleSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.nless.mypdf.R;

/**
 * 以分节、项目符号和可折叠技术字段展示隐私政策与诊断白名单。
 */
public final class PrivacyInfoDialogs {

    private static final int TEXT_PRIMARY = 0xFF202124;
    private static final int TEXT_SECONDARY = 0xFF6F7378;
    private static final int ACTION_COLOR = 0xFF0277BD;

    private PrivacyInfoDialogs() {
    }

    public static void showPrivacyPolicy(Activity activity) {
        LinearLayout content = createContent(activity);

        addMetadata(
                activity,
                content,
                "最后更新：2026 年 7 月 20 日",
                "开发者：g-o-d-v"
        );

        LinearLayout summary = createCard(activity, 0xFFF2F7FD, 0xFFD8E6F5);
        addCardTitle(activity, summary, "先看这三点");
        addBullet(activity, summary, "PDF 阅读、搜索、OCR、批注、创建、转换和安全处理均在设备本地执行。");
        addBullet(activity, summary, "匿名崩溃报告默认关闭，只有您明确同意后才会发送新报告。");
        addBullet(activity, summary, "应用内反馈只有在您点击“提交反馈”后才会通过 FormSubmit 转发至开发者邮箱；联系邮箱选填。");
        content.addView(summary, cardLayoutParams(activity));

        addSectionTitle(activity, content, "1. 本地文档处理");
        addBullet(activity, content, "核心功能不会上传 PDF、页面图像、正文或 PDF 元数据。");
        addBullet(activity, content, "不会上传 OCR 结果、搜索词、批注内容、密码、文件名、路径或 URI。");

        addSectionTitle(activity, content, "2. 匿名崩溃报告");
        addParagraph(activity, content, "开启后，MyPDF 使用 Firebase Crashlytics 发送新的 Java、native 崩溃，以及 Android 11 及以上设备的部分 ANR 报告。");
        addBullet(activity, content, "可能包含 Firebase 安装标识符、随机会话标识、崩溃时间和应用版本。");
        addBullet(activity, content, "可能包含 Android 版本、设备型号、ABI、内存和磁盘等技术信息。");
        addBullet(activity, content, "可能包含异常堆栈、native 信号、二进制库信息、minidump 和白名单中的粗粒度状态。");

        addSectionTitle(activity, content, "3. MyPDF 添加的诊断状态");
        addBullet(activity, content, "当前功能、当前操作及阶段、应用进程和结果状态。");
        addBullet(activity, content, "文件大小区间、页数区间、是否加密或存在权限限制。");
        addBullet(activity, content, "OCR 模式与精度、批注类型、保存方式、耗时和可用内存区间。");
        addParagraph(activity, content, "这些状态不包含文档内容，也不会设置姓名、邮箱、手机号、Android ID、广告 ID、IMEI、序列号或自定义用户 ID。");

        addSectionTitle(activity, content, "4. 崩溃报告控制");
        addBullet(activity, content, "首次开启前，请求删除同意前暂存在设备上的未发送报告，并清除同意前的本地诊断上下文。");
        addBullet(activity, content, "您可以随时关闭；为确保主进程和 PDF 合并进程停止收集，应用会退出一次。");
        addBullet(activity, content, "关闭状态下可以单独请求删除设备上的未发送报告。");

        addSectionTitle(activity, content, "5. 主动问题反馈");
        addLabeledBullet(activity, content, "GitHub Issues", "适合公开讨论和持续跟踪。发布前可修改或删除预填信息，提交内容和 GitHub 账号可能公开。");
        addLabeledBullet(activity, content, "应用内反馈", "问题类型、问题描述、复现步骤和选填联系邮箱，仅在您点击提交后通过 FormSubmit 转发至开发者邮箱。");
        addLabeledBullet(activity, content, "自动附带", "应用版本、构建类型、Android 版本、设备厂商与型号、ABI、系统语言及崩溃报告开关状态。");
                addParagraph(activity, content, "反馈表单不支持文件或截图附件。请不要在文字中主动粘贴 PDF 内容、密码或其他敏感信息。");

        addSectionTitle(activity, content, "6. 数据用途");
        addParagraph(activity, content, "数据仅用于定位问题、修复崩溃、改进兼容性和评估功能建议，不用于广告、用户画像、阅读内容分析、营销或出售。基础版不启用 Google Analytics、广告 SDK 或 Firebase Performance Monitoring。");

        addSectionTitle(activity, content, "7. 第三方服务与保留");
        addLabeledBullet(activity, content, "Crashlytics", "由 Google LLC 提供。根据 Firebase 公开说明，崩溃堆栈、处理后的 minidump 和相关安装标识符通常保留约 90 天后开始移除。");
        addLabeledBullet(activity, content, "FormSubmit", "用于接收应用内反馈并转发至开发者邮箱。请求会直接发送到 formsubmit.co；MyPDF 不使用 Firebase Authentication 或 Cloud Firestore 保存反馈。根据 FormSubmit 公开说明，提交归档最多保留 30 天。");
        addLabeledBullet(activity, content, "反馈编号", "提交成功后会显示由 MyPDF 本地生成的反馈编号，便于您通过公开联系渠道补充说明或提出删除请求。");

        addSectionTitle(activity, content, "8. 联系方式");
        addLabeledLine(activity, content, "GitHub", "https://github.com/g-o-d-v/MyPDF/issues");
        addLabeledLine(activity, content, "邮箱", activity.getString(R.string.public_contact_email));

        LinearLayout warning = createCard(activity, 0xFFFFF7ED, 0xFFF2D7B5);
        addCardTitle(activity, warning, "提交反馈前请注意");
        addParagraph(activity, warning, "请勿提交包含敏感内容的 PDF、页面截图、正文、OCR 结果、搜索词、批注内容或密码。");
        content.addView(warning, cardLayoutParams(activity));

        showDialog(activity, "MyPDF 隐私政策", content);
    }

    public static void showDiagnosticAllowlist(Activity activity) {
        LinearLayout content = createContent(activity);

        LinearLayout intro = createCard(activity, 0xFFF2F7FD, 0xFFD8E6F5);
        addCardTitle(activity, intro, "这份白名单说明什么");
        addParagraph(activity, intro, "以下内容只说明 MyPDF 主动添加到 Crashlytics 的应用自定义状态。用户主动填写并通过 FormSubmit 发送的反馈不属于此白名单，具体见隐私政策的“主动问题反馈”章节。");
        content.addView(intro, cardLayoutParams(activity));

        addSectionTitle(activity, content, "允许发送的中文说明");
        addLabeledBullet(activity, content, "应用环境", "主进程或 PDF 合并进程、Debug 或 Release、CPU 架构。");
        addLabeledBullet(activity, content, "当前状态", "当前功能页面、正在执行的操作、操作阶段和成功/失败结果。");
        addLabeledBullet(activity, content, "文档粗粒度状态", "来源类别、文件大小区间、页数区间、是否加密、是否存在权限限制。");
        addLabeledBullet(activity, content, "OCR 状态", "是否启用、搜索/OCR 模式、960/1280/1440 精度档位。");
        addLabeledBullet(activity, content, "编辑状态", "批注类型和覆盖保存/另存副本方式，不包含批注内容。");
        addLabeledBullet(activity, content, "性能状态", "操作耗时区间、系统可用内存区间、是否启用 largeHeap。");
        addLabeledBullet(activity, content, "退出与符号状态", "上次退出原因类别、退出进程类别、native 符号状态和诊断结构版本。");

        LinearLayout fixedLogNotice = createCard(activity, 0xFFF7F7F8, 0xFFE0E2E5);
        addCardTitle(activity, fixedLogNotice, "事件日志限制");
        addParagraph(activity, fixedLogNotice, "所有事件日志只能使用预先写入代码的固定名称，不能拼接用户输入或文档内容。");
        content.addView(fixedLogNotice, cardLayoutParams(activity));

        addSectionTitle(activity, content, "明确禁止");
        LinearLayout denied = createCard(activity, 0xFFFFF4F4, 0xFFF0CDCD);
        addLabeledBullet(activity, denied, "文档与内容", "PDF 文件、文件名、路径、URI、PDF 元数据、正文、OCR 文本、搜索词、批注内容或手写轨迹、密码、截图。");
        addLabeledBullet(activity, denied, "身份与设备标识", "联系人、邮箱、手机号、姓名、Android ID、广告 ID、IMEI、序列号、精确位置。");
        addLabeledBullet(activity, denied, "其他敏感数据", "不会读取或上传剪贴板中原有的内容，也不会收集应用列表和 Logcat。");
        content.addView(denied, cardLayoutParams(activity));

        addSectionTitle(activity, content, "技术字段（供开发者与源码审查）");
        TextView technicalToggle = createActionText(activity, "显示技术字段（24 项）  ▾");
        LinearLayout technicalBox = createCard(activity, 0xFFF7F7F8, 0xFFE0E2E5);
        technicalBox.setVisibility(View.GONE);

        TextView fields = new TextView(activity);
        fields.setText("app_process · build_type · app_abi\n"
                + "current_screen · current_operation\n"
                + "operation_stage · operation_result\n"
                + "document_source · file_size_bucket\n"
                + "page_count_bucket · encrypted\n"
                + "permission_restricted · ocr_enabled\n"
                + "ocr_mode · ocr_width\n"
                + "annotation_type · save_mode\n"
                + "operation_duration_bucket\n"
                + "available_memory_bucket\n"
                + "last_exit_reason · last_exit_process\n"
                + "large_heap_enabled\n"
                + "native_symbol_status · diagnostic_schema");
        fields.setTextColor(TEXT_PRIMARY);
        fields.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        fields.setTypeface(Typeface.MONOSPACE);
        fields.setLineSpacing(dp(activity, 3), 1.16f);
        fields.setTextIsSelectable(true);
        technicalBox.addView(fields);

        technicalToggle.setOnClickListener(v -> {
            boolean showing = technicalBox.getVisibility() == View.VISIBLE;
            technicalBox.setVisibility(showing ? View.GONE : View.VISIBLE);
            technicalToggle.setText(showing
                    ? "显示技术字段（24 项）  ▾"
                    : "收起技术字段（24 项）  ▴");
        });
        content.addView(technicalToggle);
        content.addView(technicalBox, cardLayoutParams(activity));

        showDialog(activity, "诊断数据白名单", content);
    }

    private static LinearLayout createContent(Activity activity) {
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(activity, 20), dp(activity, 4), dp(activity, 20), dp(activity, 24));
        return content;
    }

    private static void showDialog(Activity activity, String title, LinearLayout content) {
        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(false);
        scroll.setScrollbarFadingEnabled(false);
        scroll.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        int maxHeight = Math.min(
                dp(activity, 620),
                Math.round(activity.getResources().getDisplayMetrics().heightPixels * 0.80f)
        );
        scroll.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                maxHeight
        ));

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(title)
                .setView(scroll, 0, 0, 0, 0)
                .setPositiveButton("知道了", null)
                .create();
        dialog.setOnShowListener(ignored -> {
            android.view.Window window = dialog.getWindow();
            if (window != null) {
                int width = Math.round(activity.getResources().getDisplayMetrics().widthPixels * 0.94f);
                window.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
            }
        });
        dialog.show();
    }

    private static void addMetadata(
            Activity activity,
            LinearLayout parent,
            String firstLine,
            String secondLine
    ) {
        TextView metadata = new TextView(activity);
        metadata.setText(firstLine + "\n" + secondLine);
        metadata.setTextColor(TEXT_SECONDARY);
        metadata.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        metadata.setLineSpacing(dp(activity, 2), 1.12f);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.bottomMargin = dp(activity, 12);
        parent.addView(metadata, params);
    }

    private static void addSectionTitle(Activity activity, LinearLayout parent, String title) {
        TextView titleView = new TextView(activity);
        titleView.setText(title);
        titleView.setTextColor(TEXT_PRIMARY);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        titleView.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.topMargin = dp(activity, 18);
        params.bottomMargin = dp(activity, 8);
        parent.addView(titleView, params);
    }

    private static void addCardTitle(Activity activity, LinearLayout parent, String title) {
        TextView titleView = new TextView(activity);
        titleView.setText(title);
        titleView.setTextColor(TEXT_PRIMARY);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        titleView.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.bottomMargin = dp(activity, 6);
        parent.addView(titleView, params);
    }

    private static void addParagraph(Activity activity, LinearLayout parent, String text) {
        TextView paragraph = new TextView(activity);
        paragraph.setText(text);
        paragraph.setTextColor(TEXT_PRIMARY);
        paragraph.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        paragraph.setLineSpacing(dp(activity, 3), 1.16f);
        paragraph.setTextIsSelectable(true);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.bottomMargin = dp(activity, 8);
        parent.addView(paragraph, params);
    }

    private static void addBullet(Activity activity, LinearLayout parent, String text) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.TOP);

        TextView bullet = new TextView(activity);
        bullet.setText("•");
        bullet.setTextColor(TEXT_PRIMARY);
        bullet.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        bullet.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        bullet.setLayoutParams(new LinearLayout.LayoutParams(
                dp(activity, 20),
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        TextView body = new TextView(activity);
        body.setText(text);
        body.setTextColor(TEXT_PRIMARY);
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        body.setLineSpacing(dp(activity, 3), 1.16f);
        body.setTextIsSelectable(true);
        body.setLayoutParams(new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
        ));

        row.addView(bullet);
        row.addView(body);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.bottomMargin = dp(activity, 7);
        parent.addView(row, params);
    }

    private static void addLabeledBullet(
            Activity activity,
            LinearLayout parent,
            String label,
            String detail
    ) {
        SpannableStringBuilder text = labeledText(label, detail);

        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.TOP);

        TextView bullet = new TextView(activity);
        bullet.setText("•");
        bullet.setTextColor(TEXT_PRIMARY);
        bullet.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        bullet.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        bullet.setLayoutParams(new LinearLayout.LayoutParams(
                dp(activity, 20),
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        TextView body = new TextView(activity);
        body.setText(text);
        body.setTextColor(TEXT_PRIMARY);
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        body.setLineSpacing(dp(activity, 3), 1.16f);
        body.setTextIsSelectable(true);
        body.setLayoutParams(new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
        ));

        row.addView(bullet);
        row.addView(body);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.bottomMargin = dp(activity, 8);
        parent.addView(row, params);
    }

    private static void addLabeledLine(
            Activity activity,
            LinearLayout parent,
            String label,
            String value
    ) {
        TextView line = new TextView(activity);
        line.setText(labeledText(label, value));
        line.setTextColor(TEXT_PRIMARY);
        line.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        line.setLineSpacing(dp(activity, 3), 1.16f);
        line.setTextIsSelectable(true);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.bottomMargin = dp(activity, 8);
        parent.addView(line, params);
    }

    private static SpannableStringBuilder labeledText(String label, String value) {
        SpannableStringBuilder text = new SpannableStringBuilder();
        int start = text.length();
        text.append(label).append("：");
        text.setSpan(
                new StyleSpan(Typeface.BOLD),
                start,
                text.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        );
        text.append(value);
        return text;
    }

    private static LinearLayout createCard(Activity activity, int fillColor, int strokeColor) {
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(
                dp(activity, 14),
                dp(activity, 12),
                dp(activity, 14),
                dp(activity, 8)
        );

        GradientDrawable background = new GradientDrawable();
        background.setColor(fillColor);
        background.setCornerRadius(dp(activity, 10));
        background.setStroke(dp(activity, 1), strokeColor);
        card.setBackground(background);
        return card;
    }

    private static LinearLayout.LayoutParams cardLayoutParams(Activity activity) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.topMargin = dp(activity, 8);
        params.bottomMargin = dp(activity, 8);
        return params;
    }

    private static TextView createActionText(Activity activity, String text) {
        TextView action = new TextView(activity);
        action.setText(text);
        action.setTextColor(ACTION_COLOR);
        action.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        action.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        action.setGravity(Gravity.CENTER_VERTICAL);
        action.setPadding(0, dp(activity, 8), 0, dp(activity, 8));
        action.setClickable(true);
        action.setFocusable(true);
        return action;
    }

    private static int dp(Activity activity, int value) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                value,
                activity.getResources().getDisplayMetrics()
        ));
    }
}
