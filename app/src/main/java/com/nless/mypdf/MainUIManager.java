package com.nless.mypdf;

import android.content.Intent;
import android.net.Uri;
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
                if (activity instanceof MainActivity) {
                    ((MainActivity) activity).loadFavoriteData();
                }
            } else if (id == R.id.nav_settings) {
                activity.startActivity(new Intent(activity, SettingsActivity.class));
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
                    intent.putExtra("pdf_path", item.path);
                    intent.putExtra("pdf_name", item.name);

                    if (activity instanceof MainActivity) {
                        MainActivity mainAct = (MainActivity) activity;
                        if (mainAct.getCurrentMode() == MainActivity.MODE_FOLDER && mainAct.getCurrentFolderUri() != null) {
                            intent.putExtra("parent_uri", mainAct.getCurrentFolderUri().toString());
                        }
                    }

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

        if (currentMode == MainActivity.MODE_RECENT) {
            popup.getMenu().add(Menu.NONE, 0, 0, "从历史记录移除");
            popup.setOnMenuItemClickListener(menuItem -> {
                if (activity instanceof MainActivity) {
                    ((MainActivity) activity).removeRecentRecord(item);
                }
                return true;
            });
            popup.show();
            return;
        }

        if (currentMode == MainActivity.MODE_HOME || currentMode == MainActivity.MODE_FOLDER) {
            popup.getMenu().add(Menu.NONE, 1, 0, "从列表中移除");
        } else if (currentMode == MainActivity.MODE_FAVORITE) {
            popup.getMenu().add(Menu.NONE, 1, 0, "取消收藏");
        }

        popup.getMenu().add(Menu.NONE, 2, 0, "彻底删除");
        popup.getMenu().add(Menu.NONE, 3, 0, "详情");

        popup.setOnMenuItemClickListener(menuItem -> {
            MainActivity mainAct = (activity instanceof MainActivity) ? (MainActivity) activity : null;
            if (mainAct == null) return true;

            switch (menuItem.getItemId()) {
                case 1:
                    if (mainAct.getCurrentMode() == MainActivity.MODE_HOME || mainAct.getCurrentMode() == MainActivity.MODE_FOLDER) {
                        new AlertDialog.Builder(activity)
                                .setTitle("确认移除")
                                .setMessage("确定要从列表中移除此项目吗？（不会删除源物理文件）")
                                .setPositiveButton("确定", (d, w) -> {
                                    mainAct.removePdfItemFromApp(item);
                                    Toast.makeText(activity, "已从列表中移除", Toast.LENGTH_SHORT).show();
                                })
                                .setNegativeButton("取消", null)
                                .show();
                    } else if (mainAct.getCurrentMode() == MainActivity.MODE_FAVORITE) {
                        mainAct.removeFavoriteRecord(item);
                        Toast.makeText(activity, "已取消收藏", Toast.LENGTH_SHORT).show();
                    }
                    break;
                case 2:
                    new AlertDialog.Builder(activity)
                            .setTitle("警告：彻底删除")
                            .setMessage("该操作将永久删除磁盘上的此文件，不可恢复！确认删除吗？")
                            .setPositiveButton("彻底删除", (d, w) -> {
                                mainAct.performPhysicalDelete(item);
                            })
                            .setNegativeButton("取消", null)
                            .show();
                    break;
                case 3:
                    mainAct.showFileDetailsDialog(item);
                    break;
            }
            return true;
        });
        popup.show();
    }

    public void removePdfItemFromUI(PdfItem item) {
        int index = pdfItemList.indexOf(item);
        if (index != -1) {
            pdfItemList.remove(index);
            adapter.notifyItemRemoved(index);
            if (pdfItemList.isEmpty()) {
                showEmptyStateByMode();
            }
        }
    }

    public void clearList() {
        int size = pdfItemList.size();
        if (size > 0) {
            pdfItemList.clear();
            adapter.notifyItemRangeRemoved(0, size);
        }
        recyclerView.setVisibility(View.GONE);
        tvEmptyState.setVisibility(View.GONE);
    }

    // 🌟 新增：友好的加载中提示UI
    public void showLoadingState() {
        recyclerView.setVisibility(View.GONE);
        tvEmptyState.setVisibility(View.VISIBLE);
        tvEmptyState.setText("加载中，请稍候...");
        tvEmptyState.setClickable(false);
        if (fabAdd != null) fabAdd.setVisibility(View.GONE);
    }

    public void showSearchingState() {
        recyclerView.setVisibility(View.GONE);
        tvEmptyState.setVisibility(View.VISIBLE);
        tvEmptyState.setText("搜索中...");
        tvEmptyState.setClickable(false);
    }

    public void showEmptyState(String message, boolean clickable) {
        recyclerView.setVisibility(View.GONE);
        tvEmptyState.setVisibility(View.VISIBLE);
        tvEmptyState.setText(message);
        tvEmptyState.setClickable(clickable);
    }

    public void showFab(boolean visible) {
        if (fabAdd != null) {
            fabAdd.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
    }

    public void showEmptyStateByMode() {
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

        // 🌟 完美替代方案：使用原生的动态返回箭头，无需任何资源文件，自动适配主题颜色！
        androidx.appcompat.graphics.drawable.DrawerArrowDrawable backArrow =
                new androidx.appcompat.graphics.drawable.DrawerArrowDrawable(activity);
        backArrow.setProgress(1.0f); // 进度 1.0f 代表完全变成“返回箭头”
        toolbar.setNavigationIcon(backArrow);

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

        searchItem.setOnActionExpandListener(new MenuItem.OnActionExpandListener() {
            @Override
            public boolean onMenuItemActionExpand(MenuItem item) {
                if (activity instanceof MainActivity) {
                    ((MainActivity) activity).enterSearchMode();
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