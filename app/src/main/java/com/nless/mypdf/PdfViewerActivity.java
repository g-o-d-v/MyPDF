package com.nless.mypdf;

import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.documentfile.provider.DocumentFile;

import com.github.barteksc.pdfviewer.PDFView;

import com.itextpdf.text.Image;
import com.itextpdf.text.pdf.PdfContentByte;
import com.itextpdf.text.pdf.PdfReader;
import com.itextpdf.text.pdf.PdfStamper;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class PdfViewerActivity extends AppCompatActivity {

    public enum EditState {
        DRAG, DOODLE, TEXT
    }

    private EditState currentEditState = EditState.DRAG;
    private boolean isEditMode = false;
    private boolean isMenuVisible = true;

    private boolean isAtLastPage = false;

    private String currentFileName = "未命名";
    private String pdfPath = "";
    private String pdfName = "";
    private Uri pdfUri;
    private String parentUriStr = null;
    private String rawModifiedTime = "";

    // 🌟 全新异步预加载引擎参数：存储后台提前计算好的下一卷数据
    private Uri preloadedNextUri = null;
    private String preloadedNextPath = "";
    private String preloadedNextName = "";

    private PdfDbHelper dbHelper;
    private PDFView pdfView;
    private PdfOverlayView pdfOverlay;
    private LinearLayout topMenuLayout;

    private ImageView ivBackOrExit, ivUndo, ivFavorite, ivSave, ivEditMode;
    private TextView tvTopTitle;
    private View dividerLine;
    private LinearLayout llEditToolsRow;
    private ImageView ivToolDrag, ivToolDoodle, ivToolText;

    private LinearLayout llDoodleProperties;
    private TextView tvPenTool, tvArrowTool, tvShapeTool;
    private ImageView ivPenDrop, ivArrowDrop, ivShapeDrop;
    private SeekBar seekDoodleSize;

    private LinearLayout llTextProperties;
    private TextView tvStyleBold, tvStyleItalic, tvStyleUnderline;
    private SeekBar seekTextSize;

    private boolean isTextBold = false;
    private boolean isTextItalic = false;
    private boolean isTextUnderline = false;
    private int currentDrawColor = 0xFF000000;
    private String currentActiveTool = "铅笔";

    private float lastPdfXOffset = 0;
    private float lastPdfYOffset = 0;
    private float lastPdfZoom = 0;

    private final int[] colorViewIds = {
            R.id.color_black, R.id.color_white, R.id.color_gray, R.id.color_red,
            R.id.color_yellow, R.id.color_green, R.id.color_blue, R.id.color_purple,
            R.id.t_color_black, R.id.t_color_white, R.id.t_color_gray, R.id.t_color_red,
            R.id.t_color_yellow, R.id.t_color_green, R.id.t_color_blue, R.id.t_color_purple
    };
    private final int[] colorValues = {
            0xFF000000, 0xFFFFFFFF, 0xFF999999, 0xFFF44336,
            0xFFFFEB3B, 0xFF4CAF50, 0xFF2196F3, 0xFF9C27B0,
            0xFF000000, 0xFFFFFFFF, 0xFF999999, 0xFFF44336,
            0xFFFFEB3B, 0xFF4CAF50, 0xFF2196F3, 0xFF9C27B0
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getSupportActionBar() != null) getSupportActionBar().hide();
        setContentView(R.layout.activity_pdf_viewer);

        dbHelper = new PdfDbHelper(this);
        initViews();
        setupListeners();
        loadPdfFromIntent();
        updateFavoriteIconState();
        updateAllColorBlocksUI();
    }

    private void initViews() {
        pdfView = findViewById(R.id.pdfView);
        pdfOverlay = findViewById(R.id.pdfOverlay);
        topMenuLayout = findViewById(R.id.top_menu_layout);

        ivBackOrExit = findViewById(R.id.iv_back_or_exit);
        tvTopTitle = findViewById(R.id.tv_top_title);
        ivUndo = findViewById(R.id.iv_undo);
        ivFavorite = findViewById(R.id.iv_favorite);
        ivSave = findViewById(R.id.iv_save);
        ivEditMode = findViewById(R.id.iv_edit_mode);
        dividerLine = findViewById(R.id.divider_line);

        llEditToolsRow = findViewById(R.id.ll_edit_tools_row);
        ivToolDrag = findViewById(R.id.iv_tool_drag);
        ivToolDoodle = findViewById(R.id.iv_tool_doodle);
        ivToolText = findViewById(R.id.iv_tool_text);

        llDoodleProperties = findViewById(R.id.ll_doodle_properties);
        tvPenTool = findViewById(R.id.tv_pen_tool);
        tvArrowTool = findViewById(R.id.tv_arrow_tool);
        tvShapeTool = findViewById(R.id.tv_shape_tool);
        ivPenDrop = findViewById(R.id.iv_pen_drop);
        ivArrowDrop = findViewById(R.id.iv_arrow_drop);
        ivShapeDrop = findViewById(R.id.iv_shape_drop);
        seekDoodleSize = findViewById(R.id.seek_doodle_size);

        llTextProperties = findViewById(R.id.ll_text_properties);
        tvStyleBold = findViewById(R.id.tv_style_bold);
        tvStyleItalic = findViewById(R.id.tv_style_italic);
        tvStyleUnderline = findViewById(R.id.tv_style_underline);
        seekTextSize = findViewById(R.id.seek_text_size);

        topMenuLayout.setVisibility(View.VISIBLE);
        llEditToolsRow.setVisibility(View.GONE);

        if (pdfOverlay != null) {
            pdfOverlay.setPdfView(pdfView);
        }

        pdfView.getViewTreeObserver().addOnPreDrawListener(() -> {
            if (pdfView != null && pdfOverlay != null) {
                float currentX = pdfView.getCurrentXOffset();
                float currentY = pdfView.getCurrentYOffset();
                float currentZoom = pdfView.getZoom();
                if (currentX != lastPdfXOffset || currentY != lastPdfYOffset || currentZoom != lastPdfZoom) {
                    lastPdfXOffset = currentX;
                    lastPdfYOffset = currentY;
                    lastPdfZoom = currentZoom;
                    pdfOverlay.invalidate();
                }
            }
            return true;
        });
    }

    private void setupListeners() {
        ivBackOrExit.setOnClickListener(v -> handleBackAction());
        ivEditMode.setOnClickListener(v -> toggleEditMode(true));
        ivSave.setOnClickListener(v -> handleSaveAction());
        ivFavorite.setOnClickListener(v -> handleFavoriteToggle());

        ivUndo.setOnClickListener(v -> {
            if (pdfOverlay != null) pdfOverlay.undo();
        });

        ivToolDrag.setOnClickListener(v -> switchEditState(EditState.DRAG));
        ivToolDoodle.setOnClickListener(v -> switchEditState(EditState.DOODLE));
        ivToolText.setOnClickListener(v -> switchEditState(EditState.TEXT));

        tvPenTool.setOnClickListener(v -> activateDoodleTool(tvPenTool, tvPenTool.getText().toString()));
        tvArrowTool.setOnClickListener(v -> activateDoodleTool(tvArrowTool, tvArrowTool.getText().toString()));
        tvShapeTool.setOnClickListener(v -> activateDoodleTool(tvShapeTool, tvShapeTool.getText().toString()));

        ivPenDrop.setOnClickListener(v -> showToolPopup(ivPenDrop, tvPenTool, new String[]{"铅笔", "荧光笔"}));
        ivArrowDrop.setOnClickListener(v -> showToolPopup(ivArrowDrop, tvArrowTool, new String[]{"单向箭头", "双向箭头"}));
        ivShapeDrop.setOnClickListener(v -> showToolPopup(ivShapeDrop, tvShapeTool, new String[]{"矩形框", "实心矩形", "圆形"}));

        tvStyleBold.setOnClickListener(v -> {
            isTextBold = !isTextBold;
            tvStyleBold.setTextColor(isTextBold ? android.graphics.Color.parseColor("#4CAF50") : android.graphics.Color.parseColor("#999999"));
        });
        tvStyleItalic.setOnClickListener(v -> {
            isTextItalic = !isTextItalic;
            tvStyleItalic.setTextColor(isTextItalic ? android.graphics.Color.parseColor("#4CAF50") : android.graphics.Color.parseColor("#999999"));
        });
        tvStyleUnderline.setOnClickListener(v -> {
            isTextUnderline = !isTextUnderline;
            tvStyleUnderline.setTextColor(isTextUnderline ? android.graphics.Color.parseColor("#4CAF50") : android.graphics.Color.parseColor("#999999"));
        });

        seekDoodleSize.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if(pdfOverlay != null) pdfOverlay.setStrokeWidth(progress + 2f);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        setupColorBlocks();
        setupTextInputCallback();

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                handleBackAction();
            }
        });
    }

    private void showToolPopup(View anchor, TextView targetText, String[] options) {
        android.widget.PopupMenu popup = new android.widget.PopupMenu(this, anchor);
        for (int i = 0; i < options.length; i++) {
            popup.getMenu().add(0, i, 0, options[i]);
        }
        popup.setOnMenuItemClickListener(item -> {
            String selected = item.getTitle().toString();
            targetText.setText(selected);
            activateDoodleTool(targetText, selected);
            return true;
        });
        popup.show();
    }

    private void activateDoodleTool(TextView activeText, String toolName) {
        tvPenTool.setTextColor(android.graphics.Color.parseColor("#333333"));
        tvPenTool.setTypeface(null, android.graphics.Typeface.NORMAL);
        tvArrowTool.setTextColor(android.graphics.Color.parseColor("#333333"));
        tvArrowTool.setTypeface(null, android.graphics.Typeface.NORMAL);
        tvShapeTool.setTextColor(android.graphics.Color.parseColor("#333333"));
        tvShapeTool.setTypeface(null, android.graphics.Typeface.NORMAL);

        activeText.setTextColor(android.graphics.Color.parseColor("#4CAF50"));
        activeText.setTypeface(null, android.graphics.Typeface.BOLD);

        currentActiveTool = toolName;
        if (pdfOverlay != null) {
            pdfOverlay.setToolType(toolName);
        }
    }

    private void setupColorBlocks() {
        for (int i = 0; i < colorViewIds.length; i++) {
            final int color = colorValues[i];
            View colorView = findViewById(colorViewIds[i]);
            if (colorView != null) {
                colorView.setOnClickListener(v -> {
                    currentDrawColor = color;
                    if (pdfOverlay != null) pdfOverlay.setDrawColor(color);
                    updateAllColorBlocksUI();
                });
            }
        }
    }

    private void updateAllColorBlocksUI() {
        for (int i = 0; i < colorViewIds.length; i++) {
            View view = findViewById(colorViewIds[i]);
            if (view != null) {
                int color = colorValues[i];
                boolean isSelected = (color == currentDrawColor);

                GradientDrawable gd = new GradientDrawable();
                gd.setColor(color);
                if (isSelected) {
                    int strokeColor = (color == 0xFFFFFFFF) ? 0xFF000000 : 0xFFFFFFFF;
                    gd.setStroke(6, strokeColor);
                } else {
                    gd.setStroke(0, 0x00000000);
                }
                view.setBackground(gd);
            }
        }

        ColorStateList csl = ColorStateList.valueOf(currentDrawColor);
        seekDoodleSize.setProgressTintList(csl);
        seekDoodleSize.setThumbTintList(csl);
        seekTextSize.setProgressTintList(csl);
        seekTextSize.setThumbTintList(csl);
    }

    private void setupTextInputCallback() {
        if (pdfOverlay != null) {
            pdfOverlay.setOnTextRequestListener((docX, docY) -> {
                final android.widget.EditText input = new android.widget.EditText(this);
                new AlertDialog.Builder(this)
                        .setTitle("输入文本")
                        .setView(input)
                        .setPositiveButton("确定", (dialog, which) -> {
                            String text = input.getText().toString();
                            if (!text.isEmpty()) {
                                int size = seekTextSize.getProgress() + 10;
                                pdfOverlay.addTextAction(text, docX, docY, currentDrawColor, size, isTextBold, isTextItalic, isTextUnderline);
                            }
                        })
                        .setNegativeButton("取消", null)
                        .show();
            });
        }
    }

    private void loadPdfFromIntent() {
        String uriString = getIntent().getStringExtra("pdf_uri");
        pdfPath = getIntent().getStringExtra("pdf_path");
        pdfName = getIntent().getStringExtra("pdf_name");
        parentUriStr = getIntent().getStringExtra("parent_uri");

        if (uriString != null) {
            pdfUri = Uri.parse(uriString);
            currentFileName = pdfName;

            if (currentFileName != null && currentFileName.toLowerCase().endsWith(".pdf")) {
                currentFileName = currentFileName.substring(0, currentFileName.length() - 4);
            }
            if (currentFileName != null && currentFileName.contains("-副本")) {
                currentFileName = currentFileName.split("-副本")[0];
            }

            tvTopTitle.setText(currentFileName);
            reloadPdfView(0);
        } else {
            finish();
        }
    }

    private void reloadPdfView(int targetPageIndex) {
        pdfView.fromUri(pdfUri)
                .defaultPage(targetPageIndex)
                .enableSwipe(true)
                .swipeHorizontal(false)
                .onTap(e -> {
                    if (!isEditMode || currentEditState == EditState.DRAG) {
                        // 🌟 核心唤醒：如果处于最后一页，且预加载完成，点击右侧秒切下一卷！
                        if (isAtLastPage && e.getX() > pdfView.getWidth() * 0.8f) {
                            if (preloadedNextUri != null) {
                                executeLoadNextVolume();
                            } else {
                                Toast.makeText(PdfViewerActivity.this, "已经是本系列的最后一卷啦", Toast.LENGTH_SHORT).show();
                            }
                            return true;
                        }
                        toggleMenuVisibility();
                    }
                    return true;
                })
                .onPageChange((page, pageCount) -> {
                    if (!isEditMode) {
                        tvTopTitle.setText(currentFileName + " (" + (page + 1) + "/" + pageCount + ")");
                    }
                    isAtLastPage = (page == pageCount - 1);

                    // 🌟 提前预判提示：刚刚翻到底部时，如果发现预加载已准备就绪，友好提示用户
                    if (isAtLastPage) {
                        if (preloadedNextUri != null) {
                            Toast.makeText(this, "点击右侧加载下一卷：" + preloadedNextName, Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(this, "已经是最后一卷啦", Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .onLoad(nbPages -> {
                    if (!isEditMode) {
                        tvTopTitle.setText(currentFileName + " (" + (targetPageIndex + 1) + "/" + nbPages + ")");
                    }
                    isAtLastPage = (targetPageIndex == nbPages - 1);
                    rawModifiedTime = new SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()).format(new Date());

                    // 🌟 终极优化引擎发动：只要当前的 PDF 读取成功渲染出来，后台立刻去寻找下一卷！
                    preloadNextVolumeInfo();
                })
                .load();
    }

    // =========================================================================
    // 🌟 终极预加载引擎：在用户看当前卷时，后台默默去扫盘把下一卷的全部信息算好
    // =========================================================================
    private void preloadNextVolumeInfo() {
        preloadedNextUri = null;
        preloadedNextPath = "";
        preloadedNextName = "";

        new Thread(() -> {
            try {
                List<String> siblings = new ArrayList<>();

                if (parentUriStr != null && !parentUriStr.isEmpty()) {
                    DocumentFile folder = DocumentFile.fromTreeUri(this, Uri.parse(parentUriStr));
                    if (folder != null && folder.exists()) {
                        for (DocumentFile f : folder.listFiles()) {
                            if (!f.isDirectory() && f.getName() != null && f.getName().toLowerCase().endsWith(".pdf")) {
                                siblings.add(f.getName());
                            }
                        }
                    }
                }
                else if (pdfPath != null && !pdfPath.isEmpty()) {
                    File currentFile = new File(pdfPath);
                    File parentDir = currentFile.getParentFile();
                    if (parentDir != null && parentDir.exists() && parentDir.isDirectory()) {
                        File[] files = parentDir.listFiles();
                        if (files != null) {
                            for (File f : files) {
                                if (f.getName().toLowerCase().endsWith(".pdf")) {
                                    siblings.add(f.getName());
                                }
                            }
                        }
                    }
                }

                String nextFileName = SmartVolumeSniffer.findNextVolume(pdfName, siblings);

                if (nextFileName != null) {
                    if (parentUriStr != null && !parentUriStr.isEmpty()) {
                        DocumentFile folder = DocumentFile.fromTreeUri(this, Uri.parse(parentUriStr));
                        if (folder != null) {
                            for (DocumentFile f : folder.listFiles()) {
                                if (nextFileName.equals(f.getName())) {
                                    preloadedNextUri = f.getUri();
                                    preloadedNextPath = MainActivity.cleanPath(preloadedNextUri.getPath());
                                    preloadedNextName = nextFileName;
                                    break;
                                }
                            }
                        }
                    } else {
                        File nextFile = new File(new File(pdfPath).getParentFile(), nextFileName);
                        if (nextFile.exists()) {
                            preloadedNextUri = Uri.fromFile(nextFile);
                            preloadedNextPath = nextFile.getAbsolutePath();
                            preloadedNextName = nextFileName;
                        }
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }

    // 真正执行加载动作：由于在后台已经把数据全算好了，这里直接无脑读取，0延迟！
    private void executeLoadNextVolume() {
        Toast.makeText(this, "正在无缝加载: " + preloadedNextName, Toast.LENGTH_SHORT).show();

        pdfPath = preloadedNextPath;
        pdfName = preloadedNextName;
        pdfUri = preloadedNextUri;

        currentFileName = pdfName;
        if (currentFileName.toLowerCase().endsWith(".pdf")) {
            currentFileName = currentFileName.substring(0, currentFileName.length() - 4);
        }
        if (currentFileName.contains("-副本")) {
            currentFileName = currentFileName.split("-副本")[0];
        }

        if (dbHelper != null) {
            String time = new SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()).format(new Date());
            PdfItem nextItem = new PdfItem(pdfUri, pdfName, pdfPath, time, false);
            dbHelper.recordViewHistory(nextItem);
        }

        if (pdfOverlay != null) pdfOverlay.clearActions();
        updateFavoriteIconState();
        reloadPdfView(0);
    }
    // =========================================================================

    private void toggleEditMode(boolean enterEdit) {
        isEditMode = enterEdit;
        if (isEditMode) {
            tvTopTitle.setText("编辑模式");
            ivBackOrExit.setImageResource(android.R.drawable.ic_menu_close_clear_cancel);
            ivFavorite.setVisibility(View.GONE);
            ivEditMode.setVisibility(View.GONE);
            ivUndo.setVisibility(View.VISIBLE);
            ivSave.setVisibility(View.VISIBLE);

            dividerLine.setVisibility(View.VISIBLE);
            llEditToolsRow.setVisibility(View.VISIBLE);

            switchEditState(EditState.DRAG);
        } else {
            int currentPage = pdfView.getCurrentPage();
            int pageCount = pdfView.getPageCount();
            tvTopTitle.setText(currentFileName + " (" + (currentPage + 1) + "/" + pageCount + ")");
            ivBackOrExit.setImageResource(android.R.drawable.ic_menu_revert);

            ivUndo.setVisibility(View.GONE);
            ivSave.setVisibility(View.GONE);
            ivFavorite.setVisibility(View.VISIBLE);
            ivEditMode.setVisibility(View.VISIBLE);

            dividerLine.setVisibility(View.GONE);
            llEditToolsRow.setVisibility(View.GONE);
            llDoodleProperties.setVisibility(View.GONE);
            llTextProperties.setVisibility(View.GONE);

            if (pdfOverlay != null) {
                pdfOverlay.clearActions();
                pdfOverlay.setMode(PdfOverlayView.Mode.DRAG);
                pdfOverlay.setClickable(false);
                pdfOverlay.setFocusable(false);
            }

            if (pdfView != null) {
                pdfView.setSwipeEnabled(true);
            }
        }
    }

    private void switchEditState(EditState newState) {
        currentEditState = newState;

        ivToolDrag.setColorFilter(android.graphics.Color.parseColor("#999999"));
        ivToolDoodle.setColorFilter(android.graphics.Color.parseColor("#999999"));
        ivToolText.setColorFilter(android.graphics.Color.parseColor("#999999"));

        llDoodleProperties.setVisibility(View.GONE);
        llTextProperties.setVisibility(View.GONE);

        switch (newState) {
            case DRAG:
                ivToolDrag.setColorFilter(android.graphics.Color.parseColor("#4CAF50"));
                pdfView.setSwipeEnabled(true);
                if(pdfOverlay != null) {
                    pdfOverlay.setMode(PdfOverlayView.Mode.DRAG);
                    pdfOverlay.setClickable(false);
                    pdfOverlay.setFocusable(false);
                }
                break;
            case DOODLE:
                ivToolDoodle.setColorFilter(android.graphics.Color.parseColor("#4CAF50"));
                llDoodleProperties.setVisibility(View.VISIBLE);
                pdfView.setSwipeEnabled(false);
                if(pdfOverlay != null) {
                    pdfOverlay.setMode(PdfOverlayView.Mode.DOODLE);
                    pdfOverlay.setToolType(currentActiveTool);
                    pdfOverlay.setClickable(true);
                    pdfOverlay.setFocusable(true);
                }
                break;
            case TEXT:
                ivToolText.setColorFilter(android.graphics.Color.parseColor("#4CAF50"));
                llTextProperties.setVisibility(View.VISIBLE);
                pdfView.setSwipeEnabled(false);
                if(pdfOverlay != null) {
                    pdfOverlay.setMode(PdfOverlayView.Mode.TEXT);
                    pdfOverlay.setClickable(true);
                    pdfOverlay.setFocusable(true);
                }
                break;
        }
    }

    private void handleBackAction() {
        if (isEditMode) {
            new AlertDialog.Builder(this)
                    .setTitle("退出编辑")
                    .setMessage("确定要放弃未保存的修改并退出吗？")
                    .setPositiveButton("确定退出", (dialog, which) -> {
                        toggleEditMode(false);
                    })
                    .setNegativeButton("继续编辑", null)
                    .show();
        } else {
            finish();
        }
    }

    private void handleSaveAction() {
        new AlertDialog.Builder(this)
                .setTitle("保存修改")
                .setItems(new String[]{"覆盖原文件 (直接保存)", "另存为新文件 (副本)"}, (dialog, which) -> {
                    if (which == 0) {
                        executeOverwriteSave();
                    } else {
                        executeiTextFastMerge();
                    }
                })
                .show();
    }

    private void executeOverwriteSave() {
        if (pdfUri == null) return;
        if (pdfOverlay == null || pdfOverlay.isActionStackEmpty()) {
            Toast.makeText(this, "当前没有任何批注内容，无需保存", Toast.LENGTH_SHORT).show();
            return;
        }

        AlertDialog progressDialog = new AlertDialog.Builder(this)
                .setTitle("正在覆盖保存")
                .setMessage("请稍候，正在将批注烙印至原文件中...")
                .setCancelable(false)
                .create();
        progressDialog.show();

        final float pageWidth = pdfView.getPageSize(0).getWidth();
        final float pageHeight = pdfView.getPageSize(0).getHeight();
        final float spacing = pdfView.getSpacingPx();
        final int currentPage = pdfView.getCurrentPage();

        new Thread(() -> {
            try {
                InputStream fis = null;
                try {
                    if ("content".equals(pdfUri.getScheme())) {
                        fis = getContentResolver().openInputStream(pdfUri);
                    } else {
                        fis = new FileInputStream(new File(pdfUri.getPath()));
                    }
                } catch (Exception e) {
                    fis = new FileInputStream(new File(pdfPath));
                }
                if (fis == null) throw new Exception("源文件读取失败");

                PdfReader reader = new PdfReader(fis);

                File tempFile = new File(getCacheDir(), "temp_overwrite_" + System.currentTimeMillis() + ".pdf");
                FileOutputStream tempFos = new FileOutputStream(tempFile);

                PdfStamper stamper = new PdfStamper(reader, tempFos);
                int pageCount = reader.getNumberOfPages();

                for (int i = 1; i <= pageCount; i++) {
                    Bitmap pageCropBitmap = pdfOverlay.getPageAnnotationBitmap(i - 1, pageWidth, pageHeight, spacing);
                    if (pageCropBitmap != null) {
                        PdfContentByte overContent = stamper.getOverContent(i);
                        ByteArrayOutputStream stream = new ByteArrayOutputStream();
                        pageCropBitmap.compress(Bitmap.CompressFormat.PNG, 100, stream);
                        Image iTextImage = Image.getInstance(stream.toByteArray());

                        iTextImage.setAbsolutePosition(0, 0);
                        iTextImage.scaleAbsolute(reader.getPageSize(i).getWidth(), reader.getPageSize(i).getHeight());

                        overContent.addImage(iTextImage);
                        pageCropBitmap.recycle();
                    }
                }

                stamper.close();
                reader.close();
                fis.close();

                OutputStream originalOs = null;
                try {
                    if ("content".equals(pdfUri.getScheme())) {
                        originalOs = getContentResolver().openOutputStream(pdfUri, "rwt");
                        if (originalOs == null) originalOs = getContentResolver().openOutputStream(pdfUri);
                    } else {
                        originalOs = new FileOutputStream(new File(pdfPath));
                    }
                } catch (Exception e) {
                    if (pdfPath != null && !pdfPath.isEmpty()) {
                        originalOs = new FileOutputStream(new File(pdfPath));
                    }
                }

                if (originalOs == null) throw new Exception("没有原文件的写入/覆盖权限，请使用另存为功能");

                FileInputStream tempFis = new FileInputStream(tempFile);
                byte[] buffer = new byte[8192];
                int len;
                while ((len = tempFis.read(buffer)) > 0) {
                    originalOs.write(buffer, 0, len);
                }
                originalOs.flush();
                originalOs.close();
                tempFis.close();

                tempFile.delete();

                if (pdfPath != null && !pdfPath.isEmpty()) {
                    MediaScannerConnection.scanFile(this, new String[]{pdfPath}, new String[]{"application/pdf"}, null);
                }

                runOnUiThread(() -> {
                    progressDialog.dismiss();
                    if (dbHelper != null) {
                        dbHelper.updateLastModifiedTime(pdfUri.toString());
                    }
                    Toast.makeText(this, "保存成功！已覆盖原文件", Toast.LENGTH_LONG).show();

                    toggleEditMode(false);
                    reloadPdfView(currentPage);
                });

            } catch (Exception e) {
                e.printStackTrace();
                runOnUiThread(() -> {
                    progressDialog.dismiss();
                    Toast.makeText(this, "覆盖失败：" + e.getMessage() + "\n(建议使用另存为副本)", Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    private void executeiTextFastMerge() {
        if (pdfUri == null) return;
        if (pdfOverlay == null || pdfOverlay.isActionStackEmpty()) {
            Toast.makeText(this, "当前没有任何批注内容，无需另存", Toast.LENGTH_SHORT).show();
            return;
        }

        AlertDialog progressDialog = new AlertDialog.Builder(this)
                .setTitle("正在极速切页保存")
                .setMessage("请稍候，文件正在逐页拼装写入...")
                .setCancelable(false)
                .create();
        progressDialog.show();

        final float pageWidth = pdfView.getPageSize(0).getWidth();
        final float pageHeight = pdfView.getPageSize(0).getHeight();
        final float spacing = pdfView.getSpacingPx();

        new Thread(() -> {
            try {
                InputStream fis = null;
                try {
                    if ("content".equals(pdfUri.getScheme())) {
                        fis = getContentResolver().openInputStream(pdfUri);
                    } else {
                        fis = new FileInputStream(new File(pdfUri.getPath()));
                    }
                } catch (Exception e) {
                    fis = new FileInputStream(new File(pdfPath));
                }
                if (fis == null) throw new Exception("源文件读取失败");

                PdfReader reader = new PdfReader(fis);

                String timeStamp = new SimpleDateFormat("HHmmss", Locale.getDefault()).format(new Date());
                String targetCopyName = currentFileName + "-副本-" + timeStamp + ".pdf";

                File destFile = new File(getExternalFilesDir(null), targetCopyName);
                FileOutputStream fos = new FileOutputStream(destFile);

                PdfStamper stamper = new PdfStamper(reader, fos);
                int pageCount = reader.getNumberOfPages();

                for (int i = 1; i <= pageCount; i++) {
                    Bitmap pageCropBitmap = pdfOverlay.getPageAnnotationBitmap(i - 1, pageWidth, pageHeight, spacing);

                    if (pageCropBitmap != null) {
                        PdfContentByte overContent = stamper.getOverContent(i);
                        ByteArrayOutputStream stream = new ByteArrayOutputStream();
                        pageCropBitmap.compress(Bitmap.CompressFormat.PNG, 100, stream);
                        Image iTextImage = Image.getInstance(stream.toByteArray());

                        iTextImage.setAbsolutePosition(0, 0);
                        iTextImage.scaleAbsolute(reader.getPageSize(i).getWidth(), reader.getPageSize(i).getHeight());

                        overContent.addImage(iTextImage);
                        pageCropBitmap.recycle();
                    }
                }

                stamper.close();
                reader.close();
                fis.close();

                runOnUiThread(() -> {
                    progressDialog.dismiss();
                    if (dbHelper != null) {
                        try {
                            PdfItem copyItem = new PdfItem(Uri.fromFile(destFile), targetCopyName, destFile.getAbsolutePath(), rawModifiedTime, false);
                            dbHelper.insertOrUpdateHomeItem(copyItem);
                        } catch (Exception ignore) {}
                    }
                    Toast.makeText(this, "烙印成功！副本已保存并推至大厅", Toast.LENGTH_LONG).show();
                    if (pdfOverlay != null) pdfOverlay.clearActions();
                    toggleEditMode(false);
                });

            } catch (Exception e) {
                e.printStackTrace();
                runOnUiThread(() -> {
                    progressDialog.dismiss();
                    Toast.makeText(this, "保存失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    private void toggleMenuVisibility() {
        if (isEditMode) return;
        isMenuVisible = !isMenuVisible;

        if (isMenuVisible) {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);

            topMenuLayout.setVisibility(View.VISIBLE);
            topMenuLayout.setTranslationY(-topMenuLayout.getHeight());
            topMenuLayout.animate().translationY(0).setDuration(250).start();
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            );

            topMenuLayout.animate().translationY(-topMenuLayout.getHeight()).setDuration(250)
                    .withEndAction(() -> topMenuLayout.setVisibility(View.GONE)).start();
        }
    }

    private void updateFavoriteIconState() {
        if (dbHelper == null || pdfPath == null) return;
        boolean isFav = dbHelper.isFavorite(pdfPath, pdfName);
        ivFavorite.setImageResource(isFav ? R.drawable.ic_star_fill : R.drawable.ic_star_circle);
    }

    private void handleFavoriteToggle() {
        if (dbHelper == null) return;
        boolean isNowFav = dbHelper.toggleFavorite(pdfPath, pdfName, pdfUri.toString(), rawModifiedTime);
        ivFavorite.setImageResource(isNowFav ? R.drawable.ic_star_fill : R.drawable.ic_star_circle);
        Toast.makeText(this, isNowFav ? "已加入收藏夹" : "已取消收藏", Toast.LENGTH_SHORT).show();
    }
}