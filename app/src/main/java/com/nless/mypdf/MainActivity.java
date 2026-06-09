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
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

public class MainActivity extends AppCompatActivity {

    private MainUIManager uiManager;
    private PdfDbHelper dbHelper;
    private boolean isHomeScreen = true; // 用于区分当前是主页还是文件夹详情页

    private final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault());
    private final ExecutorService executorService = Executors.newSingleThreadExecutor();

    // 1. 单文件选择
    private final ActivityResultLauncher<Intent> filePickerLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    Uri uri = result.getData().getData();
                    if (uri != null && isHomeScreen) {
                        // 查重拦截
                        if (dbHelper.exists(uri.toString())) {
                            Toast.makeText(this, "该文件已在首页列表中", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        DocumentFile documentFile = DocumentFile.fromSingleUri(this, uri);
                        if (documentFile != null && documentFile.exists()) {
                            String name = documentFile.getName();
                            String time = dateFormat.format(new Date(documentFile.lastModified()));
                            PdfItem item = new PdfItem(uri, name != null ? name : "未命名.pdf", uri.getPath(), time, false);

                            dbHelper.insertItem(item); // 存入数据库
                            uiManager.addPdfItem(item);
                        }
                    }
                }
            }
    );

    // 2. 文件夹选择
    private final ActivityResultLauncher<Intent> folderPickerLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    Uri treeUri = result.getData().getData();
                    if (treeUri != null && isHomeScreen) {
                        // 查重拦截
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
                                    PdfItem item = new PdfItem(treeUri, name != null ? name : "未命名文件夹", treeUri.getPath(), "", true);

                                    runOnUiThread(() -> {
                                        dbHelper.insertItem(item); // 存入数据库
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

        String folderUriStr = getIntent().getStringExtra("folder_uri");
        isHomeScreen = (folderUriStr == null);

        if (isHomeScreen) {
            // 是主页：初始化数据库，读取并展示保存的内容
            dbHelper = new PdfDbHelper(this);
            List<PdfItem> savedItems = dbHelper.getAllItems();
            for (PdfItem item : savedItems) {
                uiManager.addPdfItem(item);
            }
        } else {
            // 是文件夹详情页：不读数据库，动态加载里面的内容
            String folderName = getIntent().getStringExtra("folder_name");
            uiManager.setupAsFolderView(folderName != null ? folderName : "文件夹");
            loadFolderContents(Uri.parse(folderUriStr));
        }

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (!uiManager.closeDrawerIfOpen()) {
                    this.setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                }
            }
        });
    }

    // 原位无缝切换到文件夹详情，不再重启 Activity
    public void switchToFolderView(PdfItem folderItem) {
        // 1. 改变 UI 状态为文件夹模式
        isHomeScreen = false;
        uiManager.clearList();
        uiManager.setupAsFolderView(folderItem.name);

        // 2. 异步加载文件夹内部文件（加载不影响用户操作）
        loadFolderContents(folderItem.uri);

        // 3. 拦截物理返回键，使其效果变为“退回首页”而不是退出应用
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                isHomeScreen = true;
                uiManager.clearList();
                uiManager.setupAsHomeView(); // 恢复汉堡菜单和加号

                // 重新读取数据库加载首页收藏
                List<PdfItem> savedItems = dbHelper.getAllItems();
                for (PdfItem item : savedItems) {
                    uiManager.addPdfItem(item);
                }

                // 销毁当前拦截器，下一次按返回键就正常退出了
                this.setEnabled(false);
            }
        });
    }

    private void loadFolderContents(Uri folderUri) {
        executorService.execute(() -> {
            DocumentFile folder = DocumentFile.fromTreeUri(this, folderUri);
            if (folder != null && folder.exists() && folder.isDirectory()) {
                for (DocumentFile file : folder.listFiles()) {
                    if (!file.isDirectory()) {
                        boolean isPdf = "application/pdf".equals(file.getType()) ||
                                (file.getName() != null && file.getName().toLowerCase().endsWith(".pdf"));
                        if (isPdf) {
                            String name = file.getName();
                            String time = dateFormat.format(new Date(file.lastModified()));
                            PdfItem item = new PdfItem(file.getUri(), name != null ? name : "未知文件", "", time, false);
                            runOnUiThread(() -> uiManager.addPdfItem(item));
                        }
                    }
                }
            }
        });
    }

    // 核心暴露给 UI：移除（仅清除数据库和列表，不删源文件）
    public void removePdfItemFromApp(PdfItem item) {
        if (isHomeScreen && dbHelper != null) {
            dbHelper.deleteItem(item.uri.toString());
        }
    }

    // 核心暴露给 UI：物理彻底删除
    public void deleteFilePhysically(PdfItem item, Consumer<Boolean> callback) {
        executorService.execute(() -> {
            boolean success = false;
            try {
                DocumentFile docFile;
                if (item.isFolder) {
                    docFile = DocumentFile.fromTreeUri(this, item.uri);
                } else {
                    docFile = DocumentFile.fromSingleUri(this, item.uri);
                }
                if (docFile != null && docFile.exists()) {
                    success = docFile.delete();
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
            // 如果物理删除成功，同步清理数据库
            if (success && isHomeScreen && dbHelper != null) {
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