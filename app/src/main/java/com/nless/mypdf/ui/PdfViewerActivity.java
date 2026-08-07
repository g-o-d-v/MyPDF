package com.nless.mypdf.ui;


import com.nless.mypdf.R;
import com.nless.mypdf.core.PdfSecurityContext;
import com.nless.mypdf.core.PdfTextPage;
import com.nless.mypdf.core.PdfTextSelectionRepository;
import com.nless.mypdf.core.SearchPreferences;
import com.nless.mypdf.core.ReadingPreferences;
import com.nless.mypdf.core.SmartVolumeSniffer;
import com.nless.mypdf.data.PdfDbHelper;
import com.nless.mypdf.data.PdfItem;
import com.nless.mypdf.diagnostics.DiagnosticContext;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.ActionMode;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
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
import com.shockwave.pdfium.util.SizeF;
import com.nless.pdf_search_engine.androidpdfviewer.AndroidPdfViewerAdapter;
import com.nless.pdf_search_engine.core.PdfSearchCallback;
import com.nless.pdf_search_engine.core.PdfSearchManager;
import com.nless.pdf_search_engine.core.PdfSearchMode;
import com.nless.pdf_search_engine.core.PdfSearchOptions;
import com.nless.pdf_search_engine.core.PdfSearchPageOrder;
import com.nless.pdf_search_engine.core.PdfSearchProgressInfo;
import com.nless.pdf_search_engine.core.PdfSearchResult;
import com.nless.pdf_search_engine.core.PdfTextLayerOcrFallbackPolicy;
import com.nless.pdf_search_engine.core.PdfSearchSource;
//import com.github.barteksc.pdfviewer.scroll.DefaultScrollHandle; // 🌟 引入原生滑动条组件


