package com.nless.mypdf;

import android.content.Intent;
import android.view.Menu;
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
import androidx.appcompat.widget.SearchView;

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
        toolbar.setNavigationOnClickListener(v -> drawerLayout.openDrawer(GravityCompat.START));
    }

    private void setupListeners() {
        navigationView.setNavigationItemSelectedListener(item -> {
            drawerLayout.closeDrawer(GravityCompat.START);
            int id = item.getItemId();
            if (id == R.id.nav_recent) {
                if (activity instanceof MainActivity) {
                    ((MainActivity) activity).loadRecentData();
                }
            } else if (id == R.id.nav_favorites) {
                Toast.makeText(activity, "收藏功能开发中", Toast.LENGTH_SHORT).show();
            }
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
                long currentTime = System.currentTimeMillis();
                if (currentTime - lastClickTime < 300) return;
                lastClickTime = currentTime;

                if (!item.isFolder) {
                    if (activity instanceof MainActivity) {
                        ((MainActivity) activity).recordViewHistory(item);
                    }
                    Intent intent = new Intent(activity, PdfViewerActivity.class);
                    intent.putExtra("pdf_uri", item.uri.toString());
                    activity.startActivity(intent);
                } else {
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

        int currentMode = MainActivity.MODE_HOME;
        if (activity instanceof MainActivity) {
            currentMode = ((MainActivity) activity).getCurrentMode();
        }

        // 🌟 核心规则 2：最近查看中的文件应该【只有】移除功能，禁止生成重命名、详情等任何多余选项
        if (currentMode == MainActivity.MODE_RECENT) {
            popup.getMenu().add(0, 4, 0, "从历史记录移除 (Remove from history)");
            popup.setOnMenuItemClickListener(menuItem -> {
                if (menuItem.getItemId() == 4) {
                    removePdfItemFromUI(item);
                    if (activity instanceof MainActivity) {
                        ((MainActivity) activity).removeRecentRecord(item);
                    }
                    Toast.makeText(activity, "已从历史记录移除", Toast.LENGTH_SHORT).show();
                }
                return true;
            });
            popup.show();
            return;
        }

        // 其他页面的通用业务功能
        popup.getMenu().add(0, 1, 0, "重命名 (Rename)");
        popup.getMenu().add(0, 2, 0, "复制 (Duplicate)");
        popup.getMenu().add(0, 3, 0, "移动 (Move)");

        // 🌟 核心规则 1 & 3 & 4：权限分流器
        if (currentMode == MainActivity.MODE_HOME || currentMode == MainActivity.MODE_FOLDER) {
            // 首页中的文件、以及点进去文件夹内的文件，全部规整提供“移除”选项
            popup.getMenu().add(0, 4, 0, "移除 (Remove from list)");
        } else if (currentMode == MainActivity.MODE_FAVORITE) {
            // 🌟 顺应要求，完美对后续收藏功能提前打通权限：“收藏中的文件应该有移除（取消收藏）功能”
            popup.getMenu().add(0, 4, 0, "取消收藏 (Remove from favorites)");
        }
        // 当 currentMode == MainActivity.MODE_SEARCH (搜索) 时，自动漏过此判断，实现“在搜索中的文件应该没有移除功能”

        popup.getMenu().add(0, 5, 0, "彻底删除 (Delete permanently)");
        popup.getMenu().add(0, 6, 0, "详情 (Details)");

        popup.setOnMenuItemClickListener(menuItem -> {
            switch (menuItem.getItemId()) {
                case 4:
                    removePdfItemFromUI(item);
                    if (activity instanceof MainActivity) {
                        MainActivity mainAct = (MainActivity) activity;
                        if (mainAct.getCurrentMode() == MainActivity.MODE_HOME || mainAct.getCurrentMode() == MainActivity.MODE_FOLDER) {
                            mainAct.removePdfItemFromApp(item); // 触发移除动作
                            Toast.makeText(activity, "已从列表中移除", Toast.LENGTH_SHORT).show();
                        } else if (mainAct.getCurrentMode() == MainActivity.MODE_FAVORITE) {
                            // mainAct.removeFavoriteRecord(item); // 留给后续收藏功能单点挂载
                            Toast.makeText(activity, "已取消收藏", Toast.LENGTH_SHORT).show();
                        }
                    }
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
                showEmptyStateByMode();
            }
        }
    }

    // 🌟 核心优化 1：清空方法彻底解耦，不自动跑默认空提示
    public void clearList() {
        int size = pdfItemList.size();
        if (size > 0) {
            pdfItemList.clear();
            adapter.notifyItemRangeRemoved(0, size);
        }
        recyclerView.setVisibility(View.GONE);
        tvEmptyState.setVisibility(View.GONE);
    }

    // 🌟 新增：显式控制中间缓冲态“搜索中...”
    public void showSearchingState() {
        recyclerView.setVisibility(View.GONE);
        tvEmptyState.setVisibility(View.VISIBLE);
        tvEmptyState.setText("搜索中...");
        tvEmptyState.setClickable(false);
    }

    // 🌟 新增：显式注入自定义空文案与事件挂载状态
    public void showEmptyState(String message, boolean clickable) {
        recyclerView.setVisibility(View.GONE);
        tvEmptyState.setVisibility(View.VISIBLE);
        tvEmptyState.setText(message);
        tvEmptyState.setClickable(clickable);
    }

    // 🌟 新增：显式开关右下角浮动加号
    public void showFab(boolean visible) {
        if (fabAdd != null) {
            fabAdd.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
    }

    public void showEmptyStateByMode() {
        // 🌟 确保列表隐藏，文字浮现
        recyclerView.setVisibility(View.GONE);
        tvEmptyState.setVisibility(View.VISIBLE);

        if (activity instanceof MainActivity) {
            int mode = ((MainActivity) activity).getCurrentMode();
            if (mode == MainActivity.MODE_HOME) {
                tvEmptyState.setText("暂无文件，请点击添加");
                tvEmptyState.setClickable(true);
            } else {
                tvEmptyState.setText("没有文件");
                tvEmptyState.setClickable(false);
            }
        }
    }

    public void addPdfItem(PdfItem item) {
        pdfItemList.add(item);
        adapter.notifyItemInserted(pdfItemList.size() - 1);

        // 🌟 核心修正：只要有文件被添加进来，无条件强制让 RecyclerView 显示，让空提示隐藏
        tvEmptyState.setVisibility(View.GONE);
        recyclerView.setVisibility(View.VISIBLE);
    }

    public void setShowPath(boolean showPath) {
        if (adapter != null) {
            adapter.setShowPath(showPath);
        }
    }

    public void setRecentMode(boolean isRecent) {
        if (adapter != null) {
            adapter.setRecentMode(isRecent);
        }
    }

    public void setupAsSubView(String title) {
        if (activity.getSupportActionBar() != null) {
            activity.getSupportActionBar().setTitle(title);
        }
        fabAdd.setVisibility(View.GONE);
        drawerLayout.setFitsSystemWindows(true);
        drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED);
        toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material);
        toolbar.setNavigationOnClickListener(v -> activity.onBackPressed());
    }

    public void setupAsHomeView() {
        if (activity.getSupportActionBar() != null) {
            activity.getSupportActionBar().setTitle(R.string.app_name);
        }
        fabAdd.setVisibility(View.VISIBLE);
        drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED);

        ActionBarDrawerToggle toggle = new ActionBarDrawerToggle(
                activity, drawerLayout, toolbar,
                R.string.navigation_drawer_open,
                R.string.navigation_drawer_close);
        drawerLayout.addDrawerListener(toggle);
        toggle.syncState();
        toolbar.setNavigationOnClickListener(v -> drawerLayout.openDrawer(GravityCompat.START));
    }

    public void updateTitle(String title) {
        if (activity.getSupportActionBar() != null) {
            activity.getSupportActionBar().setTitle(title);
        }
    }

    public void setupOptionsMenu(Menu menu) {
        MenuItem searchItem = menu.findItem(R.id.action_search);
        if (searchItem == null) return;
        SearchView searchView = (SearchView) searchItem.getActionView();
        if (searchView == null) return;

        searchView.setQueryHint("搜索文件名...");
        searchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
            @Override
            public boolean onQueryTextSubmit(String query) {
                if (activity instanceof MainActivity) {
                    ((MainActivity) activity).performSearch(query);
                }
                searchView.clearFocus();
                return true;
            }

            @Override
            public boolean onQueryTextChange(String newText) {
                if (newText.isEmpty() && activity instanceof MainActivity) {
                    ((MainActivity) activity).exitSubView();
                }
                return false;
            }
        });

        // 🌟 核心修改 2：加入 ActionExpand 侦听接力，捕捉点击放大镜图标的一瞬间
        searchItem.setOnActionExpandListener(new MenuItem.OnActionExpandListener() {
            @Override
            public boolean onMenuItemActionExpand(MenuItem item) {
                if (activity instanceof MainActivity) {
                    ((MainActivity) activity).enterSearchMode(); // 展开即锁死当前背景模式
                }
                return true;
            }

            @Override
            public boolean onMenuItemActionCollapse(MenuItem item) {
                if (activity instanceof MainActivity) {
                    ((MainActivity) activity).exitSubView();
                }
                return true;
            }
        });
    }

    public boolean handleToolbarMenuClick(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_switch_layout) {
            isGridView = !isGridView;
            if (isGridView) {
                recyclerView.setLayoutManager(new GridLayoutManager(activity, 3));
            } else {
                recyclerView.setLayoutManager(new LinearLayoutManager(activity));
            }
            adapter.setGridView(isGridView);
            item.setIcon(isGridView ? android.R.drawable.ic_menu_sort_by_size : R.drawable.ic_view_grid);
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