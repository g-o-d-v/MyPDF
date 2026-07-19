package com.nless.mypdf;

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
import android.util.Log;
import android.view.ActionMode;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.view.View;
import android.widget.EditText;
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

    // 🌟 新增：用来存储阅读进度的本地轻量级文件（不碰数据库）
    private SharedPreferences progressPrefs;

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
    }

    private void initViews() {
        pdfView = findViewById(R.id.pdfView);
        pdfOverlay = findViewById(R.id.pdfOverlay);
        topMenuLayout = findViewById(R.id.top_menu_layout);
        annotationPanelContainer = findViewById(R.id.annotation_panel_container);
        searchPanelContainer = findViewById(R.id.search_panel_container);
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
        setupSearchPanelListeners();

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                handleBackAction();
            }
        });
    }

    private void updateNextVolumeButtonState() {
        if (isAtLastPage && preloadedNextUri != null && !isEditMode && !isSearchMode) {
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
            tvTopTitle.setText(currentFileName + " (" + (currentPage + 1) + "/" + pageCount + ")");
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
                showSearchResults(keyword, results);
            }

            @Override
            public void onSearchFailed(Throwable error) {
                if (!isActiveSearchRequest(requestId)) return;
                setSearchInProgress(false);
                clearPdfSearch(false);
                showSearchStatus("搜索失败：" + safeMessage(error), true);
            }

            @Override
            public void onSearchCancelled() {
                if (!isActiveSearchRequest(requestId)) return;
                setSearchInProgress(false);
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

            // 🌟 读取无侵入式的历史阅读进度
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
                .swipeHorizontal(false)
//                .scrollHandle(new DefaultScrollHandle(this))
                .enableAntialiasing(false)
                .pageFitPolicy(com.github.barteksc.pdfviewer.util.FitPolicy.WIDTH)
                .fitEachPage(true)
                .autoSpacing(false)
                .pageSnap(false)
                .pageFling(false)
                .onLoad(pageCount -> Log.d("PdfOpenPerf",
                        "Pdfium load completed in "
                                + (System.currentTimeMillis() - pdfOpenStartedAtMs)
                                + "ms, pages=" + pageCount))
                .onTap(e -> {
                    if (pdfOverlay != null && pdfOverlay.hasTextSelection()) {
                        clearTextSelectionUi();
                        return true;
                    }
                    if (!isEditMode && !isSearchMode) {
                        toggleMenuVisibility();
                    }
                    return true;
                })
                .onLongPress(this::handlePdfLongPress)
                .onError(error -> {
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
                    if (!isEditMode && !isSearchMode) {
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
                        if (!isEditMode && !isSearchMode) {
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
                    if (androidPdfViewerAdapter != null) {
                        androidPdfViewerAdapter.invalidatePageSizeCache();
                    }
                    if (pdfOverlay != null) {
                        pdfOverlay.refreshPageGeometry();
                    }
                    if (!isEditMode && !isSearchMode) {
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

        deleteUnlockedPdfTemp();
        pdfPath = preloadedNextPath;
        pdfName = preloadedNextName;
        pdfUri = preloadedNextUri;
        workingPdfUri = null;
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


        // 🌟 连卷时，不要读历史进度，强制从 0 (第1页) 开始看新的一卷！
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
        if (isEditMode || isSearchMode) return;
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
            pdfSearchManager.close();
            pdfSearchManager = null;
        }
        deleteUnlockedPdfTemp();
        super.onDestroy();
    }

}