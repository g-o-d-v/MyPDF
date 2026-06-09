package com.nless.mypdf;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.documentfile.provider.DocumentFile;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
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

    private int currentMode = MODE_HOME;
    private int preSearchMode = MODE_HOME;

    private MainUIManager uiManager;
    private PdfDbHelper dbHelper;

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

    private final ActivityResultLauncher<Intent> filePickerLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    Uri uri = result.getData().getData();
                    if (uri != null && currentMode == MODE_HOME) {
                        if (dbHelper.exists(uri.toString())) {
                            Toast.makeText(this, "该文件已在首页列表中", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        DocumentFile documentFile = DocumentFile.fromSingleUri(this, uri);
                        if (documentFile != null && documentFile.exists()) {
                            String name = documentFile.getName();
                            String time = dateFormat.format(new Date(documentFile.lastModified()));
                            String displayPath = cleanPath(uri.getPath());

                            PdfItem item = new PdfItem(uri, name != null ? name : "未命名.pdf", displayPath, time, false);
                            dbHelper.insertItem(item);
                            uiManager.addPdfItem(item);
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
                        if (dbHelper.exists(treeUri.toString())) {
                            Toast.makeText(this, "该文件夹已在首页列表中", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        getContentResolver().takePersistableUriPermission(treeUri,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);

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

                                    runOnUiThread(() -> {
                                        dbHelper.insertItem(item);
                                        uiManager.addPdfItem(item);
                                    });
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

    public void loadHomeData() {
        currentMode = MODE_HOME;
        uiManager.clearList();
        uiManager.setShowPath(true);
        uiManager.setRecentMode(false);
        List<PdfItem> savedItems = dbHelper.getAllItems();
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

    private void loadFolderContents(Uri folderUri, String folderName) {
        executorService.execute(() -> {
            DocumentFile folder = DocumentFile.fromTreeUri(this, folderUri);
            if (folder != null && folder.exists() && folder.isDirectory()) {
                DocumentFile[] files = folder.listFiles();
                if (files == null || files.length == 0) {
                    runOnUiThread(() -> uiManager.showEmptyStateByMode());
                    return;
                }
                boolean addedAny = false;
                for (DocumentFile file : files) {
                    if (!file.isDirectory()) {
                        boolean isPdf = "application/pdf".equals(file.getType()) ||
                                (file.getName() != null && file.getName().toLowerCase().endsWith(".pdf"));
                        if (isPdf) {
                            String name = file.getName();
                            String time = dateFormat.format(new Date(file.lastModified()));
                            // 🌟 核心修复：不再手动拼接路径，而是从系统 URI 中提取该文件的真实物理路径
                            String realPath = cleanPath(file.getUri().getPath());
                            PdfItem item = new PdfItem(file.getUri(), name != null ? name : "未知文件", realPath, time, false);
                            runOnUiThread(() -> uiManager.addPdfItem(item));
                            addedAny = true;
                        }
                    }
                }
                if (!addedAny) runOnUiThread(() -> uiManager.showEmptyStateByMode());
            } else {
                runOnUiThread(() -> uiManager.showEmptyStateByMode());
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
        currentMode = MODE_RECENT;
        uiManager.clearList();
        uiManager.setShowPath(true);
        uiManager.setRecentMode(true);
        uiManager.setupAsSubView("最近查看");

        List<PdfItem> recentItems = dbHelper.getRecentItems();
        if (recentItems.isEmpty()) {
            uiManager.showEmptyStateByMode();
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
                dbHelper.deleteItem(item.uri.toString());
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
                    if (item.name.toLowerCase().contains(keyword.toLowerCase())) {
                        results.add(item);
                    }
                }
                runOnUiThread(() -> {
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
            List<PdfItem> allHomeItems = dbHelper.getAllItems();
            List<PdfItem> searchResults = new ArrayList<>();
            HashSet<String> uniqueFileRegistry = new HashSet<>();

            for (PdfItem item : allHomeItems) {
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
                        for (DocumentFile file : folder.listFiles()) {
                            if (!file.isDirectory() && file.getName() != null) {
                                boolean isPdf = "application/pdf".equals(file.getType()) || file.getName().toLowerCase().endsWith(".pdf");
                                if (isPdf && file.getName().toLowerCase().contains(lowerKeyword)) {
                                    String name = file.getName();
                                    String time = dateFormat.format(new Date(file.lastModified()));
                                    // 🌟 核心修复：搜索时同样使用系统真实的物理路径进行注册和比对
                                    String realPath = cleanPath(file.getUri().getPath());

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

            runOnUiThread(() -> {
                uiManager.clearList();
                uiManager.updateTitle("搜索结果 (" + searchResults.size() + "条)");

                if (preSearchMode == MODE_HOME) {
                    uiManager.showFab(true);
                    if (searchResults.isEmpty()) {
                        uiManager.showEmptyState("暂无文件，请点击添加", true);
                    }
                } else {
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
        if (currentMode == MODE_HOME && dbHelper != null) {
            dbHelper.deleteItem(item.uri.toString());
        }
    }

    public void deleteFilePhysically(PdfItem item, Consumer<Boolean> callback) {
        executorService.execute(() -> {
            boolean success = false;
            try {
                DocumentFile docFile = item.isFolder ? DocumentFile.fromTreeUri(this, item.uri) : DocumentFile.fromSingleUri(this, item.uri);
                if (docFile != null && docFile.exists()) {
                    success = docFile.delete();
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
            if (success && currentMode == MODE_HOME && dbHelper != null) {
                dbHelper.deleteItem(item.uri.toString());
            }
            boolean finalSuccess = success;
            runOnUiThread(() -> callback.accept(finalSuccess));
        });
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