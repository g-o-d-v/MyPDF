package com.nless.mypdf;

import android.content.Intent;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.ActionBarDrawerToggle;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.PopupMenu;
import androidx.appcompat.widget.Toolbar;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.navigation.NavigationView;

import java.util.ArrayList;
import java.util.List;

public class MainUIManager {

    private final AppCompatActivity activity;

    private DrawerLayout drawerLayout;
    private Toolbar toolbar;
    private NavigationView navigationView;
    private TextView tvEmptyState;
    private RecyclerView recyclerView;
    private FloatingActionButton fabAdd;

    private List<PdfItem> pdfItemList = new ArrayList<>();
    private PdfListAdapter adapter;
    private boolean isGridView = false;

    public MainUIManager(AppCompatActivity activity) {
        this.activity = activity;
    }

    public void setupUI() {
        findViews();
        setupToolbarAndDrawer();
        setupRecyclerView();
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
        activity.setSupportActionBar(toolbar);
        ActionBarDrawerToggle toggle = new ActionBarDrawerToggle(
                activity, drawerLayout, toolbar,
                R.string.navigation_drawer_open,
                R.string.navigation_drawer_close);
        drawerLayout.addDrawerListener(toggle);
        toggle.syncState();
    }

    private void setupListeners() {
        navigationView.setNavigationItemSelectedListener(item -> {
            Toast.makeText(activity, "点击了: " + item.getTitle(), Toast.LENGTH_SHORT).show();
            drawerLayout.closeDrawer(GravityCompat.START);
            return true;
        });

        View.OnClickListener pickFileListener = v -> {
            new AlertDialog.Builder(activity)
                    .setTitle("选择类型")
                    .setItems(new String[]{"添加 PDF 文件", "添加文件夹"}, (dialog, which) -> {
                        if (activity instanceof MainActivity) {
                            if (which == 0) {
                                ((MainActivity) activity).openFilePicker();
                            } else {
                                ((MainActivity) activity).openFolderPicker();
                            }
                        }
                    })
                    .show();
        };
        tvEmptyState.setOnClickListener(pickFileListener);
        fabAdd.setOnClickListener(pickFileListener);
    }

    private void setupRecyclerView() {
        recyclerView.setLayoutManager(new LinearLayoutManager(activity));
        adapter = new PdfListAdapter(pdfItemList, new PdfListAdapter.OnItemInteractionListener() {
            private long lastClickTime = 0;

            @Override
            public void onClick(PdfItem item) {
                // 将防抖时间缩短到 300ms，防止正常连击被误杀
                long currentTime = System.currentTimeMillis();
                if (currentTime - lastClickTime < 300) return;
                lastClickTime = currentTime;

                if (!item.isFolder) {
                    // 点击 PDF：直接独立启动阅读器，与后台加载完全互不干扰
                    Intent intent = new Intent(activity, PdfViewerActivity.class);
                    intent.putExtra("pdf_uri", item.uri.toString());
                    activity.startActivity(intent);
                } else {
                    // 点击文件夹：调用 MainActivity 进行无缝原位切换
                    if (activity instanceof MainActivity) {
                        ((MainActivity) activity).switchToFolderView(item);
                    }
                }
            }

            @Override
            public void onLongClick(View anchorView, PdfItem item) {
                showPopupMenu(anchorView, item);
            }
        });
        recyclerView.setAdapter(adapter);
    }

    private void showPopupMenu(View anchor, PdfItem item) {
        PopupMenu popup = new PopupMenu(activity, anchor);
        popup.getMenu().add(0, 1, 0, "重命名 (Rename)");
        popup.getMenu().add(0, 2, 0, "复制 (Duplicate)");
        popup.getMenu().add(0, 3, 0, "移动 (Move)");
        popup.getMenu().add(0, 4, 0, "移除 (Remove from list)");
        popup.getMenu().add(0, 5, 0, "彻底删除 (Delete permanently)");
        popup.getMenu().add(0, 6, 0, "详情 (Details)");

        popup.setOnMenuItemClickListener(menuItem -> {
            switch (menuItem.getItemId()) {
                case 4:
                    removePdfItemFromUI(item);
                    if (activity instanceof MainActivity) {
                        ((MainActivity) activity).removePdfItemFromApp(item);
                    }
                    Toast.makeText(activity, "已从首页移除", Toast.LENGTH_SHORT).show();
                    break;
                case 5:
                    new AlertDialog.Builder(activity)
                            .setTitle("警告：彻底删除")
                            .setMessage("该操作将从您的手机存储中永久删除此文件，不可恢复！确认删除吗？")
                            .setPositiveButton("彻底删除", (dialog, which) -> {
                                if (activity instanceof MainActivity) {
                                    ((MainActivity) activity).deleteFilePhysically(item, success -> {
                                        if (success) {
                                            removePdfItemFromUI(item);
                                            Toast.makeText(activity, "文件已彻底删除", Toast.LENGTH_SHORT).show();
                                        } else {
                                            Toast.makeText(activity, "删除失败，可能是系统权限限制", Toast.LENGTH_SHORT).show();
                                        }
                                    });
                                }
                            })
                            .setNegativeButton("取消", null)
                            .show();
                    break;
                default:
                    Toast.makeText(activity, "暂未实现: " + menuItem.getTitle(), Toast.LENGTH_SHORT).show();
                    break;
            }
            return true;
        });
        popup.show();
    }

