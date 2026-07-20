package com.nless.mypdf.feature.export;


import com.nless.mypdf.R;
import com.nless.mypdf.core.PdfSecurityContext;
import com.nless.mypdf.core.SearchPreferences;
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
import androidx.appcompat.widget.Toolbar;
import androidx.documentfile.provider.DocumentFile;

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

/** PDF 转图片、文本导出和添加不可见 OCR 文字层的统一界面。 */
public class ExportConvertActivity extends AppCompatActivity {

    public static final String EXTRA_MODE = "export_convert_mode";
    public static final String MODE_PDF_TO_IMAGES = "pdf_to_images";
    public static final String MODE_EXPORT_TEXT = "export_text";
    public static final String MODE_SEARCHABLE_PDF = "searchable_pdf";

    private static final int TEXT_PRIMARY = 0xFF202124;
    private static final int TEXT_SECONDARY = 0xFF6F7378;
    private static final int DIVIDER = 0xFFE1E5EA;
    private static final int BLUE = 0xFF03A9F4;

    private Toolbar toolbar;
    private TextView descriptionView;
    private LinearLayout sourceCard;
    private LinearLayout settingsCard;
    private TextView tipView;
    private TextView actionButton;

    private TextView sourceNameView;
    private TextView sourceDetailView;
    private EditText startPageInput;
    private EditText endPageInput;

    private String mode = MODE_PDF_TO_IMAGES;
    private Uri sourceUri;
    private Uri workingSourceUri;
    private File unlockedSourceTemp;
    private String sourcePassword = "";
    private PdfSecurityContext.Info sourceSecurityInfo;
    private String searchableOpenPassword = "";
    private String searchableManagementPassword = "";
    private boolean passwordDialogShowing;
    private String sourceName = "";
    private int sourcePageCount;
    private int customStartPage = 1;
    private int customEndPage = 1;

    private int rangeModeIndex;
    private int imageFormatIndex;
    private int imageResolutionIndex = 1;
    private int jpegQualityIndex = 1;
    private int textModeIndex;
    private int pageHeaderIndex;
    private int searchableScopeIndex;
    private int ocrPrecisionIndex = 1;

    private final ExecutorService workerExecutor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private AlertDialog progressDialog;
    private ProgressBar progressBar;
    private TextView progressText;

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

