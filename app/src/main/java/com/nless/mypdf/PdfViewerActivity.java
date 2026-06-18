package com.nless.mypdf;

import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.RectF;
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

import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;

import com.nless.pdf_search_engine.core.PdfSearchManager;
import com.nless.pdf_search_engine.ocr.OcrPageResult;
import com.nless.pdf_search_engine.ocr.OcrTextBlock;
import com.nless.pdf_search_engine.paddle.PaddleOcrEngine;

import android.graphics.Canvas;

import com.nless.pdf_search_engine.pdfium.PdfiumTextSearchEngine;
import com.nless.pdf_search_engine.pdfium.PdfiumTextSearchResult;

import com.nless.pdf_search_engine.core.PdfSearchCallback;
import com.nless.pdf_search_engine.core.PdfSearchMode;
import com.nless.pdf_search_engine.core.PdfSearchOptions;
import com.nless.pdf_search_engine.core.PdfSearchRect;
import com.nless.pdf_search_engine.core.PdfSearchResult;
import com.nless.pdf_search_engine.core.PdfSearchSource;

import com.nless.pdf_search_engine.androidpdfviewer.AndroidPdfViewerAdapter;
import com.nless.pdf_search_engine.androidpdfviewer.PdfOverlaySearchHighlighter;



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

    private final List<PdfOverlayView.SearchHighlight> currentSearchHighlights = new ArrayList<>();
    private int currentSearchIndex = -1;
    private String currentSearchKeyword = "";

    private final java.util.Map<String, OcrPageResult> ocrPageMemoryCache = new java.util.HashMap<>();
    private long ocrSearchToken = 0;

    private final PdfiumTextSearchEngine pdfiumTextSearchEngine = new PdfiumTextSearchEngine();
    private long textSearchToken = 0;

    private PdfSearchManager pdfSearchManager;

    private AndroidPdfViewerAdapter androidPdfViewerAdapter;
    private PdfOverlaySearchHighlighter overlaySearchHighlighter;


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

        initViews();
        setupListeners();
        loadPdfFromIntent();
        updateFavoriteIconState();
        updateAllColorBlocksUI();

    }

    @Override
    protected void onDestroy() {
        if (pdfSearchManager != null) {
            pdfSearchManager.cancel();
            pdfSearchManager.clearCache();
        }
        super.onDestroy();
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

        androidPdfViewerAdapter = new AndroidPdfViewerAdapter(pdfView);
        overlaySearchHighlighter = new PdfOverlaySearchHighlighter();


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

        tvTopTitle.setOnLongClickListener(v -> {
            showPdfSearchDialog();
            return true;
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

//    private void showPdfSearchDialog() {
//        final android.widget.EditText input = new android.widget.EditText(this);
//        input.setHint("输入当前页搜索关键词");
//
//        new AlertDialog.Builder(this)
//                .setTitle("搜索 PDF 内容")
//                .setView(input)
//                .setPositiveButton("OCR搜索当前页", (dialog, which) -> {
//                    String keyword = input.getText().toString().trim();
//                    if (!keyword.isEmpty()) {
//                        searchCurrentPageByOcr(keyword);
//                    }
//                })
//                .setNegativeButton("取消", null)
//                .setNeutralButton("清除高亮", (dialog, which) -> {
//                    clearPdfSearch();
//                })
//                .show();
//    }

    private void showPdfSearchDialog() {
        final android.widget.EditText input = new android.widget.EditText(this);
        input.setHint("输入搜索关键词");

        new AlertDialog.Builder(this)
                .setTitle("搜索 PDF 内容")
                .setView(input)
                .setPositiveButton("下一步", (dialog, which) -> {
                    String keyword = input.getText().toString().trim();
                    if (keyword.isEmpty()) return;

                    showSearchModeDialog(keyword);
                })
                .setNegativeButton("取消", null)
                .setNeutralButton("清除", (dialog, which) -> clearPdfSearch())
                .show();
    }

    private void showSearchModeDialog(String keyword) {
        String[] modes = new String[]{
                "智能当前页：文本层优先，失败后 OCR 当前页",
                "文本全文搜索：适合普通 PDF",
                "智能全文搜索：全文文本层优先，失败后 OCR 当前页",
                "OCR 当前页：适合扫描件"
        };

        new AlertDialog.Builder(this)
                .setTitle("选择搜索模式")
                .setItems(modes, (dialog, which) -> {
                    if (which == 0) {
                        // 当前页文本层 -> 当前页 OCR
                        searchWithEngine(keyword, PdfSearchMode.TEXT_THEN_OCR, true);

                    } else if (which == 1) {
                        // 全文文本层搜索
                        searchWithEngine(keyword, PdfSearchMode.TEXT_ONLY, false);

                    } else if (which == 2) {
                        // 全文文本层搜索，如果全文都没有，再 OCR 当前页
                        searchWithEngine(keyword, PdfSearchMode.TEXT_THEN_OCR, false);

                    } else if (which == 3) {
                        // 只 OCR 当前页
                        searchWithEngine(keyword, PdfSearchMode.OCR_ONLY, true);
                    }
                })
                .show();
    }






    private void searchCurrentPageByOcr(String keyword) {
        if (pdfView == null || pdfOverlay == null || pdfUri == null) return;

        currentSearchKeyword = keyword;
        currentSearchHighlights.clear();
        currentSearchIndex = -1;
        pdfOverlay.clearSearchHighlights();

        final int pageIndex = pdfView.getCurrentPage();

        /**
         * 1440 准确率更好，但速度略慢。
         * 如果你觉得慢，可以先改成 1280。
         */
        final int renderWidth = 1280;
        final String cacheKey = buildOcrCacheKey(pageIndex, renderWidth);
        final long myToken = ++ocrSearchToken;

        Toast.makeText(this, "正在搜索当前页...", Toast.LENGTH_SHORT).show();

        new Thread(() -> {
            Bitmap pageBitmap = null;

            try {
                OcrPageResult ocrResult;

                synchronized (ocrPageMemoryCache) {
                    ocrResult = ocrPageMemoryCache.get(cacheKey);
                }

                if (ocrResult == null) {
                    pageBitmap = renderPdfPageForOcr(pageIndex, renderWidth);

                    if (pageBitmap == null) {
                        runOnUiThread(() -> Toast.makeText(this, "页面渲染失败", Toast.LENGTH_SHORT).show());
                        return;
                    }

                    ocrResult = PaddleOcrEngine.getInstance()
                            .recognizePage(getApplicationContext(), pageBitmap);

                    if (ocrResult == null) {
                        runOnUiThread(() -> Toast.makeText(this, "OCR识别失败", Toast.LENGTH_SHORT).show());
                        return;
                    }

                    synchronized (ocrPageMemoryCache) {
                        ocrPageMemoryCache.put(cacheKey, ocrResult);
                    }
                }

                List<PdfOverlayView.SearchHighlight> highlights =
                        buildHighlightsFromOcrResult(pageIndex, keyword, ocrResult);

                OcrPageResult finalOcrResult = ocrResult;

                runOnUiThread(() -> {
                    if (myToken != ocrSearchToken) return;

                    currentSearchHighlights.clear();
                    currentSearchHighlights.addAll(highlights);

                    pdfOverlay.setSearchHighlights(currentSearchHighlights);

                    if (currentSearchHighlights.isEmpty()) {
                        Toast.makeText(this, "当前页未找到：" + keyword, Toast.LENGTH_SHORT).show();
                    } else {
                        currentSearchIndex = 0;
                        pdfOverlay.setCurrentSearchIndex(0);
                        Toast.makeText(this, "找到 " + currentSearchHighlights.size() + " 处", Toast.LENGTH_SHORT).show();
                    }
                });

            } catch (Exception e) {
                e.printStackTrace();
                runOnUiThread(() -> Toast.makeText(this, "搜索失败：" + e.getMessage(), Toast.LENGTH_LONG).show());

            } finally {
                if (pageBitmap != null && !pageBitmap.isRecycled()) {
                    pageBitmap.recycle();
                }
            }
        }).start();
    }


    private Bitmap renderPdfPageForOcr(int pageIndex, int targetWidthPx) {
        ParcelFileDescriptor pfd = null;
        PdfRenderer renderer = null;
        PdfRenderer.Page page = null;

        try {
            pfd = getContentResolver().openFileDescriptor(pdfUri, "r");
            if (pfd == null) {
                return null;
            }

            renderer = new PdfRenderer(pfd);

            if (pageIndex < 0 || pageIndex >= renderer.getPageCount()) {
                return null;
            }

            page = renderer.openPage(pageIndex);

            int pageWidth = page.getWidth();
            int pageHeight = page.getHeight();

            if (pageWidth <= 0 || pageHeight <= 0) {
                return null;
            }

            float scale = targetWidthPx / (float) pageWidth;
            int targetHeightPx = Math.max(1, Math.round(pageHeight * scale));

            Bitmap bitmap = Bitmap.createBitmap(
                    targetWidthPx,
                    targetHeightPx,
                    Bitmap.Config.ARGB_8888
            );

            Canvas canvas = new Canvas(bitmap);
            canvas.drawColor(android.graphics.Color.WHITE);

            page.render(
                    bitmap,
                    null,
                    null,
                    PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY
            );

            return bitmap;

        } catch (Exception e) {
            e.printStackTrace();
            return null;

        } finally {
            try {
                if (page != null) page.close();
            } catch (Exception ignored) {
            }

            try {
                if (renderer != null) renderer.close();
            } catch (Exception ignored) {
            }

            try {
                if (pfd != null) pfd.close();
            } catch (Exception ignored) {
            }
        }
    }


    private List<PdfOverlayView.SearchHighlight> buildHighlightsFromOcrResult(
            int pageIndex,
            String keyword,
            OcrPageResult ocrResult
    ) {
        List<PdfOverlayView.SearchHighlight> result = new ArrayList<>();

        if (ocrResult == null || ocrResult.blocks == null) return result;
        if (keyword == null || keyword.trim().isEmpty()) return result;

        String lowerKeyword = keyword.toLowerCase(Locale.getDefault());

        for (OcrTextBlock block : ocrResult.blocks) {
            if (block == null || block.text == null || block.rectInBitmap == null) continue;

            String originalText = block.text;
            String lowerText = originalText.toLowerCase(Locale.getDefault());

            int fromIndex = 0;

            while (true) {
                int matchIndex = lowerText.indexOf(lowerKeyword, fromIndex);
                if (matchIndex < 0) break;

                RectF keywordBitmapRect = estimateKeywordRectInBlock(
                        block.rectInBitmap,
                        originalText,
                        matchIndex,
                        keyword.length()
                );

                RectF docRect = bitmapRectToDocRect(
                        pageIndex,
                        keywordBitmapRect,
                        ocrResult.bitmapWidth,
                        ocrResult.bitmapHeight
                );

                if (docRect != null) {
                    result.add(new PdfOverlayView.SearchHighlight(pageIndex, docRect));
                }

                fromIndex = matchIndex + Math.max(1, lowerKeyword.length());
            }
        }

        return result;
    }

    private RectF estimateKeywordRectInBlock(
            RectF blockRect,
            String blockText,
            int matchStart,
            int keywordLength
    ) {
        if (blockRect == null || blockText == null || blockText.isEmpty()) {
            return blockRect;
        }

        int totalLength = blockText.length();
        if (totalLength <= 0) return blockRect;

        int matchEnd = Math.min(totalLength, matchStart + keywordLength);

        float blockWidth = blockRect.width();
        float blockHeight = blockRect.height();

        float startRatio = matchStart / (float) totalLength;
        float endRatio = matchEnd / (float) totalLength;

        float left = blockRect.left + blockWidth * startRatio;
        float right = blockRect.left + blockWidth * endRatio;

        /**
         * OCR 检测框有时候上下会偏大。
         * 这里把高度稍微收缩一点，让高亮更像文字背景。
         */
        float centerY = blockRect.centerY();
        float refinedHeight = blockHeight * 0.78f;

        float top = centerY - refinedHeight / 2f;
        float bottom = centerY + refinedHeight / 2f;

        /**
         * 给左右一点 padding，避免关键词边缘被裁掉。
         */
        float paddingX = Math.max(2f, blockWidth / Math.max(1, totalLength) * 0.18f);

        left -= paddingX;
        right += paddingX;

        if (left < blockRect.left) left = blockRect.left;
        if (right > blockRect.right) right = blockRect.right;

        return new RectF(left, top, right, bottom);
    }



    private RectF bitmapRectToDocRect(
            int pageIndex,
            RectF bitmapRect,
            int bitmapWidth,
            int bitmapHeight
    ) {
        if (pdfView == null || bitmapRect == null || bitmapWidth <= 0 || bitmapHeight <= 0) {
            return null;
        }

        float pageWidth = pdfView.getPageSize(pageIndex).getWidth();
        float pageHeight = pdfView.getPageSize(pageIndex).getHeight();
        float spacing = pdfView.getSpacingPx();

        float pageStartY = pageIndex * (pageHeight + spacing);

        float left = bitmapRect.left / bitmapWidth * pageWidth;
        float right = bitmapRect.right / bitmapWidth * pageWidth;

        float top = bitmapRect.top / bitmapHeight * pageHeight;
        float bottom = bitmapRect.bottom / bitmapHeight * pageHeight;

        RectF raw = new RectF(
                left,
                pageStartY + top,
                right,
                pageStartY + bottom
        );

        return normalizeHighlightRect(raw, pageHeight);

    }

    private RectF normalizeHighlightRect(RectF rect, float pageHeight) {
        if (rect == null) return null;

        float height = rect.height();

        /**
         * 限制最小和最大高亮高度。
         * 这些值可以后续按实际视觉效果微调。
         */
        float minHeight = pageHeight * 0.012f;
        float maxHeight = pageHeight * 0.035f;

        float targetHeight = height;

        if (targetHeight < minHeight) {
            targetHeight = minHeight;
        } else if (targetHeight > maxHeight) {
            targetHeight = maxHeight;
        }

        float cy = rect.centerY();

        return new RectF(
                rect.left,
                cy - targetHeight / 2f,
                rect.right,
                cy + targetHeight / 2f
        );
    }


//    private void searchInCurrentPdf(String keyword) {
//        if (pdfView == null || pdfOverlay == null) return;
//
//        currentSearchKeyword = keyword;
//        currentSearchHighlights.clear();
//        currentSearchIndex = -1;
//
//        pdfOverlay.clearSearchHighlights();
//
//        Toast.makeText(this, "正在搜索：" + keyword, Toast.LENGTH_SHORT).show();
//
//        /*
//         * 这里需要接 AndroidPdfViewer 的 searchText API。
//         * 不同版本签名可能不同。
//         * 下一步我们根据编译提示适配。
//         */
//        pdfView.search
//    }

    private RectF convertPageRectToDocRect(int pageIndex, RectF pageRect, boolean flipY) {
        if (pdfView == null || pageRect == null) return null;

        float pageWidth = pdfView.getPageSize(pageIndex).getWidth();
        float pageHeight = pdfView.getPageSize(pageIndex).getHeight();
        float spacing = pdfView.getSpacingPx();

        float pageStartY = pageIndex * (pageHeight + spacing);

        RectF result;

        if (flipY) {
            result = new RectF(
                    pageRect.left,
                    pageStartY + (pageHeight - pageRect.bottom),
                    pageRect.right,
                    pageStartY + (pageHeight - pageRect.top)
            );
        } else {
            result = new RectF(
                    pageRect.left,
                    pageStartY + pageRect.top,
                    pageRect.right,
                    pageStartY + pageRect.bottom
            );
        }

        return result;
    }

    private void jumpToSearchResult(int index) {
        if (pdfOverlay == null) return;
        if (index < 0 || index >= currentSearchHighlights.size()) return;

        currentSearchIndex = index;
        pdfOverlay.setCurrentSearchIndex(currentSearchIndex);

        PdfOverlayView.SearchHighlight h = currentSearchHighlights.get(index);
        if (h != null) {
            pdfView.jumpTo(h.pageIndex, true);
            Toast.makeText(
                    this,
                    "结果 " + (currentSearchIndex + 1) + "/" + currentSearchHighlights.size(),
                    Toast.LENGTH_SHORT
            ).show();
        }
    }

    private void jumpToNextSearchResult() {
        if (overlaySearchHighlighter == null || overlaySearchHighlighter.isEmpty()) {
            return;
        }

        AndroidPdfViewerAdapter.ViewerSearchHighlight h = overlaySearchHighlighter.next();

        if (h == null) return;

        currentSearchIndex = overlaySearchHighlighter.getCurrentIndex();

        if (pdfOverlay != null) {
            pdfOverlay.setCurrentSearchIndex(currentSearchIndex);
        }

        if (pdfView != null) {
            pdfView.jumpTo(h.pageIndex, true);
        }

        Toast.makeText(
                this,
                "结果 " + (currentSearchIndex + 1) + "/" + overlaySearchHighlighter.size(),
                Toast.LENGTH_SHORT
        ).show();
    }


    private void jumpToPreviousSearchResult() {
        if (overlaySearchHighlighter == null || overlaySearchHighlighter.isEmpty()) {
            return;
        }

        AndroidPdfViewerAdapter.ViewerSearchHighlight h = overlaySearchHighlighter.previous();

        if (h == null) return;

        currentSearchIndex = overlaySearchHighlighter.getCurrentIndex();

        if (pdfOverlay != null) {
            pdfOverlay.setCurrentSearchIndex(currentSearchIndex);
        }

        if (pdfView != null) {
            pdfView.jumpTo(h.pageIndex, true);
        }

        Toast.makeText(
                this,
                "结果 " + (currentSearchIndex + 1) + "/" + overlaySearchHighlighter.size(),
                Toast.LENGTH_SHORT
        ).show();
    }


    private void clearPdfSearch() {
        currentSearchKeyword = "";
        currentSearchHighlights.clear();
        currentSearchIndex = -1;

        if (overlaySearchHighlighter != null) {
            overlaySearchHighlighter.clear();
        }

        if (pdfOverlay != null) {
            pdfOverlay.clearSearchHighlights();
        }

        Toast.makeText(this, "已清除搜索高亮", Toast.LENGTH_SHORT).show();
    }


    private void searchWithEngine(String keyword, PdfSearchMode mode, boolean currentPageOnly) {
        if (pdfSearchManager == null || pdfUri == null || pdfOverlay == null || pdfView == null) {
            return;
        }

        currentSearchKeyword = keyword;
        currentSearchHighlights.clear();
        currentSearchIndex = -1;
        pdfOverlay.clearSearchHighlights();

        PdfSearchOptions options = new PdfSearchOptions();
        options.mode = mode;
        options.currentPageOnly = currentPageOnly;
        options.currentPage = pdfView.getCurrentPage();

        /*
         * 文本层全文搜索通常没问题。
         * OCR 全文搜索很重，所以默认只允许当前页 OCR。
         */
        options.allowFullDocumentOcr = false;
        options.ocrRenderWidth = 1280;
        options.fallbackToOcrWhenTextNotFound = true;

        pdfSearchManager.search(pdfUri, keyword, options, new PdfSearchCallback() {
            @Override
            public void onSearchStarted(String keyword) {
                Toast.makeText(PdfViewerActivity.this, "正在搜索：" + keyword, Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onSearchProgress(int currentPage, int totalPage, PdfSearchSource source) {
                String sourceName = source == PdfSearchSource.OCR ? "OCR" : "文本";

                if (totalPage > 0) {
                    tvTopTitle.setText(sourceName + "搜索中 (" + (currentPage + 1) + "/" + totalPage + ")");
                } else {
                    tvTopTitle.setText(sourceName + "搜索中...");
                }
            }


            @Override
            public void onSearchCompleted(List<PdfSearchResult> results) {
                int currentPage = pdfView.getCurrentPage();
                int pageCount = pdfView.getPageCount();
                tvTopTitle.setText(currentFileName + " (" + (currentPage + 1) + "/" + pageCount + ")");

                showEngineSearchResults(keyword, results);
            }


            @Override
            public void onSearchFailed(Throwable error) {
                Toast.makeText(
                        PdfViewerActivity.this,
                        "搜索失败：" + (error != null ? error.getMessage() : "未知错误"),
                        Toast.LENGTH_LONG
                ).show();
            }

            @Override
            public void onSearchCancelled() {
                Toast.makeText(PdfViewerActivity.this, "搜索已取消", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void showEngineSearchResults(String keyword, List<PdfSearchResult> results) {
        currentSearchHighlights.clear();
        currentSearchIndex = -1;

        if (results == null || results.isEmpty()) {
            if (pdfOverlay != null) {
                pdfOverlay.clearSearchHighlights();
            }

            if (overlaySearchHighlighter != null) {
                overlaySearchHighlighter.clear();
            }

            Toast.makeText(this, "未找到：" + keyword, Toast.LENGTH_SHORT).show();
            return;
        }

        if (androidPdfViewerAdapter == null) {
            androidPdfViewerAdapter = new AndroidPdfViewerAdapter(pdfView);
        }

        List<AndroidPdfViewerAdapter.ViewerSearchHighlight> viewerHighlights =
                androidPdfViewerAdapter.convertResults(results);

        if (overlaySearchHighlighter == null) {
            overlaySearchHighlighter = new PdfOverlaySearchHighlighter();
        }

        overlaySearchHighlighter.setHighlights(viewerHighlights);

        for (AndroidPdfViewerAdapter.ViewerSearchHighlight h : viewerHighlights) {
            if (h == null || h.rectInDoc == null) continue;

            currentSearchHighlights.add(
                    new PdfOverlayView.SearchHighlight(
                            h.pageIndex,
                            h.rectInDoc
                    )
            );
        }

        if (pdfOverlay != null) {
            pdfOverlay.setSearchHighlights(currentSearchHighlights);
        }

        if (currentSearchHighlights.isEmpty()) {
            Toast.makeText(this, "未找到有效高亮区域：" + keyword, Toast.LENGTH_SHORT).show();
            return;
        }

        currentSearchIndex = overlaySearchHighlighter.getCurrentIndex();

        if (pdfOverlay != null) {
            pdfOverlay.setCurrentSearchIndex(currentSearchIndex);
        }

        AndroidPdfViewerAdapter.ViewerSearchHighlight first =
                overlaySearchHighlighter.getCurrent();

        if (first != null && pdfView != null) {
            pdfView.jumpTo(first.pageIndex, true);
        }

        Toast.makeText(
                this,
                "找到 " + currentSearchHighlights.size() + " 处",
                Toast.LENGTH_SHORT
        ).show();
    }


    private RectF searchRectToDocRect(PdfSearchRect searchRect, PdfSearchSource source) {
        if (searchRect == null || searchRect.rectInPdfPoint == null) return null;
        if (pdfView == null) return null;

        int pageIndex = searchRect.pageIndex;

        if (searchRect.pageWidth <= 0 || searchRect.pageHeight <= 0) {
            return null;
        }

        float viewPageWidth = pdfView.getPageSize(pageIndex).getWidth();
        float viewPageHeight = pdfView.getPageSize(pageIndex).getHeight();
        float spacing = pdfView.getSpacingPx();

        float pageStartY = pageIndex * (viewPageHeight + spacing);

        RectF pdfRect = searchRect.rectInPdfPoint;

        float left = pdfRect.left / searchRect.pageWidth * viewPageWidth;
        float right = pdfRect.right / searchRect.pageWidth * viewPageWidth;

        float top = (searchRect.pageHeight - pdfRect.bottom) / searchRect.pageHeight * viewPageHeight;
        float bottom = (searchRect.pageHeight - pdfRect.top) / searchRect.pageHeight * viewPageHeight;

        RectF raw = new RectF(
                left,
                pageStartY + top,
                right,
                pageStartY + bottom
        );

        if (source == PdfSearchSource.OCR) {
            return normalizeOcrSearchHighlightRect(raw, viewPageHeight);
        } else {
            return normalizePdfiumTextHighlightRect(raw, viewPageHeight);
        }
    }

    private RectF normalizeOcrSearchHighlightRect(RectF rect, float pageHeight) {
        if (rect == null) return null;

        float height = rect.height();

        float minHeight = pageHeight * 0.014f;
        float maxHeight = pageHeight * 0.040f;

        float targetHeight = height;

        if (targetHeight < minHeight) {
            targetHeight = minHeight;
        } else if (targetHeight > maxHeight) {
            targetHeight = maxHeight;
        }

        float cy = rect.centerY();

        /*
         * OCR 高亮偏低时，向上移动一点。
         * 注意：Android/View 文档坐标 Y 向下，所以向上是减小 Y。
         *
         * 如果仍然偏低，可以把 0.18f 调到 0.25f。
         * 如果偏高，可以调到 0.10f。
         */
        float shiftUp = targetHeight * 0.18f;
        cy -= shiftUp;

        float paddingX = Math.max(1f, rect.width() * 0.05f);

        return new RectF(
                rect.left - paddingX,
                cy - targetHeight / 2f,
                rect.right + paddingX,
                cy + targetHeight / 2f
        );
    }

    private void searchTextLayerByPdfium(String keyword) {
        if (pdfView == null || pdfOverlay == null || pdfUri == null) return;

        currentSearchKeyword = keyword;
        currentSearchHighlights.clear();
        currentSearchIndex = -1;
        pdfOverlay.clearSearchHighlights();

        final long myToken = ++textSearchToken;

        Toast.makeText(this, "正在搜索文本层...", Toast.LENGTH_SHORT).show();

        new Thread(() -> {
            try {
                List<PdfiumTextSearchResult> results =
                        pdfiumTextSearchEngine.search(
                                getApplicationContext(),
                                pdfUri,
                                keyword
                        );

                List<PdfOverlayView.SearchHighlight> highlights = new ArrayList<>();

                for (PdfiumTextSearchResult item : results) {
                    if (item == null || item.rectInPdfPoint == null) continue;

                    RectF docRect = pdfiumPdfRectToDocRect(
                            item.pageIndex,
                            item.rectInPdfPoint,
                            item.pageWidth,
                            item.pageHeight
                    );

                    if (docRect != null) {
                        highlights.add(new PdfOverlayView.SearchHighlight(
                                item.pageIndex,
                                docRect
                        ));
                    }
                }

                runOnUiThread(() -> {
                    if (myToken != textSearchToken) return;

                    currentSearchHighlights.clear();
                    currentSearchHighlights.addAll(highlights);

                    pdfOverlay.setSearchHighlights(currentSearchHighlights);

                    if (currentSearchHighlights.isEmpty()) {
                        Toast.makeText(this, "文本层未找到：" + keyword, Toast.LENGTH_SHORT).show();
                    } else {
                        currentSearchIndex = 0;
                        pdfOverlay.setCurrentSearchIndex(0);

                        PdfOverlayView.SearchHighlight first = currentSearchHighlights.get(0);
                        if (first != null) {
                            pdfView.jumpTo(first.pageIndex, true);
                        }

                        Toast.makeText(this, "文本层找到 " + currentSearchHighlights.size() + " 处", Toast.LENGTH_SHORT).show();
                    }
                });

            } catch (Exception e) {
                e.printStackTrace();
                runOnUiThread(() ->
                        Toast.makeText(this, "文本搜索失败：" + e.getMessage(), Toast.LENGTH_LONG).show()
                );
            }
        }).start();
    }

    private RectF pdfiumPdfRectToDocRect(
            int pageIndex,
            RectF pdfRect,
            float sourcePageWidth,
            float sourcePageHeight
    ) {
        if (pdfView == null || pdfRect == null) return null;
        if (sourcePageWidth <= 0 || sourcePageHeight <= 0) return null;

        float viewPageWidth = pdfView.getPageSize(pageIndex).getWidth();
        float viewPageHeight = pdfView.getPageSize(pageIndex).getHeight();
        float spacing = pdfView.getSpacingPx();

        float pageStartY = pageIndex * (viewPageHeight + spacing);

        /*
         * PDFium FPDFText_GetCharBox 返回：
         * left/right/bottom/top，PDF 坐标系，原点左下，Y 轴向上。
         *
         * 你的 PdfOverlayView 文档坐标：
         * 原点左上，Y 轴向下。
         *
         * 所以 Y 方向需要翻转。
         */
        float left = pdfRect.left / sourcePageWidth * viewPageWidth;
        float right = pdfRect.right / sourcePageWidth * viewPageWidth;

        float top = (sourcePageHeight - pdfRect.bottom) / sourcePageHeight * viewPageHeight;
        float bottom = (sourcePageHeight - pdfRect.top) / sourcePageHeight * viewPageHeight;

        RectF raw = new RectF(
                left,
                pageStartY + top,
                right,
                pageStartY + bottom
        );

        return normalizePdfiumTextHighlightRect(raw, viewPageHeight);
    }

    private RectF normalizePdfiumTextHighlightRect(RectF rect, float pageHeight) {
        if (rect == null) return null;

        float height = rect.height();

        /*
         * 这里的比例可以后续按效果微调。
         * 对大多数 A4/漫画/小说 PDF 来说，这个范围比较保守。
         */
        float minHeight = pageHeight * 0.010f;
        float maxHeight = pageHeight * 0.045f;

        float targetHeight = height;

        if (targetHeight < minHeight) {
            targetHeight = minHeight;
        } else if (targetHeight > maxHeight) {
            targetHeight = maxHeight;
        }

        float cy = rect.centerY();

        float paddingX = Math.max(1f, rect.width() * 0.04f);

        return new RectF(
                rect.left - paddingX,
                cy - targetHeight / 2f,
                rect.right + paddingX,
                cy + targetHeight / 2f
        );
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

                    preloadNextVolumeInfo();

                    // 预热 OCR
                    new Thread(() -> {
                        try {
                            PaddleOcrEngine.getInstance().initEngine(getApplicationContext());
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }).start();
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

        if (pdfOverlay != null) {
            pdfOverlay.clearActions();
            pdfOverlay.clearSearchHighlights();
        }

        if (overlaySearchHighlighter != null) {
            overlaySearchHighlighter.clear();
        }

        if (pdfSearchManager != null) {
            pdfSearchManager.cancel();
            pdfSearchManager.clearCache();
        }


        synchronized (ocrPageMemoryCache) {
            ocrPageMemoryCache.clear();
        }

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

    private String buildOcrCacheKey(int pageIndex, int renderWidth) {
        String fileKey = pdfUri != null ? pdfUri.toString() : "";
        return fileKey + "#" + pageIndex + "#" + renderWidth;
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