package com.nless.mypdf;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.documentfile.provider.DocumentFile;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

public class MainActivity extends AppCompatActivity {

    public static final int MODE_HOME = 0;
    public static final int MODE_FOLDER = 1;
    public static final int MODE_RECENT = 2;
    public static final int MODE_SEARCH = 3;
    public static final int MODE_FAVORITE = 4;

    private int currentMode = MODE_HOME;
    private int preSearchMode = MODE_HOME;

    private MainUIManager uiManager;
    private PdfDbHelper dbHelper;

    private PdfItem pendingAuthItem;
    private String pendingAuthAction;

    // 🌟 新增：全局异步任务令牌！用 volatile 保证多线程立即可见
    private volatile long currentTaskToken = 0;

    private final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault());
    private final ExecutorService executorService = Executors.newSingleThreadExecutor();

    public int getCurrentMode() {
        return currentMode;
    }

    public static String cleanPath(String rawPath) {
        if (rawPath == null) return "";
        try {
            rawPath = java.net.URLDecoder.decode(rawPath, "UTF-8");
        } catch (Exception e) {
            // 降级
        }
        if (rawPath.contains(":")) {
            return rawPath.substring(rawPath.lastIndexOf(":") + 1);
        }
        return rawPath;
    }

    private boolean hasStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        } else {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
        }
    }

    private final ActivityResultLauncher<Intent> manageStorageLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> checkPendingActionAfterPermission()
    );

    private final ActivityResultLauncher<String> requestPermissionLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(),
            isGranted -> {
                if (isGranted) checkPendingActionAfterPermission();
                else Toast.makeText(this, "未授予权限，操作取消", Toast.LENGTH_SHORT).show();
            }
    );

    private void checkPendingActionAfterPermission() {
        if (pendingAuthItem == null) return;

        if (hasStoragePermission()) {
            if ("DELETE".equals(pendingAuthAction)) {
                performPhysicalDelete(pendingAuthItem);
            }
        } else {
            Toast.makeText(this, "未授予存储管理权限，操作失败", Toast.LENGTH_SHORT).show();
        }
        pendingAuthItem = null;
    }

    public void promptForStoragePermission(PdfItem item, String action) {
        this.pendingAuthItem = item;
        this.pendingAuthAction = action;

        new AlertDialog.Builder(this)
                .setTitle("需要存储授权")
                .setMessage("由于 Android 系统安全限制，删除此物理文件需要您开启【所有文件访问】权限。\n\n是否前往设置开启？")
                .setPositiveButton("去授权", (dialog, which) -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        try {
                            Intent intent = new Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                            intent.setData(Uri.parse("package:" + getPackageName()));
                            manageStorageLauncher.launch(intent);
                        } catch (Exception e) {
                            Intent intent = new Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                            manageStorageLauncher.launch(intent);
                        }
                    } else {
                        requestPermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE);
                    }
                })
                .setNegativeButton("仅从列表中移除", (dialog, which) -> {
                    if ("DELETE".equals(action)) {
                        removePdfItemFromApp(item);
                    }
                })
                .show();
    }

    private final ActivityResultLauncher<Intent> filePickerLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    Uri uri = result.getData().getData();
                    if (uri != null && currentMode == MODE_HOME) {
                        String displayPath = cleanPath(uri.getPath());
                        DocumentFile documentFile = DocumentFile.fromSingleUri(this, uri);
                        String name = documentFile != null && documentFile.getName() != null ? documentFile.getName() : "未命名.pdf";

                        if (dbHelper.existsInHome(uri.toString())) {
                            Toast.makeText(this, "该文件已在首页列表中", Toast.LENGTH_SHORT).show();
                            return;
                        }

                        if (documentFile != null && documentFile.exists()) {
                            try {
                                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                            } catch (SecurityException ignore) {}

                            String time = dateFormat.format(new Date(documentFile.lastModified()));
                            PdfItem item = new PdfItem(uri, name, displayPath, time, false);
                            dbHelper.insertOrUpdateHomeItem(item);
                            loadHomeData();
                        }
                    }
                }
            }
    );

    private final ActivityResultLauncher<Intent> folderPickerLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    Uri treeUri = result.getData().getData();
                    if (treeUri != null && currentMode == MODE_HOME) {
                        if (dbHelper.existsInHome(treeUri.toString())) {
                            Toast.makeText(this, "该文件夹已在首页列表中", Toast.LENGTH_SHORT).show();
                            return;
                        }

                        try {
                            getContentResolver().takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                        } catch (SecurityException ignore) {}

                        executorService.execute(() -> {
                            DocumentFile documentFile = DocumentFile.fromTreeUri(this, treeUri);
                            if (documentFile != null && documentFile.exists()) {
                                boolean hasDirectPdf = false;
                                for (DocumentFile file : documentFile.listFiles()) {
                                    if (!file.isDirectory()) {
                                        boolean isPdf = "application/pdf".equals(file.getType()) ||
                                                (file.getName() != null && file.getName().toLowerCase().endsWith(".pdf"));
                                        if (isPdf) {
                                            hasDirectPdf = true;
                                            break;
                                        }
                                    }
                                }

                                if (hasDirectPdf) {
                                    String name = documentFile.getName();
                                    String displayPath = cleanPath(treeUri.getPath());
                                    PdfItem item = new PdfItem(treeUri, name != null ? name : "未命名文件夹", displayPath, "", true);

                                    dbHelper.insertOrUpdateHomeItem(item);
                                    runOnUiThread(this::loadHomeData);
                                } else {
                                    runOnUiThread(() -> Toast.makeText(MainActivity.this,
                                            "该文件夹层不包含直接的 PDF 文件，无法添加", Toast.LENGTH_LONG).show());
                                }
                            }
                        });
                    }
                }
            }
    );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        uiManager = new MainUIManager(this);
        uiManager.setupUI();
        dbHelper = new PdfDbHelper(this);

        loadHomeData();

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (uiManager.closeDrawerIfOpen()) {
                    return;
                }
                if (currentMode != MODE_HOME) {
                    exitSubView();
                } else {
                    this.setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                    this.setEnabled(true);
                }
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (currentMode == MODE_HOME) {
            loadHomeData();
        }
    }

    public void performPhysicalDelete(PdfItem item) {
        deleteFilePhysically(item, success -> {
            if (success) {
                uiManager.removePdfItemFromUI(item);
                Toast.makeText(this, "物理文件已彻底删除", Toast.LENGTH_SHORT).show();
            } else {
                if (hasStoragePermission()) {
                    Toast.makeText(this, "删除失败：此文件受系统保护，不支持彻底删除", Toast.LENGTH_SHORT).show();
                } else {
                    promptForStoragePermission(item, "DELETE");
                }
            }
        });
    }

    private void deleteFilePhysically(PdfItem item, Consumer<Boolean> callback) {
        executorService.execute(() -> {
            boolean success = false;
            try {
                if (item.path != null && !item.path.isEmpty()) {
                    File f = new File(item.path);
                    if (f.exists() && f.delete()) {
                        success = true;
                    }
                }

                if (!success && item.uri != null) {
                    DocumentFile docFile = item.isFolder ? DocumentFile.fromTreeUri(this, item.uri) : DocumentFile.fromSingleUri(this, item.uri);
                    if (docFile != null && docFile.exists()) {
                        success = docFile.delete();
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }

            if (success && dbHelper != null) {
                dbHelper.deleteItem(item.uri.toString());
            }
            boolean finalSuccess = success;
            runOnUiThread(() -> {
                callback.accept(finalSuccess);
                if (finalSuccess && currentMode == MODE_HOME) loadHomeData();
            });
        });
    }

    public void showFileDetailsDialog(PdfItem item) {
        executorService.execute(() -> {
            StringBuilder detailsBuilder = new StringBuilder();
            try {
                DocumentFile docFile = item.isFolder ? DocumentFile.fromTreeUri(this, item.uri) : DocumentFile.fromSingleUri(this, item.uri);

                String sizeStr = "未知";
                if (docFile != null && docFile.exists() && !item.isFolder) {
                    long bytes = docFile.length();
                    if (bytes > 1024 * 1024) {
                        sizeStr = String.format(Locale.getDefault(), "%.2f MB", bytes / (1024f * 1024f));
                    } else {
                        sizeStr = String.format(Locale.getDefault(), "%.2f KB", bytes / 1024f);
                    }
                } else if (item.isFolder) {
                    sizeStr = "文件夹";
                }

                boolean isFav = dbHelper.isFavorite(item.path, item.name);

                String createTimeStr = item.time;
                String isModifiedStr = "否";
                String modifiedTimeStr = "无";

                android.database.sqlite.SQLiteDatabase db = dbHelper.getReadableDatabase();
                android.database.Cursor cursor = db.rawQuery("SELECT createTime, lastModified FROM items WHERE uri=?", new String[]{item.uri.toString()});
                if (cursor.moveToFirst()) {
                    long cTime = cursor.getLong(cursor.getColumnIndexOrThrow("createTime"));
                    long mTime = cursor.getLong(cursor.getColumnIndexOrThrow("lastModified"));
                    if (cTime > 0) createTimeStr = dateFormat.format(new Date(cTime));
                    if (mTime > 0) {
                        isModifiedStr = "是";
                        modifiedTimeStr = dateFormat.format(new Date(mTime));
                    }
                }
                cursor.close();

                detailsBuilder.append("名称：").append(item.name).append("\n\n");
                detailsBuilder.append("创建时间：").append(createTimeStr).append("\n\n");
                detailsBuilder.append("是否修改：").append(isModifiedStr).append("\n\n");
                detailsBuilder.append("修改时间：").append(modifiedTimeStr).append("\n\n");
                detailsBuilder.append("是否收藏：").append(isFav ? "是" : "否").append("\n\n");
                detailsBuilder.append("文件大小：").append(sizeStr);

            } catch (Exception e) {
                detailsBuilder.append("获取详情失败：").append(e.getMessage());
            }

            String finalDetails = detailsBuilder.toString();
            runOnUiThread(() -> {
                new AlertDialog.Builder(this)
                        .setTitle("属性详情")
                        .setMessage(finalDetails)
                        .setPositiveButton("我知道了", null)
                        .show();
            });
        });
    }

    public void loadHomeData() {
        currentTaskToken++; // 🌟 发放新令牌：每次进入首页，废除之前的任何后台加载！
        currentMode = MODE_HOME;
        uiManager.clearList();
        uiManager.setShowPath(true);
        uiManager.setRecentMode(false);
        uiManager.showFab(true);
        List<PdfItem> savedItems = dbHelper.getAllHomeItems();
        if (savedItems.isEmpty()) {
            uiManager.showEmptyStateByMode();
        } else {
            for (PdfItem item : savedItems) {
                uiManager.addPdfItem(item);
            }
        }
    }

    public void exitSubView() {
        if (currentMode == MODE_SEARCH) {
            exitSearchMode();
        } else {
            uiManager.clearList();
            uiManager.setupAsHomeView();
            loadHomeData(); // 这会改变全局 Token
        }
    }

    public void enterSearchMode() {
        if (currentMode != MODE_SEARCH) {
            preSearchMode = currentMode;
            currentMode = MODE_SEARCH;
        }
    }

    public void exitSearchMode() {
        uiManager.clearList();
        if (preSearchMode == MODE_RECENT) {
            loadRecentData(); // 这也会改变全局 Token
        } else {
            uiManager.setupAsHomeView();
            loadHomeData();
        }
    }

    private void loadFolderContents(Uri folderUri, String folderName) {
        final long myToken = ++currentTaskToken; // 🌟 领走本次的专属任务令牌

        executorService.execute(() -> {
            DocumentFile folder = DocumentFile.fromTreeUri(this, folderUri);
            if (folder != null && folder.exists() && folder.isDirectory()) {
                DocumentFile[] files = folder.listFiles();
                if (files == null || files.length == 0) {
                    runOnUiThread(() -> {
                        if (myToken == currentTaskToken) uiManager.showEmptyState("没有文件", false);
                    });
                    return;
                }
                boolean addedAny = false;
                Arrays.sort(files, (f1, f2) -> {
                    String name1 = f1.getUri().getLastPathSegment();
                    String name2 = f2.getUri().getLastPathSegment();
                    return name1.compareTo(name2);
                });

                for (DocumentFile file : files) {
                    if (myToken != currentTaskToken) return; // 🌟 核心拦截：一旦用户按了返回键切走，线程直接自杀，绝不污染UI！

                    if (!file.isDirectory()) {
                        boolean isPdf = "application/pdf".equals(file.getType()) ||
                                (file.getName() != null && file.getName().toLowerCase().endsWith(".pdf"));
                        if (isPdf) {
                            String name = file.getName();
                            String realPath = cleanPath(file.getUri().getPath());

                            if (dbHelper != null && dbHelper.isFileExcluded(realPath, name)) {
                                continue;
                            }

                            String time = dateFormat.format(new Date(file.lastModified()));
                            PdfItem item = new PdfItem(file.getUri(), name != null ? name : "未知文件", realPath, time, false);

                            // 投递到 UI 前再核对一次令牌，确保绝对安全
                            runOnUiThread(() -> {
                                if (myToken == currentTaskToken) {
                                    uiManager.addPdfItem(item);
                                }
                            });
                            addedAny = true;
                        }
                    }
                }
                if (!addedAny) runOnUiThread(() -> {
                    if (myToken == currentTaskToken) uiManager.showEmptyState("没有文件", false);
                });
            } else {
                runOnUiThread(() -> {
                    if (myToken == currentTaskToken) uiManager.showEmptyState("没有文件", false);
                });
            }
        });
    }

    public void switchToFolderView(PdfItem folderItem) {
        currentMode = MODE_FOLDER;
        uiManager.clearList();
        uiManager.setShowPath(false);
        uiManager.setRecentMode(false);
        uiManager.setupAsSubView(folderItem.name);
        loadFolderContents(folderItem.uri, folderItem.name);
    }

    public void loadRecentData() {
        currentTaskToken++; // 🌟 刷新令牌
        currentMode = MODE_RECENT;
        uiManager.clearList();
        uiManager.setShowPath(true);
        uiManager.setRecentMode(true);
        uiManager.setupAsSubView("最近查看");

        List<PdfItem> recentItems = dbHelper.getRecentItems();
        if (recentItems.isEmpty()) {
            uiManager.showEmptyState("没有文件", false);
        } else {
            for (PdfItem item : recentItems) {
                uiManager.addPdfItem(item);
            }
        }
    }

    public void recordViewHistory(PdfItem item) {
        if (dbHelper != null && !item.isFolder) {
            executorService.execute(() -> dbHelper.recordViewHistory(item));
        }
    }

    public void removeRecentRecord(PdfItem item) {
        if (dbHelper != null) {
            executorService.execute(() -> {
                dbHelper.removeRecentItem(item.uri.toString());
                runOnUiThread(() -> {
                    if (currentMode == MODE_RECENT) {
                        loadRecentData();
                    }
                });
            });
        }
    }

    public void performSearch(String keyword) {
        if (dbHelper == null || keyword.trim().isEmpty()) return;
        final long myToken = ++currentTaskToken; // 🌟 搜索任务也领取令牌

        if (currentMode == MODE_RECENT || (currentMode == MODE_SEARCH && preSearchMode == MODE_RECENT)) {
            List<PdfItem> currentRecents = dbHelper.getRecentItems();
            uiManager.clearList();
            uiManager.showSearchingState();
            currentMode = MODE_SEARCH;
            uiManager.setupAsSubView("搜索历史结果: " + keyword);
            uiManager.setShowPath(true);
            uiManager.setRecentMode(false);

            executorService.execute(() -> {
                List<PdfItem> results = new ArrayList<>();
                for (PdfItem item : currentRecents) {
                    if (myToken != currentTaskToken) return; // 🌟 过期拦截
                    if (item.name.toLowerCase().contains(keyword.toLowerCase())) {
                        results.add(item);
                    }
                }
                runOnUiThread(() -> {
                    if (myToken != currentTaskToken) return; // 🌟 UI拦截
                    uiManager.clearList();
                    if (results.isEmpty()) {
                        uiManager.showEmptyState("没有文件", false);
                    } else {
                        for (PdfItem item : results) uiManager.addPdfItem(item);
                    }
                });
            });
            return;
        }

        uiManager.clearList();
        uiManager.showSearchingState();

        if (currentMode != MODE_SEARCH) {
            preSearchMode = currentMode;
        }
        currentMode = MODE_SEARCH;

        uiManager.setupAsSubView("正在搜索...");
        uiManager.setShowPath(true);
        uiManager.setRecentMode(false);

        executorService.execute(() -> {
            String lowerKeyword = keyword.toLowerCase();
            List<PdfItem> allHomeItems = dbHelper.getAllHomeItems();
            List<PdfItem> searchResults = new ArrayList<>();
            HashSet<String> uniqueFileRegistry = new HashSet<>();

            for (PdfItem item : allHomeItems) {
                if (myToken != currentTaskToken) return; // 🌟 过期拦截

                if (!item.isFolder) {
                    if (item.name.toLowerCase().contains(lowerKeyword)) {
                        String fileKey = item.name + "@" + item.path;
                        if (!uniqueFileRegistry.contains(fileKey)) {
                            uniqueFileRegistry.add(fileKey);
                            searchResults.add(item);
                        }
                    }
                } else {
                    DocumentFile folder = DocumentFile.fromTreeUri(this, item.uri);
                    if (folder != null && folder.exists()) {
                        DocumentFile[] files = folder.listFiles();
                        if (files != null) {
                            for (DocumentFile file : files) {
                                if (myToken != currentTaskToken) return; // 🌟 深度嵌套时也要拦截

                                if (!file.isDirectory() && file.getName() != null) {
                                    boolean isPdf = "application/pdf".equals(file.getType()) || file.getName().toLowerCase().endsWith(".pdf");
                                    if (isPdf && file.getName().toLowerCase().contains(lowerKeyword)) {
                                        String name = file.getName();
                                        String time = dateFormat.format(new Date(file.lastModified()));
                                        String realPath = cleanPath(file.getUri().getPath());

                                        if (dbHelper.isFileExcluded(realPath, name)) {
                                            continue;
                                        }

                                        String fileKey = name + "@" + realPath;
                                        if (!uniqueFileRegistry.contains(fileKey)) {
                                            uniqueFileRegistry.add(fileKey);
                                            PdfItem result = new PdfItem(file.getUri(), name, realPath, time, false);
                                            searchResults.add(result);
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            runOnUiThread(() -> {
                if (myToken != currentTaskToken) return; // 🌟 UI 拦截
                uiManager.clearList();
                uiManager.updateTitle("搜索结果 (" + searchResults.size() + "条)");

                if (preSearchMode == MODE_HOME) {
                    uiManager.showFab(true);
                    if (searchResults.isEmpty()) {
                        uiManager.showEmptyState("暂无文件，请点击添加", true);
                    }
                } else {
                    uiManager.showFab(false);
                    if (searchResults.isEmpty()) {
                        uiManager.showEmptyState("没有文件", false);
                    }
                }

                for (PdfItem item : searchResults) {
                    uiManager.addPdfItem(item);
                }
            });
        });
    }

    public void removePdfItemFromApp(PdfItem item) {
        if (dbHelper != null) {
            executorService.execute(() -> {
                dbHelper.removeHomeItem(item);
                runOnUiThread(() -> {
                    if (currentMode == MODE_HOME) loadHomeData();
                });
            });
        }
    }

    public void loadFavoriteData() {
        currentTaskToken++; // 🌟 刷新令牌
        currentMode = MODE_FAVORITE;
        uiManager.clearList();
        uiManager.setShowPath(true);
        uiManager.setRecentMode(false);
        uiManager.setupAsSubView("收藏夹");

        List<PdfItem> favoriteItems = dbHelper.getFavoriteItems();
        if (favoriteItems.isEmpty()) {
            uiManager.showEmptyState("没有文件", false);
        } else {
            for (PdfItem item : favoriteItems) {
                uiManager.addPdfItem(item);
            }
        }
    }

    public void removeFavoriteRecord(PdfItem item) {
        if (dbHelper != null) {
            executorService.execute(() -> {
                dbHelper.removeFavoriteItem(item.uri.toString());
                runOnUiThread(() -> {
                    if (currentMode == MODE_FAVORITE) {
                        loadFavoriteData();
                    }
                });
            });
        }
    }

    public void openFilePicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/pdf");
        filePickerLauncher.launch(intent);
    }

    public void openFolderPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        folderPickerLauncher.launch(intent);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_toolbar, menu);
        uiManager.setupOptionsMenu(menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (uiManager.handleToolbarMenuClick(item)) {
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}