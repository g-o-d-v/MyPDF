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
import android.provider.DocumentsContract;
import android.database.Cursor;

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

    private volatile long currentTaskToken = 0;

    private Uri currentFolderUri = null;

    private final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault());
    private final ExecutorService executorService = Executors.newSingleThreadExecutor();

    public int getCurrentMode() {
        return currentMode;
    }

    public Uri getCurrentFolderUri() {
        return currentFolderUri;
    }

    public static String cleanPath(String rawPath) {
        if (rawPath == null) return "";
        try {
            rawPath = java.net.URLDecoder.decode(rawPath, "UTF-8");
        } catch (Exception e) {
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

                        // 🌟 添加文件夹属于较重的操作，加入提示
                        uiManager.showLoadingState();

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
                                    runOnUiThread(() -> {
                                        Toast.makeText(MainActivity.this, "该文件夹不包含 PDF 文件", Toast.LENGTH_LONG).show();
                                        loadHomeData();
                                    });
                                }
                            } else {
                                runOnUiThread(this::loadHomeData);
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
        if (item != null && item.isFolder) {
            removePdfItemFromApp(item);
            Toast.makeText(this, "已从列表中移除文件夹，未删除文件夹及其中任何文件", Toast.LENGTH_LONG).show();
            return;
        }
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

                if (!success && item.uri != null && !item.isFolder) {
                    DocumentFile docFile = DocumentFile.fromSingleUri(this, item.uri);
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
        PdfFileDetailsDialog.show(this, item, dbHelper, executorService);
    }

    public void loadHomeData() {
        currentTaskToken++;
        currentMode = MODE_HOME;
        currentFolderUri = null;
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
            loadHomeData();
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
            loadRecentData();
        } else {
            uiManager.setupAsHomeView();
            loadHomeData();
        }
    }

    // =========================================================================
    // 🌟 终极极速加载引擎：彻底抛弃缓慢的 DocumentFile 遍历，采用 Cursor 批量寻址
    // =========================================================================
    // =========================================================================
    // 🌟 终极极速加载引擎：修复 Tree URI 解析异常，完美匹配 SAF 文件夹
    // =========================================================================
    private void loadFolderContents(Uri folderUri, String folderName) {
        final long myToken = ++currentTaskToken;

        // 🌟 显示加载中，速度极快通常只会闪现一下
        uiManager.showLoadingState();

        executorService.execute(() -> {
            List<PdfItem> tempFiles = new ArrayList<>();
            android.database.Cursor cursor = null; // 使用完整路径，无需去顶部导包
            try {
                // 🌟 核心修复点：提取文件夹树的 ID，必须用 getTreeDocumentId！
                String folderId = android.provider.DocumentsContract.getTreeDocumentId(folderUri);
                Uri childrenUri = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(folderUri, folderId);

                // 2. 告诉系统我们只需要这几个核心字段，拒绝冗余垃圾信息
                String[] projection = new String[]{
                        android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE,
                        android.provider.DocumentsContract.Document.COLUMN_LAST_MODIFIED
                };

                // 3. 一次性把整个文件夹的属性拉回内存 (极速 O(1) 操作)
                cursor = getContentResolver().query(childrenUri, projection, null, null, null);

                if (cursor != null) {
                    while (cursor.moveToNext()) {
                        if (myToken != currentTaskToken) break; // 令牌过期直接中断

                        String docId = cursor.getString(0);
                        String name = cursor.getString(1);
                        String mimeType = cursor.getString(2);
                        long lastModified = cursor.getLong(3);

                        // 4. 判断是否是 PDF
                        boolean isPdf = "application/pdf".equals(mimeType) ||
                                (name != null && name.toLowerCase().endsWith(".pdf"));

                        if (isPdf && name != null) {
                            // 极速构建单个文件的专属 URI
                            Uri fileUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(folderUri, docId);
                            String realPath = cleanPath(fileUri.getPath());

                            // 排除检测
                            if (dbHelper != null && dbHelper.isFileExcluded(realPath, name)) {
                                continue;
                            }

                            String time = dateFormat.format(new Date(lastModified));
                            tempFiles.add(new PdfItem(fileUri, name, realPath, time, false));
                        }
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                // 🌟 必须关闭游标防止内存泄漏
                if (cursor != null) {
                    cursor.close();
                }
            }

            if (myToken != currentTaskToken) return;

            // 5. 在内存中完成极速“自然语义”排序
            if (!tempFiles.isEmpty()) {
                java.util.Collections.sort(tempFiles, (item1, item2) -> {
                    String n1 = item1.name != null ? item1.name : "";
                    String n2 = item2.name != null ? item2.name : "";
                    return new SmartVolumeSniffer.NaturalComparator().compare(n1, n2);
                });

                // 6. 排序完成后，一次性推送到主线程 UI 上，彻底消灭逐个添加带来的界面卡顿
                runOnUiThread(() -> {
                    if (myToken == currentTaskToken) {
                        uiManager.clearList();
                        for (PdfItem item : tempFiles) {
                            uiManager.addPdfItem(item);
                        }
                    }
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
        currentFolderUri = folderItem.uri;
        uiManager.clearList();
        uiManager.setShowPath(false);
        uiManager.setRecentMode(false);
        uiManager.setupAsSubView(folderItem.name);
        loadFolderContents(folderItem.uri, folderItem.name);
    }

    public void loadRecentData() {
        currentTaskToken++;
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
        final long myToken = ++currentTaskToken;

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
                    if (myToken != currentTaskToken) return;
                    if (item.name.toLowerCase().contains(keyword.toLowerCase())) {
                        results.add(item);
                    }
                }
                runOnUiThread(() -> {
                    if (myToken != currentTaskToken) return;
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
                if (myToken != currentTaskToken) return;

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
                                if (myToken != currentTaskToken) return;

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
                if (myToken != currentTaskToken) return;
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
        currentTaskToken++;
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