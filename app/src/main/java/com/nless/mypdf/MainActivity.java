package com.nless.mypdf;

import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    private MainUIManager uiManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // 修复 1：传入正确的 savedInstanceState 变量
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 实例化 UI 管理器并让其接管视图逻辑
        uiManager = new MainUIManager(this);
        uiManager.setupUI();

        // 修复 2：使用 OnBackPressedDispatcher 适配最新的 Android 返回手势规范
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                // 先检查是否需要关闭侧滑菜单
                if (!uiManager.closeDrawerIfOpen()) {
                    // 如果侧滑菜单没有打开，我们需要执行系统默认的返回逻辑（通常是退出 Activity）
                    // 必须先临时禁用当前的拦截器，防止造成死循环，然后再触发系统的返回
                    this.setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                }
            }
        });
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_toolbar, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        // 将 Toolbar 菜单点击事件委托给 UIManager 处理
        if (uiManager.handleToolbarMenuClick(item)) {
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}