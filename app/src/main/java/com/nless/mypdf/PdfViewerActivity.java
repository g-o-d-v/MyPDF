package com.nless.mypdf;

import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.github.barteksc.pdfviewer.PDFView;
import com.github.barteksc.pdfviewer.listener.OnTapListener;
import android.view.MotionEvent;

public class PdfViewerActivity extends AppCompatActivity {

    private PDFView pdfView;
    private LinearLayout topMenuLayout;
    private TextView tvPageInfo;

    private boolean isMenuVisible = false;
    private WindowInsetsControllerCompat windowInsetsController;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 1. 初始化沉浸式全屏控制器
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        windowInsetsController = new WindowInsetsControllerCompat(getWindow(), getWindow().getDecorView());
        windowInsetsController.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);

        setContentView(R.layout.activity_pdf_viewer);

        pdfView = findViewById(R.id.pdfView);
        topMenuLayout = findViewById(R.id.top_menu_layout); // 需要在 XML 中给顶部菜单加个 ID
        tvPageInfo = findViewById(R.id.tv_page_info);

        findViewById(R.id.iv_back).setOnClickListener(v -> finish());

        // 初始状态：隐藏系统状态栏和我们的顶部菜单
        hideSystemUIAndMenu();

        String uriString = getIntent().getStringExtra("pdf_uri");
        if (uriString != null) {
            displayPdf(Uri.parse(uriString));
        } else {
            finish();
        }
    }

    private void displayPdf(Uri uri) {
        pdfView.fromUri(uri)
                .defaultPage(0)
                .enableSwipe(true)
                .swipeHorizontal(false)
                .onPageChange((page, pageCount) -> tvPageInfo.setText((page + 1) + " / " + pageCount))
                .onTap(new OnTapListener() {
                    @Override
                    public boolean onTap(MotionEvent e) {
                        // 拦截单击事件，切换菜单显示状态
                        toggleMenuVisibility();
                        return true; // 返回 true 表示我们消费了这个事件
                    }
                })
                .spacing(4)
                .load();
    }

    private void toggleMenuVisibility() {
        if (isMenuVisible) {
            hideSystemUIAndMenu();
        } else {
            showSystemUIAndMenu();
        }
    }

    private void hideSystemUIAndMenu() {
        // 隐藏系统状态栏和底部导航栏
        windowInsetsController.hide(WindowInsetsCompat.Type.systemBars());
        // 隐藏我们自己的菜单，添加渐渐消失的动画
        topMenuLayout.animate().alpha(0f).setDuration(200).withEndAction(() -> topMenuLayout.setVisibility(View.GONE)).start();
        isMenuVisible = false;
    }

    private void showSystemUIAndMenu() {
        // 显示系统状态栏（为了让菜单不挡住刘海屏）
        windowInsetsController.show(WindowInsetsCompat.Type.statusBars());
        // 显示我们的菜单
        topMenuLayout.setVisibility(View.VISIBLE);
        topMenuLayout.animate().alpha(1f).setDuration(200).start();
        isMenuVisible = true;
    }
}