    private final ActivityResultLauncher<Uri> outputFolderPicker = registerForActivityResult(
            new ActivityResultContracts.OpenDocumentTree(),
            uri -> {
                if (uri == null) return;
                try {
                    getContentResolver().takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    );
                } catch (Exception ignore) {
                }
                exportImages(uri);
            }
    );

    private final ActivityResultLauncher<String> textOutputPicker = registerForActivityResult(
            new ActivityResultContracts.CreateDocument("text/plain"),
            uri -> {
                if (uri == null) return;
                try {
                    getContentResolver().takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    );
                } catch (Exception ignore) {
                }
                exportText(uri);
            }
    );

    private final ActivityResultLauncher<String> pdfOutputPicker = registerForActivityResult(
            new ActivityResultContracts.CreateDocument("application/pdf"),
            uri -> {
                if (uri == null) return;
                try {
                    getContentResolver().takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    );
                } catch (Exception ignore) {
                }
                createSearchablePdf(uri);
            }
    );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_export_convert);

        toolbar = findViewById(R.id.export_convert_toolbar);
        toolbar.setNavigationIcon(android.R.drawable.ic_menu_revert);
        toolbar.setNavigationOnClickListener(v -> finish());
        descriptionView = findViewById(R.id.export_convert_description);
        sourceCard = findViewById(R.id.export_convert_source_card);
        settingsCard = findViewById(R.id.export_convert_settings_card);
        tipView = findViewById(R.id.export_convert_tip);
        actionButton = findViewById(R.id.export_convert_button);
        actionButton.setOnClickListener(v -> startSelectedOperation());

        String requestedMode = getIntent().getStringExtra(EXTRA_MODE);
        if (MODE_EXPORT_TEXT.equals(requestedMode) || MODE_SEARCHABLE_PDF.equals(requestedMode)) {
            mode = requestedMode;
        }
        ocrPrecisionIndex = precisionIndex(SearchPreferences.getOcrRenderWidth(this));
        buildUi();
    }

    @Override
    protected void onDestroy() {
        cancelled.set(true);
        workerExecutor.shutdownNow();
        deleteUnlockedSourceTemp();
        super.onDestroy();
    }

    private void buildUi() {
        if (MODE_EXPORT_TEXT.equals(mode)) {
            toolbar.setTitle("导出文本");
            descriptionView.setText("把 PDF 的文字导出为 UTF-8 文本文件。文本层可直接提取，扫描页可选择离线 OCR。");
        } else if (MODE_SEARCHABLE_PDF.equals(mode)) {
            toolbar.setTitle("添加可搜索文字层");
            descriptionView.setText("保留原 PDF 页面外观，在扫描页中加入不可见 OCR 文字层，生成新的可搜索、可复制 PDF。");
        } else {
            toolbar.setTitle("PDF 转图片");
            descriptionView.setText("将 PDF 的全部页面或连续页面范围导出为 PNG/JPEG 图片，每个页面生成一张图片。");
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
        sourceNameView.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        sourceDetailView = text(sourceUri == null
                        ? "点击右侧按钮选择本地 PDF 文件"
                        : sourcePageCount > 0
                                ? sourcePageCount + " 页"
                                    + (sourceSecurityInfo != null && sourceSecurityInfo.hasRestrictions()
                                        ? " · 有权限限制" : "")
                                : "正在读取文件信息……",
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
        rememberPageRangeInputs();
        settingsCard.removeAllViews();
        String[] ranges = {"全部页面", "连续页码范围"};
        addChoiceRow("页面范围", ranges[clamp(rangeModeIndex, ranges.length)], ranges,
                rangeModeIndex, index -> {
                    rangeModeIndex = index;
                    buildSettingsCard();
                });
        if (rangeModeIndex == 1) addPageRangeRow();

        if (MODE_EXPORT_TEXT.equals(mode)) {
            String[] modes = {"自动（文本优先）", "仅文本层", "仅 OCR"};
            addChoiceRow("提取方式", modes[clamp(textModeIndex, modes.length)], modes,
                    textModeIndex, index -> {
                        textModeIndex = index;
                        buildSettingsCard();
                        updateTip();
                    });
            String[] headers = {"保留页码分隔", "不保留页码分隔"};
            addChoiceRow("页面分隔", headers[clamp(pageHeaderIndex, headers.length)], headers,
                    pageHeaderIndex, index -> pageHeaderIndex = index);
            if (textModeIndex != 1) addOcrPrecisionRow();
        } else if (MODE_SEARCHABLE_PDF.equals(mode)) {
            String[] scopes = {"仅无文本层页面（推荐）", "全部所选页面"};
            addChoiceRow("OCR 处理范围", scopes[clamp(searchableScopeIndex, scopes.length)], scopes,
                    searchableScopeIndex, index -> {
                        searchableScopeIndex = index;
                        updateTip();
                    });
            addOcrPrecisionRow();
            addFixedInfoRow("文字层显示", "不可见，保留原页面外观");
        } else {
            String[] formats = {"PNG", "JPEG"};
            addChoiceRow("输出格式", formats[clamp(imageFormatIndex, formats.length)], formats,
                    imageFormatIndex, index -> {
                        imageFormatIndex = index;
                        buildSettingsCard();
                    });
            String[] resolutions = {"快速（1280 px）", "平衡（1920 px）", "高清（2560 px）"};
            addChoiceRow("图像清晰度",
                    resolutions[clamp(imageResolutionIndex, resolutions.length)],
                    resolutions,
                    imageResolutionIndex,
                    index -> imageResolutionIndex = index);
            if (imageFormatIndex == 1) {
                String[] qualities = {"80%", "90%", "95%"};
                addChoiceRow("JPEG 质量", qualities[clamp(jpegQualityIndex, qualities.length)],
                        qualities, jpegQualityIndex, index -> jpegQualityIndex = index);
            }
        }
    }

    private void addOcrPrecisionRow() {
        String[] values = {"快速", "平衡", "高精度"};
        addChoiceRow("OCR 识别精度", values[clamp(ocrPrecisionIndex, values.length)], values,
                ocrPrecisionIndex, index -> {
                    ocrPrecisionIndex = index;
                    SearchPreferences.setOcrRenderWidth(this, ocrWidth(index));
                    updateTip();
                });
    }

    private void addPageRangeRow() {
        addDivider();
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(dp(16), dp(12), dp(16), dp(14));
        TextView label = text("连续页码范围", 15, TEXT_PRIMARY, false);
        container.addView(label, matchWrap());

        LinearLayout row = horizontalRow();
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(10), 0, 0);
        int maxPage = Math.max(1, sourcePageCount);
        customStartPage = Math.max(1, Math.min(maxPage, customStartPage));
        customEndPage = Math.max(customStartPage, Math.min(maxPage, customEndPage));
        startPageInput = pageNumberInput(customStartPage);
        endPageInput = pageNumberInput(customEndPage);
        TextView to = text("至", 14, TEXT_SECONDARY, false);
        to.setGravity(Gravity.CENTER);
        row.addView(startPageInput, new LinearLayout.LayoutParams(0, dp(46), 1f));
        LinearLayout.LayoutParams toParams = new LinearLayout.LayoutParams(dp(44), dp(46));
        row.addView(to, toParams);
        row.addView(endPageInput, new LinearLayout.LayoutParams(0, dp(46), 1f));
        container.addView(row, matchWrap());

        TextView helper = text(sourcePageCount > 0
                        ? "请输入 1 至 " + sourcePageCount + " 之间的连续页码"
                        : "选择 PDF 后可设置页码范围",
                12, TEXT_SECONDARY, false);
        helper.setPadding(0, dp(8), 0, 0);
        container.addView(helper, matchWrap());
        settingsCard.addView(container, matchWrap());
    }

    private void updateTip() {
        if (MODE_EXPORT_TEXT.equals(mode)) {
            if (textModeIndex == 1) {
                tipView.setText("仅文本层模式速度最快，但扫描 PDF 或没有可用文本层的页面将无法导出文字。输出为 UTF-8 TXT 文件。");
            } else if (textModeIndex == 2) {
                tipView.setText("仅 OCR 会逐页渲染并识别，即使页面已有文本层也会重新 OCR。页数越多、精度越高，耗时和内存占用越大；识别结果可能存在错字、漏字和阅读顺序偏差。");
            } else {
                tipView.setText("自动模式优先读取 PDF 文本层，只对没有有效文字的页面执行 OCR。扫描件页数较多时可能耗时较长，识别结果仍需人工检查。");
            }
        } else if (MODE_SEARCHABLE_PDF.equals(mode)) {
            tipView.setText("OCR 文字会以不可见方式写入，不会覆盖或改变扫描页面的可见内容。识别错误只会影响搜索、选择和复制结果。旋转页面首版会跳过文字层写入，并在完成结果中提示。始终另存为新 PDF。");
        } else {
            tipView.setText("PNG 适合文字、线稿和无损保存；JPEG 文件通常更小，适合照片类页面。每个 PDF 页面只导出一张图片；超长页面会在保持完整内容的前提下自动降低输出宽度，避免切段时把文字从中间截断。");
        }
    }

    private void inspectSource(Uri uri) {
        inspectSourceWithPassword(uri, "", false);
    }

    private void inspectSourceWithPassword(Uri uri, String password, boolean retry) {
        deleteUnlockedSourceTemp();
        sourceUri = uri;
        workingSourceUri = null;
        sourcePassword = password == null ? "" : password;
        sourceSecurityInfo = null;
        searchableOpenPassword = "";
        searchableManagementPassword = "";
        sourceName = queryDisplayName(uri);
        sourcePageCount = 0;
        customStartPage = 1;
        customEndPage = 1;
        buildSourceCard();
        refreshActionButton();
        workerExecutor.execute(() -> {
            try {
                PdfSecurityContext.Info info = PdfSecurityContext.inspect(
                        this,
                        uri,
                        null,
                        sourcePassword
                );
                File unlocked = null;
                Uri processUri = uri;
                if (info.encrypted) {
                    unlocked = PdfSecurityContext.createDecryptedTemp(
                            this,
                            uri,
                            null,
                            sourcePassword,
                            "export-unlocked-"
                    );
                    processUri = Uri.fromFile(unlocked);
                }
                int count = PdfExportService.readPageCount(this, processUri);
                File finalUnlocked = unlocked;
                Uri finalProcessUri = processUri;
                runOnUiThread(() -> {
                    if (!uri.equals(sourceUri)) {
                        if (finalUnlocked != null) finalUnlocked.delete();
                        return;
                    }
                    unlockedSourceTemp = finalUnlocked;
                    workingSourceUri = finalProcessUri;
                    sourceSecurityInfo = info;
                    sourcePageCount = count;
                    customStartPage = 1;
                    customEndPage = Math.max(1, count);
                    buildSourceCard();
                    if (rangeModeIndex == 1) buildSettingsCard();
                    refreshActionButton();
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (!uri.equals(sourceUri)) return;
                    if (PdfSecurityContext.isPasswordError(error)) {
                        showSourcePasswordDialog(uri, retry);
                    } else {
                        sourceUri = null;
                        workingSourceUri = null;
                        sourcePageCount = 0;
                        buildSourceCard();
                        refreshActionButton();
                        showError("无法读取 PDF", error);
                    }
                });
            }
        });
    }

    private void showSourcePasswordDialog(Uri uri, boolean wrongPassword) {
        if (passwordDialogShowing) return;
        passwordDialogShowing = true;
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setHint("请输入 PDF 密码");
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(wrongPassword ? "密码不正确" : "PDF 已加密")
                .setMessage("请输入打开密码或所有者密码后继续。密码只保存在本次应用运行期间。")
                .setView(input)
                .setNegativeButton("取消", (d, which) -> {
                    passwordDialogShowing = false;
                    sourceUri = null;
                    workingSourceUri = null;
                    sourcePageCount = 0;
                    buildSourceCard();
                    refreshActionButton();
                })
                .setPositiveButton("确定", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    String password = input.getText().toString();
                    if (password.isEmpty()) {
                        input.setError("请输入密码");
                        return;
                    }
                    passwordDialogShowing = false;
                    dialog.dismiss();
                    inspectSourceWithPassword(uri, password, true);
                }));
        dialog.show();
    }

    private void deleteUnlockedSourceTemp() {
        if (unlockedSourceTemp != null && unlockedSourceTemp.exists()) {
            unlockedSourceTemp.delete();
        }
        unlockedSourceTemp = null;
    }

    private void startSelectedOperation() {
        if (sourceUri == null || sourcePageCount <= 0) {
            Toast.makeText(this, "请先选择可读取的 PDF", Toast.LENGTH_SHORT).show();
            return;
        }
        PdfExportService.PageRange range = readPageRange();
        if (range == null) return;
        if (sourceSecurityInfo != null) {
            if ((MODE_EXPORT_TEXT.equals(mode) || MODE_PDF_TO_IMAGES.equals(mode))
                    && !sourceSecurityInfo.canCopyOrExportText()) {
                Toast.makeText(this, "文档权限禁止复制或提取内容", Toast.LENGTH_LONG).show();
                return;
            }
            if (MODE_SEARCHABLE_PDF.equals(mode)
                    && !sourceSecurityInfo.canCreateSearchableLayer()) {
                Toast.makeText(this,
                        "文档权限禁止修改页面内容，无法添加可搜索文字层",
                        Toast.LENGTH_LONG).show();
                return;
            }
        }
        Runnable launch = () -> {
            if (MODE_EXPORT_TEXT.equals(mode)) {
                textOutputPicker.launch(baseName(sourceName) + ".txt");
            } else if (MODE_SEARCHABLE_PDF.equals(mode)) {
                pdfOutputPicker.launch(baseName(sourceName) + "-可搜索版.pdf");
            } else {
                outputFolderPicker.launch(null);
            }
        };
        Runnable launchWithOcrWarning = () -> {
            if (operationMayUseOcr() && range.count() >= 10) {
                new AlertDialog.Builder(this)
                        .setTitle("OCR 处理提示")
                        .setMessage("所选范围共 " + range.count()
                                + " 页。OCR 需要逐页渲染和识别，可能耗时较长并占用较多内存；"
                                + "扫描清晰度、倾斜、字体和复杂版式也会影响结果。建议处理期间保持应用在前台，并在完成后抽查文字。")
                        .setNegativeButton("取消", null)
                        .setPositiveButton("继续", (dialog, which) -> launch.run())
                        .show();
            } else {
                launch.run();
            }
        };
        if (MODE_SEARCHABLE_PDF.equals(mode)
                && sourceSecurityInfo != null
                && sourceSecurityInfo.encrypted
                && sourceSecurityInfo.hasRestrictions()) {
            showSearchableProtectionDialog(launchWithOcrWarning);
        } else {
            searchableOpenPassword = sourcePassword == null ? "" : sourcePassword;
            searchableManagementPassword = "";
            launchWithOcrWarning.run();
        }
    }

    private void exportImages(Uri directoryUri) {
        PdfExportService.PageRange range = readPageRange();
        if (range == null) return;
        DiagnosticContext.startOperation(this, "export_images");
        DocumentFile directory = DocumentFile.fromTreeUri(this, directoryUri);
        PdfExportService.ImageOptions options = new PdfExportService.ImageOptions();
        options.format = imageFormatIndex == 1
                ? PdfExportService.ImageFormat.JPEG
                : PdfExportService.ImageFormat.PNG;
        options.targetWidth = new int[]{1280, 1920, 2560}[clamp(imageResolutionIndex, 3)];
        options.jpegQuality = new int[]{80, 90, 95}[clamp(jpegQualityIndex, 3)];
        options.filePrefix = baseName(sourceName);

        startProgress("正在导出图片");
        workerExecutor.execute(() -> {
            try {
                PdfExportService.ResultSummary summary = PdfExportService.exportPagesToImages(
                        this,
                        workingSourceUri == null ? sourceUri : workingSourceUri,
                        directory,
                        range,
                        options,
                        cancelled,
                        this::postProgress
                );
                DiagnosticContext.finishOperation(this, "export_images", true);
                runOnUiThread(() -> {
                    dismissProgress();
                    new AlertDialog.Builder(this)
                            .setTitle("图片导出完成")
                            .setMessage("已导出 " + summary.outputFiles + " 张图片。")
                            .setPositiveButton("完成", null)
                            .show();
                });
            } catch (Exception error) {
                if (isCancelledError(error)) DiagnosticContext.cancelOperation(this, "export_images");
                else DiagnosticContext.finishOperation(this, "export_images", false);
                runOnUiThread(() -> {
                    dismissProgress();
                    if (!isCancelledError(error)) showError("导出失败", error);
                    else Toast.makeText(this, "导出已取消", Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void exportText(Uri outputUri) {
        PdfExportService.PageRange range = readPageRange();
        if (range == null) return;
        DiagnosticContext.startOperation(this, "export_text");
        PdfExportService.TextOptions options = new PdfExportService.TextOptions();
        options.mode = new PdfExportService.TextMode[]{
                PdfExportService.TextMode.AUTO,
                PdfExportService.TextMode.TEXT_ONLY,
                PdfExportService.TextMode.OCR_ONLY
        }[clamp(textModeIndex, 3)];
        options.includePageHeaders = pageHeaderIndex == 0;
        options.ocrRenderWidth = ocrWidth(ocrPrecisionIndex);

        startProgress("正在导出文本");
        workerExecutor.execute(() -> {
            File temp = null;
            try {
                temp = File.createTempFile("mypdf-export-text-", ".txt", getCacheDir());
                PdfExportService.ResultSummary summary = PdfExportService.exportText(
                        this,
                        workingSourceUri == null ? sourceUri : workingSourceUri,
                        temp,
                        range,
                        options,
                        cancelled,
                        this::postProgress
                );
                postProgress(1, 1, "正在写入保存位置");
                copyTempToUri(temp, outputUri);
                PdfExportService.ResultSummary finalSummary = summary;
                DiagnosticContext.setSearchProfile(this, options.mode != PdfExportService.TextMode.TEXT_ONLY,
                        options.mode == PdfExportService.TextMode.OCR_ONLY ? "ocr_only"
                                : options.mode == PdfExportService.TextMode.TEXT_ONLY ? "text_only" : "smart",
                        options.ocrRenderWidth);
                DiagnosticContext.finishOperation(this, "export_text", true);
                runOnUiThread(() -> {
                    dismissProgress();
                    String message = "文本文件已保存。";
                    if (finalSummary.ocrPages > 0) {
                        message += "\n\n其中 " + finalSummary.ocrPages
                                + " 页使用 OCR，建议检查错字、漏字和段落顺序。";
                    }
                    if (finalSummary.failedPages > 0) {
                        message += "\n\n有 " + finalSummary.failedPages + " 页未能成功识别。";
                    }
                    new AlertDialog.Builder(this)
                            .setTitle("文本导出完成")
                            .setMessage(message)
                            .setPositiveButton("完成", null)
                            .show();
                });
            } catch (Exception error) {
                deleteOutput(outputUri);
                if (isCancelledError(error)) DiagnosticContext.cancelOperation(this, "export_text");
                else DiagnosticContext.finishOperation(this, "export_text", false);
                runOnUiThread(() -> {
                    dismissProgress();
                    if (!isCancelledError(error)) showError("导出失败", error);
                    else Toast.makeText(this, "导出已取消", Toast.LENGTH_SHORT).show();
                });
            } finally {
                if (temp != null && temp.exists()) temp.delete();
            }
        });
    }

    private void showSearchableProtectionDialog(Runnable onValidated) {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(4), dp(20), 0);

        TextView openLabel = text("当前打开密码（没有则留空）", 14, TEXT_PRIMARY, false);
        EditText openInput = new EditText(this);
        openInput.setSingleLine(true);
        openInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        openInput.setHint("用于保持生成文件原有的打开方式");
        openInput.setText(sourcePassword == null ? "" : sourcePassword);
        openInput.setBackgroundResource(R.drawable.bg_creation_input);
        openInput.setPadding(dp(12), dp(10), dp(12), dp(10));

        TextView ownerLabel = text("权限管理密码", 14, TEXT_PRIMARY, false);
        ownerLabel.setPadding(0, dp(14), 0, 0);
        EditText ownerInput = new EditText(this);
        ownerInput.setSingleLine(true);
        ownerInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        ownerInput.setHint("用于验证并恢复现有权限限制");
        ownerInput.setBackgroundResource(R.drawable.bg_creation_input);
        ownerInput.setPadding(dp(12), dp(10), dp(12), dp(10));

        content.addView(openLabel, matchWrap());
        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(match(), wrap());
        inputParams.topMargin = dp(7);
        content.addView(openInput, inputParams);
        content.addView(ownerLabel, matchWrap());
        LinearLayout.LayoutParams ownerParams = new LinearLayout.LayoutParams(match(), wrap());
        ownerParams.topMargin = dp(7);
        content.addView(ownerInput, ownerParams);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("保留现有权限保护")
                .setMessage("添加文字层会修改 PDF 页面内容。当前权限允许修改，因此可以继续；为避免生成结果丢失原有密码和权限，请验证权限管理密码。")
                .setView(content)
                .setNegativeButton("取消", null)
                .setPositiveButton("验证并继续", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    String openPassword = openInput.getText().toString();
                    String managementPassword = ownerInput.getText().toString();
                    if (managementPassword.isEmpty()) {
                        ownerInput.setError("请输入权限管理密码");
                        return;
                    }
                    if (!openPassword.isEmpty() && TextUtils.equals(openPassword, managementPassword)) {
                        ownerInput.setError("权限管理密码不能与打开密码相同");
                        return;
                    }
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
                    Toast.makeText(this, "正在验证权限管理密码…", Toast.LENGTH_SHORT).show();
                    workerExecutor.execute(() -> {
                        try {
                            PdfSecurityContext.Info managementInfo = PdfSecurityContext.inspect(
                                    this, sourceUri, null, managementPassword);
                            if (!managementInfo.ownerPermission) {
                                throw new IllegalStateException("权限管理密码不正确");
                            }
                            if (!openPassword.isEmpty()) {
                                PdfSecurityContext.inspect(this, sourceUri, null, openPassword);
                            }
                            runOnUiThread(() -> {
                                searchableOpenPassword = openPassword;
                                searchableManagementPassword = managementPassword;
                                dialog.dismiss();
                                if (onValidated != null) onValidated.run();
                            });
                        } catch (Throwable error) {
                            runOnUiThread(() -> {
                                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                                ownerInput.setError(PdfSecurityContext.readableError(error));
                            });
                        }
                    });
                }));
        dialog.show();
    }

    private void createSearchablePdf(Uri outputUri) {
        PdfExportService.PageRange range = readPageRange();
        if (range == null) return;
        DiagnosticContext.startOperation(this, "create_searchable_pdf");
        PdfExportService.SearchableOptions options = new PdfExportService.SearchableOptions();
        options.scope = searchableScopeIndex == 1
                ? PdfExportService.SearchableScope.ALL_SELECTED
                : PdfExportService.SearchableScope.MISSING_TEXT_ONLY;
        options.ocrRenderWidth = ocrWidth(ocrPrecisionIndex);

        startProgress("正在生成可搜索 PDF");
        workerExecutor.execute(() -> {
            File temp = null;
            try {
                temp = File.createTempFile("mypdf-searchable-", ".pdf", getCacheDir());
                PdfExportService.ResultSummary summary = PdfExportService.createSearchablePdf(
                        this,
                        workingSourceUri == null ? sourceUri : workingSourceUri,
                        temp,
                        range,
                        options,
                        cancelled,
                        this::postProgress
                );
                if (sourceSecurityInfo != null && sourceSecurityInfo.encrypted) {
                    postProgress(1, 1, "正在恢复原有密码和权限保护");
                    String userPassword = sourceSecurityInfo.hasRestrictions()
                            ? searchableOpenPassword
                            : sourcePassword;
                    String ownerPassword = sourceSecurityInfo.hasRestrictions()
                            ? searchableManagementPassword
                            : sourcePassword;
                    PdfSecurityContext.protectFileWithPolicy(
                            this,
                            temp,
                            userPassword,
                            ownerPassword,
                            sourceSecurityInfo.declaredPermissionBits
                    );
                }
                postProgress(1, 1, "正在写入保存位置");
                copyTempToUri(temp, outputUri);
                String outputName = queryDisplayName(outputUri);
                addCreatedPdfToHome(outputUri, outputName);
                PdfExportService.ResultSummary finalSummary = summary;
                DiagnosticContext.setDocumentProfile(this, outputUri, "saf", sourcePageCount,
                        sourceSecurityInfo != null && sourceSecurityInfo.encrypted,
                        sourceSecurityInfo != null && sourceSecurityInfo.hasRestrictions());
                DiagnosticContext.setSearchProfile(this, true, "ocr_only", options.ocrRenderWidth);
                DiagnosticContext.finishOperation(this, "create_searchable_pdf", true);
                runOnUiThread(() -> {
                    dismissProgress();
                    StringBuilder message = new StringBuilder();
                    if (finalSummary.ocrPages == 0) {
                        message.append("所选页面已经包含文本层，已生成完整 PDF 副本。");
                    } else {
                        message.append("已对 ").append(finalSummary.ocrPages)
                                .append(" 页执行 OCR，并写入不可见文字层。");
                    }
                    if (finalSummary.skippedPages > 0) {
                        message.append("\n\n已跳过 ").append(finalSummary.skippedPages)
                                .append(" 个已有文本层的页面。");
                    }
                    if (finalSummary.failedPages > 0) {
                        message.append("\n\n有 ").append(finalSummary.failedPages)
                                .append(" 页未成功写入（包括旋转页或识别失败页）。");
                    }
                    if (sourceSecurityInfo != null && sourceSecurityInfo.encrypted) {
                        message.append("\n\n已保留源文件的打开密码和权限限制。");
                    }
                    message.append("\n\nOCR 结果可能存在错误，建议用搜索和复制功能抽查。");
                    showSearchableSuccess(outputUri, outputName, message.toString());
                });
            } catch (Exception error) {
                deleteOutput(outputUri);
                if (isCancelledError(error)) DiagnosticContext.cancelOperation(this, "create_searchable_pdf");
                else DiagnosticContext.finishOperation(this, "create_searchable_pdf", false);
                runOnUiThread(() -> {
                    dismissProgress();
                    if (!isCancelledError(error)) showError("生成失败", error);
                    else Toast.makeText(this, "生成已取消", Toast.LENGTH_SHORT).show();
                });
            } finally {
                if (temp != null && temp.exists()) temp.delete();
            }
        });
    }

    private void showSearchableSuccess(Uri uri, String name, String message) {
        new AlertDialog.Builder(this)
                .setTitle("可搜索 PDF 已生成")
                .setMessage(message + "\n\n文件已加入首页列表。")
                .setNegativeButton("完成", null)
                .setPositiveButton("打开", (dialog, which) -> openPdf(uri, name))
                .show();
    }

    private void openPdf(Uri uri, String name) {
        Intent intent = new Intent(this, PdfViewerActivity.class);
        intent.putExtra("pdf_uri", uri.toString());
        intent.putExtra("pdf_path", MainActivity.cleanPath(uri.getPath()));
        intent.putExtra("pdf_name", name == null || name.trim().isEmpty()
                ? "可搜索文档.pdf"
                : name);
        startActivity(intent);
    }

    private PdfExportService.PageRange readPageRange() {
        if (sourcePageCount <= 0) return null;
        if (rangeModeIndex == 0) {
            return new PdfExportService.PageRange(0, sourcePageCount - 1);
        }
        int start = readPositiveInt(startPageInput, customStartPage);
        int end = readPositiveInt(endPageInput, customEndPage);
        if (start < 1 || end < 1 || start > sourcePageCount || end > sourcePageCount) {
            Toast.makeText(this, "页码必须在 1 至 " + sourcePageCount + " 之间",
                    Toast.LENGTH_SHORT).show();
            return null;
        }
        if (start > end) {
            Toast.makeText(this, "起始页不能大于结束页", Toast.LENGTH_SHORT).show();
            return null;
        }
        customStartPage = start;
        customEndPage = end;
        return new PdfExportService.PageRange(start - 1, end - 1);
    }

    private void rememberPageRangeInputs() {
        if (startPageInput != null) {
            customStartPage = readPositiveInt(startPageInput, customStartPage);
        }
        if (endPageInput != null) {
            customEndPage = readPositiveInt(endPageInput, customEndPage);
        }
        startPageInput = null;
        endPageInput = null;
    }

    private boolean operationMayUseOcr() {
        if (MODE_SEARCHABLE_PDF.equals(mode)) return true;
        return MODE_EXPORT_TEXT.equals(mode) && textModeIndex != 1;
    }

    private void startProgress(String title) {
        cancelled.set(false);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(24), dp(8), dp(24), dp(4));
        progressText = text("正在准备……", 14, TEXT_PRIMARY, false);
        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(1000);
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

    private void postProgress(int current, int total, String message) {
        runOnUiThread(() -> {
            if (progressDialog == null || !progressDialog.isShowing()) return;
            progressText.setText(message == null ? "正在处理……" : message);
            int progress = total <= 0 ? 0 : Math.round(current * 1000f / total);
            progressBar.setProgress(Math.max(0, Math.min(1000, progress)));
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
        if (settingsCard.getChildCount() > 0) addDivider();
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
        final int[] current = {clamp(selectedIndex, values.length)};
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

    private void addFixedInfoRow(String title, String value) {
        if (settingsCard.getChildCount() > 0) addDivider();
        LinearLayout row = horizontalRow();
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(15), dp(16), dp(15));
        TextView titleView = text(title, 15, TEXT_PRIMARY, false);
        TextView valueView = text(value, 13, TEXT_SECONDARY, false);
        valueView.setGravity(Gravity.END);
        row.addView(titleView, new LinearLayout.LayoutParams(0, wrap(), 1f));
        row.addView(valueView, new LinearLayout.LayoutParams(0, wrap(), 1.25f));
        settingsCard.addView(row, matchWrap());
    }

    private void addDivider() {
        settingsCard.addView(divider(), new LinearLayout.LayoutParams(match(), dp(1)));
    }

    private EditText pageNumberInput(int value) {
        EditText input = new EditText(this);
        input.setText(String.valueOf(Math.max(1, value)));
        input.setSelectAllOnFocus(true);
        input.setGravity(Gravity.CENTER);
        input.setTextColor(TEXT_PRIMARY);
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setBackgroundResource(R.drawable.bg_creation_input);
        return input;
    }

    private TextView smallPrimaryButton(String label) {
        TextView view = text(label, 14, 0xFFFFFFFF, true);
        view.setGravity(Gravity.CENTER);
        view.setBackgroundResource(R.drawable.bg_creation_primary);
        view.setClickable(true);
        view.setFocusable(true);
        return view;
    }

    private void refreshActionButton() {
        boolean enabled = sourceUri != null && sourcePageCount > 0;
        actionButton.setEnabled(enabled);
        actionButton.setAlpha(enabled ? 1f : 0.68f);
        if (MODE_EXPORT_TEXT.equals(mode)) {
            actionButton.setText("选择保存位置并导出");
        } else if (MODE_SEARCHABLE_PDF.equals(mode)) {
            actionButton.setText("选择保存位置并生成");
        } else {
            actionButton.setText("选择输出文件夹并导出");
        }
    }

    private void copyTempToUri(File temp, Uri outputUri) throws Exception {
        try (InputStream input = new FileInputStream(temp);
             OutputStream output = getContentResolver().openOutputStream(outputUri, "w")) {
            if (output == null) throw new IllegalStateException("无法写入所选保存位置");
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) output.write(buffer, 0, read);
            output.flush();
        }
    }

    private void addCreatedPdfToHome(Uri uri, String outputName) {
        DocumentFile file = DocumentFile.fromSingleUri(this, uri);
        String name = outputName;
        if (file != null && file.getName() != null) name = file.getName();
        if (name == null || name.trim().isEmpty()) name = "可搜索文档.pdf";
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
        return message == null || message.trim().isEmpty()
                ? current.getClass().getSimpleName()
                : message;
    }

    private boolean isCancelledError(Throwable error) {
        if (cancelled.get()) return true;
        String message = readableError(error);
        return message.contains("取消");
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

    private static String baseName(String name) {
        String value = name == null ? "文档" : name.trim();
        if (value.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
            value = value.substring(0, value.length() - 4);
        }
        value = value.replaceAll("[\\\\/:*?\"<>|]", "_");
        return value.isEmpty() ? "文档" : value;
    }

    private static int readPositiveInt(EditText input, int fallback) {
        if (input == null) return fallback;
        try {
            return Integer.parseInt(input.getText().toString().trim());
        } catch (Exception ignore) {
            return fallback;
        }
    }

    private int precisionIndex(int width) {
        if (width == SearchPreferences.OCR_WIDTH_FAST) return 0;
        if (width == SearchPreferences.OCR_WIDTH_HIGH) return 2;
        return 1;
    }

    private int ocrWidth(int index) {
        if (index == 0) return SearchPreferences.OCR_WIDTH_FAST;
        if (index == 2) return SearchPreferences.OCR_WIDTH_HIGH;
        return SearchPreferences.OCR_WIDTH_BALANCED;
    }

    private LinearLayout horizontalRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        return row;
    }

    private TextView text(String value, int sp, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setTextColor(color);
        view.setLineSpacing(0f, 1.16f);
        if (bold) view.setTypeface(view.getTypeface(), android.graphics.Typeface.BOLD);
        return view;
    }

    private View divider() {
        View view = new View(this);
        view.setBackgroundColor(DIVIDER);
        return view;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(match(), wrap());
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static int clamp(int value, int size) {
        if (size <= 0) return 0;
        return Math.max(0, Math.min(size - 1, value));
    }

    private static int match() {
        return ViewGroup.LayoutParams.MATCH_PARENT;
    }

    private static int wrap() {
        return ViewGroup.LayoutParams.WRAP_CONTENT;
    }

    private interface IndexConsumer {
        void accept(int index);
    }
}
