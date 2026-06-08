package com.nless.mypdf;

import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.ActionBarDrawerToggle;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.navigation.NavigationView;

public class MainUIManager {

    private final AppCompatActivity activity;

    // UI 组件
    private DrawerLayout drawerLayout;
    private Toolbar toolbar;
    private NavigationView navigationView;
    private TextView tvEmptyState;
    private RecyclerView recyclerView;
    private FloatingActionButton fabAdd;

    // 状态
    private boolean isGridView = false;

    public MainUIManager(AppCompatActivity activity) {
        this.activity = activity;
    }

    /**
     * 初始化所有 UI 组件并设置监听器
     */
    public void setupUI() {
        findViews();
        setupToolbarAndDrawer();
        setupListeners();
    }

    private void findViews() {
        drawerLayout = activity.findViewById(R.id.drawer_layout);
        toolbar = activity.findViewById(R.id.toolbar);
        navigationView = activity.findViewById(R.id.nav_view);
        tvEmptyState = activity.findViewById(R.id.tv_empty_state);
        recyclerView = activity.findViewById(R.id.recycler_view);
        fabAdd = activity.findViewById(R.id.fab_add);
    }

    private void setupToolbarAndDrawer() {
        // 设置 Toolbar 作为 ActionBar
        activity.setSupportActionBar(toolbar);

        // 绑定抽屉开关与 Toolbar
        ActionBarDrawerToggle toggle = new ActionBarDrawerToggle(
                activity, drawerLayout, toolbar,
                R.string.navigation_drawer_open,
                R.string.navigation_drawer_close);
        drawerLayout.addDrawerListener(toggle);
        toggle.syncState();
    }

    private void setupListeners() {
        // 侧滑菜单点击事件
        navigationView.setNavigationItemSelectedListener(item -> {
            Toast.makeText(activity, "点击了: " + item.getTitle(), Toast.LENGTH_SHORT).show();
            drawerLayout.closeDrawer(GravityCompat.START);
            return true;
        });

        // 中间文本和右下角按钮共用一个点击逻辑（选择文件）
        View.OnClickListener pickFileListener = v -> {
            Toast.makeText(activity, "触发选择文件/文件夹", Toast.LENGTH_SHORT).show();
            // 后续在这里接入 SAF 文件选择器逻辑
        };

        tvEmptyState.setOnClickListener(pickFileListener);
        fabAdd.setOnClickListener(pickFileListener);
    }

    /**
     * 处理右上角菜单的点击事件
     */
    public boolean handleToolbarMenuClick(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_search) {
            Toast.makeText(activity, "触发搜索", Toast.LENGTH_SHORT).show();
            return true;
        } else if (id == R.id.action_switch_layout) {
            isGridView = !isGridView;
            Toast.makeText(activity, isGridView ? "切换到网格模式" : "切换到列表模式", Toast.LENGTH_SHORT).show();
            // 后续在这里刷新 RecyclerView 的 LayoutManager
            return true;
        }
        return false;
    }

    /**
     * 对外暴露方法：尝试关闭侧滑菜单
     * @return 如果菜单之前是打开的并被关闭，返回 true；否则返回 false
     */
    public boolean closeDrawerIfOpen() {
        if (drawerLayout != null && drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START);
            return true;
        }
        return false;
    }
}