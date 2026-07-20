package com.nless.mypdf.feature.feedback;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.util.Patterns;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.nless.mypdf.R;
import com.nless.mypdf.diagnostics.DiagnosticContext;

/** 用户主动通过 FormSubmit 提交到开发者邮箱的应用内反馈表单。 */
public class FeedbackActivity extends AppCompatActivity {

    private static final String[] CATEGORY_LABELS = {
            "Bug / 功能异常", "功能建议", "使用疑问", "其他"
    };
    private static final String[] CATEGORY_VALUES = {
            "bug", "feature", "question", "other"
    };

    private MaterialAutoCompleteTextView categoryInput;
    private EditText descriptionInput;
    private EditText reproductionInput;
    private EditText emailInput;
    private Button submitButton;
    private ProgressBar progressBar;
    private int selectedCategoryIndex;
    private boolean submitting;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_feedback);

        Toolbar toolbar = findViewById(R.id.feedback_toolbar);
        toolbar.setTitle("应用内反馈");
        toolbar.setNavigationIcon(android.R.drawable.ic_menu_revert);
        toolbar.setNavigationOnClickListener(v -> finish());

        categoryInput = findViewById(R.id.feedback_category);
        descriptionInput = findViewById(R.id.feedback_description);
        reproductionInput = findViewById(R.id.feedback_reproduction);
        emailInput = findViewById(R.id.feedback_email);
        submitButton = findViewById(R.id.feedback_submit);
        progressBar = findViewById(R.id.feedback_progress);
        TextView deviceInfo = findViewById(R.id.feedback_device_info);

        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_list_item_1,
                CATEGORY_LABELS
        );
        categoryInput.setAdapter(adapter);
        categoryInput.setText(CATEGORY_LABELS[0], false);
        categoryInput.setOnItemClickListener((parent, view, position, id) ->
                selectedCategoryIndex = position);
        categoryInput.setOnClickListener(v -> categoryInput.showDropDown());

        deviceInfo.setText(FormSubmitFeedbackService.buildDeviceSummary(this));
        submitButton.setOnClickListener(v -> submit());
    }

    private void submit() {
        if (submitting) return;

        String description = text(descriptionInput);
        String reproduction = text(reproductionInput);
        String email = text(emailInput);

        if (description.length() < 10) {
            descriptionInput.setError("请至少填写 10 个字符的问题描述");
            descriptionInput.requestFocus();
            return;
        }
        if (description.length() > 4000) {
            descriptionInput.setError("问题描述不能超过 4000 个字符");
            return;
        }
        if (reproduction.length() > 4000) {
            reproductionInput.setError("复现步骤不能超过 4000 个字符");
            return;
        }
        if (!email.isEmpty() && (!Patterns.EMAIL_ADDRESS.matcher(email).matches()
                || email.length() > 254)) {
            emailInput.setError("请输入有效邮箱，或留空");
            emailInput.requestFocus();
            return;
        }
        if (!hasInternetCapability()) {
            Toast.makeText(this, "当前没有可用网络，请联网后重试", Toast.LENGTH_LONG).show();
            return;
        }

        int safeIndex = Math.max(0, Math.min(selectedCategoryIndex, CATEGORY_VALUES.length - 1));
        String categoryValue = CATEGORY_VALUES[safeIndex];
        String categoryLabel = CATEGORY_LABELS[safeIndex];
        setSubmitting(true);
        DiagnosticContext.startOperation(this, "feedback_submit");

        FormSubmitFeedbackService.submit(
                this,
                categoryValue,
                categoryLabel,
                description,
                reproduction,
                email,
                new FormSubmitFeedbackService.Callback() {
                    @Override
                    public void onSuccess(String feedbackId) {
                        DiagnosticContext.finishOperation(
                                FeedbackActivity.this,
                                "feedback_submit",
                                true
                        );
                        setSubmitting(false);
                        descriptionInput.setText("");
                        reproductionInput.setText("");
                        emailInput.setText("");
                        showSuccess(feedbackId);
                    }

                    @Override
                    public void onFailure(
                            FormSubmitFeedbackService.SubmissionException error
                    ) {
                        DiagnosticContext.finishOperation(
                                FeedbackActivity.this,
                                "feedback_submit",
                                false
                        );
                        setSubmitting(false);
                        showFailure(error);
                    }
                }
        );
    }

    private void showSuccess(String feedbackId) {
        new AlertDialog.Builder(this)
                .setTitle("反馈已提交")
                .setMessage("感谢反馈。内容已经交给 FormSubmit 转发至开发者邮箱。"
                        + "\n\n反馈编号：\n" + feedbackId
                        + "\n\n需要补充说明时，可通过 GitHub Issues 或公开联系邮箱提供此编号。")
                .setNeutralButton("复制编号", (dialog, which) -> copyText(
                        "MyPDF 反馈编号",
                        feedbackId,
                        "反馈编号已复制"
                ))
                .setPositiveButton("完成", (dialog, which) -> finish())
                .show();
    }

    private void showFailure(FormSubmitFeedbackService.SubmissionException error) {
        String message;
        switch (error.getReason()) {
            case TIMEOUT:
                message = "连接反馈服务超时，请检查网络后重试。";
                break;
            case HTTP_ERROR:
                message = error.getHttpCode() > 0
                        ? "反馈服务返回错误（HTTP " + error.getHttpCode() + "）。"
                        : "反馈服务返回错误。";
                message += serviceDetail(error);
                break;
            case ACTIVATION_REQUIRED:
                message = "应用内反馈通道尚未完成首次激活。请先通过项目中的 "
                        + "docs/formsubmit-activate.html 网页表单触发确认邮件，"
                        + "再登录接收邮箱完成激活。";
                break;
            case SERVICE_REJECTED:
                message = "反馈服务拒绝了本次提交。" + serviceDetail(error);
                break;
            case INVALID_RESPONSE:
                message = "反馈服务返回了无法识别的响应。" + serviceDetail(error);
                break;
            case NETWORK:
            default:
                message = "暂时无法连接反馈服务，请检查网络后重试。";
                break;
        }

        new AlertDialog.Builder(this)
                .setTitle("提交失败")
                .setMessage(message + "\n\n表单内容仍保留在当前页面。")
                .setNeutralButton("复制反馈内容", (dialog, which) -> copyText(
                        "MyPDF 反馈内容",
                        buildFallbackText(),
                        "反馈内容已复制"
                ))
                .setPositiveButton("知道了", null)
                .show();
    }


    private String serviceDetail(FormSubmitFeedbackService.SubmissionException error) {
        String detail = error.getMessage();
        if (detail == null) return "";
        detail = detail.replaceAll("\\s+", " ").trim();
        if (detail.isEmpty()
                || "反馈服务拒绝了本次提交".equals(detail)
                || "反馈请求失败".equals(detail)) {
            return " 请稍后重试。";
        }
        if (detail.length() > 220) {
            detail = detail.substring(0, 220) + "…";
        }
        return "\n\n服务返回：" + detail;
    }

    private String buildFallbackText() {
        int safeIndex = Math.max(0, Math.min(selectedCategoryIndex, CATEGORY_LABELS.length - 1));
        return "MyPDF 应用反馈\n\n"
                + "问题类型：" + CATEGORY_LABELS[safeIndex] + "\n\n"
                + "问题描述：\n" + text(descriptionInput) + "\n\n"
                + "复现步骤：\n" + text(reproductionInput) + "\n\n"
                + "联系邮箱：" + text(emailInput) + "\n\n"
                + "自动附带的技术信息：\n"
                + FormSubmitFeedbackService.buildDeviceSummary(this);
    }

    private void setSubmitting(boolean value) {
        submitting = value;
        categoryInput.setEnabled(!value);
        descriptionInput.setEnabled(!value);
        reproductionInput.setEnabled(!value);
        emailInput.setEnabled(!value);
        submitButton.setEnabled(!value);
        submitButton.setText(value ? "正在提交…" : "提交反馈");
        progressBar.setVisibility(value ? View.VISIBLE : View.GONE);
    }

    private boolean hasInternetCapability() {
        ConnectivityManager manager =
                (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (manager == null) return true;
        Network network = manager.getActiveNetwork();
        if (network == null) return false;
        NetworkCapabilities capabilities = manager.getNetworkCapabilities(network);
        return capabilities != null
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    private void copyText(String label, String value, String successMessage) {
        ClipboardManager clipboard =
                (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) {
            Toast.makeText(this, "无法访问剪贴板", Toast.LENGTH_SHORT).show();
            return;
        }
        clipboard.setPrimaryClip(ClipData.newPlainText(label, value));
        Toast.makeText(this, successMessage, Toast.LENGTH_SHORT).show();
    }

    private static String text(EditText input) {
        return input.getText() == null ? "" : input.getText().toString().trim();
    }
}
