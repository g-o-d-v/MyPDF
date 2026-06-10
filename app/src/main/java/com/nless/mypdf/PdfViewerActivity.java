package com.nless.mypdf;

import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.github.barteksc.pdfviewer.PDFView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class PdfViewerActivity extends AppCompatActivity {

    private boolean isEditMode = false;
    private boolean isMenuVisible = true;

    // 核心凭证
    private String currentFileName = "未命名";
    private String pdfPath = "";
    private String pdfName = "";
    private Uri pdfUri;

    // 业务依赖
    private PdfDbHelper dbHelper;
    private String rawModifiedTime = ""; // 记录系统原始时间，用于临时生成历史/收藏记录

    // 控件
    private PDFView pdfView;
    private LinearLayout topMenuLayout;
    private ImageView ivBackOrExit;
    private TextView tvTopTitle;
    private ImageView ivUndo;
    private ImageView ivFavorite; // 🌟 收藏图标
    private ImageView ivSave;
    private ImageView ivEditMode;

    private LinearLayout llEditToolsRow;
    private ImageView ivToolDrag;
    private ImageView ivToolText;
    private ImageView ivToolDoodle;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getSupportActionBar() != null) {
            getSupportActionBar().hide();
        }
        setContentView(R.layout.activity_pdf_viewer);

        dbHelper = new PdfDbHelper(this); // 初始化数据库控制器

        initViews();
        setupListeners();
        loadPdfFromIntent();
        updateFavoriteIconState(); // 🌟 初始化时动态检查并设置收藏图标形状
    }

    private void initViews() {
        pdfView = findViewById(R.id.pdfView);
        topMenuLayout = findViewById(R.id.top_menu_layout);

        ivBackOrExit = findViewById(R.id.iv_back_or_exit);
        tvTopTitle = findViewById(R.id.tv_top_title);
        ivUndo = findViewById(R.id.iv_undo);
        ivFavorite = findViewById(R.id.iv_favorite);
        ivSave = findViewById(R.id.iv_save);
        ivEditMode = findViewById(R.id.iv_edit_mode);

        llEditToolsRow = findViewById(R.id.ll_edit_tools_row);
        ivToolDrag = findViewById(R.id.iv_tool_drag);
        ivToolText = findViewById(R.id.iv_tool_text);
        ivToolDoodle = findViewById(R.id.iv_tool_doodle);

        topMenuLayout.setVisibility(View.VISIBLE);
        llEditToolsRow.setVisibility(View.GONE);
    }

    private void setupListeners() {
        ivBackOrExit.setOnClickListener(v -> handleBackAction());
        ivEditMode.setOnClickListener(v -> toggleEditMode(true));
        ivSave.setOnClickListener(v -> handleSaveAction());
        ivUndo.setOnClickListener(v -> Toast.makeText(this, "撤回上一步", Toast.LENGTH_SHORT).show());

        // 🌟 收藏点击事件挂载
        ivFavorite.setOnClickListener(v -> handleFavoriteToggle());

        ivToolDrag.setOnClickListener(v -> activateTool("drag", ivToolDrag));
        ivToolText.setOnClickListener(v -> activateTool("text", ivToolText));
        ivToolDoodle.setOnClickListener(v -> activateTool("doodle", ivToolDoodle));

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                handleBackAction();
            }
        });
    }

    private void loadPdfFromIntent() {
        String uriString = getIntent().getStringExtra("pdf_uri");
        // 🌟 接收由主页虚拟列表、深层文件夹、搜索或历史记录传进来的清洗后路径与名称凭证
        pdfPath = getIntent().getStringExtra("pdf_path");
        pdfName = getIntent().getStringExtra("pdf_name");

        if (uriString != null) {
            pdfUri = Uri.parse(uriString);

            // 格式化解析无路径、无点缀名
            currentFileName = pdfName;
            if (currentFileName == null || currentFileName.trim().isEmpty()) {
                currentFileName = pdfUri.getLastPathSegment();
                if (currentFileName != null && currentFileName.contains(":")) {
                    currentFileName = currentFileName.substring(currentFileName.lastIndexOf(":") + 1);
                }
            }
            if (currentFileName != null && currentFileName.toLowerCase().endsWith(".pdf")) {
                currentFileName = currentFileName.substring(0, currentFileName.length() - 4);
            }

            tvTopTitle.setText(currentFileName);

            pdfView.fromUri(pdfUri)
                    .defaultPage(0)
                    .enableSwipe(true)
                    .swipeHorizontal(false)
                    .onTap(e -> {
                        toggleMenuVisibility();
                        return true;
                    })
                    .onPageChange((page, pageCount) -> {
                        if (!isEditMode) {
                            tvTopTitle.setText(currentFileName + " (" + (page + 1) + "/" + pageCount + ")");
                        }
                    })
                    .onLoad(nbPages -> {
                        if (!isEditMode) {
                            tvTopTitle.setText(currentFileName + " (1/" + nbPages + ")");
                        }
                        // 顺带读取原始修改时间，用作生成潜在新纪录的填充项
                        rawModifiedTime = new SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()).format(new Date());
                    })
                    .load();
        } else {
            Toast.makeText(this, "无法获取文件路径", Toast.LENGTH_SHORT).show();
            finish();
        }
    }

    // 🌟 核心控制 1：初始化与更新收藏图标外观
    private void updateFavoriteIconState() {
        if (dbHelper == null || pdfPath == null) return;

        boolean isFav = dbHelper.isFavorite(pdfPath, pdfName);
        if (isFav) {
            // 🌟【在此处修改】：替换为你的 SVG 实心填充图标 XML 路径
            ivFavorite.setImageResource(R.drawable.ic_star_fill);
        } else {
            // 🌟【在此处修改】：替换为你的 SVG 空心图标 XML 路径
            ivFavorite.setImageResource(R.drawable.ic_star_circle);
        }
    }

    // 🌟 核心控制 2：处理用户点击收藏动作
    private void handleFavoriteToggle() {
        if (dbHelper == null) return;

        // 触发数据库标志位翻转
        boolean isNowFav = dbHelper.toggleFavorite(pdfPath, pdfName, pdfUri.toString(), rawModifiedTime);

        // 动态变更当前渲染样式
        if (isNowFav) {
            ivFavorite.setImageResource(R.drawable.ic_star_fill); // 改为你的实心 XML
            Toast.makeText(this, "已加入收藏夹", Toast.LENGTH_SHORT).show();
        } else {
            ivFavorite.setImageResource(R.drawable.ic_star_circle); // 改为你的空心 XML
            Toast.makeText(this, "已取消收藏", Toast.LENGTH_SHORT).show();
        }
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
                    View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
            topMenuLayout.animate().translationY(-topMenuLayout.getHeight()).setDuration(250)
                    .withEndAction(() -> topMenuLayout.setVisibility(View.GONE)).start();
        }
    }

    private void toggleEditMode(boolean enterEdit) {
        isEditMode = enterEdit;
        if (isEditMode) {
            tvTopTitle.setText("编辑模式");
            ivBackOrExit.setImageResource(android.R.drawable.ic_menu_close_clear_cancel);

            ivFavorite.setVisibility(View.GONE);
            ivEditMode.setVisibility(View.GONE);
            ivUndo.setVisibility(View.VISIBLE);
            ivSave.setVisibility(View.VISIBLE);

            llEditToolsRow.setVisibility(View.VISIBLE);
            llEditToolsRow.setAlpha(0f);
            llEditToolsRow.animate().alpha(1f).setDuration(200).start();
            activateTool("drag", ivToolDrag);
        } else {
            int currentPage = pdfView.getCurrentPage();
            int pageCount = pdfView.getPageCount();
            tvTopTitle.setText(currentFileName + " (" + (currentPage + 1) + "/" + pageCount + ")");
            ivBackOrExit.setImageResource(android.R.drawable.ic_menu_revert);

            ivUndo.setVisibility(View.GONE);
            ivSave.setVisibility(View.GONE);
            ivFavorite.setVisibility(View.VISIBLE);
            ivEditMode.setVisibility(View.VISIBLE);
            llEditToolsRow.setVisibility(View.GONE);
        }
    }

    private void handleBackAction() {
        if (isEditMode) {
            new AlertDialog.Builder(this)
                    .setTitle("退出编辑")
                    .setMessage("尚未保存修改，确定要退出编辑模式吗？")
                    .setPositiveButton("退出", (dialog, which) -> toggleEditMode(false))
                    .setNegativeButton("取消", null)
                    .show();
        } else {
            finish();
        }
    }

    private void handleSaveAction() {
        new AlertDialog.Builder(this)
                .setTitle("保存修改")
                .setItems(new String[]{"覆盖原文件 (Save)", "另存为新文件 (Save As)"}, (dialog, which) -> {
                    Toast.makeText(this, "保存逻辑开发中...", Toast.LENGTH_SHORT).show();
                    toggleEditMode(false);
                })
                .show();
    }

    private void activateTool(String toolName, ImageView activeImageView) {
        ivToolDrag.setColorFilter(android.graphics.Color.parseColor("#999999"));
        ivToolText.setColorFilter(android.graphics.Color.parseColor("#999999"));
        ivToolDoodle.setColorFilter(android.graphics.Color.parseColor("#999999"));
        activeImageView.setColorFilter(android.graphics.Color.parseColor("#4CAF50"));
        Toast.makeText(this, "切换到工具: " + toolName, Toast.LENGTH_SHORT).show();
    }
}