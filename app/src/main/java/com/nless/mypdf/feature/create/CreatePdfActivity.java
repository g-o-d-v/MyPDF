package com.nless.mypdf.feature.create;


import com.nless.mypdf.R;
import com.nless.mypdf.data.PdfDbHelper;
import com.nless.mypdf.data.PdfItem;
import com.nless.mypdf.diagnostics.DiagnosticContext;
import com.nless.mypdf.ui.MainActivity;
import com.nless.mypdf.ui.PdfViewerActivity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
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
import androidx.exifinterface.media.ExifInterface;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 图片、文本和空白页面创建 PDF 的统一界面。 */
public class CreatePdfActivity extends AppCompatActivity {

    public static final String EXTRA_MODE = "create_pdf_mode";
    public static final String MODE_IMAGES = "images";
    public static final String MODE_TEXT = "text";
    public static final String MODE_BLANK = "blank";

    private static final String STATE_IMAGES = "state_images";
    private static final String STATE_PAGE_SIZE = "state_page_size";
    private static final String STATE_ORIENTATION = "state_orientation";
    private static final String STATE_MARGIN = "state_margin";
    private static final String STATE_QUALITY = "state_quality";
    private static final String STATE_FONT_SIZE = "state_font_size";
    private static final String STATE_LINE_SPACING = "state_line_spacing";
    private static final String STATE_ALIGNMENT = "state_alignment";
    private static final String STATE_TEXT = "state_text";
    private static final String STATE_PAGE_COUNT = "state_page_count";

    private static final int TEXT_PRIMARY = 0xFF202124;
    private static final int TEXT_SECONDARY = 0xFF6F7378;
    private static final int DIVIDER = 0xFFE1E5EA;
    private static final int BLUE = 0xFF03A9F4;

    private static final String[] SUPPORTED_IMAGE_MIME_TYPES = {
            "image/jpeg",
            "image/jpg",
            "image/png",
            "image/x-png",
            "image/webp"
    };

    private Toolbar toolbar;
    private TextView descriptionView;
    private LinearLayout contentCard;
    private LinearLayout settingsCard;
    private TextView tipView;
    private TextView createButton;

    private String mode = MODE_IMAGES;
    private final ArrayList<Uri> imageUris = new ArrayList<>();
    private LinearLayout imageList;
    private TextView imageEmptyView;
    private TextView imageCountView;
    private EditText textInput;
    private TextView textCountView;
    private EditText pageCountInput;

    private int pageSizeIndex = 0;
    private int orientationIndex = 0;
    private int marginIndex = 0;
    private int qualityIndex = 1;
    private int fontSizeIndex = 2;
    private int lineSpacingIndex = 1;
    private int alignmentIndex = 0;
    private String restoredText = "";
    private int restoredPageCount = 1;

    private final ExecutorService workerExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService thumbnailExecutor = Executors.newFixedThreadPool(2);
    private AlertDialog progressDialog;
    private ProgressBar progressBar;
    private TextView progressText;

    private final ActivityResultLauncher<String[]> imagePickerLauncher = registerForActivityResult(
            new ActivityResultContracts.OpenMultipleDocuments(),
            uris -> {
                if (uris == null || uris.isEmpty()) return;
                Set<String> existing = new LinkedHashSet<>();
                for (Uri uri : imageUris) existing.add(uri.toString());
                int added = 0;
                int unsupported = 0;
                for (Uri uri : uris) {
                    if (uri == null || existing.contains(uri.toString())) continue;
                    if (imageUris.size() >= 200) break;
                    if (!PdfCreationService.isSupportedImage(getContentResolver(), uri)) {
                        unsupported++;
                        continue;
                    }
                    try {
                        getContentResolver().takePersistableUriPermission(
                                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                        );
                    } catch (Exception ignore) {
                    }
                    imageUris.add(uri);
                    existing.add(uri.toString());
                    added++;
                }
                rebuildImageList();
                if (unsupported > 0) {
                    String message = added > 0
                            ? "已添加 " + added + " 张图片，已跳过 " + unsupported
                                    + " 个不支持的文件"
                            : "未添加图片：仅支持 JPG/JPEG、PNG、WebP，GIF 已排除";
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                } else if (added == 0) {
                    Toast.makeText(this, "没有添加新的图片", Toast.LENGTH_SHORT).show();
                }
            }
    );