    private void removePdfItemFromUI(PdfItem item) {
        int index = pdfItemList.indexOf(item);
        if (index != -1) {
            pdfItemList.remove(index);
            adapter.notifyItemRemoved(index);
            if (pdfItemList.isEmpty()) {
                tvEmptyState.setVisibility(View.VISIBLE);
                recyclerView.setVisibility(View.GONE);
            }
        }
    }

    // 核心优化 1：清空列表
    public void clearList() {
        int size = pdfItemList.size();
        if (size > 0) {
            pdfItemList.clear();
            adapter.notifyItemRangeRemoved(0, size);
        }
        tvEmptyState.setVisibility(View.VISIBLE);
        recyclerView.setVisibility(View.GONE);
    }

    // 核心优化 2：局部插入代替全局刷新，彻底解决首次点击失效和相互干扰问题
    public void addPdfItem(PdfItem item) {
        pdfItemList.add(item);
        // 使用 notifyItemInserted 仅插入一行，不干扰用户当前正在点击的列表项
        adapter.notifyItemInserted(pdfItemList.size() - 1);

        if (tvEmptyState.getVisibility() == View.VISIBLE) {
            tvEmptyState.setVisibility(View.GONE);
            recyclerView.setVisibility(View.VISIBLE);
        }
    }

    public void setupAsFolderView(String folderName) {
        if (activity.getSupportActionBar() != null) {
            activity.getSupportActionBar().setTitle(folderName);
        }
        fabAdd.setVisibility(View.GONE);
        drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED);
        toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material);
        toolbar.setNavigationOnClickListener(v -> activity.onBackPressed());
    }

    // 核心优化 3：提供恢复首页的 UI 切换方法
    public void setupAsHomeView() {
        if (activity.getSupportActionBar() != null) {
            activity.getSupportActionBar().setTitle(R.string.app_name); // 或者写死 "MyPDF"
        }
        fabAdd.setVisibility(View.VISIBLE);
        drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED);

        // 重新绑定汉堡菜单
        ActionBarDrawerToggle toggle = new ActionBarDrawerToggle(
                activity, drawerLayout, toolbar,
                R.string.navigation_drawer_open,
                R.string.navigation_drawer_close);
        drawerLayout.addDrawerListener(toggle);
        toggle.syncState();
        toolbar.setNavigationOnClickListener(v -> drawerLayout.openDrawer(GravityCompat.START));
    }

    public boolean handleToolbarMenuClick(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_search) {
            Toast.makeText(activity, "触发搜索", Toast.LENGTH_SHORT).show();
            return true;
        } else if (id == R.id.action_switch_layout) {
            // 1. 切换状态
            isGridView = !isGridView;

            // 2. 切换 RecyclerView 的排版管理器
            if (isGridView) {
                recyclerView.setLayoutManager(new GridLayoutManager(activity, 3));
                // 当切换为网格视图时，右上角图标变成“列表图标”（提示用户点击可以切回列表）
                item.setIcon(android.R.drawable.ic_menu_sort_by_size);
            } else {
                recyclerView.setLayoutManager(new LinearLayoutManager(activity));
                // 当切换为列表视图时，右上角图标变成“网格图标”（提示用户点击可以切回网格）
                item.setIcon(R.drawable.ic_view_grid);
            }

            // 3. 通知适配器更新视图类型
            adapter.setGridView(isGridView);

            return true;
        }
        return false;
    }

    public boolean closeDrawerIfOpen() {
        if (drawerLayout != null && drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START);
            return true;
        }
        return false;
    }
}