import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class PdfViewerActivity extends AppCompatActivity {

    public enum EditState {
        DRAG, DOODLE, TEXT
    }

    private EditState currentEditState = EditState.DRAG;
    private boolean isEditMode = false;
    private boolean isSearchMode = false;
    private boolean isMenuVisible = true;

    private boolean isAtLastPage = false;
    private boolean nextVolumeButtonTargetVisible = false;

    private String currentFileName = "未命名";
    private String pdfPath = "";
    private String pdfName = "";
    private Uri pdfUri;
    private Uri workingPdfUri;
    // 仅在密码文档首次使用搜索时生成；阅读本身直接由 Pdfium 使用密码打开原文件。
    private Uri searchWorkingPdfUri;
    private File unlockedPdfTempFile;
    private boolean encryptedSearchPreparationRunning;
    private String pendingEncryptedSearchKeyword;
    private PdfSearchMode pendingEncryptedSearchMode;
    private String pdfPassword = "";
    private PdfSecurityContext.Info pdfSecurityInfo;
    private boolean securityInspectionRunning;
    private final List<Runnable> pendingSecurityActions = new ArrayList<>();
    private boolean passwordDialogShowing;
    private boolean directPasswordFallbackAttempted;
    private String annotationOpenPassword = "";
    private String annotationManagementPassword = "";
    private long pdfOpenStartedAtMs;
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
    private LinearLayout searchPanelContainer;
    private FrameLayout pdfContentFrame;

    private LinearLayout readingControlBar;
    private TextView btnReadVertical;
    private TextView btnReadHorizontal;
    private TextView btnPageAnimationNone;
    private TextView btnPageAnimationEnabled;
    private TextView tvReadingProgress;
    private SeekBar seekReadingProgress;

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

    private TextView tvSearchModeSelector;
    private EditText etSearchKeyword;
    private TextView btnSearchExecute;
    private TextView tvSearchStatus;
    private LinearLayout llSearchNavigationRow;
    private TextView btnSearchPrevious, tvSearchPosition, btnSearchNext;

    private boolean isTextBold = false;
    private boolean isTextItalic = false;
    private boolean isTextUnderline = false;
    private int currentDrawColor = 0xFF000000;
    private String currentActiveTool = "铅笔";

    private float lastPdfXOffset = 0;
    private float lastPdfYOffset = 0;
    private float lastPdfZoom = 0;

    // 阅读进度和每个文件的阅读方向都保存在本地轻量级配置中。
    private SharedPreferences progressPrefs;

    private static final int COMIC_PROGRESS_MAX = 1000;
    private static final float LONG_COMIC_PAGE_RATIO = 3.2f;
    private static final float SINGLE_PAGE_LONG_COMIC_RATIO = 3.0f;
    private static final String TYPE_LONG_COMIC = "long_comic";
    private static final String TYPE_NORMAL = "normal";

    private final Handler readingProgressHandler = new Handler(Looper.getMainLooper());
    private Runnable pendingReadingProgressSave;
    private boolean horizontalReadingMode;
    private boolean savedHorizontalReadingMode;
    private boolean longComicMode;
    private boolean documentTypeKnown;
    private boolean readingProgressTracking;
    private boolean readingControlsReady;
    private boolean readingControlsFeatureEnabled;
    private boolean readingControlsShown;
    private int loadedPageCount;
    private int currentReadingPage;
    private float currentReadingPositionOffset;
    private float pendingRestorePositionOffset = -1f;

    private final List<PdfOverlayView.SearchHighlight> currentSearchHighlights = new ArrayList<>();
    private final List<SearchMatch> currentSearchMatches = new ArrayList<>();
    private PdfSearchManager pdfSearchManager;
    private AndroidPdfViewerAdapter androidPdfViewerAdapter;
    private PdfSearchMode selectedSearchMode = PdfSearchMode.TEXT_THEN_OCR;
    private String selectedSearchModeLabel = "智能模式";
    private int currentSearchMatchIndex = -1;
    private boolean searchInProgress = false;
    private int searchRequestId = 0;
    private long appliedSearchCacheGeneration = 0L;

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
        appliedSearchCacheGeneration = SearchPreferences.getCacheClearGeneration(this);
        // 🌟 初始化轻量级进度存储
        progressPrefs = getSharedPreferences("pdf_reading_progress", MODE_PRIVATE);
        readingControlsFeatureEnabled =
                ReadingPreferences.isExperimentalReadingControlsEnabled(this);

        initViews();
        setupListeners();
        loadPdfFromIntent();
        updateAllColorBlocksUI();
    }

    @Override
    protected void onResume() {
        super.onResume();
        long generation = SearchPreferences.getCacheClearGeneration(this);
        if (generation != appliedSearchCacheGeneration) {
            appliedSearchCacheGeneration = generation;
            if (pdfSearchManager != null) {
                pdfSearchManager.clearCache();
            }
            if (pdfOverlay != null) {
                clearPdfSearch(false);
            }
        }
        refreshExperimentalReadingControlsPreference();

        // 顶部标题始终保持原版“文件名（当前页/总页数）”格式。
        // 实验性底部阅读控制栏只影响底部 UI，不改变顶部页码进度。
        if (!isEditMode && !isSearchMode && pdfView != null && pdfView.getPageCount() > 0) {
            updateReadingTitle(pdfView.getCurrentPage(), pdfView.getPageCount());
        }
    }

    private void refreshExperimentalReadingControlsPreference() {
        boolean enabled = ReadingPreferences.isExperimentalReadingControlsEnabled(this);
        if (enabled == readingControlsFeatureEnabled || readingControlBar == null) return;

        readingControlsFeatureEnabled = enabled;
        if (!enabled) {
            readingControlsShown = false;
            readingControlBar.animate().cancel();
            readingControlBar.setVisibility(View.GONE);
            readingControlBar.setAlpha(0f);
            readingControlBar.setTranslationY(0f);

            if (horizontalReadingMode && pdfView != null && pdfView.getPageCount() > 0) {
                int page = Math.max(0, pdfView.getCurrentPage());
                float offset = clamp01(pdfView.getPositionOffset());
                horizontalReadingMode = false;
                savedHorizontalReadingMode = false;
                saveReadingDirection();
                pendingRestorePositionOffset = offset;
                readingControlsReady = false;
                updateReadingControlStyles();
                reloadPdfView(page);
            } else {
                updateReadingControlStyles();
            }
        } else {
            readingControlsShown = isMenuVisible && !isEditMode && !isSearchMode;
            if (readingControlsShown) {
                readingControlBar.setVisibility(View.VISIBLE);
                readingControlBar.setAlpha(readingControlsReady ? 1f : 0.78f);
                readingControlBar.setTranslationY(0f);
            }
            updateReadingControlStyles();
        }
        updateNextVolumeButtonOffset(false);
    }

    private void initViews() {
        pdfView = findViewById(R.id.pdfView);
        pdfOverlay = findViewById(R.id.pdfOverlay);
        topMenuLayout = findViewById(R.id.top_menu_layout);
        annotationPanelContainer = findViewById(R.id.annotation_panel_container);
        searchPanelContainer = findViewById(R.id.search_panel_container);
        pdfContentFrame = findViewById(R.id.pdf_content_frame);
        readingControlBar = findViewById(R.id.reading_control_bar);
        btnReadVertical = findViewById(R.id.btn_read_vertical);
        btnReadHorizontal = findViewById(R.id.btn_read_horizontal);
        btnPageAnimationNone = findViewById(R.id.btn_page_animation_none);
        btnPageAnimationEnabled = findViewById(R.id.btn_page_animation_enabled);
        tvReadingProgress = findViewById(R.id.tv_reading_progress);
        seekReadingProgress = findViewById(R.id.seek_reading_progress);

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

        tvSearchModeSelector = findViewById(R.id.tv_search_mode_selector);
        etSearchKeyword = findViewById(R.id.et_search_keyword);
        btnSearchExecute = findViewById(R.id.btn_search_execute);
        tvSearchStatus = findViewById(R.id.tv_search_status);
        llSearchNavigationRow = findViewById(R.id.ll_search_navigation_row);
        btnSearchPrevious = findViewById(R.id.btn_search_previous);
        tvSearchPosition = findViewById(R.id.tv_search_position);
        btnSearchNext = findViewById(R.id.btn_search_next);

        topMenuLayout.setVisibility(View.VISIBLE);
        annotationPanelContainer.setVisibility(View.GONE);
        searchPanelContainer.setVisibility(View.GONE);
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

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
        );
        params.gravity = android.view.Gravity.BOTTOM | android.view.Gravity.END;
        // 保留原版胶囊较高的基础位置；底部阅读菜单出现时再额外上移。
        params.bottomMargin = dp(50);
        params.rightMargin = dp(20);
        pdfContentFrame.addView(btnNextVolume, params);

        updateReadingControlStyles();
        setReadingControlsEnabled(false);
        if (!readingControlsFeatureEnabled && readingControlBar != null) {
            readingControlBar.setVisibility(View.GONE);
            readingControlBar.setAlpha(0f);
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

        btnReadVertical.setOnClickListener(v -> switchReadingDirection(false));
        btnReadHorizontal.setOnClickListener(v -> {
            if (longComicMode) {
                Toast.makeText(this, "条漫模式仅支持纵向阅读", Toast.LENGTH_SHORT).show();
                return;
            }
            switchReadingDirection(true);
        });
        btnPageAnimationNone.setOnClickListener(v -> setPageTurnAnimationEnabled(false));
        btnPageAnimationEnabled.setOnClickListener(v -> setPageTurnAnimationEnabled(true));
        seekReadingProgress.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) return;
                if (longComicMode) {
                    int percent = Math.round(progress * 100f / COMIC_PROGRESS_MAX);
                    tvReadingProgress.setText(percent + "%");
                } else {
                    tvReadingProgress.setText((progress + 1) + " / " + Math.max(1, loadedPageCount));
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                readingProgressTracking = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                readingProgressTracking = false;
                if (!readingControlsReady || pdfView == null || pdfView.getPageCount() <= 0) return;
                boolean smooth = ReadingPreferences.isPageTurnAnimationEnabled(PdfViewerActivity.this);
                try {
                    if (longComicMode) {
                        float offset = seekBar.getProgress() / (float) COMIC_PROGRESS_MAX;
                        pdfView.setPositionOffset(offset, smooth);
                        pdfView.loadPages();
                    } else {
                        int page = Math.max(0, Math.min(
                                seekBar.getProgress(), pdfView.getPageCount() - 1));
                        pdfView.jumpTo(page, smooth);
                    }
                } catch (Exception ignored) {
                    // PDF 仍在加载或视图已退出时，不让进度条操作导致崩溃。
                }
            }
        });

        setupColorBlocks();
        setupTextInputCallback();
        setupSearchPanelListeners();

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                handleBackAction();
            }
        });
    }

    private void updateNextVolumeButtonState() {
        if (btnNextVolume == null) return;

        boolean shouldShow = isAtLastPage
                && preloadedNextUri != null
                && !isEditMode
                && !isSearchMode;
        nextVolumeButtonTargetVisible = shouldShow;

        // 横向翻页过程中 onPageChange/onPageScroll 可能交错到达。先取消旧动画，
        // 避免“显示请求”被之前尚未结束的隐藏动画再次设为 GONE。
        btnNextVolume.animate().cancel();

        if (shouldShow) {
            btnNextVolume.setText("下一卷：\n" + preloadedNextName + "  〉");
            btnNextVolume.bringToFront();
            updateNextVolumeButtonOffset(false);

            if (btnNextVolume.getVisibility() != View.VISIBLE) {
                btnNextVolume.setAlpha(0f);
                btnNextVolume.setTranslationX(dp(50));
                btnNextVolume.setVisibility(View.VISIBLE);
                btnNextVolume.animate()
                        .alpha(1f)
                        .translationX(0f)
                        .setDuration(250)
                        .start();
            } else {
                // 已经可见时直接恢复稳定状态，避免滚动回调反复触发动画抖动。
                btnNextVolume.setAlpha(1f);
                btnNextVolume.setTranslationX(0f);
            }
            return;
        }

        if (btnNextVolume.getVisibility() == View.VISIBLE) {
            btnNextVolume.animate()
                    .alpha(0f)
                    .translationX(dp(50))
                    .setDuration(180)
                    .withEndAction(() -> {
                        if (!nextVolumeButtonTargetVisible) {
                            btnNextVolume.setVisibility(View.GONE);
                        }
                    })
                    .start();
        }
    }

    private void setupSearchPanelListeners() {
        tvSearchModeSelector.setOnClickListener(this::showSearchModePopup);
        btnSearchExecute.setOnClickListener(v -> {
            if (searchInProgress) {
                cancelActiveSearch();
            } else {
                executeSearchFromPanel();
            }
        });
        btnSearchPrevious.setOnClickListener(v -> navigateSearchResult(-1));
        btnSearchNext.setOnClickListener(v -> navigateSearchResult(1));
        etSearchKeyword.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                executeSearchFromPanel();
                return true;
            }
            return false;
        });
    }

    private void showSearchModePopup(View anchor) {
        if (searchInProgress) return;
        android.widget.PopupMenu popup = new android.widget.PopupMenu(this, anchor);
        popup.getMenu().add(0, 1, 0, "智能模式");
        popup.getMenu().add(0, 2, 1, "仅文本模式");
        popup.getMenu().add(0, 3, 2, "仅扫描模式");
        popup.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1:
                    setSelectedSearchMode(PdfSearchMode.TEXT_THEN_OCR, "智能模式");
                    return true;
                case 2:
                    setSelectedSearchMode(PdfSearchMode.TEXT_ONLY, "仅文本模式");
                    return true;
                case 3:
                    setSelectedSearchMode(PdfSearchMode.OCR_ONLY, "仅扫描模式");
                    return true;
                default:
                    return false;
            }
        });
        popup.show();
    }

    private void setSelectedSearchMode(PdfSearchMode mode, String label) {
        selectedSearchMode = mode == null ? PdfSearchMode.TEXT_THEN_OCR : mode;
        selectedSearchModeLabel = label == null ? "智能模式" : label;
        tvSearchModeSelector.setText(selectedSearchModeLabel + " ▾");
        clearPdfSearch(false);
        showSearchStatus("已切换为" + selectedSearchModeLabel + "，输入关键词后开始搜索。", false);
        schedulePdfViewportRelayout();
    }

    private void toggleSearchMode(boolean enterSearchMode) {
        if (enterSearchMode == isSearchMode) return;
        if (enterSearchMode && isEditMode) return;

        isSearchMode = enterSearchMode;
        updateReadingControlsVisibility();
        updateNextVolumeButtonState();

        if (isSearchMode) {
            clearTextSelectionUi();
            isMenuVisible = true;
            topMenuLayout.animate().cancel();
            topMenuLayout.setTranslationY(0f);
            topMenuLayout.setVisibility(View.VISIBLE);

            tvTopTitle.setText("搜索模式");
            ivBackOrExit.setImageResource(android.R.drawable.ic_menu_close_clear_cancel);
            ivUndo.setVisibility(View.GONE);
            ivSave.setVisibility(View.GONE);
            annotationPanelContainer.setVisibility(View.GONE);
            searchPanelContainer.setVisibility(View.VISIBLE);

            if (pdfOverlay != null) {
                pdfOverlay.setMode(PdfOverlayView.Mode.DRAG);
                pdfOverlay.setClickable(false);
                pdfOverlay.setFocusable(false);
            }
            if (pdfView != null) pdfView.setSwipeEnabled(true);

            if (currentSearchMatches.isEmpty()) {
                tvSearchStatus.setVisibility(View.GONE);
                llSearchNavigationRow.setVisibility(View.GONE);
            }
            etSearchKeyword.post(() -> {
                if (!isFinishing() && isSearchMode) {
                    etSearchKeyword.requestFocus();
                }
            });
        } else {
            searchRequestId++;
            if (pdfSearchManager != null) pdfSearchManager.cancel();
            setSearchInProgress(false);
            clearPdfSearch(false);
            searchPanelContainer.setVisibility(View.GONE);
            hideKeyboard();

            int currentPage = pdfView != null ? pdfView.getCurrentPage() : 0;
            int pageCount = pdfView != null ? pdfView.getPageCount() : 0;
            updateReadingTitle(currentPage, pageCount);
            ivBackOrExit.setImageResource(android.R.drawable.ic_menu_revert);
        }
        schedulePdfViewportRelayout();
    }

    private void executeSearchFromPanel() {
        if (!isSearchMode || pdfSearchManager == null || pdfUri == null || pdfView == null || pdfOverlay == null) {
            return;
        }
        String keyword = etSearchKeyword.getText().toString().trim();
        if (keyword.isEmpty()) {
            showSearchStatus("请输入要搜索的关键词。", true);
            llSearchNavigationRow.setVisibility(View.GONE);
            etSearchKeyword.requestFocus();
            return;
        }
        hideKeyboard();
        searchWithEngine(keyword, selectedSearchMode);
    }

    private PdfSearchOptions createSearchOptions(PdfSearchMode mode) {
        PdfSearchOptions options = new PdfSearchOptions();
        options.mode = mode;
        options.currentPageOnly = false;
        options.currentPage = Math.max(0, pdfView.getCurrentPage());
        options.startPage = 0;
        options.endPage = -1;

        // alpha03 的索引模式会把文本层与 OCR 页面内容缓存下来，后续更换关键词无需重复 OCR。
        options.enableDocumentIndex = true;
        options.usePersistentPageIndexCache = true;
        options.usePersistentOcrCache = true;

        options.ocrRenderWidth = SearchPreferences.getOcrRenderWidth(this);
        options.allowFullDocumentOcr = mode != PdfSearchMode.TEXT_ONLY;
        options.maxOcrPages = 0; // 0 表示不额外限制，由用户主动取消长任务。
        options.ocrPageOrder = SearchPreferences.isCurrentPageFirstEnabled(this)
                ? PdfSearchPageOrder.CURRENT_PAGE_OUTWARD
                : PdfSearchPageOrder.NATURAL;
        options.enableOcrPipeline = true;
        options.ocrPrefetchPages = 1;
        options.stopAfterFirstOcrMatch = false;

        // 智能模式始终允许对“没有可用文本层”的页面进行 OCR。
        // 开启“增强 OCR”后，文本层可用但未命中的页面也会继续 OCR。
        boolean enhancedOcr = SearchPreferences.isSmartEnhancedOcrEnabled(this);
        options.fallbackToOcrWhenTextNotFound = mode == PdfSearchMode.TEXT_THEN_OCR;
        options.enablePageLevelTextOcrFallback = true;
        options.textLayerOcrFallbackPolicy = enhancedOcr
                ? PdfTextLayerOcrFallbackPolicy.UNUSABLE_OR_NO_MATCH
                : PdfTextLayerOcrFallbackPolicy.UNUSABLE_TEXT_LAYER_ONLY;
        options.enableCrossSourceDeduplication = true;
        options.detectMultiColumnLayout = true;
        options.maxResults = 0;

        options.queryOptions.caseSensitive = SearchPreferences.isCaseSensitive(this);
        // Unicode 兼容规范化和连续空格折叠保持为内部默认开启，不占用普通设置页。
        options.queryOptions.normalizeUnicode = true;
        options.queryOptions.collapseWhitespace = true;
        options.queryOptions.allowCrossLineMatch = SearchPreferences.isCrossLineMatchEnabled(this);
        options.queryOptions.ignoreWhitespaceForMatching =
                SearchPreferences.isIgnoreAllWhitespaceEnabled(this);
        options.queryOptions.tolerateOcrOZeroConfusion =
                SearchPreferences.isOcrOZeroToleranceEnabled(this);
        options.queryOptions.joinHyphenatedLineBreaks =
                SearchPreferences.isJoinHyphenatedLineBreaksEnabled(this);
        options.queryOptions.wholeWord = SearchPreferences.isWholeWordEnabled(this);
        return options;
    }

    private void searchWithEngine(String keyword, PdfSearchMode mode) {
        if (pdfSearchManager == null || pdfUri == null || pdfView == null || pdfOverlay == null) return;

        pdfSearchManager.cancel();
        clearPdfSearch(false);
        final int requestId = ++searchRequestId;
        final PdfSearchOptions options = createSearchOptions(mode);
        DiagnosticContext.startOperation(this, "pdf_search");
        String diagnosticMode = mode == PdfSearchMode.TEXT_ONLY ? "text_only"
                : mode == PdfSearchMode.OCR_ONLY ? "ocr_only" : "smart";
        DiagnosticContext.setSearchProfile(this, mode != PdfSearchMode.TEXT_ONLY,
                diagnosticMode, options.ocrRenderWidth);
        setSearchInProgress(true);
        showSearchStatus("正在使用" + selectedSearchModeLabel + "搜索…", false);

        if (pdfPassword != null && !pdfPassword.isEmpty() && searchWorkingPdfUri == null) {
            prepareEncryptedSearchSource(keyword, mode);
            return;
        }

        Uri searchUri = searchWorkingPdfUri == null ? pdfUri : searchWorkingPdfUri;
        pdfSearchManager.search(searchUri, keyword, options, new PdfSearchCallback() {
            @Override
            public void onSearchStarted(String value) {
                if (!isActiveSearchRequest(requestId)) return;
                showSearchStatus("正在使用" + selectedSearchModeLabel + "搜索“" + value + "”…", false);
            }

            @Override
            public void onSearchProgress(int currentPage, int totalPage, PdfSearchSource source) {
                if (!isActiveSearchRequest(requestId)) return;
                String sourceText = source == PdfSearchSource.OCR ? "OCR" : "文本层";
                String pageText = totalPage > 0
                        ? "，第 " + (currentPage + 1) + " / " + totalPage + " 页"
                        : "";
                showSearchStatus("正在进行" + sourceText + "搜索" + pageText + "…", false);
            }

            @Override
            public void onSearchProgress(PdfSearchProgressInfo progressInfo) {
                if (!isActiveSearchRequest(requestId) || progressInfo == null) return;
                String sourceText = progressInfo.source == PdfSearchSource.OCR ? "OCR" : "文本层";
                String progressText = progressInfo.targetPages > 0
                        ? progressInfo.processedPages + " / " + progressInfo.targetPages + " 页"
                        : "第 " + (progressInfo.pageIndex + 1) + " 页";
                showSearchStatus(
                        "正在进行" + sourceText + "搜索：" + progressText
                                + "，已发现 " + progressInfo.cumulativeMatchCount + " 个结果…",
                        false
                );
            }

            @Override
            public void onSearchCompleted(List<PdfSearchResult> results) {
                if (!isActiveSearchRequest(requestId)) return;
                setSearchInProgress(false);
                DiagnosticContext.finishOperation(PdfViewerActivity.this, "pdf_search", true);
                showSearchResults(keyword, results);
            }

            @Override
            public void onSearchFailed(Throwable error) {
                if (!isActiveSearchRequest(requestId)) return;
                setSearchInProgress(false);
                DiagnosticContext.finishOperation(PdfViewerActivity.this, "pdf_search", false);
                clearPdfSearch(false);
                showSearchStatus("搜索失败：" + safeMessage(error), true);
            }

            @Override
            public void onSearchCancelled() {
                if (!isActiveSearchRequest(requestId)) return;
                setSearchInProgress(false);
                DiagnosticContext.cancelOperation(PdfViewerActivity.this, "pdf_search");
                showSearchStatus("搜索已取消。", false);
            }
        });
    }

    private void prepareEncryptedSearchSource(String keyword, PdfSearchMode mode) {
        pendingEncryptedSearchKeyword = keyword;
        pendingEncryptedSearchMode = mode;
        if (encryptedSearchPreparationRunning) {
            showSearchStatus("正在准备加密文档，请稍候…", false);
            return;
        }
        encryptedSearchPreparationRunning = true;
        setSearchInProgress(true);
        showSearchStatus("首次搜索正在准备加密文档；完成后将复用缓存…", false);
        final Uri expectedUri = pdfUri;
        final String expectedPassword = pdfPassword;
        new Thread(() -> {
            File unlocked = null;
            try {
                unlocked = PdfSecurityContext.createDecryptedTemp(
                        this,
                        expectedUri,
                        pdfPath,
                        expectedPassword,
                        "search-unlocked-"
                );
                File finalUnlocked = unlocked;
                runOnUiThread(() -> {
                    if (!expectedUri.equals(pdfUri) || isFinishing() || isDestroyed()) {
                        if (finalUnlocked.exists()) finalUnlocked.delete();
                        return;
                    }
                    if (unlockedPdfTempFile != null && unlockedPdfTempFile.exists()) {
                        unlockedPdfTempFile.delete();
                    }
                    unlockedPdfTempFile = finalUnlocked;
                    searchWorkingPdfUri = Uri.fromFile(finalUnlocked);
                    encryptedSearchPreparationRunning = false;
                    String nextKeyword = pendingEncryptedSearchKeyword;
                    PdfSearchMode nextMode = pendingEncryptedSearchMode;
                    pendingEncryptedSearchKeyword = null;
                    pendingEncryptedSearchMode = null;
                    setSearchInProgress(false);
                    searchWithEngine(nextKeyword == null ? keyword : nextKeyword,
                            nextMode == null ? mode : nextMode);
                });
            } catch (Throwable error) {
                if (unlocked != null && unlocked.exists()) unlocked.delete();
                runOnUiThread(() -> {
                    encryptedSearchPreparationRunning = false;
                    pendingEncryptedSearchKeyword = null;
                    pendingEncryptedSearchMode = null;
                    setSearchInProgress(false);
                    DiagnosticContext.finishOperation(PdfViewerActivity.this, "pdf_search", false);
                    showSearchStatus("无法准备加密文档搜索：" + safeMessage(error), true);
                });
            }
        }, "encrypted-pdf-search-prepare").start();
    }

    private boolean isActiveSearchRequest(int requestId) {
        return isSearchMode
                && requestId == searchRequestId
                && !isFinishing()
                && !isDestroyed();
    }

    private void cancelActiveSearch() {
        if (!searchInProgress) return;
        searchRequestId++;
        if (pdfSearchManager != null) pdfSearchManager.cancel();
        DiagnosticContext.cancelOperation(this, "pdf_search");
        setSearchInProgress(false);
        showSearchStatus("搜索已取消。", false);
    }

    private void setSearchInProgress(boolean inProgress) {
        searchInProgress = inProgress;
        btnSearchExecute.setText(inProgress ? "取消" : "搜索");
        tvSearchModeSelector.setEnabled(!inProgress);
        tvSearchModeSelector.setAlpha(inProgress ? 0.55f : 1f);
        etSearchKeyword.setEnabled(!inProgress);
        etSearchKeyword.setAlpha(inProgress ? 0.75f : 1f);
    }

    private void showSearchResults(String keyword, List<PdfSearchResult> results) {
        clearPdfSearch(false);
        if (results == null || results.isEmpty()) {
            showSearchStatus(selectedSearchModeLabel + "未查询到“" + keyword + "”。", false);
            return;
        }

        if (androidPdfViewerAdapter == null) {
            androidPdfViewerAdapter = new AndroidPdfViewerAdapter(pdfView);
        }

        List<SearchMatch> convertedMatches = new ArrayList<>();
        for (PdfSearchResult result : results) {
            if (result == null) continue;
            List<AndroidPdfViewerAdapter.ViewerSearchHighlight> rects =
                    androidPdfViewerAdapter.convertResults(Collections.singletonList(result));
            if (rects == null || rects.isEmpty()) continue;
            convertedMatches.add(new SearchMatch(result.pageIndex, rects));
        }

        convertedMatches.sort(Comparator
                .comparingInt((SearchMatch item) -> item.pageIndex)
                .thenComparingDouble(item -> item.firstTop)
                .thenComparingDouble(item -> item.firstLeft));

        currentSearchMatches.addAll(convertedMatches);
        for (int matchIndex = 0; matchIndex < currentSearchMatches.size(); matchIndex++) {
            SearchMatch match = currentSearchMatches.get(matchIndex);
            for (AndroidPdfViewerAdapter.ViewerSearchHighlight item : match.rects) {
                if (item == null || item.rectInPageRatio == null) continue;
                currentSearchHighlights.add(new PdfOverlayView.SearchHighlight(
                        item.pageIndex,
                        item.rectInPageRatio,
                        matchIndex
                ));
            }
        }

        if (currentSearchMatches.isEmpty() || currentSearchHighlights.isEmpty()) {
            clearPdfSearch(false);
            showSearchStatus("查询到了结果，但无法换算页面高亮坐标。", true);
            return;
        }

        pdfOverlay.setSearchHighlights(currentSearchHighlights);
        currentSearchMatchIndex = 0;
        pdfOverlay.setCurrentSearchIndex(currentSearchMatchIndex);
        showSearchStatus(
                selectedSearchModeLabel + "查询到了 " + currentSearchMatches.size() + " 个结果。",
                false
        );
        llSearchNavigationRow.setVisibility(View.VISIBLE);
        updateSearchNavigationUi();
        schedulePdfViewportRelayout(() -> focusSearchMatch(currentSearchMatchIndex));
    }

    private void navigateSearchResult(int direction) {
        if (currentSearchMatches.isEmpty() || searchInProgress) return;
        int size = currentSearchMatches.size();
        currentSearchMatchIndex = (currentSearchMatchIndex + direction + size) % size;
        pdfOverlay.setCurrentSearchIndex(currentSearchMatchIndex);
        updateSearchNavigationUi();
        focusSearchMatch(currentSearchMatchIndex);
    }

    private void updateSearchNavigationUi() {
        int total = currentSearchMatches.size();
        if (total <= 0 || currentSearchMatchIndex < 0) {
            tvSearchPosition.setText("0 / 0");
            llSearchNavigationRow.setVisibility(View.GONE);
            return;
        }
        tvSearchPosition.setText((currentSearchMatchIndex + 1) + " / " + total);
        llSearchNavigationRow.setVisibility(View.VISIBLE);
    }

    private void focusSearchMatch(int matchIndex) {
        if (matchIndex < 0 || matchIndex >= currentSearchMatches.size()) return;
        SearchMatch match = currentSearchMatches.get(matchIndex);
        pdfView.jumpTo(match.pageIndex, false);
        pdfView.post(() -> pdfView.post(() -> {
            if (!isSearchMode || matchIndex != currentSearchMatchIndex) return;
            RectF bounds = new RectF();
            if (!pdfOverlay.getSearchMatchBoundsInDocument(matchIndex, bounds)) {
                pdfOverlay.invalidate();
                return;
            }
            float zoom = pdfView.getZoom();
            float targetX = pdfView.getWidth() / 2f - bounds.centerX() * zoom;
            float targetY = pdfView.getHeight() / 2f - bounds.centerY() * zoom;
            try {
                pdfView.moveTo(targetX, targetY);
                pdfView.loadPages();
            } catch (Exception ignored) {
                pdfView.jumpTo(match.pageIndex, true);
            }
            pdfOverlay.invalidate();
        }));
    }

    private void showSearchStatus(String message, boolean isError) {
        tvSearchStatus.setText(message == null ? "" : message);
        tvSearchStatus.setTextColor(isError ? 0xFFB71C1C : 0xFF8A5A00);
        tvSearchStatus.setVisibility(View.VISIBLE);
    }

    private void hideKeyboard() {
        View focused = getCurrentFocus();
        if (focused == null) focused = etSearchKeyword;
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null && focused != null) {
            imm.hideSoftInputFromWindow(focused.getWindowToken(), 0);
        }
        if (focused != null) focused.clearFocus();
    }

    private void clearPdfSearch() {
        clearPdfSearch(true);
    }

    private void clearPdfSearch(boolean showFeedback) {
        currentSearchHighlights.clear();
        currentSearchMatches.clear();
        currentSearchMatchIndex = -1;
        if (pdfOverlay != null) pdfOverlay.clearSearchHighlights();
        if (llSearchNavigationRow != null) llSearchNavigationRow.setVisibility(View.GONE);
        if (tvSearchPosition != null) tvSearchPosition.setText("0 / 0");
        if (showFeedback && isSearchMode) {
            showSearchStatus("已清除搜索结果。", false);
        }
    }

    private void handlePdfLongPress(MotionEvent event) {
        if (event == null || isEditMode || isSearchMode || pdfOverlay == null
                || pdfView == null || pdfUri == null) {
            return;
        }
        final float viewX = event.getX();
        final float viewY = event.getY();
        if (pdfSecurityInfo == null) {
            Toast.makeText(this, "正在读取文档权限，请稍候…", Toast.LENGTH_SHORT).show();
            ensureSecurityInfo(() -> handlePdfLongPressAt(viewX, viewY));
            return;
        }
        handlePdfLongPressAt(viewX, viewY);
    }

    private void handlePdfLongPressAt(float viewX, float viewY) {
        if (pdfSecurityInfo != null && !pdfSecurityInfo.canCopyOrExportText()) {
            Toast.makeText(this, "文档权限禁止复制或提取文本", Toast.LENGTH_LONG).show();
            return;
        }
        if (isEditMode || isSearchMode || pdfOverlay == null || pdfView == null || pdfUri == null) {
            return;
        }

        PdfOverlayView.PageHit hit = pdfOverlay.locateViewPoint(viewX, viewY);
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

    private void ensureSecurityInfo(Runnable action) {
        if (action != null) pendingSecurityActions.add(action);
        if (pdfSecurityInfo != null) {
            runPendingSecurityActions();
            return;
        }
        PdfSecurityContext.Info cached = PdfSecurityContext.getCached(this, pdfUri, pdfPath, pdfPassword);
        if (cached != null) {
            pdfSecurityInfo = cached;
            runPendingSecurityActions();
            return;
        }
        if (securityInspectionRunning || pdfUri == null) return;
        securityInspectionRunning = true;
        final Uri expectedUri = pdfUri;
        final String expectedPath = pdfPath;
        final String expectedPassword = pdfPassword;
        new Thread(() -> {
            try {
                PdfSecurityContext.Info info = PdfSecurityContext.inspect(
                        this, expectedUri, expectedPath, expectedPassword);
                runOnUiThread(() -> {
                    securityInspectionRunning = false;
                    if (!expectedUri.equals(pdfUri) || isFinishing() || isDestroyed()) {
                        pendingSecurityActions.clear();
                        return;
                    }
                    pdfSecurityInfo = info;
                    runPendingSecurityActions();
                });
            } catch (Throwable error) {
                runOnUiThread(() -> {
                    securityInspectionRunning = false;
                    pendingSecurityActions.clear();
                    Toast.makeText(this, "无法读取文档权限：" + safeMessage(error),
                            Toast.LENGTH_LONG).show();
                });
            }
        }, "pdf-security-inspect-lazy").start();
    }

    private void runPendingSecurityActions() {
        if (pendingSecurityActions.isEmpty()) return;
        List<Runnable> actions = new ArrayList<>(pendingSecurityActions);
        pendingSecurityActions.clear();
        for (Runnable action : actions) {
            if (action != null) action.run();
        }
    }

    private void resetTextSelectionRepository() {
        textSelectionRequestId++;
        clearTextSelectionUi();
        if (textSelectionRepository != null) {
            textSelectionRepository.close();
            textSelectionRepository = null;
        }
        if (pdfUri != null) {
            textSelectionRepository = new PdfTextSelectionRepository(this, pdfUri, pdfPath, pdfPassword);
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
        if (pdfSecurityInfo != null && !pdfSecurityInfo.canCopyOrExportText()) {
            Toast.makeText(this, "文档权限禁止复制文本", Toast.LENGTH_LONG).show();
            return;
        }
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
            initializeReadingStateForDocument();

            int lastReadPage = progressPrefs.getInt(pdfUri.toString(), 0);
            prepareSecurePdfAndLoad(lastReadPage, null, false);
        } else {
            finish();
        }
    }


    private void prepareSecurePdfAndLoad(int targetPageIndex, String password, boolean fromRetry) {
        final Uri expectedUri = pdfUri;
        if (expectedUri == null) return;

        // 阅读阶段不再使用 PDFBox 生成完整明文副本。Pdfium/AndroidPdfViewer
        // 可以直接用密码打开原文件；安全权限信息改为在用户第一次执行复制、
        // 批注等受限操作时按需读取。
        deleteUnlockedPdfTemp();
        workingPdfUri = expectedUri;
        pdfPassword = password == null ? "" : password;
        pdfSecurityInfo = PdfSecurityContext.getCached(this, expectedUri, pdfPath, pdfPassword);
        securityInspectionRunning = false;
        pendingSecurityActions.clear();
        passwordDialogShowing = false;
        directPasswordFallbackAttempted = false;
        resetTextSelectionRepository();
        reloadPdfView(targetPageIndex);
    }

    private void showPdfPasswordDialog(int targetPageIndex, boolean wrongPassword) {
        if (passwordDialogShowing || isFinishing() || isDestroyed()) return;
        passwordDialogShowing = true;
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setHint("请输入 PDF 密码");
        input.setPadding(dp(16), dp(12), dp(16), dp(12));
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(wrongPassword ? "密码不正确" : "PDF 已加密")
                .setMessage(wrongPassword ? "请重新输入密码。" : "请输入打开密码或所有者密码后继续。")
                .setView(input)
                .setNegativeButton("取消", (d, which) -> {
                    passwordDialogShowing = false;
                    finish();
                })
                .setPositiveButton("打开", null)
                .setOnCancelListener(d -> {
                    passwordDialogShowing = false;
                    finish();
                })
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
                    prepareSecurePdfAndLoad(targetPageIndex, password, true);
                }));
        dialog.show();
    }

    /**
     * 少数由不同安全处理器生成的 PDF 能被 PDFBox 正确解密，但 Pdfium 会把
     * 同一密码误报为错误。直接打开失败后只回退一次：验证密码并生成磁盘缓存
     * 中的临时明文副本，再交给 Pdfium 渲染。正常文件仍保持零重写的快速路径。
     */
    private void tryPasswordCompatibilityFallback(int targetPageIndex, Throwable directError) {
        if (directPasswordFallbackAttempted || pdfUri == null) {
            showPdfPasswordDialog(targetPageIndex, true);
            return;
        }
        directPasswordFallbackAttempted = true;
        final Uri expectedUri = pdfUri;
        final String expectedPath = pdfPath;
        final String expectedPassword = pdfPassword;
        Toast.makeText(this, "正在兼容读取加密 PDF…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            File unlocked = null;
            try {
                PdfSecurityContext.Info info = PdfSecurityContext.inspect(
                        this, expectedUri, expectedPath, expectedPassword);
                unlocked = PdfSecurityContext.createDecryptedTemp(
                        this, expectedUri, expectedPath, expectedPassword, "viewer-fallback-");
                File ready = unlocked;
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed() || !expectedUri.equals(pdfUri)) {
                        ready.delete();
                        return;
                    }
                    deleteUnlockedPdfTemp();
                    unlockedPdfTempFile = ready;
                    workingPdfUri = Uri.fromFile(ready);
                    pdfSecurityInfo = info;
                    resetTextSelectionRepository();
                    reloadPdfView(targetPageIndex);
                });
            } catch (Throwable fallbackError) {
                if (unlocked != null && unlocked.exists()) unlocked.delete();
                runOnUiThread(() -> {
                    if (PdfSecurityContext.isPasswordError(fallbackError)) {
                        showPdfPasswordDialog(targetPageIndex, true);
                    } else {
                        new AlertDialog.Builder(this)
                                .setTitle("PDF 加载失败")
                                .setMessage(PdfSecurityContext.readableError(fallbackError))
                                .setPositiveButton("知道了", null)
                                .show();
                    }
                });
            }
        }, "pdf-password-compat-fallback").start();
    }

    private void deleteUnlockedPdfTemp() {
        if (unlockedPdfTempFile != null && unlockedPdfTempFile.exists()) {
            unlockedPdfTempFile.delete();
        }
        unlockedPdfTempFile = null;
        searchWorkingPdfUri = null;
        encryptedSearchPreparationRunning = false;
        pendingEncryptedSearchKeyword = null;
        pendingEncryptedSearchMode = null;
    }

    private void reloadPdfView(int targetPageIndex) {
        DiagnosticContext.startOperation(this, "pdf_open");
        Uri renderUri = workingPdfUri == null ? pdfUri : workingPdfUri;
        pdfOpenStartedAtMs = System.currentTimeMillis();
        boolean loadingOriginalEncryptedSource = renderUri != null && renderUri.equals(pdfUri);
        String renderPassword = loadingOriginalEncryptedSource ? pdfPassword : "";
        Log.d("PdfOpenPerf", "Pdfium load started, passwordSupplied="
                + (renderPassword != null && !renderPassword.isEmpty())
                + ", fallbackTemp=" + !loadingOriginalEncryptedSource);
        pdfView.fromUri(renderUri)
                .password(renderPassword == null || renderPassword.isEmpty() ? null : renderPassword)
                .defaultPage(targetPageIndex)
                .enableSwipe(true)
                .swipeHorizontal(horizontalReadingMode && !longComicMode)
//                .scrollHandle(new DefaultScrollHandle(this))
                .enableAntialiasing(false)
                // 横向阅读优先完整显示整页，避免正方形、拼页漫画等被 FitPolicy.WIDTH 裁掉。
                // spacing/autoSpacing 仍保持为 0/false，不额外制造页间距。
                .pageFitPolicy(horizontalReadingMode && !longComicMode
                        ? com.github.barteksc.pdfviewer.util.FitPolicy.BOTH
                        : com.github.barteksc.pdfviewer.util.FitPolicy.WIDTH)
                .fitEachPage(true)
                .spacing(0)
                .autoSpacing(false)
                .pageSnap(horizontalReadingMode && !longComicMode)
                .pageFling(horizontalReadingMode
                        && !longComicMode
                        && ReadingPreferences.isPageTurnAnimationEnabled(this))
                .onTap(this::handleReaderTap)
                .onLongPress(this::handlePdfLongPress)
                .onError(error -> {
                    DiagnosticContext.finishOperation(this, "pdf_open", false);
                    if (PdfSecurityContext.isPasswordError(error)) {
                        if (pdfPassword != null
                                && !pdfPassword.isEmpty()
                                && !directPasswordFallbackAttempted
                                && workingPdfUri != null
                                && workingPdfUri.equals(pdfUri)) {
                            tryPasswordCompatibilityFallback(targetPageIndex, error);
                        } else {
                            showPdfPasswordDialog(targetPageIndex, true);
                        }
                    } else {
                        new AlertDialog.Builder(this)
                                .setTitle("PDF 加载失败")
                                .setMessage(PdfSecurityContext.readableError(error))
                                .setPositiveButton("知道了", null)
                                .show();
                    }
                })
                .onPageChange((page, pageCount) -> {
                    loadedPageCount = pageCount;
                    currentReadingPage = page;
                    updateReadingTitle(page, pageCount);
                    if (!readingProgressTracking && !longComicMode) {
                        updateReadingProgressUi(page, pageCount, currentReadingPositionOffset);
                    }
                    if (horizontalReadingMode && !longComicMode) {
                        // 横向模式只以稳定的页面切换结果为准，不受滚动中的瞬时回调干扰。
                        isAtLastPage = isHorizontalAtLastPage(pageCount);
                    } else if (!isPhysicallyAtDocumentEnd()) {
                        // 纵向模式沿用原版体验：只有真正无法继续向下滚动时才算到底。
                        isAtLastPage = false;
                    }
                    updateNextVolumeButtonState();

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
                    boolean horizontalPaging = horizontalReadingMode && !longComicMode;
                    // 横向滚动中的 page 参数可能短暂回报上一页。横向的权威页码只由
                    // onPageChange 更新，避免已经到最后一页后又被瞬时回调改回倒数第二页。
                    if (!horizontalPaging) {
                        currentReadingPage = page;
                    }
                    currentReadingPositionOffset = clamp01(positionOffset);
                    int pageCount = Math.max(1, pdfView.getPageCount());
                    int progressPage = horizontalPaging ? currentReadingPage : page;
                    if (!readingProgressTracking) {
                        updateReadingProgressUi(
                                progressPage, pageCount, currentReadingPositionOffset);
                    }
                    if (!horizontalPaging) {
                        // 纵向普通 PDF 与条漫都保存文档级滚动位置。1.0.1 只保存条漫
                        // offset，随后默认的 0 又会覆盖 defaultPage，导致普通 PDF
                        // 看起来无法恢复上次阅读位置。
                        scheduleReadingPositionSave(currentReadingPositionOffset);
                    }

                    final boolean shouldShowNext;
                    if (horizontalPaging) {
                        // 使用稳定页码判断，避免 onPageScroll 的瞬时上一页回调把按钮隐藏。
                        shouldShowNext = isHorizontalAtLastPage(pageCount);
                    } else {
                        // 纵向/条漫模式恢复原版物理触底判断：还能下拉就不显示。
                        shouldShowNext = !pdfView.canScrollVertically(1);
                    }
                    if (isAtLastPage != shouldShowNext) {
                        isAtLastPage = shouldShowNext;
                        updateNextVolumeButtonState();
                    }
                    if (shouldShowNext && progressPrefs != null && pdfUri != null) {
                        progressPrefs.edit().putInt(pdfUri.toString(), pageCount - 1).apply();
                    }
                    if (shouldShowNext && !horizontalReadingMode && !longComicMode) {
                        // 某些短页 PDF 的回调页码可能没有及时跳到末页，物理触底时修正显示。
                        updateReadingTitle(pageCount - 1, pageCount);
                        if (!readingProgressTracking) {
                            updateReadingProgressUi(pageCount - 1, pageCount, 1f);
                        }
                    }
                })
                .onLoad(nbPages -> {
                    Log.d("PdfOpenPerf", "Pdfium load completed in "
                            + (System.currentTimeMillis() - pdfOpenStartedAtMs)
                            + "ms, pages=" + nbPages);
                    loadedPageCount = nbPages;
                    currentReadingPage = Math.max(0, Math.min(targetPageIndex, nbPages - 1));

                    boolean detectedLongComic = detectLongComicDocument(nbPages);
                    if (!documentTypeKnown || longComicMode != detectedLongComic) {
                        longComicMode = detectedLongComic;
                        documentTypeKnown = true;
                        saveDocumentType();
                    }
                    if (longComicMode && horizontalReadingMode) {
                        horizontalReadingMode = false;
                        savedHorizontalReadingMode = false;
                        saveReadingDirection();
                        pdfView.post(() -> reloadPdfView(currentReadingPage));
                        return;
                    }
                    if (!longComicMode
                            && savedHorizontalReadingMode != horizontalReadingMode) {
                        horizontalReadingMode = savedHorizontalReadingMode;
                        pdfView.post(() -> reloadPdfView(currentReadingPage));
                        return;
                    }
                    readingControlsReady = true;
                    setReadingControlsEnabled(true);
                    updateReadingControlStyles();

                    boolean encrypted = (pdfSecurityInfo != null && pdfSecurityInfo.encrypted)
                            || (pdfPassword != null && !pdfPassword.isEmpty());
                    boolean restricted = pdfSecurityInfo != null && pdfSecurityInfo.hasRestrictions();
                    String source = pdfUri != null && "content".equals(pdfUri.getScheme())
                            ? "saf" : "unknown";
                    DiagnosticContext.setDocumentProfile(this, pdfUri, source, nbPages,
                            encrypted, restricted);
                    DiagnosticContext.finishOperation(this, "pdf_open", true);
                    if (androidPdfViewerAdapter != null) {
                        androidPdfViewerAdapter.invalidatePageSizeCache();
                    }
                    if (pdfOverlay != null) {
                        pdfOverlay.refreshPageGeometry();
                    }
                    updateReadingTitle(currentReadingPage, nbPages);
                    updateReadingProgressUi(
                            currentReadingPage, nbPages, currentReadingPositionOffset);
                    // 横向可直接按最后一页判断；纵向必须等布局完成后检查是否还能继续下拉。
                    isAtLastPage = horizontalReadingMode && !longComicMode
                            && isHorizontalAtLastPage(nbPages);
                    rawModifiedTime = new SimpleDateFormat(
                            "yyyy/MM/dd HH:mm", Locale.getDefault()).format(new Date());

                    restoreReadingPositionIfNeeded();
                    preloadNextVolumeInfo();
                    pdfView.post(() -> {
                        if (isFinishing() || pdfView.getPageCount() <= 0) return;
                        isAtLastPage = horizontalReadingMode && !longComicMode
                                ? isHorizontalAtLastPage(pdfView.getPageCount())
                                : !pdfView.canScrollVertically(1);
                        updateNextVolumeButtonState();
                    });
                })
                .load();
    }

    private void initializeReadingStateForDocument() {
        readingControlsReady = false;
        loadedPageCount = 0;
        currentReadingPage = 0;
        currentReadingPositionOffset = 0f;
        longComicMode = false;
        horizontalReadingMode = false;
        savedHorizontalReadingMode = false;
        documentTypeKnown = false;
        pendingRestorePositionOffset = -1f;
        readingControlsFeatureEnabled =
                ReadingPreferences.isExperimentalReadingControlsEnabled(this);
        isMenuVisible = true;
        readingControlsShown = readingControlsFeatureEnabled;
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
        if (topMenuLayout != null) {
            topMenuLayout.animate().cancel();
            topMenuLayout.setTranslationY(0f);
            topMenuLayout.setVisibility(View.VISIBLE);
        }
        if (readingControlBar != null) {
            readingControlBar.animate().cancel();
            readingControlBar.setTranslationY(0f);
            if (readingControlsFeatureEnabled) {
                readingControlBar.setVisibility(View.VISIBLE);
                readingControlBar.setAlpha(0.78f);
            } else {
                readingControlBar.setVisibility(View.GONE);
                readingControlBar.setAlpha(0f);
            }
        }
        updateNextVolumeButtonOffset(false);

        if (progressPrefs == null || pdfUri == null) {
            updateReadingControlStyles();
            setReadingControlsEnabled(false);
            return;
        }
        String type = progressPrefs.getString(readingTypeKey(), "");
        if (TYPE_LONG_COMIC.equals(type)) {
            longComicMode = true;
            documentTypeKnown = true;
        } else if (TYPE_NORMAL.equals(type)) {
            documentTypeKnown = true;
        }
        savedHorizontalReadingMode = readingControlsFeatureEnabled
                && progressPrefs.getBoolean(readingModeKey(), false);
        horizontalReadingMode = readingControlsFeatureEnabled
                && documentTypeKnown
                && !longComicMode
                && savedHorizontalReadingMode;
        pendingRestorePositionOffset = progressPrefs.contains(readingOffsetKey())
                ? progressPrefs.getFloat(readingOffsetKey(), -1f)
                : -1f;
        updateReadingControlStyles();
        setReadingControlsEnabled(false);
    }

    private void switchReadingDirection(boolean horizontal) {
        if (!readingControlsFeatureEnabled
                || !readingControlsReady
                || pdfView == null
                || pdfView.getPageCount() <= 0) return;
        if (horizontal && longComicMode) {
            Toast.makeText(this, "条漫模式仅支持纵向阅读", Toast.LENGTH_SHORT).show();
            return;
        }
        if (horizontalReadingMode == horizontal) return;

        int page = Math.max(0, pdfView.getCurrentPage());
        float offset = clamp01(pdfView.getPositionOffset());
        horizontalReadingMode = horizontal;
        savedHorizontalReadingMode = horizontal;
        saveReadingDirection();
        pendingRestorePositionOffset = horizontal ? -1f : offset;
        readingControlsReady = false;
        setReadingControlsEnabled(false);
        updateReadingControlStyles();
        reloadPdfView(page);
    }

    private boolean detectLongComicDocument(int pageCount) {
        if (pdfView == null || pageCount <= 0) return false;
        int sampleCount = Math.min(pageCount, 12);
        int validSamples = 0;
        int tallPages = 0;
        int extremePages = 0;
        float singlePageRatio = 0f;
        for (int sample = 0; sample < sampleCount; sample++) {
            int pageIndex = sampleCount == 1
                    ? 0
                    : Math.round(sample * (pageCount - 1f) / (sampleCount - 1f));
            SizeF size;
            try {
                size = pdfView.getPageSize(pageIndex);
            } catch (Exception ignored) {
                continue;
            }
            if (size == null || size.getWidth() <= 0f || size.getHeight() <= 0f) continue;
            validSamples++;
            float ratio = size.getHeight() / size.getWidth();
            singlePageRatio = ratio;
            if (ratio >= LONG_COMIC_PAGE_RATIO) tallPages++;
            if (ratio >= 5f) extremePages++;
        }
        if (validSamples == 0) {
            // 页面尺寸尚未准备好时沿用已保存的分类，避免模式来回跳变。
            return documentTypeKnown && longComicMode;
        }
        if (pageCount == 1) {
            return singlePageRatio >= SINGLE_PAGE_LONG_COMIC_RATIO;
        }
        int requiredTallPages = Math.max(2, (int) Math.ceil(validSamples * 0.7f));
        int requiredExtremePages = Math.max(2, (int) Math.ceil(validSamples * 0.4f));
        return tallPages >= requiredTallPages || extremePages >= requiredExtremePages;
    }

    private void updateReadingControlsVisibility() {
        if (readingControlBar == null) return;
        if (isEditMode || isSearchMode || !readingControlsFeatureEnabled) {
            readingControlsShown = false;
        }
        boolean shouldShow = readingControlsFeatureEnabled
                && readingControlsShown
                && !isEditMode
                && !isSearchMode;
        if (!shouldShow) {
            readingControlBar.animate().cancel();
            readingControlBar.setVisibility(View.GONE);
            readingControlBar.setAlpha(0f);
            readingControlBar.setTranslationY(0f);
        }
        updateNextVolumeButtonOffset(false);
    }

    private void setReadingControlsEnabled(boolean enabled) {
        readingControlsReady = enabled;
        if (btnReadVertical != null) btnReadVertical.setEnabled(enabled);
        if (btnReadHorizontal != null) btnReadHorizontal.setEnabled(enabled);
        if (btnPageAnimationNone != null) btnPageAnimationNone.setEnabled(enabled);
        if (btnPageAnimationEnabled != null) btnPageAnimationEnabled.setEnabled(enabled);
        if (seekReadingProgress != null) seekReadingProgress.setEnabled(enabled);
        if (readingControlBar != null && readingControlBar.getVisibility() == View.VISIBLE) {
            readingControlBar.setAlpha(enabled ? 1f : 0.78f);
        }
    }

    private void updateReadingControlStyles() {
        if (btnReadVertical == null || btnReadHorizontal == null) return;
        styleReadingModeButton(btnReadVertical, !horizontalReadingMode, false);
        styleReadingModeButton(
                btnReadHorizontal, horizontalReadingMode && !longComicMode, longComicMode);
        btnReadHorizontal.setContentDescription(
                longComicMode ? "横向翻页，条漫模式不可用" : "切换为横向翻页");

        boolean animationEnabled = ReadingPreferences.isPageTurnAnimationEnabled(this);
        if (btnPageAnimationNone != null) {
            styleReadingModeButton(btnPageAnimationNone, !animationEnabled, false);
        }
        if (btnPageAnimationEnabled != null) {
            styleReadingModeButton(btnPageAnimationEnabled, animationEnabled, false);
        }
    }

    private void setPageTurnAnimationEnabled(boolean enabled) {
        ReadingPreferences.setPageTurnAnimationEnabled(this, enabled);
        updateReadingControlStyles();
        Toast.makeText(
                this,
                enabled ? "已开启翻页动画" : "已关闭翻页动画",
                Toast.LENGTH_SHORT
        ).show();
    }

    private void styleReadingModeButton(TextView button, boolean selected, boolean blocked) {
        GradientDrawable background = new GradientDrawable();
        background.setCornerRadius(dp(17));
        background.setColor(selected ? 0xFF03A9F4 : 0x22FFFFFF);
        if (!selected) {
            background.setStroke(dp(1), 0x33FFFFFF);
        }
        button.setBackground(background);
        button.setTextColor(blocked ? 0xFF9CA3AF : 0xFFFFFFFF);
        button.setAlpha(blocked ? 0.45f : 1f);
        button.setTypeface(null, selected
                ? android.graphics.Typeface.BOLD
                : android.graphics.Typeface.NORMAL);
    }

    private void updateReadingTitle(int page, int pageCount) {
        if (isEditMode || isSearchMode || tvTopTitle == null) return;
        int safeCount = Math.max(1, pageCount);
        int safePage = Math.max(0, Math.min(page, safeCount - 1));
        tvTopTitle.setText(currentFileName + " (" + (safePage + 1) + "/"
                + safeCount + ")");
    }

    private void updateReadingProgressUi(int page, int pageCount, float positionOffset) {
        if (seekReadingProgress == null || tvReadingProgress == null || readingProgressTracking) {
            return;
        }
        if (longComicMode) {
            int progress = Math.round(clamp01(positionOffset) * COMIC_PROGRESS_MAX);
            seekReadingProgress.setMax(COMIC_PROGRESS_MAX);
            seekReadingProgress.setProgress(progress);
            tvReadingProgress.setText(Math.round(progress * 100f / COMIC_PROGRESS_MAX) + "%");
        } else {
            int safeCount = Math.max(1, pageCount);
            int safePage = Math.max(0, Math.min(page, safeCount - 1));
            seekReadingProgress.setMax(Math.max(0, safeCount - 1));
            seekReadingProgress.setProgress(safePage);
            tvReadingProgress.setText((safePage + 1) + " / " + safeCount);
        }
    }

    private boolean isPhysicallyAtDocumentEnd() {
        if (pdfView == null || pdfView.getPageCount() <= 0) return false;
        return horizontalReadingMode && !longComicMode
                ? isHorizontalAtLastPage(pdfView.getPageCount())
                : !pdfView.canScrollVertically(1);
    }

    private boolean isHorizontalAtLastPage(int pageCount) {
        if (!horizontalReadingMode || longComicMode || pageCount <= 0) return false;

        int observedPage = currentReadingPage;
        if (pdfView != null) {
            observedPage = Math.max(observedPage, pdfView.getCurrentPage());
        }
        return observedPage >= pageCount - 1;
    }

    private void restoreReadingPositionIfNeeded() {
        if (pdfView == null || pendingRestorePositionOffset < 0f
                || horizontalReadingMode || pdfView.getPageCount() <= 0) {
            pendingRestorePositionOffset = -1f;
            return;
        }
        final float restoreOffset = clamp01(pendingRestorePositionOffset);
        pendingRestorePositionOffset = -1f;
        pdfView.post(() -> {
            if (isFinishing() || pdfView.getPageCount() <= 0) return;
            try {
                pdfView.setPositionOffset(restoreOffset, false);
                pdfView.loadPages();
            } catch (Exception ignored) {
            }
        });
    }

    private void scheduleReadingPositionSave(float offset) {
        if (progressPrefs == null || pdfUri == null) return;
        if (pendingReadingProgressSave != null) {
            readingProgressHandler.removeCallbacks(pendingReadingProgressSave);
        }
        final String key = readingOffsetKey();
        final float safeOffset = clamp01(offset);
        pendingReadingProgressSave = () -> {
            if (progressPrefs != null) {
                progressPrefs.edit().putFloat(key, safeOffset).apply();
            }
        };
        readingProgressHandler.postDelayed(pendingReadingProgressSave, 350L);
    }

    private void saveCurrentReadingProgressImmediately() {
        if (progressPrefs == null || pdfUri == null || pdfView == null
                || pdfView.getPageCount() <= 0) return;

        int pageCount = pdfView.getPageCount();
        int page = Math.max(0, Math.min(pdfView.getCurrentPage(), pageCount - 1));
        SharedPreferences.Editor editor = progressPrefs.edit()
                .putInt(pdfUri.toString(), page);

        // 横向是离散单页阅读，页码足够恢复；纵向需要额外保存整个文档的
        // positionOffset，才能恢复到页面内部的大致滚动位置。
        if (!(horizontalReadingMode && !longComicMode)) {
            editor.putFloat(readingOffsetKey(), clamp01(pdfView.getPositionOffset()));
        }
        editor.apply();

        if (pendingReadingProgressSave != null) {
            readingProgressHandler.removeCallbacks(pendingReadingProgressSave);
            pendingReadingProgressSave = null;
        }
    }

    private void saveReadingDirection() {
        if (progressPrefs != null && pdfUri != null) {
            progressPrefs.edit().putBoolean(readingModeKey(), savedHorizontalReadingMode).apply();
        }
    }

    private void saveDocumentType() {
        if (progressPrefs != null && pdfUri != null) {
            progressPrefs.edit().putString(
                    readingTypeKey(), longComicMode ? TYPE_LONG_COMIC : TYPE_NORMAL).apply();
        }
    }

    private String readingModeKey() {
        return "reading_mode_horizontal:" + (pdfUri == null ? "" : pdfUri.toString());
    }

    private String readingTypeKey() {
        return "reading_document_type:" + (pdfUri == null ? "" : pdfUri.toString());
    }

    private String readingOffsetKey() {
        return "reading_position_offset:" + (pdfUri == null ? "" : pdfUri.toString());
    }

    private static float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    private void preloadNextVolumeInfo() {
        preloadedNextUri = null;
        preloadedNextPath = "";
        preloadedNextName = "";

        final Uri sourceUri = pdfUri;
        final String sourceName = (pdfName == null || pdfName.trim().isEmpty())
                ? currentFileName : pdfName;
        final String sourcePath = pdfPath;
        final String sourceParentUri = parentUriStr;

        new Thread(() -> {
            Uri resolvedUri = null;
            String resolvedPath = "";
            String resolvedName = "";
            List<String> siblings = new ArrayList<>();

            try {
                DocumentFile treeFolder = null;
                if (sourceParentUri != null && !sourceParentUri.isEmpty()) {
                    treeFolder = DocumentFile.fromTreeUri(
                            this, Uri.parse(sourceParentUri));
                    if (treeFolder != null && treeFolder.exists()) {
                        for (DocumentFile file : treeFolder.listFiles()) {
                            String name = file.getName();
                            if (!file.isDirectory()
                                    && name != null
                                    && name.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
                                siblings.add(name);
                            }
                        }
                    }
                }

                // SAF 树 URI 失效、权限被收回或目录为空时，继续尝试文件系统路径，
                // 不让一个不可用的 parentUriStr 阻断原本可用的下一卷识别。
                File localParent = null;
                if (sourcePath != null && !sourcePath.isEmpty()) {
                    File currentFile = new File(sourcePath);
                    localParent = currentFile.getParentFile();
                    if (siblings.isEmpty()
                            && localParent != null
                            && localParent.exists()
                            && localParent.isDirectory()) {
                        File[] files = localParent.listFiles();
                        if (files != null) {
                            for (File file : files) {
                                if (file.isFile()
                                        && file.getName().toLowerCase(Locale.ROOT)
                                        .endsWith(".pdf")) {
                                    siblings.add(file.getName());
                                }
                            }
                        }
                    }
                }

                String nextFileName =
                        SmartVolumeSniffer.findNextVolume(sourceName, siblings);
                if (nextFileName != null) {
                    if (treeFolder != null) {
                        for (DocumentFile file : treeFolder.listFiles()) {
                            if (nextFileName.equals(file.getName())) {
                                resolvedUri = file.getUri();
                                resolvedPath = MainActivity.cleanPath(
                                        resolvedUri.getPath());
                                resolvedName = nextFileName;
                                break;
                            }
                        }
                    }

                    if (resolvedUri == null && localParent != null) {
                        File nextFile = new File(localParent, nextFileName);
                        if (nextFile.exists()) {
                            resolvedUri = Uri.fromFile(nextFile);
                            resolvedPath = nextFile.getAbsolutePath();
                            resolvedName = nextFileName;
                        }
                    }
                }

                Log.d("NextVolume", "current=" + sourceName
                        + ", siblings=" + siblings.size()
                        + ", next=" + resolvedName
                        + ", resolved=" + (resolvedUri != null));
            } catch (Exception error) {
                Log.w("NextVolume", "下一卷预加载失败", error);
            }

            final Uri finalResolvedUri = resolvedUri;
            final String finalResolvedPath = resolvedPath;
            final String finalResolvedName = resolvedName;
            runOnUiThread(() -> {
                if (isFinishing()) return;
                // 异步扫描完成时若用户已经切换了文件，丢弃旧文件的扫描结果。
                if (sourceUri != null && pdfUri != null && !sourceUri.equals(pdfUri)) {
                    return;
                }
                preloadedNextUri = finalResolvedUri;
                preloadedNextPath = finalResolvedPath;
                preloadedNextName = finalResolvedName;
                updateNextVolumeButtonState();
            });
        }, "next-volume-preload").start();
    }

    private void executeLoadNextVolume() {
        nextVolumeButtonTargetVisible = false;
        btnNextVolume.animate().cancel();
        btnNextVolume.setVisibility(View.GONE);
        Toast.makeText(this, "正在无缝加载: " + preloadedNextName, Toast.LENGTH_SHORT).show();

        deleteUnlockedPdfTemp();
        boolean inheritHorizontalMode = readingControlsFeatureEnabled
                && horizontalReadingMode
                && !longComicMode;

        pdfPath = preloadedNextPath;
        pdfName = preloadedNextName;
        pdfUri = preloadedNextUri;
        workingPdfUri = null;

        // “下一卷”属于连续阅读：将当前翻页方向写入新文档的本地偏好，
        // 避免横向阅读进入下一卷后又回到默认纵向。
        if (progressPrefs != null && pdfUri != null) {
            progressPrefs.edit()
                    .putBoolean(readingModeKey(), inheritHorizontalMode)
                    .apply();
        }
        pdfPassword = "";
        pdfSecurityInfo = null;
        securityInspectionRunning = false;
        pendingSecurityActions.clear();

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

        initializeReadingStateForDocument();
        pendingRestorePositionOffset = -1f;
        prepareSecurePdfAndLoad(0, null, false);
    }

    private void toggleEditMode(boolean enterEdit) {
        if (enterEdit && pdfSecurityInfo == null) {
            Toast.makeText(this, "正在读取文档权限，请稍候…", Toast.LENGTH_SHORT).show();
            ensureSecurityInfo(() -> toggleEditMode(true));
            return;
        }
        if (enterEdit && pdfSecurityInfo != null && !pdfSecurityInfo.canAnnotate()) {
            Toast.makeText(this, "文档权限禁止添加或修改批注", Toast.LENGTH_LONG).show();
            return;
        }
        if (enterEdit && isSearchMode) {
            toggleSearchMode(false);
        }
        isEditMode = enterEdit;

        updateReadingControlsVisibility();
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
            updateReadingTitle(currentPage, pageCount);
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
        schedulePdfViewportRelayout(null);
    }

    private void schedulePdfViewportRelayout(Runnable afterRelayout) {
        if (pdfContentFrame == null || pdfView == null || pdfOverlay == null) {
            if (afterRelayout != null) afterRelayout.run();
            return;
        }

        final float positionOffset;
        try {
            positionOffset = pdfView.getPageCount() > 0 ? pdfView.getPositionOffset() : 0f;
        } catch (Exception ignored) {
            if (afterRelayout != null) afterRelayout.run();
            return;
        }

        pdfContentFrame.requestLayout();
        pdfView.requestLayout();
        pdfOverlay.requestLayout();

        // 等父布局真正完成高度调整，再恢复位置和刷新渲染/批注/搜索坐标。
        pdfContentFrame.post(() -> pdfContentFrame.post(() -> {
            if (isFinishing()) return;
            if (pdfView.getPageCount() > 0) {
                try {
                    pdfView.setPositionOffset(positionOffset, false);
                    pdfView.loadPages();
                } catch (Exception ignored) {
                    // PDF 尚在异步加载时只刷新布局；onLoad 后会重新建立页面数据。
                }
            }
            pdfOverlay.refreshPageGeometry();
            pdfOverlay.invalidate();
            pdfView.invalidate();
            if (afterRelayout != null) afterRelayout.run();
        }));
    }

    private void handleBackAction() {
        if (isSearchMode) {
            toggleSearchMode(false);
            return;
        }
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
        if (pdfOverlay == null || pdfOverlay.isActionStackEmpty()) {
            Toast.makeText(this, "当前没有任何批注内容，无需保存", Toast.LENGTH_SHORT).show();
            return;
        }
        if (pdfSecurityInfo == null) {
            Toast.makeText(this, "正在读取文档权限，请稍候…", Toast.LENGTH_SHORT).show();
            ensureSecurityInfo(this::handleSaveAction);
            return;
        }
        if (!pdfSecurityInfo.canAnnotate()) {
            Toast.makeText(this, "文档权限禁止添加或修改批注", Toast.LENGTH_LONG).show();
            return;
        }
        if (pdfSecurityInfo.encrypted && pdfSecurityInfo.hasRestrictions()) {
            showAnnotationProtectionDialog(this::showSaveActionChoice);
            return;
        }
        annotationOpenPassword = pdfPassword == null ? "" : pdfPassword;
        annotationManagementPassword = "";
        showSaveActionChoice();
    }

    private void showSaveActionChoice() {
        new AlertDialog.Builder(this)
                .setTitle("保存批注")
                .setItems(new String[]{"覆盖原文件 (直接保存)", "另存为新文件 (副本)"}, (dialog, which) -> {
                    if (which == 0) executeOverwriteSave();
                    else executeSaveCopy();
                })
                .show();
    }

    private void showAnnotationProtectionDialog(Runnable onValidated) {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(4), dp(20), 0);

        TextView openLabel = new TextView(this);
        openLabel.setText("当前打开密码（没有则留空）");
        openLabel.setTextSize(14);
        EditText openInput = new EditText(this);
        openInput.setSingleLine(true);
        openInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        openInput.setHint("用于保留副本的打开方式");
        if (pdfSecurityInfo != null && !pdfSecurityInfo.ownerPermission) {
            openInput.setText(pdfPassword == null ? "" : pdfPassword);
        }

        TextView ownerLabel = new TextView(this);
        ownerLabel.setText("权限管理密码");
        ownerLabel.setTextSize(14);
        ownerLabel.setPadding(0, dp(14), 0, 0);
        EditText ownerInput = new EditText(this);
        ownerInput.setSingleLine(true);
        ownerInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        ownerInput.setHint("用于验证并保留原有权限限制");
        if (pdfSecurityInfo != null && pdfSecurityInfo.ownerPermission) {
            ownerInput.setText(pdfPassword == null ? "" : pdfPassword);
        }

        content.addView(openLabel);
        content.addView(openInput);
        content.addView(ownerLabel);
        content.addView(ownerInput);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("保留现有权限保护")
                .setMessage("当前文档允许批注，但保存受保护 PDF 时需要权限管理密码。生成文件将继续保留原有权限设置。")
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
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
                    new Thread(() -> {
                        try {
                            PdfSecurityContext.Info managementInfo = PdfSecurityContext.inspect(
                                    this, pdfUri, pdfPath, managementPassword);
                            if (!managementInfo.ownerPermission) {
                                throw new IllegalStateException("权限管理密码不正确");
                            }
                            if (!openPassword.isEmpty()) {
                                PdfSecurityContext.inspect(this, pdfUri, pdfPath, openPassword);
                            }
                            runOnUiThread(() -> {
                                annotationOpenPassword = openPassword;
                                annotationManagementPassword = managementPassword;
                                dialog.dismiss();
                                if (onValidated != null) onValidated.run();
                            });
                        } catch (Throwable error) {
                            runOnUiThread(() -> {
                                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                                ownerInput.setError(PdfSecurityContext.readableError(error));
                            });
                        }
                    }, "annotation-security-validate").start();
                }));
        dialog.show();
    }

    private void executeOverwriteSave() {
        if (pdfUri == null) return;
        if (pdfOverlay == null || pdfOverlay.isActionStackEmpty()) {
            Toast.makeText(this, "当前没有任何批注内容，无需保存", Toast.LENGTH_SHORT).show();
            return;
        }

        DiagnosticContext.startOperation(this, "annotation_save");
        DiagnosticContext.setAnnotationProfile(this,
                currentEditState == EditState.DOODLE ? "ink" : "none", "overwrite");
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
            try {
                try (BufferedOutputStream tempOut = new BufferedOutputStream(
                             new FileOutputStream(tempFile), 1024 * 1024)) {
                    PdfBoxAnnotationWriter.write(
                            this,
                            pdfUri,
                            pdfPath,
                            tempOut,
                            actions,
                            pageMetrics,
                            annotationOpenPassword,
                            annotationManagementPassword,
                            pdfSecurityInfo
                    );
                    tempOut.flush();
                }
                PdfSecurityContext.validateProtectedFile(
                        this,
                        tempFile,
                        pdfSecurityInfo != null && pdfSecurityInfo.encrypted
                                ? annotationOpenPassword : ""
                );

                try (InputStream tempIn = new FileInputStream(tempFile);
                     OutputStream originalOut = openPdfOutputStream()) {
                    if (originalOut == null) throw new Exception("没有原文件的写入权限，请使用另存为副本");
                    copyStream(tempIn, originalOut);
                    originalOut.flush();
                }

                if (pdfPath != null && !pdfPath.isEmpty()) {
                    MediaScannerConnection.scanFile(this, new String[]{pdfPath}, new String[]{"application/pdf"}, null);
                }

                DiagnosticContext.finishOperation(this, "annotation_save", true);
                runOnUiThread(() -> {
                    progressDialog.dismiss();
                    if (dbHelper != null) dbHelper.updateLastModifiedTime(pdfUri.toString());
                    PdfSecurityContext.clear(pdfUri);
                    pdfSecurityInfo = null;
                    deleteUnlockedPdfTemp();
                    workingPdfUri = pdfUri;
                    directPasswordFallbackAttempted = false;
                    Toast.makeText(this, "批注保存成功，已覆盖原文件", Toast.LENGTH_LONG).show();
                    toggleEditMode(false);
                    resetTextSelectionRepository();
                    reloadPdfView(currentPage);
                });
            } catch (Throwable e) {
                e.printStackTrace();
                DiagnosticContext.finishOperation(this, "annotation_save", false);
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

        DiagnosticContext.startOperation(this, "annotation_save");
        DiagnosticContext.setAnnotationProfile(this,
                currentEditState == EditState.DOODLE ? "ink" : "none", "save_as");
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

            try {
                try (BufferedOutputStream target = new BufferedOutputStream(
                             new FileOutputStream(destFile), 1024 * 1024)) {
                    PdfBoxAnnotationWriter.write(
                            this,
                            pdfUri,
                            pdfPath,
                            target,
                            actions,
                            pageMetrics,
                            annotationOpenPassword,
                            annotationManagementPassword,
                            pdfSecurityInfo
                    );
                    target.flush();
                }
                PdfSecurityContext.validateProtectedFile(
                        this,
                        destFile,
                        pdfSecurityInfo != null && pdfSecurityInfo.encrypted
                                ? annotationOpenPassword : ""
                );

                DiagnosticContext.setDocumentProfile(this, Uri.fromFile(destFile), "app_copy",
                        pdfView == null ? -1 : pdfView.getPageCount(),
                        pdfSecurityInfo != null && pdfSecurityInfo.encrypted,
                        pdfSecurityInfo != null && pdfSecurityInfo.hasRestrictions());
                DiagnosticContext.finishOperation(this, "annotation_save", true);
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
            } catch (Throwable e) {
                e.printStackTrace();
                DiagnosticContext.finishOperation(this, "annotation_save", false);
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

    private boolean handleReaderTap(MotionEvent event) {
        if (pdfOverlay != null && pdfOverlay.hasTextSelection()) {
            clearTextSelectionUi();
            return true;
        }
        if (isEditMode || isSearchMode) return true;

        if (horizontalReadingMode && !longComicMode && pdfView != null) {
            float width = Math.max(1f, pdfView.getWidth());
            float x = event == null ? width / 2f : event.getX();
            if (x < width / 3f) {
                turnHorizontalPage(-1);
                return true;
            }
            if (x > width * 2f / 3f) {
                turnHorizontalPage(1);
                return true;
            }
        }

        toggleMenuVisibility();
        return true;
    }

    private void turnHorizontalPage(int direction) {
        if (pdfView == null || pdfView.getPageCount() <= 0 || direction == 0) return;
        int current = Math.max(0, pdfView.getCurrentPage());
        int target = Math.max(0, Math.min(current + direction, pdfView.getPageCount() - 1));
        if (target == current) return;
        boolean smooth = ReadingPreferences.isPageTurnAnimationEnabled(this);
        try {
            pdfView.jumpTo(target, smooth);
        } catch (Exception ignored) {
            // 页面仍在加载或 Activity 已退出时忽略本次点击。
        }
    }

    private void updateNextVolumeButtonOffset(boolean animate) {
        if (btnNextVolume == null) return;
        Runnable update = () -> {
            float target = 0f;
            if (readingControlsShown
                    && readingControlBar != null
                    && readingControlBar.getVisibility() == View.VISIBLE) {
                target = -(readingControlBar.getHeight() + dp(12));
            }
            btnNextVolume.animate().cancel();
            if (animate) {
                btnNextVolume.animate().translationY(target).setDuration(220).start();
            } else {
                btnNextVolume.setTranslationY(target);
            }
        };
        if (readingControlBar != null && readingControlBar.getHeight() == 0
                && readingControlsShown) {
            readingControlBar.post(update);
        } else {
            update.run();
        }
    }

    private void toggleMenuVisibility() {
        if (isEditMode || isSearchMode) return;
        if (isMenuVisible) {
            hideReaderChrome();
        } else {
            showReaderChrome();
        }
    }

    private void showReaderChrome() {
        if (isEditMode || isSearchMode) return;
        isMenuVisible = true;
        readingControlsShown = readingControlsFeatureEnabled;
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);

        topMenuLayout.animate().cancel();
        topMenuLayout.setVisibility(View.VISIBLE);
        if (topMenuLayout.getTranslationY() != 0f) {
            topMenuLayout.animate().translationY(0f).setDuration(220).start();
        }

        if (readingControlBar != null) {
            readingControlBar.animate().cancel();
            if (readingControlsFeatureEnabled) {
                readingControlBar.setVisibility(View.VISIBLE);
                readingControlBar.setAlpha(0f);
                readingControlBar.setTranslationY(dp(96));
                readingControlBar.animate()
                        .alpha(readingControlsReady ? 1f : 0.78f)
                        .translationY(0f)
                        .setDuration(220)
                        .start();
                readingControlBar.post(() -> updateNextVolumeButtonOffset(true));
            } else {
                readingControlBar.setVisibility(View.GONE);
                readingControlBar.setAlpha(0f);
                readingControlBar.setTranslationY(0f);
                updateNextVolumeButtonOffset(true);
            }
        }
    }

    private void hideReaderChrome() {
        isMenuVisible = false;
        readingControlsShown = false;
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        );

        topMenuLayout.animate().cancel();
        topMenuLayout.animate().translationY(-topMenuLayout.getHeight()).setDuration(220)
                .withEndAction(() -> topMenuLayout.setVisibility(View.GONE)).start();

        if (readingControlBar != null && readingControlBar.getVisibility() == View.VISIBLE) {
            readingControlBar.animate().cancel();
            readingControlBar.animate()
                    .alpha(0f)
                    .translationY(dp(96))
                    .setDuration(200)
                    .withEndAction(() -> {
                        readingControlBar.setVisibility(View.GONE);
                        readingControlBar.setTranslationY(0f);
                    })
                    .start();
        }
        updateNextVolumeButtonOffset(true);
    }

    private void showMainMenu(View anchor) {
        android.widget.PopupMenu popup = new android.widget.PopupMenu(this, anchor);
        if (isEditMode) {
            popup.getMenu().add(0, 10, 0, "保存批注");
            popup.getMenu().add(0, 11, 1, "退出批注模式");
        } else if (isSearchMode) {
            popup.getMenu().add(0, 20, 0, "清除搜索结果");
            popup.getMenu().add(0, 21, 1, "退出搜索模式");
        } else {
            popup.getMenu().add(0, 1, 0, "批注模式");
            popup.getMenu().add(0, 2, 1, "搜索模式");
            boolean favorite = dbHelper != null && pdfPath != null && dbHelper.isFavorite(pdfPath, pdfName);
            popup.getMenu().add(0, 3, 2, favorite ? "取消收藏" : "收藏");
        }
        popup.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1: toggleEditMode(true); return true;
                case 2: toggleSearchMode(true); return true;
                case 3: handleFavoriteToggle(); return true;
                case 10: handleSaveAction(); return true;
                case 11: handleBackAction(); return true;
                case 20: clearPdfSearch(); return true;
                case 21: toggleSearchMode(false); return true;
                default: return false;
            }
        });
        popup.show();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static final class SearchMatch {
        final int pageIndex;
        final List<AndroidPdfViewerAdapter.ViewerSearchHighlight> rects;
        final float firstTop;
        final float firstLeft;

        SearchMatch(
                int pageIndex,
                List<AndroidPdfViewerAdapter.ViewerSearchHighlight> rects
        ) {
            this.pageIndex = pageIndex;
            this.rects = rects == null
                    ? Collections.emptyList()
                    : Collections.unmodifiableList(new ArrayList<>(rects));
            float top = Float.MAX_VALUE;
            float left = Float.MAX_VALUE;
            for (AndroidPdfViewerAdapter.ViewerSearchHighlight item : this.rects) {
                if (item == null || item.rectInPageRatio == null) continue;
                if (item.rectInPageRatio.top < top
                        || (Float.compare(item.rectInPageRatio.top, top) == 0
                        && item.rectInPageRatio.left < left)) {
                    top = item.rectInPageRatio.top;
                    left = item.rectInPageRatio.left;
                }
            }
            this.firstTop = top == Float.MAX_VALUE ? 0f : top;
            this.firstLeft = left == Float.MAX_VALUE ? 0f : left;
        }
    }

    private void handleFavoriteToggle() {
        if (dbHelper == null) return;
        boolean isNowFav = dbHelper.toggleFavorite(pdfPath, pdfName, pdfUri.toString(), rawModifiedTime);
        Toast.makeText(this, isNowFav ? "已加入收藏夹" : "已取消收藏", Toast.LENGTH_SHORT).show();
    }
    @Override
    protected void onPause() {
        // Activity 进入后台、被其他应用覆盖或用户返回首页时立即落盘，
        // 不依赖 350 ms 防抖任务是否来得及执行。
        saveCurrentReadingProgressImmediately();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        saveCurrentReadingProgressImmediately();
        if (textSelectionActionMode != null) {
            textSelectionActionMode.finish();
            textSelectionActionMode = null;
        }
        if (textSelectionRepository != null) {
            textSelectionRepository.close();
            textSelectionRepository = null;
        }
        if (pdfSearchManager != null) {
            pdfSearchManager.close();
            pdfSearchManager = null;
        }
        if (pendingReadingProgressSave != null) {
            readingProgressHandler.removeCallbacks(pendingReadingProgressSave);
            pendingReadingProgressSave = null;
        }
        deleteUnlockedPdfTemp();
        super.onDestroy();
    }

}