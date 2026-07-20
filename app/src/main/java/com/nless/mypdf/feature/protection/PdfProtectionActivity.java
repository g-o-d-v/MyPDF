package com.nless.mypdf.feature.protection;


import com.nless.mypdf.R;
import com.nless.mypdf.core.PdfSecurityContext;
import com.nless.mypdf.data.PdfDbHelper;
import com.nless.mypdf.data.PdfItem;
import com.nless.mypdf.diagnostics.DiagnosticContext;
import com.nless.mypdf.ui.MainActivity;
import com.nless.mypdf.ui.PdfViewerActivity;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.appcompat.widget.Toolbar;
import androidx.documentfile.provider.DocumentFile;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** PDF 保护：设置/移除密码、权限限制、清除元数据。 */
public class PdfProtectionActivity extends AppCompatActivity {

    public static final String EXTRA_MODE = "pdf_protection_mode";
    public static final String MODE_PASSWORD = "password";
    public static final String MODE_PERMISSIONS = "permissions";
    public static final String MODE_METADATA = "metadata";

    private static final int TEXT_PRIMARY = 0xFF202124;
    private static final int TEXT_SECONDARY = 0xFF6F7378;
    private static final int BLUE = 0xFF03A9F4;

    private Toolbar toolbar;
    private TextView descriptionView;
    private LinearLayout sourceCard;
    private LinearLayout settingsCard;
    private TextView tipView;
    private TextView actionButton;

    private TextView sourceNameView;
    private TextView sourceDetailView;

    private String mode = MODE_PASSWORD;
    private Uri sourceUri;
    private String sourceName = "";
    private int sourcePageCount = -1;
    private boolean sourceEncrypted;
    private boolean sourceRequiresOpenPassword;

    private int passwordOperationIndex;
    private EditText currentPasswordInput;
    private EditText openPasswordInput;
    private EditText confirmPasswordInput;
    private EditText permissionCurrentPasswordInput;
    private EditText permissionOwnerPasswordInput;
    private boolean allowPrint = true;
    private boolean allowCopy = true;
    private boolean allowModify = true;
    private boolean allowAnnotate = true;

    private EditText metadataCurrentPasswordInput;
    private boolean clearInfoEnabled = true;
    private boolean clearXmpEnabled = true;

    private final ExecutorService workerExecutor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private AlertDialog progressDialog;
    private ProgressBar progressBar;
    private TextView progressText;
    private File preparedOutput;

