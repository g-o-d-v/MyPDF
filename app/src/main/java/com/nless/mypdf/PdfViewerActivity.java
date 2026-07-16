package com.nless.mypdf;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Bundle;
import android.view.ActionMode;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
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
import com.nless.pdf_search_engine.androidpdfviewer.AndroidPdfViewerAdapter;
import com.nless.pdf_search_engine.androidpdfviewer.PdfOverlaySearchHighlighter;
import com.nless.pdf_search_engine.core.PdfSearchCallback;
import com.nless.pdf_search_engine.core.PdfSearchManager;
import com.nless.pdf_search_engine.core.PdfSearchMode;
import com.nless.pdf_search_engine.core.PdfSearchOptions;
import com.nless.pdf_search_engine.core.PdfSearchResult;
import com.nless.pdf_search_engine.core.PdfSearchSource;
//import com.github.barteksc.pdfviewer.scroll.DefaultScrollHandle; // 🌟 引入原生滑动条组件


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

    // 异步预加载引擎参数
    private Uri preloadedNextUri = null;
    private String preloadedNextPath = "";
    private String preloadedNextName = "";

    private PdfDbHelper dbHelper;
    private PDFView pdfView;
    private PdfOverlayView pdfOverlay;
    private LinearLayout topMenuLayout;
    private LinearLayout annotationPanelContainer;
    private View pdfContentFrame;

    private TextView btnNextVolume;

    private ImageView ivBackOrExit, ivUndo, ivSave, ivMore;
    private TextView tvTopTitle, tvAnnotationPageWarning;
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

    // 🌟 新增：用来存储阅读进度的本地轻量级文件（不碰数据库）
    private SharedPreferences progressPrefs;

    private final List<PdfOverlayView.SearchHighlight> currentSearchHighlights = new ArrayList<>();
    private PdfSearchManager pdfSearchManager;
    private AndroidPdfViewerAdapter androidPdfViewerAdapter;
    private PdfOverlaySearchHighlighter overlaySearchHighlighter;

    private PdfTextSelectionRepository textSelectionRepository;
    private ActionMode textSelectionActionMode;
    private int textSelectionRequestId = 0;

    private static final int TEXT_ACTION_COPY = 201;
    private static final int TEXT_ACTION_SELECT_ALL = 202;
    private static final int TEXT_ACTION_CANCEL = 203;

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
        pdfSearchManager = new PdfSearchManager(this);
        // 🌟 初始化轻量级进度存储
        progressPrefs = getSharedPreferences("pdf_reading_progress", MODE_PRIVATE);

        initViews();
        setupListeners();
        loadPdfFromIntent();
        updateAllColorBlocksUI();
    }

    private void initViews() {
        pdfView = findViewById(R.id.pdfView);
        pdfOverlay = findViewById(R.id.pdfOverlay);
        topMenuLayout = findViewById(R.id.top_menu_layout);
        annotationPanelContainer = findViewById(R.id.annotation_panel_container);
        pdfContentFrame = findViewById(R.id.pdf_content_frame);

        ivBackOrExit = findViewById(R.id.iv_back_or_exit);
        tvTopTitle = findViewById(R.id.tv_top_title);
        tvAnnotationPageWarning = findViewById(R.id.tv_annotation_page_warning);
        ivUndo = findViewById(R.id.iv_undo);
        ivSave = findViewById(R.id.iv_save);
        ivMore = findViewById(R.id.iv_more);
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
        annotationPanelContainer.setVisibility(View.GONE);
        llEditToolsRow.setVisibility(View.VISIBLE);

        if (pdfOverlay != null) {
            pdfOverlay.setPdfView(pdfView);
            pdfOverlay.setOnTextSelectionChangedListener((active, selectedText) -> {
                if (active && textSelectionActionMode != null) {
                    textSelectionActionMode.invalidateContentRect();
                }
            });
        }

        btnNextVolume = new TextView(this);
        btnNextVolume.setTextColor(android.graphics.Color.WHITE);
        btnNextVolume.setTextSize(14f);
        btnNextVolume.setPadding(60, 30, 60, 30);
        btnNextVolume.setGravity(android.view.Gravity.CENTER);

        GradientDrawable gd = new GradientDrawable();
        gd.setColor(0xDD333333);
        gd.setCornerRadius(100f);
        gd.setStroke(2, 0x55FFFFFF);
        btnNextVolume.setBackground(gd);
        btnNextVolume.setElevation(15f);
        btnNextVolume.setVisibility(View.GONE);

        btnNextVolume.setOnClickListener(v -> {
            if (preloadedNextUri != null) executeLoadNextVolume();
        });

        android.widget.FrameLayout contentRoot = findViewById(android.R.id.content);
        android.widget.FrameLayout.LayoutParams params = new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
        );
        params.gravity = android.view.Gravity.BOTTOM | android.view.Gravity.END;
        params.bottomMargin = 150;
        params.rightMargin = 60;
        contentRoot.addView(btnNextVolume, params);

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
        ivMore.setOnClickListener(this::showMainMenu);
        ivSave.setOnClickListener(v -> handleSaveAction());

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

    private void updateNextVolumeButtonState() {
        if (isAtLastPage && preloadedNextUri != null && !isEditMode) {
            if (btnNextVolume.getVisibility() != View.VISIBLE) {
                btnNextVolume.setText("下一卷：\n" + preloadedNextName + "  〉");
                btnNextVolume.setAlpha(0f);
                btnNextVolume.setTranslationX(150f);
                btnNextVolume.setVisibility(View.VISIBLE);
                btnNextVolume.animate().alpha(1f).translationX(0f).setDuration(350).start();
            }
        } else {
            if (btnNextVolume.getVisibility() == View.VISIBLE) {
                btnNextVolume.animate().alpha(0f).translationX(150f).setDuration(250)
                        .withEndAction(() -> btnNextVolume.setVisibility(View.GONE)).start();
            }
        }
    }

    private void showPdfSearchDialog() {
        final android.widget.EditText input = new android.widget.EditText(this);
        input.setHint("输入搜索关键词");
        input.setSingleLine(true);
        new AlertDialog.Builder(this)
                .setTitle("搜索 PDF 内容")
                .setView(input)
                .setPositiveButton("搜索", (dialog, which) -> {
                    String keyword = input.getText().toString().trim();
                    if (!keyword.isEmpty()) showSearchModeDialog(keyword);
                })
                .setNeutralButton("清除高亮", (dialog, which) -> clearPdfSearch())
                .setNegativeButton("取消", null)
                .show();
    }

    private void showSearchModeDialog(String keyword) {
        String[] modes = {
                "智能当前页（文本优先，失败后 OCR）",
                "文本层全文搜索",
                "智能全文搜索（文本优先）",
                "OCR 当前页"
        };
        new AlertDialog.Builder(this)
                .setTitle("选择搜索模式")
                .setItems(modes, (dialog, which) -> {
                    if (which == 0) searchWithEngine(keyword, PdfSearchMode.TEXT_THEN_OCR, true);
                    else if (which == 1) searchWithEngine(keyword, PdfSearchMode.TEXT_ONLY, false);
                    else if (which == 2) searchWithEngine(keyword, PdfSearchMode.TEXT_THEN_OCR, false);
                    else searchWithEngine(keyword, PdfSearchMode.OCR_ONLY, true);
                }).show();
    }

    private void searchWithEngine(String keyword, PdfSearchMode mode, boolean currentPageOnly) {
        if (pdfSearchManager == null || pdfUri == null || pdfView == null || pdfOverlay == null) return;
        clearPdfSearch(false);
        PdfSearchOptions options = new PdfSearchOptions();
        options.mode = mode;
        options.currentPageOnly = currentPageOnly;
        options.currentPage = pdfView.getCurrentPage();
        options.allowFullDocumentOcr = false;
        options.ocrRenderWidth = 1280;
        options.fallbackToOcrWhenTextNotFound = true;

        pdfSearchManager.search(pdfUri, keyword, options, new PdfSearchCallback() {
            @Override public void onSearchStarted(String value) {
                runOnUiThread(() -> Toast.makeText(PdfViewerActivity.this, "正在搜索：" + value, Toast.LENGTH_SHORT).show());
            }
            @Override public void onSearchProgress(int currentPage, int totalPage, PdfSearchSource source) {
                runOnUiThread(() -> tvTopTitle.setText((source == PdfSearchSource.OCR ? "OCR" : "文本") + "搜索中…"));
            }
            @Override public void onSearchCompleted(List<PdfSearchResult> results) {
                runOnUiThread(() -> showSearchResults(keyword, results));
            }
            @Override public void onSearchFailed(Throwable error) {
                runOnUiThread(() -> Toast.makeText(PdfViewerActivity.this, "搜索失败：" + safeMessage(error), Toast.LENGTH_LONG).show());
            }
            @Override public void onSearchCancelled() { }
        });
    }

    private void showSearchResults(String keyword, List<PdfSearchResult> results) {
        int page = pdfView.getCurrentPage();
        tvTopTitle.setText(currentFileName + " (" + (page + 1) + "/" + pdfView.getPageCount() + ")");
        if (results == null || results.isEmpty()) {
            Toast.makeText(this, "未找到：" + keyword, Toast.LENGTH_SHORT).show();
            return;
        }
        if (androidPdfViewerAdapter == null) androidPdfViewerAdapter = new AndroidPdfViewerAdapter(pdfView);
        if (overlaySearchHighlighter == null) overlaySearchHighlighter = new PdfOverlaySearchHighlighter();
        List<AndroidPdfViewerAdapter.ViewerSearchHighlight> converted = androidPdfViewerAdapter.convertResults(results);
        overlaySearchHighlighter.setHighlights(converted);
        currentSearchHighlights.clear();
        for (AndroidPdfViewerAdapter.ViewerSearchHighlight item : converted) {
            if (item != null && item.rectInDoc != null) {
                currentSearchHighlights.add(new PdfOverlayView.SearchHighlight(item.pageIndex, item.rectInDoc));
            }
        }
        pdfOverlay.setSearchHighlights(currentSearchHighlights);
        AndroidPdfViewerAdapter.ViewerSearchHighlight first = overlaySearchHighlighter.getCurrent();
        if (first != null) pdfView.jumpTo(first.pageIndex, true);
        Toast.makeText(this, "找到 " + currentSearchHighlights.size() + " 处", Toast.LENGTH_SHORT).show();
    }

    private void clearPdfSearch() { clearPdfSearch(true); }

    private void clearPdfSearch(boolean showToast) {
        currentSearchHighlights.clear();
        if (overlaySearchHighlighter != null) overlaySearchHighlighter.clear();
        if (pdfOverlay != null) pdfOverlay.clearSearchHighlights();
        if (showToast) Toast.makeText(this, "已清除搜索高亮", Toast.LENGTH_SHORT).show();
    }

    private void handlePdfLongPress(MotionEvent event) {
        if (event == null || isEditMode || pdfOverlay == null || pdfView == null || pdfUri == null) {
            return;
        }

        PdfOverlayView.PageHit hit = pdfOverlay.locateViewPoint(event.getX(), event.getY());
        if (hit == null) return;

        if (textSelectionRepository == null) resetTextSelectionRepository();
        if (textSelectionRepository == null) return;

        final int requestId = ++textSelectionRequestId;
        Toast.makeText(this, "正在读取当前页文本层…", Toast.LENGTH_SHORT).show();
        textSelectionRepository.requestPage(hit.pageIndex, new PdfTextSelectionRepository.Callback() {
            @Override
            public void onLoaded(PdfTextPage page) {
                runOnUiThread(() -> {
                    if (requestId != textSelectionRequestId || isFinishing() || isDestroyed()) return;
                    if (page == null || page.isEmpty()) {
                        Toast.makeText(PdfViewerActivity.this,
                                "当前页面没有可选择的文本层",
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    boolean selected = pdfOverlay.beginTextSelection(page, hit.pageX, hit.pageY);
                    if (!selected) {
                        Toast.makeText(PdfViewerActivity.this,
                                "请长按文字本身以开始选择",
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    showTextSelectionActionMode();
                });
            }

            @Override
            public void onError(Throwable error) {
                runOnUiThread(() -> {
                    if (requestId != textSelectionRequestId || isFinishing() || isDestroyed()) return;
                    Toast.makeText(PdfViewerActivity.this,
                            "文本层读取失败：" + safeMessage(error),
                            Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void resetTextSelectionRepository() {
        textSelectionRequestId++;
        clearTextSelectionUi();
        if (textSelectionRepository != null) {
            textSelectionRepository.close();
            textSelectionRepository = null;
        }
        if (pdfUri != null) {
            textSelectionRepository = new PdfTextSelectionRepository(this, pdfUri, pdfPath);
        }
    }

    private void showTextSelectionActionMode() {
        if (pdfOverlay == null) return;
        if (textSelectionActionMode == null) {
            textSelectionActionMode = pdfOverlay.startActionMode(new ActionMode.Callback2() {
                @Override
                public boolean onCreateActionMode(ActionMode mode, Menu menu) {
                    menu.add(Menu.NONE, TEXT_ACTION_COPY, 0, "复制")
                            .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
                    menu.add(Menu.NONE, TEXT_ACTION_SELECT_ALL, 1, "全选")
                            .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
                    menu.add(Menu.NONE, TEXT_ACTION_CANCEL, 2, "取消")
                            .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
                    return true;
                }

                @Override
                public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
                    return false;
                }

                @Override
                public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
                    switch (item.getItemId()) {
                        case TEXT_ACTION_COPY:
                            copySelectedPdfText();
                            mode.finish();
                            return true;
                        case TEXT_ACTION_SELECT_ALL:
                            if (pdfOverlay != null) {
                                pdfOverlay.selectAllTextOnPage();
                                mode.invalidateContentRect();
                            }
                            return true;
                        case TEXT_ACTION_CANCEL:
                            mode.finish();
                            return true;
                        default:
                            return false;
                    }
                }

                @Override
                public void onDestroyActionMode(ActionMode mode) {
                    textSelectionActionMode = null;
                    if (pdfOverlay != null && pdfOverlay.hasTextSelection()) {
                        pdfOverlay.clearTextSelection();
                    }
                }

                @Override
                public void onGetContentRect(ActionMode mode, View view, Rect outRect) {
                    if (pdfOverlay == null || !pdfOverlay.getTextSelectionBoundsInView(outRect)) {
                        super.onGetContentRect(mode, view, outRect);
                    }
                }
            }, ActionMode.TYPE_FLOATING);
        }
        if (textSelectionActionMode != null) {
            textSelectionActionMode.invalidateContentRect();
        }
    }

    private void copySelectedPdfText() {
        if (pdfOverlay == null) return;
        String selected = pdfOverlay.getSelectedText();
        if (selected == null || selected.isEmpty()) return;
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("PDF 文本", selected));
            Toast.makeText(this, "已复制所选文本", Toast.LENGTH_SHORT).show();
        }
    }

    private void clearTextSelectionUi() {
        textSelectionRequestId++;
        if (textSelectionActionMode != null) {
            textSelectionActionMode.finish();
        } else if (pdfOverlay != null && pdfOverlay.hasTextSelection()) {
            pdfOverlay.clearTextSelection();
        }
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
            pdfOverlay.setOnTextRequestListener((pageIndex, pageX, pageY) -> {
                final android.widget.EditText input = new android.widget.EditText(this);
                input.setSingleLine(false);
                input.setMinLines(2);
                input.setHint("输入要附加到 PDF 的文本批注");
                new AlertDialog.Builder(this)
                        .setTitle("添加文本批注")
                        .setView(input)
                        .setPositiveButton("确定", (dialog, which) -> {
                            String text = input.getText().toString();
                            if (!text.trim().isEmpty()) {
                                int size = seekTextSize.getProgress() + 10;
                                pdfOverlay.addTextAction(
                                        text,
                                        pageIndex,
                                        pageX,
                                        pageY,
                                        currentDrawColor,
                                        size,
                                        isTextBold,
                                        isTextItalic,
                                        isTextUnderline
                                );
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
            resetTextSelectionRepository();

            // 🌟 读取无侵入式的历史阅读进度
            int lastReadPage = progressPrefs.getInt(pdfUri.toString(), 0);
            reloadPdfView(lastReadPage);
        } else {
            finish();
        }
    }

    private void reloadPdfView(int targetPageIndex) {
        pdfView.fromUri(pdfUri)
                .defaultPage(targetPageIndex)
                .enableSwipe(true)
                .swipeHorizontal(false)
//                .scrollHandle(new DefaultScrollHandle(this))
                .enableAntialiasing(false)
                .pageFitPolicy(com.github.barteksc.pdfviewer.util.FitPolicy.WIDTH)
                .fitEachPage(true)
                .autoSpacing(false)
                .pageSnap(false)
                .pageFling(false)
                .onTap(e -> {
                    if (pdfOverlay != null && pdfOverlay.hasTextSelection()) {
                        clearTextSelectionUi();
                        return true;
                    }
                    if (!isEditMode || currentEditState == EditState.DRAG) {
                        toggleMenuVisibility();
                    }
                    return true;
                })
                .onLongPress(this::handlePdfLongPress)
                .onPageChange((page, pageCount) -> {
                    if (!isEditMode) {
                        tvTopTitle.setText(currentFileName + " (" + (page + 1) + "/" + pageCount + ")");
                    }
                    isAtLastPage = (page == pageCount - 1);
                    updateNextVolumeButtonState();

                    // 实时静默保存用户的阅读进度
                    if (progressPrefs != null && pdfUri != null) {
                        progressPrefs.edit().putInt(pdfUri.toString(), page).apply();
                    }
                })
                // =========================================================================
                // 🌟 新增：物理触底探测器，完美解决“短页面导致无法判定为最后一页”的终极 Bug
                // =========================================================================
                .onPageScroll((page, positionOffset) -> {
                    if (textSelectionActionMode != null) {
                        textSelectionActionMode.invalidateContentRect();
                    }
                    // canScrollVertically(1) 用于检测 View 是否还能向下滚动
                    // 返回 false 说明已经被死死卡在最底部了，一像素都滚不动了
                    boolean isPhysicallyAtBottom = !pdfView.canScrollVertically(1);
                    int pageCount = pdfView.getPageCount();

                    // 如果物理触底了，但系统页码算错了（以为还没到最后）
                    if (isPhysicallyAtBottom && !isAtLastPage) {
                        isAtLastPage = true;

                        // 1. 强制修正顶部标题的页码显示
                        if (!isEditMode) {
                            tvTopTitle.setText(currentFileName + " (" + pageCount + "/" + pageCount + ")");
                        }

                        // 2. 强制唤醒“下一卷”悬浮胶囊
                        updateNextVolumeButtonState();

                        // 3. 强制把阅读进度记录为 100% 完结
                        if (progressPrefs != null && pdfUri != null) {
                            progressPrefs.edit().putInt(pdfUri.toString(), pageCount - 1).apply();
                        }
                    }
                    // 补充防御机制：如果用户往回滑，离开了物理底部，并且系统当前页码确实不是最后一页，那就隐藏胶囊
                    else if (!isPhysicallyAtBottom && isAtLastPage && page < pageCount - 1) {
                        isAtLastPage = false;
                        updateNextVolumeButtonState();
                    }
                })
                // =========================================================================
                .onLoad(nbPages -> {
                    if (pdfOverlay != null) {
                        pdfOverlay.refreshPageGeometry();
                    }
                    if (!isEditMode) {
                        tvTopTitle.setText(currentFileName + " (" + (targetPageIndex + 1) + "/" + nbPages + ")");
                    }
                    isAtLastPage = (targetPageIndex == nbPages - 1);
                    rawModifiedTime = new SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()).format(new Date());

                    updateNextVolumeButtonState();
                    preloadNextVolumeInfo();
                })
                .load();
    }

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

                runOnUiThread(this::updateNextVolumeButtonState);

            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }

    private void executeLoadNextVolume() {
        btnNextVolume.setVisibility(View.GONE);
        Toast.makeText(this, "正在无缝加载: " + preloadedNextName, Toast.LENGTH_SHORT).show();

        pdfPath = preloadedNextPath;
        pdfName = preloadedNextName;
        pdfUri = preloadedNextUri;
        resetTextSelectionRepository();

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

        if (pdfOverlay != null) {
            pdfOverlay.clearActions();
        }


        // 🌟 连卷时，不要读历史进度，强制从 0 (第1页) 开始看新的一卷！
        reloadPdfView(0);
    }

    private void toggleEditMode(boolean enterEdit) {
        isEditMode = enterEdit;

        updateNextVolumeButtonState();

        if (isEditMode) {
            clearTextSelectionUi();
            isMenuVisible = true;
            topMenuLayout.animate().cancel();
            topMenuLayout.setTranslationY(0f);
            topMenuLayout.setVisibility(View.VISIBLE);

            tvTopTitle.setText("批注模式");
            ivBackOrExit.setImageResource(android.R.drawable.ic_menu_close_clear_cancel);
            ivUndo.setVisibility(View.VISIBLE);
            ivSave.setVisibility(View.VISIBLE);

            annotationPanelContainer.setVisibility(View.VISIBLE);
            dividerLine.setVisibility(View.VISIBLE);
            llEditToolsRow.setVisibility(View.VISIBLE);
            tvAnnotationPageWarning.setVisibility(View.VISIBLE);

            switchEditState(EditState.DRAG);
        } else {
            int currentPage = pdfView.getCurrentPage();
            int pageCount = pdfView.getPageCount();
            tvTopTitle.setText(currentFileName + " (" + (currentPage + 1) + "/" + pageCount + ")");
            ivBackOrExit.setImageResource(android.R.drawable.ic_menu_revert);

            ivUndo.setVisibility(View.GONE);
            ivSave.setVisibility(View.GONE);

            llDoodleProperties.setVisibility(View.GONE);
            llTextProperties.setVisibility(View.GONE);
            annotationPanelContainer.setVisibility(View.GONE);

            if (pdfOverlay != null) {
                pdfOverlay.clearActions();
                pdfOverlay.setMode(PdfOverlayView.Mode.DRAG);
                pdfOverlay.setClickable(false);
                pdfOverlay.setFocusable(false);
            }

            if (pdfView != null) {
                pdfView.setSwipeEnabled(true);
            }
            schedulePdfViewportRelayout();
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

        schedulePdfViewportRelayout();
    }

    /**
     * 批注一级/二级菜单展开或收起后，重新计算 PDF 内容框。
     *
     * <p>菜单是 PDF 内容框的同级 View，不通过 translation、PopupWindow 或覆盖层显示。
     * 这里在新高度生效后恢复原来的文档滚动比例，并要求 PDFView 重新装载可见页面，
     * 避免第一页顶部或当前页上沿停留在旧视口之外。</p>
     */
    private void schedulePdfViewportRelayout() {
        if (pdfContentFrame == null || pdfView == null || pdfOverlay == null) {
            return;
        }

        final float positionOffset;
        try {
            positionOffset = pdfView.getPageCount() > 0 ? pdfView.getPositionOffset() : 0f;
        } catch (Exception ignored) {
            return;
        }

        pdfContentFrame.requestLayout();
        pdfView.requestLayout();
        pdfOverlay.requestLayout();

        // 等父布局真正完成高度调整，再恢复位置和刷新渲染/批注坐标。
        pdfContentFrame.post(() -> pdfContentFrame.post(() -> {
            if (isFinishing() || pdfView.getPageCount() <= 0) {
                return;
            }
            try {
                pdfView.setPositionOffset(positionOffset, false);
                pdfView.loadPages();
            } catch (Exception ignored) {
                // PDF 尚在异步加载时只刷新布局；onLoad 后会重新建立页面数据。
            }
            pdfOverlay.refreshPageGeometry();
            pdfOverlay.invalidate();
            pdfView.invalidate();
        }));
    }

    private void handleBackAction() {
        if (isEditMode) {
            new AlertDialog.Builder(this)
                    .setTitle("退出批注模式")
                    .setMessage("确定要放弃未保存的批注并退出吗？")
                    .setPositiveButton("确定退出", (dialog, which) -> {
                        toggleEditMode(false);
                    })
                    .setNegativeButton("继续批注", null)
                    .show();
        } else {
            finish();
        }
    }

    private void handleSaveAction() {
        new AlertDialog.Builder(this)
                .setTitle("保存批注")
                .setItems(new String[]{"覆盖原文件 (直接保存)", "另存为新文件 (副本)"}, (dialog, which) -> {
                    if (which == 0) {
                        executeOverwriteSave();
                    } else {
                        executeSaveCopy();
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
                .setTitle("正在写入批注")
                .setMessage("请稍候，正在使用 PdfBox-Android 写入矢量图形和文本...")
                .setCancelable(false)
                .create();
        progressDialog.show();

        final List<PdfOverlayView.AnnotationAction> actions = pdfOverlay.getActionsSnapshot();
        final List<PdfOverlayView.PageMetrics> pageMetrics = pdfOverlay.getPageMetricsSnapshot();
        final int currentPage = pdfView.getCurrentPage();

        new Thread(() -> {
            File tempFile = new File(getCacheDir(), "temp_overwrite_" + System.currentTimeMillis() + ".pdf");
            try (InputStream source = openPdfInputStream();
                 FileOutputStream tempOut = new FileOutputStream(tempFile)) {
                PdfBoxAnnotationWriter.write(source, tempOut, actions, pageMetrics);

                try (InputStream tempIn = new FileInputStream(tempFile);
                     OutputStream originalOut = openPdfOutputStream()) {
                    if (originalOut == null) throw new Exception("没有原文件的写入权限，请使用另存为副本");
                    copyStream(tempIn, originalOut);
                    originalOut.flush();
                }

                if (pdfPath != null && !pdfPath.isEmpty()) {
                    MediaScannerConnection.scanFile(this, new String[]{pdfPath}, new String[]{"application/pdf"}, null);
                }

                runOnUiThread(() -> {
                    progressDialog.dismiss();
                    if (dbHelper != null) dbHelper.updateLastModifiedTime(pdfUri.toString());
                    Toast.makeText(this, "批注保存成功，已覆盖原文件", Toast.LENGTH_LONG).show();
                    toggleEditMode(false);
                    resetTextSelectionRepository();
                    reloadPdfView(currentPage);
                });
            } catch (Exception e) {
                e.printStackTrace();
                runOnUiThread(() -> {
                    progressDialog.dismiss();
                    Toast.makeText(this, "覆盖失败：" + safeMessage(e) +
                        "\n建议改用另存为副本", Toast.LENGTH_LONG).show();
                });
            } finally {
                if (tempFile.exists()) tempFile.delete();
            }
        }).start();
    }

    private void executeSaveCopy() {
        if (pdfUri == null) return;
        if (pdfOverlay == null || pdfOverlay.isActionStackEmpty()) {
            Toast.makeText(this, "当前没有任何批注内容，无需另存", Toast.LENGTH_SHORT).show();
            return;
        }

        AlertDialog progressDialog = new AlertDialog.Builder(this)
                .setTitle("正在另存批注副本")
                .setMessage("请稍候，正在使用 PdfBox-Android 写入批注...")
                .setCancelable(false)
                .create();
        progressDialog.show();

        final List<PdfOverlayView.AnnotationAction> actions = pdfOverlay.getActionsSnapshot();
        final List<PdfOverlayView.PageMetrics> pageMetrics = pdfOverlay.getPageMetricsSnapshot();

        new Thread(() -> {
            String timeStamp = new SimpleDateFormat("HHmmss", Locale.getDefault()).format(new Date());
            String baseName = currentFileName == null ? "未命名" : currentFileName.replaceFirst("(?i)\\.pdf$", "");
            String targetCopyName = baseName + "-批注副本-" + timeStamp + ".pdf";
            File destFile = new File(getExternalFilesDir(null), targetCopyName);

            try (InputStream source = openPdfInputStream();
                 FileOutputStream target = new FileOutputStream(destFile)) {
                PdfBoxAnnotationWriter.write(source, target, actions, pageMetrics);

                runOnUiThread(() -> {
                    progressDialog.dismiss();
                    if (dbHelper != null) {
                        try {
                            PdfItem copyItem = new PdfItem(Uri.fromFile(destFile), targetCopyName,
                                    destFile.getAbsolutePath(), rawModifiedTime, false);
                            dbHelper.insertOrUpdateHomeItem(copyItem);
                        } catch (Exception ignore) {}
                    }
                    Toast.makeText(this, "批注副本保存成功，已加入首页", Toast.LENGTH_LONG).show();
                    if (pdfOverlay != null) pdfOverlay.clearActions();
                    toggleEditMode(false);
                });
            } catch (Exception e) {
                e.printStackTrace();
                if (destFile.exists()) destFile.delete();
                runOnUiThread(() -> {
                    progressDialog.dismiss();
                    Toast.makeText(this, "保存失败：" + safeMessage(e), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    private InputStream openPdfInputStream() throws Exception {
        InputStream input = null;
        if (pdfUri != null && "content".equals(pdfUri.getScheme())) {
            input = getContentResolver().openInputStream(pdfUri);
        } else if (pdfUri != null && pdfUri.getPath() != null) {
            File f = new File(pdfUri.getPath());
            if (f.isFile()) input = new FileInputStream(f);
        }
        if (input == null && pdfPath != null && !pdfPath.isEmpty()) {
            input = new FileInputStream(new File(pdfPath));
        }
        if (input == null) throw new Exception("源 PDF 读取失败");
        return input;
    }

    private OutputStream openPdfOutputStream() throws Exception {
        if (pdfUri != null && "content".equals(pdfUri.getScheme())) {
            OutputStream out = getContentResolver().openOutputStream(pdfUri, "rwt");
            return out != null ? out : getContentResolver().openOutputStream(pdfUri);
        }
        if (pdfPath != null && !pdfPath.isEmpty()) return new FileOutputStream(new File(pdfPath));
        if (pdfUri != null && pdfUri.getPath() != null) return new FileOutputStream(new File(pdfUri.getPath()));
        return null;
    }

    private static void copyStream(InputStream in, OutputStream out) throws Exception {
        byte[] buffer = new byte[64 * 1024];
        int read;
        while ((read = in.read(buffer)) >= 0) out.write(buffer, 0, read);
    }

    private static String safeMessage(Throwable error) {
        String message = error == null ? null : error.getMessage();
        return message == null || message.trim().isEmpty() ? "未知错误" : message;
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

    private void showMainMenu(View anchor) {
        android.widget.PopupMenu popup = new android.widget.PopupMenu(this, anchor);
        if (isEditMode) {
            popup.getMenu().add(0, 10, 0, "保存批注");
            popup.getMenu().add(0, 11, 1, "退出批注模式");
        } else {
            popup.getMenu().add(0, 1, 0, "批注模式");
            popup.getMenu().add(0, 2, 1, "搜索模式");
            boolean favorite = dbHelper != null && pdfPath != null && dbHelper.isFavorite(pdfPath, pdfName);
            popup.getMenu().add(0, 3, 2, favorite ? "取消收藏" : "收藏");
        }
        popup.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1: toggleEditMode(true); return true;
                case 2: showPdfSearchDialog(); return true;
                case 3: handleFavoriteToggle(); return true;
                case 10: handleSaveAction(); return true;
                case 11: handleBackAction(); return true;
                default: return false;
            }
        });
        popup.show();
    }

    private void handleFavoriteToggle() {
        if (dbHelper == null) return;
        boolean isNowFav = dbHelper.toggleFavorite(pdfPath, pdfName, pdfUri.toString(), rawModifiedTime);
        Toast.makeText(this, isNowFav ? "已加入收藏夹" : "已取消收藏", Toast.LENGTH_SHORT).show();
    }
    @Override
    protected void onDestroy() {
        if (textSelectionActionMode != null) {
            textSelectionActionMode.finish();
            textSelectionActionMode = null;
        }
        if (textSelectionRepository != null) {
            textSelectionRepository.close();
            textSelectionRepository = null;
        }
        if (pdfSearchManager != null) {
            pdfSearchManager.cancel();
            pdfSearchManager.clearCache();
        }
        super.onDestroy();
    }

}