    private final ActivityResultLauncher<String> outputLauncher = registerForActivityResult(
            new ActivityResultContracts.CreateDocument("application/pdf"),
            uri -> {
                if (uri != null) {
                    try {
                        getContentResolver().takePersistableUriPermission(
                                uri,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        );
                    } catch (Exception ignore) {
                    }
                    createPdf(uri);
                }
            }
    );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_create_pdf);

        toolbar = findViewById(R.id.create_pdf_toolbar);
        toolbar.setNavigationIcon(android.R.drawable.ic_menu_revert);
        toolbar.setNavigationOnClickListener(v -> finish());

        descriptionView = findViewById(R.id.create_pdf_description);
        contentCard = findViewById(R.id.create_pdf_content_card);
        settingsCard = findViewById(R.id.create_pdf_settings_card);
        tipView = findViewById(R.id.create_pdf_tip);
        createButton = findViewById(R.id.create_pdf_button);
        createButton.setOnClickListener(v -> chooseOutputLocation());

        String requestedMode = getIntent().getStringExtra(EXTRA_MODE);
        if (MODE_TEXT.equals(requestedMode) || MODE_BLANK.equals(requestedMode)) mode = requestedMode;
        if (savedInstanceState == null && MODE_TEXT.equals(mode)) marginIndex = 1;

        restoreState(savedInstanceState);
        buildCurrentModeUi();
    }

    private void restoreState(Bundle state) {
        if (state == null) return;
        ArrayList<String> savedImages = state.getStringArrayList(STATE_IMAGES);
        if (savedImages != null) {
            for (String value : savedImages) imageUris.add(Uri.parse(value));
        }
        pageSizeIndex = state.getInt(STATE_PAGE_SIZE, pageSizeIndex);
        orientationIndex = state.getInt(STATE_ORIENTATION, orientationIndex);
        marginIndex = state.getInt(STATE_MARGIN, marginIndex);
        qualityIndex = state.getInt(STATE_QUALITY, qualityIndex);
        fontSizeIndex = state.getInt(STATE_FONT_SIZE, fontSizeIndex);
        lineSpacingIndex = state.getInt(STATE_LINE_SPACING, lineSpacingIndex);
        alignmentIndex = state.getInt(STATE_ALIGNMENT, alignmentIndex);
        restoredText = state.getString(STATE_TEXT, "");
        restoredPageCount = state.getInt(STATE_PAGE_COUNT, 1);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        ArrayList<String> savedImages = new ArrayList<>();
        for (Uri uri : imageUris) savedImages.add(uri.toString());
        outState.putStringArrayList(STATE_IMAGES, savedImages);
        outState.putInt(STATE_PAGE_SIZE, pageSizeIndex);
        outState.putInt(STATE_ORIENTATION, orientationIndex);
        outState.putInt(STATE_MARGIN, marginIndex);
        outState.putInt(STATE_QUALITY, qualityIndex);
        outState.putInt(STATE_FONT_SIZE, fontSizeIndex);
        outState.putInt(STATE_LINE_SPACING, lineSpacingIndex);
        outState.putInt(STATE_ALIGNMENT, alignmentIndex);
        if (textInput != null) outState.putString(STATE_TEXT, textInput.getText().toString());
        if (pageCountInput != null) outState.putInt(STATE_PAGE_COUNT, readPageCount());
    }

    private void buildCurrentModeUi() {
        contentCard.removeAllViews();
        settingsCard.removeAllViews();
        if (MODE_TEXT.equals(mode)) buildTextUi();
        else if (MODE_BLANK.equals(mode)) buildBlankUi();
        else buildImageUi();
        refreshCreateButton();
    }

    private void buildImageUi() {
        toolbar.setTitle("图片转 PDF");
        descriptionView.setText("选择一张或多张图片，调整顺序后合成为一个 PDF。支持 JPG/JPEG、PNG、WebP，不支持 GIF。");
        tipView.setText("普通图片每张一页，超长图片按可读宽度自动分段。选择器会自动过滤 GIF 等不支持的格式；若文件扩展名或类型标记不准确，应用也会在加入列表前再次检查。");

        LinearLayout heading = horizontalRow();
        imageCountView = text("已选择 0 张图片", 15, TEXT_PRIMARY, true);
        heading.addView(imageCountView, new LinearLayout.LayoutParams(0, wrap(), 1f));
        TextView addButton = actionButton("添加图片");
        addButton.setOnClickListener(v -> imagePickerLauncher.launch(SUPPORTED_IMAGE_MIME_TYPES));
        heading.addView(addButton, new LinearLayout.LayoutParams(dp(104), dp(40)));
        contentCard.addView(heading);

        imageEmptyView = text("尚未选择图片\n点击“添加图片”开始", 13, TEXT_SECONDARY, false);
        imageEmptyView.setGravity(Gravity.CENTER);
        imageEmptyView.setPadding(0, dp(28), 0, dp(24));
        contentCard.addView(imageEmptyView, matchWrap());

        imageList = new LinearLayout(this);
        imageList.setOrientation(LinearLayout.VERTICAL);
        imageList.setPadding(0, dp(10), 0, 0);
        contentCard.addView(imageList, matchWrap());
        rebuildImageList();

        String[] pageSizes = {"适应图片", "A4", "A5", "Letter"};
        pageSizeIndex = clamp(pageSizeIndex, pageSizes.length);
        addChoiceRow("页面尺寸", pageSizes[pageSizeIndex], pageSizes,
                pageSizeIndex, index -> { pageSizeIndex = index; });

        String[] orientations = {"自动", "纵向", "横向"};
        orientationIndex = clamp(orientationIndex, orientations.length);
        addChoiceRow("页面方向", orientations[orientationIndex], orientations,
                orientationIndex, index -> { orientationIndex = index; });

        String[] margins = {"无边距", "窄边距", "标准边距"};
        marginIndex = clamp(marginIndex, margins.length);
        addChoiceRow("页面边距", margins[marginIndex], margins,
                marginIndex, index -> { marginIndex = index; });

        String[] qualities = {"节省空间", "平衡", "高清"};
        qualityIndex = clamp(qualityIndex, qualities.length);
        addChoiceRow("图片质量", qualities[qualityIndex], qualities,
                qualityIndex, index -> { qualityIndex = index; });
    }

    private void buildTextUi() {
        toolbar.setTitle("文本转 PDF");
        descriptionView.setText("输入或粘贴纯文本，设置基础版式后生成带真实文本层的 PDF。");
        tipView.setText("文本会自动换行和分页。当前版本提供基础排版，不包含 Word 式富文本、表格、目录和图片混排。");

        textInput = new EditText(this);
        textInput.setId(View.generateViewId());
        textInput.setHint("在这里输入或粘贴文本……");
        textInput.setGravity(Gravity.TOP | Gravity.START);
        textInput.setTextColor(TEXT_PRIMARY);
        textInput.setHintTextColor(0xFF9AA0A6);
        textInput.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        textInput.setMinHeight(dp(260));
        textInput.setBackgroundResource(R.drawable.bg_creation_input);
        textInput.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        contentCard.addView(textInput, new LinearLayout.LayoutParams(match(), dp(280)));
        if (!restoredText.isEmpty()) textInput.setText(restoredText);

        textCountView = text(textInput.getText().length() + " 个字符", 12, TEXT_SECONDARY, false);
        textCountView.setGravity(Gravity.END);
        textCountView.setPadding(0, dp(8), 0, 0);
        contentCard.addView(textCountView, matchWrap());
        textInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                textCountView.setText(s.length() + " 个字符");
                refreshCreateButton();
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        String[] pageSizes = {"A4", "A5", "Letter"};
        pageSizeIndex = clamp(pageSizeIndex, pageSizes.length);
        addChoiceRow("页面尺寸", pageSizes[pageSizeIndex], pageSizes,
                pageSizeIndex, index -> { pageSizeIndex = index; });

        String[] orientations = {"纵向", "横向"};
        orientationIndex = clamp(orientationIndex, orientations.length);
        addChoiceRow("页面方向", orientations[orientationIndex], orientations,
                orientationIndex, index -> { orientationIndex = index; });

        String[] fontSizes = {"10", "12", "14", "16", "18", "20", "24"};
        fontSizeIndex = clamp(fontSizeIndex, fontSizes.length);
        addChoiceRow("正文字号", fontSizes[fontSizeIndex] + " pt", fontSizes,
                fontSizeIndex, index -> { fontSizeIndex = index; });

        String[] lineSpacings = {"紧凑（1.2）", "标准（1.5）", "宽松（1.8）"};
        lineSpacingIndex = clamp(lineSpacingIndex, lineSpacings.length);
        addChoiceRow("行距", lineSpacings[lineSpacingIndex], lineSpacings,
                lineSpacingIndex, index -> { lineSpacingIndex = index; });

        String[] margins = {"窄", "标准", "宽"};
        marginIndex = clamp(marginIndex, margins.length);
        addChoiceRow("页面边距", margins[marginIndex], margins,
                marginIndex, index -> { marginIndex = index; });

        String[] alignments = {"左对齐", "居中", "右对齐"};
        alignmentIndex = clamp(alignmentIndex, alignments.length);
        addChoiceRow("文本对齐", alignments[alignmentIndex], alignments,
                alignmentIndex, index -> { alignmentIndex = index; });
    }

    private void buildBlankUi() {
        toolbar.setTitle("创建空白 PDF");
        descriptionView.setText("选择页面尺寸、方向和页数，创建一个新的空白 PDF。");
        tipView.setText("空白 PDF 适合后续批注、手写或作为页面管理中的插入素材。最多一次创建 200 页。");

        TextView label = text("页面数量", 15, TEXT_PRIMARY, true);
        contentCard.addView(label, matchWrap());

        LinearLayout stepper = horizontalRow();
        stepper.setPadding(0, dp(12), 0, 0);
        TextView minus = squareButton("−");
        TextView plus = squareButton("+");
        pageCountInput = new EditText(this);
        pageCountInput.setId(View.generateViewId());
        pageCountInput.setText(String.valueOf(restoredPageCount));
        pageCountInput.setGravity(Gravity.CENTER);
        pageCountInput.setTextColor(TEXT_PRIMARY);
        pageCountInput.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        pageCountInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        pageCountInput.setSelectAllOnFocus(true);
        pageCountInput.setBackgroundResource(R.drawable.bg_creation_input);
        LinearLayout.LayoutParams countParams = new LinearLayout.LayoutParams(0, dp(48), 1f);
        countParams.setMargins(dp(10), 0, dp(10), 0);
        stepper.addView(minus, new LinearLayout.LayoutParams(dp(48), dp(48)));
        stepper.addView(pageCountInput, countParams);
        stepper.addView(plus, new LinearLayout.LayoutParams(dp(48), dp(48)));
        contentCard.addView(stepper, matchWrap());

        minus.setOnClickListener(v -> setPageCount(readPageCount() - 1));
        plus.setOnClickListener(v -> setPageCount(readPageCount() + 1));
        pageCountInput.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) setPageCount(readPageCount());
        });

        String[] pageSizes = {"A4", "A5", "Letter"};
        pageSizeIndex = clamp(pageSizeIndex, pageSizes.length);
        addChoiceRow("页面尺寸", pageSizes[pageSizeIndex], pageSizes,
                pageSizeIndex, index -> { pageSizeIndex = index; });

        String[] orientations = {"纵向", "横向"};
        orientationIndex = clamp(orientationIndex, orientations.length);
        addChoiceRow("页面方向", orientations[orientationIndex], orientations,
                orientationIndex, index -> { orientationIndex = index; });
    }

    private void rebuildImageList() {
        if (imageList == null || imageEmptyView == null || imageCountView == null) return;
        imageList.removeAllViews();
        boolean empty = imageUris.isEmpty();
        imageEmptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
        imageCountView.setText("已选择 " + imageUris.size() + " 张图片");

        for (int i = 0; i < imageUris.size(); i++) {
            if (i > 0) imageList.addView(dividerView());
            imageList.addView(createImageRow(imageUris.get(i), i));
        }
        refreshCreateButton();
    }

    private View createImageRow(Uri uri, int position) {
        LinearLayout row = horizontalRow();
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(9), 0, dp(9));

        ImageView thumbnail = new ImageView(this);
        thumbnail.setScaleType(ImageView.ScaleType.CENTER_CROP);
        thumbnail.setBackgroundColor(0xFFE9EDF1);
        thumbnail.setImageResource(android.R.drawable.ic_menu_gallery);
        row.addView(thumbnail, new LinearLayout.LayoutParams(dp(58), dp(58)));

        LinearLayout textColumn = new LinearLayout(this);
        textColumn.setOrientation(LinearLayout.VERTICAL);
        textColumn.setPadding(dp(12), 0, dp(6), 0);
        TextView name = text(queryDisplayName(uri), 14, TEXT_PRIMARY, true);
        name.setMaxLines(1);
        name.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        TextView order = text("第 " + (position + 1) + " 页", 12, TEXT_SECONDARY, false);
        order.setPadding(0, dp(5), 0, 0);
        textColumn.addView(name);
        textColumn.addView(order);
        row.addView(textColumn, new LinearLayout.LayoutParams(0, wrap(), 1f));

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        TextView up = miniButton("↑", "上移");
        TextView down = miniButton("↓", "下移");
        TextView remove = miniButton("×", "删除");
        up.setEnabled(position > 0);
        up.setAlpha(position > 0 ? 1f : 0.35f);
        down.setEnabled(position < imageUris.size() - 1);
        down.setAlpha(position < imageUris.size() - 1 ? 1f : 0.35f);
        up.setOnClickListener(v -> moveImage(position, position - 1));
        down.setOnClickListener(v -> moveImage(position, position + 1));
        remove.setOnClickListener(v -> {
            if (position >= 0 && position < imageUris.size()) {
                imageUris.remove(position);
                rebuildImageList();
            }
        });
        controls.addView(up, new LinearLayout.LayoutParams(dp(38), dp(42)));
        controls.addView(down, new LinearLayout.LayoutParams(dp(38), dp(42)));
        controls.addView(remove, new LinearLayout.LayoutParams(dp(38), dp(42)));
        row.addView(controls);

        thumbnailExecutor.execute(() -> {
            Bitmap bitmap = decodeThumbnail(uri, dp(120));
            if (bitmap == null) return;
            runOnUiThread(() -> {
                if (!isFinishing() && !isDestroyed() && position < imageUris.size()
                        && imageUris.get(position).equals(uri)) {
                    thumbnail.setImageBitmap(bitmap);
                } else {
                    bitmap.recycle();
                }
            });
        });
        return row;
    }

    private void moveImage(int from, int to) {
        if (from < 0 || from >= imageUris.size() || to < 0 || to >= imageUris.size()) return;
        Uri value = imageUris.remove(from);
        imageUris.add(to, value);
        rebuildImageList();
    }

    private void addChoiceRow(
            String title,
            String currentValue,
            String[] values,
            int selectedIndex,
            IndexConsumer consumer
    ) {
        if (settingsCard.getChildCount() > 0) settingsCard.addView(dividerView());
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
        final int[] currentSelection = {selectedIndex};
        row.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle(title)
                .setSingleChoiceItems(values, currentSelection[0], (dialog, which) -> {
                    currentSelection[0] = which;
                    consumer.accept(which);
                    valueView.setText(displayChoiceValue(title, values[which]));
                    dialog.dismiss();
                })
                .setNegativeButton("取消", null)
                .show());
        settingsCard.addView(row, matchWrap());
    }

    private String displayChoiceValue(String title, String raw) {
        if ("正文字号".equals(title)) return raw + " pt";
        return raw;
    }

    private void chooseOutputLocation() {
        if (!validateInput()) return;
        outputLauncher.launch(defaultFileName());
    }

    private boolean validateInput() {
        if (MODE_IMAGES.equals(mode) && imageUris.isEmpty()) {
            Toast.makeText(this, "请先选择图片", Toast.LENGTH_SHORT).show();
            return false;
        }
        if (MODE_TEXT.equals(mode)
                && (textInput == null || textInput.getText().toString().trim().isEmpty())) {
            Toast.makeText(this, "请输入要转换的文本", Toast.LENGTH_SHORT).show();
            return false;
        }
        if (MODE_BLANK.equals(mode)) setPageCount(readPageCount());
        return true;
    }

    private void createPdf(Uri outputUri) {
        DiagnosticContext.startOperation(this, "create_pdf");
        String outputName = queryDisplayName(outputUri);
        final String modeSnapshot = mode;
        final String textSnapshot = textInput == null ? "" : textInput.getText().toString();
        final List<Uri> imageSnapshot = new ArrayList<>(imageUris);
        final PdfCreationService.ImageOptions imageOptions = MODE_IMAGES.equals(modeSnapshot)
                ? buildImageOptions() : null;
        final PdfCreationService.TextOptions textOptions = MODE_TEXT.equals(modeSnapshot)
                ? buildTextOptions() : null;
        final PdfCreationService.BlankOptions blankOptions = MODE_BLANK.equals(modeSnapshot)
                ? buildBlankOptions() : null;

        showProgressDialog();
        workerExecutor.execute(() -> {
            File temp = null;
            try {
                temp = File.createTempFile("mypdf-create-", ".pdf", getCacheDir());
                PdfCreationService.ProgressCallback callback = (current, total, message) ->
                        runOnUiThread(() -> updateProgress(current, total, message));

                if (MODE_TEXT.equals(modeSnapshot)) {
                    PdfCreationService.createTextPdf(
                            textSnapshot, temp, outputName, textOptions, callback
                    );
                } else if (MODE_BLANK.equals(modeSnapshot)) {
                    PdfCreationService.createBlankPdf(
                            temp, outputName, blankOptions, callback
                    );
                } else {
                    PdfCreationService.createImagePdf(
                            this, imageSnapshot, temp, outputName, imageOptions, callback
                    );
                }

                runOnUiThread(() -> updateProgress(1, 1, "正在写入保存位置"));
                copyTempToUri(temp, outputUri);
                addCreatedPdfToHome(outputUri, outputName);
                int createdPages = MODE_IMAGES.equals(modeSnapshot) ? imageSnapshot.size()
                        : MODE_BLANK.equals(modeSnapshot) && blankOptions != null ? blankOptions.pageCount : -1;
                DiagnosticContext.setDocumentProfile(this, outputUri, "saf", createdPages, false, false);
                DiagnosticContext.finishOperation(this, "create_pdf", true);
                runOnUiThread(() -> {
                    dismissProgressDialog();
                    showSuccessDialog(outputUri, outputName);
                });
            } catch (Exception e) {
                DiagnosticContext.finishOperation(this, "create_pdf", false);
                runOnUiThread(() -> {
                    dismissProgressDialog();
                    new AlertDialog.Builder(this)
                            .setTitle("创建失败")
                            .setMessage(readableError(e))
                            .setPositiveButton("知道了", null)
                            .show();
                });
            } finally {
                if (temp != null && temp.exists()) temp.delete();
            }
        });
    }

    private PdfCreationService.ImageOptions buildImageOptions() {
        PdfCreationService.ImageOptions options = new PdfCreationService.ImageOptions();
        options.pageSize = new PdfCreationService.PageSize[]{
                PdfCreationService.PageSize.FIT_IMAGE,
                PdfCreationService.PageSize.A4,
                PdfCreationService.PageSize.A5,
                PdfCreationService.PageSize.LETTER
        }[clamp(pageSizeIndex, 4)];
        options.orientation = new PdfCreationService.Orientation[]{
                PdfCreationService.Orientation.AUTO,
                PdfCreationService.Orientation.PORTRAIT,
                PdfCreationService.Orientation.LANDSCAPE
        }[clamp(orientationIndex, 3)];
        options.marginPoints = new float[]{0f, 18f, 36f}[clamp(marginIndex, 3)];
        options.jpegQuality = new float[]{0.72f, 0.85f, 0.95f}[clamp(qualityIndex, 3)];
        return options;
    }

    private PdfCreationService.TextOptions buildTextOptions() {
        PdfCreationService.TextOptions options = new PdfCreationService.TextOptions();
        options.pageSize = new PdfCreationService.PageSize[]{
                PdfCreationService.PageSize.A4,
                PdfCreationService.PageSize.A5,
                PdfCreationService.PageSize.LETTER
        }[clamp(pageSizeIndex, 3)];
        options.orientation = orientationIndex == 1
                ? PdfCreationService.Orientation.LANDSCAPE
                : PdfCreationService.Orientation.PORTRAIT;
        options.fontSize = new float[]{10f, 12f, 14f, 16f, 18f, 20f, 24f}[
                clamp(fontSizeIndex, 7)
        ];
        options.lineSpacing = new float[]{1.2f, 1.5f, 1.8f}[clamp(lineSpacingIndex, 3)];
        options.marginPoints = new float[]{36f, 54f, 72f}[clamp(marginIndex, 3)];
        options.alignment = new PdfCreationService.TextAlignment[]{
                PdfCreationService.TextAlignment.LEFT,
                PdfCreationService.TextAlignment.CENTER,
                PdfCreationService.TextAlignment.RIGHT
        }[clamp(alignmentIndex, 3)];
        return options;
    }

    private PdfCreationService.BlankOptions buildBlankOptions() {
        PdfCreationService.BlankOptions options = new PdfCreationService.BlankOptions();
        options.pageSize = new PdfCreationService.PageSize[]{
                PdfCreationService.PageSize.A4,
                PdfCreationService.PageSize.A5,
                PdfCreationService.PageSize.LETTER
        }[clamp(pageSizeIndex, 3)];
        options.orientation = orientationIndex == 1
                ? PdfCreationService.Orientation.LANDSCAPE
                : PdfCreationService.Orientation.PORTRAIT;
        options.pageCount = readPageCount();
        return options;
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
        if (name == null || name.trim().isEmpty()) name = "新建文档.pdf";
        long modified = file == null ? System.currentTimeMillis() : file.lastModified();
        String time = new SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault())
                .format(new Date(modified > 0 ? modified : System.currentTimeMillis()));
        String path = MainActivity.cleanPath(uri.getPath());
        new PdfDbHelper(this).insertOrUpdateHomeItem(new PdfItem(uri, name, path, time, false));
    }

    private void showSuccessDialog(Uri uri, String name) {
        new AlertDialog.Builder(this)
                .setTitle("PDF 已创建")
                .setMessage((name == null ? "新建文档.pdf" : name) + "\n\n文件已加入首页列表。")
                .setNegativeButton("完成", (dialog, which) -> finish())
                .setPositiveButton("打开", (dialog, which) -> openCreatedPdf(uri, name))
                .show();
    }

    private void openCreatedPdf(Uri uri, String name) {
        Intent intent = new Intent(this, PdfViewerActivity.class);
        intent.putExtra("pdf_uri", uri.toString());
        intent.putExtra("pdf_path", MainActivity.cleanPath(uri.getPath()));
        intent.putExtra("pdf_name", name == null ? "新建文档.pdf" : name);
        startActivity(intent);
        finish();
    }

    private void showProgressDialog() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(24), dp(8), dp(24), dp(8));
        progressText = text("正在准备……", 14, TEXT_PRIMARY, false);
        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(1000);
        progressBar.setProgress(0);
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(match(), dp(8));
        progressParams.setMargins(0, dp(16), 0, dp(4));
        content.addView(progressText, matchWrap());
        content.addView(progressBar, progressParams);
        progressDialog = new AlertDialog.Builder(this)
                .setTitle("正在创建 PDF")
                .setView(content)
                .setCancelable(false)
                .create();
        progressDialog.show();
    }

    private void updateProgress(int current, int total, String message) {
        if (progressDialog == null || !progressDialog.isShowing()) return;
        progressText.setText(message == null ? "正在处理……" : message);
        int progress = total <= 0 ? 0 : Math.round(current * 1000f / total);
        progressBar.setProgress(Math.max(0, Math.min(1000, progress)));
    }

    private void dismissProgressDialog() {
        if (progressDialog != null) progressDialog.dismiss();
        progressDialog = null;
    }

    private String readableError(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        String message = current.getMessage();
        return message == null || message.trim().isEmpty()
                ? current.getClass().getSimpleName()
                : message;
    }

    private String defaultFileName() {
        String time = new SimpleDateFormat("yyyyMMdd-HHmm", Locale.getDefault()).format(new Date());
        if (MODE_TEXT.equals(mode)) return "文本文档-" + time + ".pdf";
        if (MODE_BLANK.equals(mode)) return "空白文档-" + time + ".pdf";
        return "图片文档-" + time + ".pdf";
    }

    private void refreshCreateButton() {
        boolean enabled;
        if (MODE_TEXT.equals(mode)) {
            enabled = textInput != null && !textInput.getText().toString().trim().isEmpty();
        } else if (MODE_IMAGES.equals(mode)) {
            enabled = !imageUris.isEmpty();
        } else {
            enabled = true;
        }
        createButton.setEnabled(enabled);
        createButton.setAlpha(enabled ? 1f : 0.72f);
    }

    private int readPageCount() {
        if (pageCountInput == null) return 1;
        try {
            return Math.max(1, Math.min(200, Integer.parseInt(pageCountInput.getText().toString().trim())));
        } catch (Exception ignore) {
            return 1;
        }
    }

    private void setPageCount(int count) {
        int safe = Math.max(1, Math.min(200, count));
        if (pageCountInput != null) pageCountInput.setText(String.valueOf(safe));
    }

    private String queryDisplayName(Uri uri) {
        if (uri == null) return "未命名";
        try (android.database.Cursor cursor = getContentResolver().query(
                uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    String value = cursor.getString(index);
                    if (value != null && !value.isEmpty()) return value;
                }
            }
        } catch (Exception ignore) {
        }
        DocumentFile file = DocumentFile.fromSingleUri(this, uri);
        return file != null && file.getName() != null ? file.getName() : "未命名";
    }

    private Bitmap decodeThumbnail(Uri uri, int maxSize) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream input = getContentResolver().openInputStream(uri)) {
                BitmapFactory.decodeStream(input, null, bounds);
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
            int sample = 1;
            while (Math.max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSize) sample *= 2;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            try (InputStream input = getContentResolver().openInputStream(uri)) {
                Bitmap bitmap = BitmapFactory.decodeStream(input, null, options);
                if (bitmap == null) return null;
                int rotation = 0;
                try (InputStream exifInput = getContentResolver().openInputStream(uri)) {
                    if (exifInput != null) {
                        ExifInterface exif = new ExifInterface(exifInput);
                        int orientation = exif.getAttributeInt(
                                ExifInterface.TAG_ORIENTATION,
                                ExifInterface.ORIENTATION_NORMAL
                        );
                        if (orientation == ExifInterface.ORIENTATION_ROTATE_90) rotation = 90;
                        else if (orientation == ExifInterface.ORIENTATION_ROTATE_180) rotation = 180;
                        else if (orientation == ExifInterface.ORIENTATION_ROTATE_270) rotation = 270;
                    }
                }
                if (rotation == 0) return bitmap;
                Matrix matrix = new Matrix();
                matrix.postRotate(rotation);
                Bitmap rotated = Bitmap.createBitmap(
                        bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true
                );
                if (rotated != bitmap) bitmap.recycle();
                return rotated;
            }
        } catch (Exception ignore) {
            return null;
        }
    }

    private LinearLayout horizontalRow() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        return layout;
    }

    private TextView actionButton(String label) {
        TextView view = text(label, 14, BLUE, true);
        view.setGravity(Gravity.CENTER);
        view.setBackgroundResource(R.drawable.bg_creation_secondary);
        view.setClickable(true);
        view.setFocusable(true);
        return view;
    }

    private TextView squareButton(String label) {
        TextView view = text(label, 24, BLUE, false);
        view.setGravity(Gravity.CENTER);
        view.setBackgroundResource(R.drawable.bg_creation_secondary);
        view.setClickable(true);
        view.setFocusable(true);
        return view;
    }

    private TextView miniButton(String label, String description) {
        TextView view = text(label, 20, BLUE, false);
        view.setGravity(Gravity.CENTER);
        view.setContentDescription(description);
        view.setClickable(true);
        view.setFocusable(true);
        return view;
    }

    private TextView text(String value, int sp, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextColor(color);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (bold) view.setTypeface(view.getTypeface(), android.graphics.Typeface.BOLD);
        return view;
    }

    private View dividerView() {
        View divider = new View(this);
        divider.setBackgroundColor(DIVIDER);
        divider.setLayoutParams(new LinearLayout.LayoutParams(match(), dp(1)));
        return divider;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(match(), wrap());
    }

    private static int clamp(int value, int length) {
        if (length <= 0) return 0;
        return Math.max(0, Math.min(length - 1, value));
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                value,
                getResources().getDisplayMetrics()
        ));
    }

    private static int match() { return ViewGroup.LayoutParams.MATCH_PARENT; }
    private static int wrap() { return ViewGroup.LayoutParams.WRAP_CONTENT; }

    @Override
    protected void onDestroy() {
        dismissProgressDialog();
        workerExecutor.shutdownNow();
        thumbnailExecutor.shutdownNow();
        super.onDestroy();
    }

    private interface IndexConsumer {
        void accept(int index);
    }
}