    private final ActivityResultLauncher<String[]> sourcePicker = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(),
            uri -> {
                if (uri == null) return;
                try {
                    getContentResolver().takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                    );
                } catch (Exception ignore) {
                }
                inspectSource(uri);
            }
    );

    private final ActivityResultLauncher<String> outputPicker = registerForActivityResult(
            new ActivityResultContracts.CreateDocument("application/pdf"),
            uri -> {
                if (uri == null) {
                    clearPreparedOutput();
                    DiagnosticContext.cancelOperation(this, "pdf_protection");
                    return;
                }
                try {
                    getContentResolver().takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    );
                } catch (Exception ignore) {
                }
                commitPreparedOutput(uri);
            }
    );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_pdf_protection);

        toolbar = findViewById(R.id.pdf_protection_toolbar);
        toolbar.setNavigationIcon(android.R.drawable.ic_menu_revert);
        toolbar.setNavigationOnClickListener(v -> finish());
        descriptionView = findViewById(R.id.pdf_protection_description);
        sourceCard = findViewById(R.id.pdf_protection_source_card);
        settingsCard = findViewById(R.id.pdf_protection_settings_card);
        tipView = findViewById(R.id.pdf_protection_tip);
        actionButton = findViewById(R.id.pdf_protection_button);
        actionButton.setOnClickListener(v -> startSelectedOperation());

        String requestedMode = getIntent().getStringExtra(EXTRA_MODE);
        if (MODE_PERMISSIONS.equals(requestedMode) || MODE_METADATA.equals(requestedMode)) {
            mode = requestedMode;
        }
        buildUi();
    }

    @Override
    protected void onDestroy() {
        cancelled.set(true);
        clearPreparedOutput();
        workerExecutor.shutdownNow();
        super.onDestroy();
    }

    private void buildUi() {
        if (MODE_PERMISSIONS.equals(mode)) {
            toolbar.setTitle("权限限制");
            descriptionView.setText("设置打印、复制、修改和添加批注等权限，并另存为新的受限制 PDF。权限限制依赖阅读器主动遵守，并不等同于 DRM。 ");
        } else if (MODE_METADATA.equals(mode)) {
            toolbar.setTitle("清除元数据");
            descriptionView.setText("清除标题、作者、主题、关键词、创建程序、日期以及 XMP 元数据，减少文档携带的附加信息。 ");
        } else {
            toolbar.setTitle("设置/移除密码");
            descriptionView.setText("设置打开密码，或在已知当前密码的情况下移除保护。建议始终另存为新副本，避免误操作影响原文件。 ");
        }
        buildSourceCard();
        buildSettingsCard();
        updateTip();
        refreshActionButton();
    }

    private void buildSourceCard() {
        sourceCard.removeAllViews();
        LinearLayout row = horizontalRow();
        row.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout textColumn = new LinearLayout(this);
        textColumn.setOrientation(LinearLayout.VERTICAL);
        sourceNameView = text(sourceUri == null ? "尚未选择 PDF" : sourceName,
                15, TEXT_PRIMARY, true);
        sourceNameView.setMaxLines(2);
        sourceDetailView = text(sourceUri == null
                        ? "点击右侧按钮选择本地 PDF 文件"
                        : (TextUtils.isEmpty(sourceName) ? "正在读取文件信息……" : "正在读取文件信息……"),
                12, TEXT_SECONDARY, false);
        sourceDetailView.setPadding(0, dp(6), 0, 0);
        textColumn.addView(sourceNameView);
        textColumn.addView(sourceDetailView);
        row.addView(textColumn, new LinearLayout.LayoutParams(0, wrap(), 1f));

        TextView select = smallPrimaryButton(sourceUri == null ? "选择 PDF" : "更换");
        select.setOnClickListener(v -> sourcePicker.launch(new String[]{"application/pdf"}));
        LinearLayout.LayoutParams selectParams = new LinearLayout.LayoutParams(dp(96), dp(42));
        selectParams.setMargins(dp(12), 0, 0, 0);
        row.addView(select, selectParams);
        sourceCard.addView(row, matchWrap());
    }

    private void buildSettingsCard() {
        settingsCard.removeAllViews();
        if (MODE_PERMISSIONS.equals(mode)) {
            buildPermissionSettings();
        } else if (MODE_METADATA.equals(mode)) {
            buildMetadataSettings();
        } else {
            buildPasswordSettings();
        }
    }

    private void buildPasswordSettings() {
        addChoiceRow(
                "操作类型",
                passwordOperationIndex == 0 ? "设置密码" : "移除密码",
                new String[]{"设置密码", "移除密码"},
                passwordOperationIndex,
                which -> {
                    passwordOperationIndex = which;
                    buildSettingsCard();
                    updateTip();
                }
        );
        addInputRow("当前保护密码（没有则不需要填）", true, currentPasswordInput == null ? "" : currentPasswordInput.getText().toString(), input -> currentPasswordInput = input);
        if (passwordOperationIndex == 0) {
            addDivider();
            addInputRow("新的打开密码", true, openPasswordInput == null ? "" : openPasswordInput.getText().toString(), input -> openPasswordInput = input);
            addDivider();
            addInputRow("确认新的打开密码", true, confirmPasswordInput == null ? "" : confirmPasswordInput.getText().toString(), input -> confirmPasswordInput = input);
        }
    }

    private void buildPermissionSettings() {
        String passwordLabel = sourceRequiresOpenPassword
                ? "当前打开密码"
                : "新的打开密码（可选）";
        String passwordSummary = sourceRequiresOpenPassword
                ? "用于打开原文件；生成文件将继续使用此密码。"
                : "填写后，生成文件打开时将要求输入此密码；留空则只设置权限限制。";
        addInputRow(passwordLabel, passwordSummary, true,
                permissionCurrentPasswordInput == null ? "" : permissionCurrentPasswordInput.getText().toString(),
                input -> permissionCurrentPasswordInput = input);
        addDivider();
        addInputRow("权限管理密码", true,
                permissionOwnerPasswordInput == null ? "" : permissionOwnerPasswordInput.getText().toString(),
                input -> permissionOwnerPasswordInput = input);
        addDivider();
        addSwitchRow("允许打印", "关闭后，遵守 PDF 权限的阅读器将禁止打印。", allowPrint, checked -> allowPrint = checked);
        addDivider();
        addSwitchRow("允许复制", "关闭后，本阅读器及遵守权限的阅读器将禁止复制和文本导出。", allowCopy, checked -> allowCopy = checked);
        addDivider();
        addSwitchRow("允许修改内容", "控制页面编辑、插入删除页面及其它内容修改。", allowModify, checked -> allowModify = checked);
        addDivider();
        addSwitchRow("允许添加批注", "控制添加、修改和保存 PDF 批注。", allowAnnotate, checked -> allowAnnotate = checked);
    }

    private void buildMetadataSettings() {
        addInputRow("当前保护密码（没有则不需要填）", true,
                metadataCurrentPasswordInput == null ? "" : metadataCurrentPasswordInput.getText().toString(),
                input -> metadataCurrentPasswordInput = input);
        addDivider();
        addSwitchRow("清除文档信息", "包括标题、作者、主题、关键词、创建程序、创建/修改日期等。", clearInfoEnabled, checked -> clearInfoEnabled = checked);
        addDivider();
        addSwitchRow("清除 XMP 元数据", "删除嵌入在文档目录中的 XMP 元数据流。", clearXmpEnabled, checked -> clearXmpEnabled = checked);
    }

    private void updateTip() {
        if (MODE_PERMISSIONS.equals(mode)) {
            tipView.setText("所有权限开关默认开启；只关闭你确实要限制的项目。打开密码决定打开文件时是否需要输入密码，权限管理密码用于以后修改或解除限制。PDF 权限不是 DRM，部分阅读器可能不执行禁止打印或禁止复制。管理密码不能与打开密码相同。");
        } else if (MODE_METADATA.equals(mode)) {
            tipView.setText("清除元数据不会改变页面内容。若没有密码，当前密码不需要填写；若 PDF 已加密，需要填写可修改文档的当前密码。建议完成后抽查文档属性和打开方式。");
        } else if (passwordOperationIndex == 0) {
            tipView.setText("本页面只设置打开密码，不修改权限限制。若文档已有权限限制，请先在“权限限制”中恢复全部权限后再处理打开密码。");
        } else {
            tipView.setText("若没有打开密码，不需要执行移除。存在权限限制的 PDF 应先在“权限限制”中恢复全部权限；结果始终另存为新文件。");
        }
    }

    private void refreshActionButton() {
        boolean enabled = sourceUri != null;
        actionButton.setEnabled(enabled);
        actionButton.setAlpha(enabled ? 1f : 0.68f);
        if (MODE_PERMISSIONS.equals(mode)) {
            actionButton.setText("处理并选择保存位置");
        } else if (MODE_METADATA.equals(mode)) {
            actionButton.setText("处理并选择保存位置");
        } else if (passwordOperationIndex == 1) {
            actionButton.setText("处理并选择保存位置");
        } else {
            actionButton.setText("处理并选择保存位置");
        }
    }

    private void inspectSource(Uri uri) {
        clearPreparedOutput();
        sourceUri = uri;
        sourceName = queryDisplayName(uri);
        sourcePageCount = -1;
        sourceEncrypted = false;
        sourceRequiresOpenPassword = false;
        sourceNameView.setText(sourceName);
        sourceDetailView.setText("正在读取文件信息……");
        refreshActionButton();
        PdfProtectionService service = new PdfProtectionService(this);
        workerExecutor.execute(() -> {
            PdfProtectionService.InspectResult result = service.inspect(uri);
            runOnUiThread(() -> {
                sourceEncrypted = result.encrypted;
                sourceRequiresOpenPassword = result.requiresOpenPassword;
                sourcePageCount = result.pageCount;
                sourceDetailView.setText(result.summary);
                if (MODE_PERMISSIONS.equals(mode)) {
                    if (result.permissionsKnown) {
                        allowPrint = result.allowPrint;
                        allowCopy = result.allowCopy;
                        allowModify = result.allowModify;
                        allowAnnotate = result.allowAnnotate;
                    } else if (!result.encrypted) {
                        allowPrint = true;
                        allowCopy = true;
                        allowModify = true;
                        allowAnnotate = true;
                    }
                    buildSettingsCard();
                    updateTip();
                }
            });
        });
    }

    private void startSelectedOperation() {
        if (sourceUri == null) {
            Toast.makeText(this, "请先选择 PDF", Toast.LENGTH_SHORT).show();
            return;
        }
        if (MODE_PERMISSIONS.equals(mode)) {
            String currentOpen = valueOf(permissionCurrentPasswordInput);
            String management = valueOf(permissionOwnerPasswordInput);
            if (TextUtils.isEmpty(management)) {
                Toast.makeText(this, "请填写权限管理密码", Toast.LENGTH_SHORT).show();
                return;
            }
            boolean restricted = !allowPrint || !allowCopy || !allowModify || !allowAnnotate;
            if (restricted && !TextUtils.isEmpty(currentOpen)
                    && TextUtils.equals(currentOpen, management)) {
                Toast.makeText(this, "权限管理密码不能与打开密码相同，否则权限限制可能失效", Toast.LENGTH_LONG).show();
                return;
            }
            if (!sourceRequiresOpenPassword && !TextUtils.isEmpty(currentOpen)) {
                new AlertDialog.Builder(this)
                        .setTitle("将同时设置打开密码")
                        .setMessage("当前 PDF 打开时不需要密码。继续后，生成文件将要求输入你填写的打开密码；若只想设置权限限制，请清空该输入框。")
                        .setNegativeButton("返回检查", null)
                        .setPositiveButton("继续处理", (dialog, which) -> prepareSelectedOperation())
                        .show();
                return;
            }
        } else if (MODE_METADATA.equals(mode)) {
            if (!clearInfoEnabled && !clearXmpEnabled) {
                Toast.makeText(this, "至少保留一个清理选项", Toast.LENGTH_SHORT).show();
                return;
            }
        } else if (passwordOperationIndex == 1) {
            if (!sourceEncrypted) {
                Toast.makeText(this, "该 PDF 当前没有打开密码，无需移除", Toast.LENGTH_LONG).show();
                return;
            }
        } else if (passwordOperationIndex == 0) {
            String password = valueOf(openPasswordInput);
            String confirm = valueOf(confirmPasswordInput);
            if (TextUtils.isEmpty(password)) {
                Toast.makeText(this, "请填写新的打开密码", Toast.LENGTH_SHORT).show();
                return;
            }
            if (!TextUtils.equals(password, confirm)) {
                Toast.makeText(this, "两次输入的打开密码不一致", Toast.LENGTH_SHORT).show();
                return;
            }
        }
        prepareSelectedOperation();
    }

    private String suggestOutputName() {
        String base = sourceName;
        if (TextUtils.isEmpty(base)) base = "文档.pdf";
        String lower = base.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".pdf")) base = base.substring(0, base.length() - 4);
        if (MODE_PERMISSIONS.equals(mode)) return base + "-权限限制.pdf";
        if (MODE_METADATA.equals(mode)) return base + "-已清理元数据.pdf";
        if (passwordOperationIndex == 1) return base + "-已移除密码.pdf";
        return base + "-已加密.pdf";
    }

    private void prepareSelectedOperation() {
        DiagnosticContext.startOperation(this, "pdf_protection");
        clearPreparedOutput();
        startProgress(toolbar.getTitle() == null ? "处理中" : toolbar.getTitle().toString());
        cancelled.set(false);
        actionButton.setEnabled(false);
        actionButton.setAlpha(0.68f);
        workerExecutor.execute(() -> {
            File temp = null;
            boolean success = false;
            try {
                temp = File.createTempFile("pdf_protection_", ".pdf", getCacheDir());
                PdfProtectionService service = new PdfProtectionService(this);
                if (MODE_PERMISSIONS.equals(mode)) {
                    PdfProtectionService.PermissionOptions options = new PdfProtectionService.PermissionOptions();
                    options.allowPrint = allowPrint;
                    options.allowCopy = allowCopy;
                    options.allowModify = allowModify;
                    options.allowAnnotate = allowAnnotate;
                    service.applyPermissions(
                            sourceUri,
                            valueOf(permissionCurrentPasswordInput),
                            valueOf(permissionOwnerPasswordInput),
                            options,
                            temp,
                            cancelled,
                            this::postProgress
                    );
                } else if (MODE_METADATA.equals(mode)) {
                    service.clearMetadata(
                            sourceUri,
                            valueOf(metadataCurrentPasswordInput),
                            clearInfoEnabled,
                            clearXmpEnabled,
                            temp,
                            cancelled,
                            this::postProgress
                    );
                } else {
                    service.setOrRemovePassword(
                            sourceUri,
                            valueOf(currentPasswordInput),
                            passwordOperationIndex == 1,
                            valueOf(openPasswordInput),
                            temp,
                            cancelled,
                            this::postProgress
                    );
                }
                if (cancelled.get()) throw new IllegalStateException("处理已取消");
                preparedOutput = temp;
                success = true;
                runOnUiThread(() -> {
                    dismissProgress();
                    refreshActionButton();
                    outputPicker.launch(suggestOutputName());
                });
            } catch (Exception error) {
                if (isCancelledError(error)) DiagnosticContext.cancelOperation(this, "pdf_protection");
                else DiagnosticContext.finishOperation(this, "pdf_protection", false);
                runOnUiThread(() -> {
                    dismissProgress();
                    refreshActionButton();
                    if (isCancelledError(error)) {
                        Toast.makeText(this, "处理已取消", Toast.LENGTH_SHORT).show();
                    } else {
                        showError("处理失败", error);
                    }
                });
            } finally {
                if (!success && temp != null && temp.exists()) temp.delete();
            }
        });
    }

    private void commitPreparedOutput(Uri outputUri) {
        File temp = preparedOutput;
        preparedOutput = null;
        if (temp == null || !temp.isFile()) {
            showError("保存失败", new IllegalStateException("临时处理结果不存在，请重新执行"));
            return;
        }
        startProgress("正在保存");
        cancelled.set(false);
        workerExecutor.execute(() -> {
            try {
                postProgress("正在写入保存位置");
                copyTempToUri(temp, outputUri);
                String outputName = queryDisplayName(outputUri);
                addCreatedPdfToHome(outputUri, outputName);
                DiagnosticContext.setDocumentProfile(this, outputUri, "saf", sourcePageCount,
                        MODE_PERMISSIONS.equals(mode) || sourceEncrypted, MODE_PERMISSIONS.equals(mode));
                DiagnosticContext.finishOperation(this, "pdf_protection", true);
                runOnUiThread(() -> {
                    dismissProgress();
                    refreshActionButton();
                    showSuccess(outputUri, outputName);
                });
            } catch (Exception error) {
                deleteOutput(outputUri);
                if (isCancelledError(error)) DiagnosticContext.cancelOperation(this, "pdf_protection");
                else DiagnosticContext.finishOperation(this, "pdf_protection", false);
                runOnUiThread(() -> {
                    dismissProgress();
                    refreshActionButton();
                    if (isCancelledError(error)) {
                        Toast.makeText(this, "保存已取消", Toast.LENGTH_SHORT).show();
                    } else {
                        showError("保存失败", error);
                    }
                });
            } finally {
                if (temp.exists()) temp.delete();
            }
        });
    }

    private void clearPreparedOutput() {
        File file = preparedOutput;
        preparedOutput = null;
        if (file != null && file.exists()) file.delete();
    }

    private void showSuccess(Uri uri, String name) {
        String title;
        String message;
        if (MODE_PERMISSIONS.equals(mode)) {
            title = "权限限制已应用";
            message = "新的受限制 PDF 已生成，并已加入首页列表。建议用其他阅读器抽查打印、复制和批注权限是否符合预期。";
        } else if (MODE_METADATA.equals(mode)) {
            title = "元数据已清理";
            message = "新的 PDF 已生成，并已加入首页列表。建议打开文档属性检查标题、作者和 XMP 是否已清除。";
        } else if (passwordOperationIndex == 1) {
            title = "密码已移除";
            message = "新的 PDF 副本已生成，并已加入首页列表。原文件仍保持不变。";
        } else {
            title = "密码已设置";
            message = "新的受保护 PDF 已生成，并已加入首页列表。请妥善保存打开密码。";
        }
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setNegativeButton("完成", null)
                .setPositiveButton("打开", (dialog, which) -> openPdf(uri, name))
                .show();
    }

    private void openPdf(Uri uri, String name) {
        Intent intent = new Intent(this, PdfViewerActivity.class);
        intent.putExtra("pdf_uri", uri.toString());
        intent.putExtra("pdf_path", MainActivity.cleanPath(uri.getPath()));
        intent.putExtra("pdf_name", name == null || name.trim().isEmpty() ? "处理结果.pdf" : name);
        startActivity(intent);
    }

    private void startProgress(String title) {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(24), dp(8), dp(24), dp(4));
        progressText = text("正在准备……", 14, TEXT_PRIMARY, false);
        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setIndeterminate(true);
        LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(match(), dp(8));
        barParams.setMargins(0, dp(16), 0, dp(4));
        content.addView(progressText, matchWrap());
        content.addView(progressBar, barParams);
        progressDialog = new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(content)
                .setNegativeButton("取消", (dialog, which) -> cancelled.set(true))
                .setCancelable(false)
                .create();
        progressDialog.show();
    }

    private void postProgress(String message) {
        runOnUiThread(() -> {
            if (progressDialog == null || !progressDialog.isShowing()) return;
            if (progressText != null) progressText.setText(message == null ? "正在处理……" : message);
        });
    }

    private void dismissProgress() {
        if (progressDialog != null) progressDialog.dismiss();
        progressDialog = null;
    }

    private void addChoiceRow(
            String title,
            String currentValue,
            String[] values,
            int selectedIndex,
            IndexConsumer consumer
    ) {
        LinearLayout row = horizontalRow();
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(13), dp(12), dp(13));
        row.setMinimumHeight(dp(58));
        row.setClickable(true);
        row.setFocusable(true);

        TextView titleView = text(title, 15, TEXT_PRIMARY, false);
        TextView valueView = text(currentValue, 14, BLUE, false);
        valueView.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        TextView arrow = text("›", 23, 0xFF8A8F95, false);
        arrow.setGravity(Gravity.CENTER);
        row.addView(titleView, new LinearLayout.LayoutParams(0, wrap(), 1f));
        row.addView(valueView, new LinearLayout.LayoutParams(wrap(), wrap()));
        row.addView(arrow, new LinearLayout.LayoutParams(dp(28), dp(40)));
        final int[] current = {Math.max(0, Math.min(values.length - 1, selectedIndex))};
        row.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle(title)
                .setSingleChoiceItems(values, current[0], (dialog, which) -> {
                    current[0] = which;
                    consumer.accept(which);
                    valueView.setText(values[which]);
                    dialog.dismiss();
                })
                .setNegativeButton("取消", null)
                .show());
        settingsCard.addView(row, matchWrap());
    }

    private void addInputRow(String label, boolean password, String initialValue, InputBinder binder) {
        addInputRow(label, null, password, initialValue, binder);
    }

    private void addInputRow(
            String label,
            String summary,
            boolean password,
            String initialValue,
            InputBinder binder
    ) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(16), dp(14), dp(16), dp(14));
        TextView title = text(label, 14, TEXT_PRIMARY, false);
        row.addView(title, matchWrap());
        if (summary != null && !summary.trim().isEmpty()) {
            TextView summaryView = text(summary, 12, TEXT_SECONDARY, false);
            summaryView.setLineSpacing(0f, 1.14f);
            LinearLayout.LayoutParams summaryParams = new LinearLayout.LayoutParams(match(), wrap());
            summaryParams.topMargin = dp(4);
            row.addView(summaryView, summaryParams);
        }
        EditText input = new EditText(this);
        input.setText(initialValue == null ? "" : initialValue);
        input.setHint(password ? "留空表示无" : "请输入");
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        input.setTextColor(TEXT_PRIMARY);
        input.setSingleLine(true);
        input.setBackgroundResource(R.drawable.bg_creation_input);
        input.setPadding(dp(12), dp(10), dp(12), dp(10));
        if (password) {
            input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        }
        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(match(), wrap());
        inputParams.topMargin = dp(8);
        row.addView(input, inputParams);
        settingsCard.addView(row, matchWrap());
        binder.bind(input);
    }

    private void addSwitchRow(String title, String summary, boolean checked, BooleanConsumer consumer) {
        LinearLayout row = horizontalRow();
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(13), dp(16), dp(13));

        LinearLayout textColumn = new LinearLayout(this);
        textColumn.setOrientation(LinearLayout.VERTICAL);
        TextView titleView = text(title, 15, TEXT_PRIMARY, false);
        TextView summaryView = text(summary, 12, TEXT_SECONDARY, false);
        summaryView.setLineSpacing(0f, 1.14f);
        summaryView.setPadding(0, dp(5), dp(12), 0);
        textColumn.addView(titleView);
        textColumn.addView(summaryView);

        SwitchCompat switchCompat = new SwitchCompat(this);
        switchCompat.setChecked(checked);
        switchCompat.setThumbTintList(getColorStateList(R.color.switch_thumb_tint));
        switchCompat.setTrackTintList(getColorStateList(R.color.switch_track_tint));
        switchCompat.setOnCheckedChangeListener((buttonView, isChecked) -> consumer.accept(isChecked));

        row.addView(textColumn, new LinearLayout.LayoutParams(0, wrap(), 1f));
        row.addView(switchCompat, new LinearLayout.LayoutParams(wrap(), wrap()));
        settingsCard.addView(row, matchWrap());
    }

    private void addDivider() {
        settingsCard.addView(divider(), new LinearLayout.LayoutParams(match(), dp(1)));
    }

    private TextView smallPrimaryButton(String label) {
        TextView view = text(label, 14, 0xFFFFFFFF, true);
        view.setGravity(Gravity.CENTER);
        view.setBackgroundResource(R.drawable.bg_creation_primary);
        view.setClickable(true);
        view.setFocusable(true);
        return view;
    }

    private LinearLayout horizontalRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        return row;
    }

    private View divider() {
        View view = new View(this);
        view.setBackgroundColor(0xFFE1E5EA);
        return view;
    }

    private TextView text(String value, int sp, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setTextColor(color);
        if (bold) view.setTypeface(view.getTypeface(), android.graphics.Typeface.BOLD);
        return view;
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                value,
                getResources().getDisplayMetrics()
        ));
    }

    private int match() {
        return ViewGroup.LayoutParams.MATCH_PARENT;
    }

    private int wrap() {
        return ViewGroup.LayoutParams.WRAP_CONTENT;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(match(), wrap());
    }

    private void copyTempToUri(File temp, Uri outputUri) throws Exception {
        OutputStream rawOutput = getContentResolver().openOutputStream(outputUri, "w");
        if (rawOutput == null) throw new IllegalStateException("无法写入所选保存位置");
        try (BufferedInputStream input = new BufferedInputStream(
                new FileInputStream(temp), 1024 * 1024);
             BufferedOutputStream output = new BufferedOutputStream(rawOutput, 1024 * 1024)) {
            byte[] buffer = new byte[1024 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) output.write(buffer, 0, read);
            }
            output.flush();
        }
    }

    private void addCreatedPdfToHome(Uri uri, String outputName) {
        DocumentFile file = DocumentFile.fromSingleUri(this, uri);
        String name = outputName;
        if (file != null && file.getName() != null) name = file.getName();
        if (name == null || name.trim().isEmpty()) name = "处理结果.pdf";
        long modified = file == null ? System.currentTimeMillis() : file.lastModified();
        String time = new SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault())
                .format(new Date(modified > 0 ? modified : System.currentTimeMillis()));
        String path = MainActivity.cleanPath(uri.getPath());
        new PdfDbHelper(this).insertOrUpdateHomeItem(new PdfItem(uri, name, path, time, false));
    }

    private void deleteOutput(Uri uri) {
        try {
            DocumentFile file = DocumentFile.fromSingleUri(this, uri);
            if (file != null) file.delete();
        } catch (Throwable ignore) {
        }
    }

    private void showError(String title, Throwable error) {
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(readableError(error))
                .setPositiveButton("知道了", null)
                .show();
    }

    private String readableError(Throwable error) {
        Throwable current = error;
        while (current != null && current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        if (current == null) return "未知错误";
        String message = current.getMessage();
        return PdfSecurityContext.readableError(error);
    }

    private boolean isCancelledError(Throwable error) {
        if (cancelled.get()) return true;
        String message = readableError(error);
        return message != null && message.contains("取消");
    }

    private String queryDisplayName(Uri uri) {
        if (uri == null) return "未命名.pdf";
        try (Cursor cursor = getContentResolver().query(
                uri,
                new String[]{OpenableColumns.DISPLAY_NAME},
                null,
                null,
                null
        )) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    String value = cursor.getString(index);
                    if (value != null && !value.trim().isEmpty()) return value;
                }
            }
        } catch (Exception ignore) {
        }
        return "未命名.pdf";
    }

    private String valueOf(EditText editText) {
        return editText == null ? "" : editText.getText().toString().trim();
    }

    private interface IndexConsumer {
        void accept(int index);
    }

    private interface InputBinder {
        void bind(EditText input);
    }

    private interface BooleanConsumer {
        void accept(boolean value);
    }
}
