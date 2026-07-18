package com.nless.mypdf;

import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ResultReceiver;
import android.provider.OpenableColumns;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.documentfile.provider.DocumentFile;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** 页面排序、删除、另存所选页面和合并 PDF 的统一界面。 */
public class ManagePdfPagesActivity extends AppCompatActivity {

    public static final String EXTRA_MODE = "manage_pdf_mode";
    public static final String MODE_REORDER = "reorder";
    public static final String MODE_DELETE = "delete";
    public static final String MODE_SAVE_SELECTED = "save_selected";
    public static final String MODE_MERGE = "merge";

    private static final int TEXT_PRIMARY = 0xFF202124;
    private static final int TEXT_SECONDARY = 0xFF6F7378;
    private static final int BLUE = 0xFF03A9F4;

    private Toolbar toolbar;
    private TextView descriptionView;
    private TextView sectionTitleView;
    private LinearLayout sourceCard;
    private LinearLayout controlCard;
    private TextView emptyView;
    private RecyclerView recyclerView;
    private TextView tipView;
    private TextView actionButton;

    private String mode = MODE_REORDER;
    private Uri sourceUri;
    private String sourceName = "";
    private int sourcePageCount;
    private PdfThumbnailRepository thumbnailRepository;
    private final List<PageItem> pageItems = new ArrayList<>();
    private final List<MergeItem> mergeItems = new ArrayList<>();
    private PageAdapter pageAdapter;
    private MergeAdapter mergeAdapter;
    private ItemTouchHelper itemTouchHelper;
    private int dragChangedStart = RecyclerView.NO_POSITION;
    private int dragChangedEnd = RecyclerView.NO_POSITION;

    private static final Object PAYLOAD_ORDER_LABEL = new Object();

    private final ExecutorService workerExecutor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private AlertDialog progressDialog;
    private ProgressBar progressBar;
    private TextView progressText;
    private String activeMergeTaskId;
    private Uri activeMergeOutputUri;
    private ResultReceiver mergeResultReceiver;

    private final ActivityResultLauncher<String[]> sourcePicker = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(),
            uri -> {
                if (uri == null) return;
                persistReadPermission(uri);
                inspectPageSource(uri);
            }
    );

    private final ActivityResultLauncher<String[]> mergePicker = registerForActivityResult(
            new ActivityResultContracts.OpenMultipleDocuments(),
            uris -> {
                if (uris == null || uris.isEmpty()) return;
                addMergeSources(uris);
            }
    );

    private final ActivityResultLauncher<String> outputPicker = registerForActivityResult(
            new ActivityResultContracts.CreateDocument("application/pdf"),
            uri -> {
                if (uri == null) return;
                persistWritePermission(uri);
                runCurrentOperation(uri);
            }
    );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_manage_pdf_pages);

        toolbar = findViewById(R.id.manage_pdf_toolbar);
        toolbar.setNavigationIcon(android.R.drawable.ic_menu_revert);
        toolbar.setNavigationOnClickListener(v -> finish());
        descriptionView = findViewById(R.id.manage_pdf_description);
        sectionTitleView = findViewById(R.id.manage_pdf_section_title);
        sourceCard = findViewById(R.id.manage_pdf_source_card);
        controlCard = findViewById(R.id.manage_pdf_control_card);
        emptyView = findViewById(R.id.manage_pdf_empty);
        recyclerView = findViewById(R.id.manage_pdf_recycler);
        tipView = findViewById(R.id.manage_pdf_tip);
        actionButton = findViewById(R.id.manage_pdf_button);
        actionButton.setOnClickListener(v -> chooseOutput());

        mergeResultReceiver = createMergeResultReceiver();

        String requestedMode = getIntent().getStringExtra(EXTRA_MODE);
        if (MODE_DELETE.equals(requestedMode)
                || MODE_SAVE_SELECTED.equals(requestedMode)
                || MODE_MERGE.equals(requestedMode)) {
            mode = requestedMode;
        }
        buildUi();
    }

    @Override
    protected void onDestroy() {
        cancelled.set(true);
        if (isFinishing() && activeMergeTaskId != null) {
            PdfMergeWorkerService.cancel(this, activeMergeTaskId);
        }
        closeThumbnailRepository();
        workerExecutor.shutdownNow();
        super.onDestroy();
    }

    private void buildUi() {
        if (MODE_DELETE.equals(mode)) {
            toolbar.setTitle("删除页面");
            descriptionView.setText("点击缩略图选择要删除的页面，生成删除后的 PDF 副本；原文件不会被修改。");
            sectionTitleView.setText("PDF 文件");
            tipView.setText("至少需要保留一页。删除操作只影响新生成的 PDF，原 PDF 不会被覆盖。");
        } else if (MODE_SAVE_SELECTED.equals(mode)) {
            toolbar.setTitle("另存所选页面");
            descriptionView.setText("选择要保留的页面，并按原顺序另存为新的 PDF。");
            sectionTitleView.setText("PDF 文件");
            tipView.setText("输出仍然是 PDF，会保留所选页面中的文本层、矢量内容、图片和批注。");
        } else if (MODE_MERGE.equals(mode)) {
            toolbar.setTitle("合并 PDF");
            descriptionView.setText("添加两个或更多 PDF，调整文件顺序后合并为一个新文件。");
            sectionTitleView.setText("待合并文件");
            tipView.setText("不同页面尺寸会按原样保留。加密、损坏或包含特殊交互内容的 PDF 可能无法完整合并。");
        } else {
            toolbar.setTitle("页面排序");
            descriptionView.setText("长按页面缩略图并拖动，调整 PDF 页面顺序后另存为新文件。");
            sectionTitleView.setText("PDF 文件");
            tipView.setText("超长页面缩略图会显示顶部预览，输出内容仍会完整保留。原 PDF 不会被修改。");
        }

        if (MODE_MERGE.equals(mode)) {
            recyclerView.setLayoutManager(new LinearLayoutManager(this));
            mergeAdapter = new MergeAdapter();
            recyclerView.setAdapter(mergeAdapter);
            buildMergeSourceCard();
            buildMergeControlCard();
        } else {
            recyclerView.setLayoutManager(new GridLayoutManager(this, 2));
            pageAdapter = new PageAdapter();
            recyclerView.setAdapter(pageAdapter);
            buildPageSourceCard();
            buildPageControlCard();
            if (MODE_REORDER.equals(mode)) attachReorderHelper();
        }
        updateEmptyState();
        refreshActionButton();
    }

    private void buildPageSourceCard() {
        sourceCard.removeAllViews();
        LinearLayout row = horizontalRow();
        row.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout textColumn = new LinearLayout(this);
        textColumn.setOrientation(LinearLayout.VERTICAL);
        TextView name = text(sourceUri == null ? "尚未选择 PDF" : sourceName,
                15, TEXT_PRIMARY, true);
        name.setMaxLines(2);
        name.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        TextView detail = text(sourceUri == null
                        ? "选择 PDF 后将按需加载页面缩略图"
                        : sourcePageCount > 0
                                ? sourcePageCount + " 页"
                                : "正在读取页面信息……",
                12, TEXT_SECONDARY, false);
        detail.setPadding(0, dp(6), 0, 0);
        textColumn.addView(name);
        textColumn.addView(detail);
        row.addView(textColumn, new LinearLayout.LayoutParams(0, wrap(), 1f));

        TextView select = smallPrimaryButton(sourceUri == null ? "选择 PDF" : "更换");
        select.setOnClickListener(v -> sourcePicker.launch(new String[]{"application/pdf"}));
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(dp(96), dp(42));
        buttonParams.setMargins(dp(12), 0, 0, 0);
        row.addView(select, buttonParams);
        sourceCard.addView(row, matchWrap());
    }

    private void buildMergeSourceCard() {
        sourceCard.removeAllViews();
        LinearLayout row = horizontalRow();
        row.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout textColumn = new LinearLayout(this);
        textColumn.setOrientation(LinearLayout.VERTICAL);
        TextView name = text(mergeItems.isEmpty()
                        ? "尚未添加 PDF"
                        : "已添加 " + mergeItems.size() + " 个 PDF",
                15, TEXT_PRIMARY, true);
        TextView detail = text(mergeItems.isEmpty()
                        ? "至少添加两个 PDF，可分多次继续添加"
                        : "最终将按下方列表从上到下的顺序合并",
                12, TEXT_SECONDARY, false);
        detail.setPadding(0, dp(6), 0, 0);
        textColumn.addView(name);
        textColumn.addView(detail);
        row.addView(textColumn, new LinearLayout.LayoutParams(0, wrap(), 1f));

        TextView add = smallPrimaryButton("添加 PDF");
        add.setOnClickListener(v -> mergePicker.launch(new String[]{"application/pdf"}));
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(dp(96), dp(42));
        buttonParams.setMargins(dp(12), 0, 0, 0);
        row.addView(add, buttonParams);
        sourceCard.addView(row, matchWrap());
    }

    private void buildPageControlCard() {
        controlCard.removeAllViews();
        LinearLayout row = horizontalRow();
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(10), dp(10), dp(10));

        TextView status;
        if (MODE_REORDER.equals(mode)) {
            status = text(sourcePageCount > 0
                            ? "共 " + sourcePageCount + " 页 · 长按拖动排序"
                            : "选择 PDF 后显示页面缩略图",
                    13, TEXT_SECONDARY, false);
            row.addView(status, new LinearLayout.LayoutParams(0, wrap(), 1f));
            TextView reset = smallSecondaryButton("恢复顺序");
            reset.setEnabled(sourcePageCount > 0);
            reset.setAlpha(sourcePageCount > 0 ? 1f : 0.5f);
            reset.setOnClickListener(v -> resetPageOrder());
            row.addView(reset, new LinearLayout.LayoutParams(dp(94), dp(38)));
        } else {
            int selected = selectedPageCount();
            status = text(sourcePageCount > 0
                            ? "已选择 " + selected + " / " + sourcePageCount + " 页"
                            : "选择 PDF 后点击缩略图选择页面",
                    13, selected > 0 ? BLUE : TEXT_SECONDARY, false);
            row.addView(status, new LinearLayout.LayoutParams(0, wrap(), 1f));

            TextView selectAll = smallSecondaryButton("全选");
            selectAll.setEnabled(sourcePageCount > 0);
            selectAll.setAlpha(sourcePageCount > 0 ? 1f : 0.5f);
            selectAll.setOnClickListener(v -> setAllPagesSelected(true));
            row.addView(selectAll, new LinearLayout.LayoutParams(dp(64), dp(38)));

            TextView clear = smallSecondaryButton("清除");
            clear.setEnabled(sourcePageCount > 0);
            clear.setAlpha(sourcePageCount > 0 ? 1f : 0.5f);
            clear.setOnClickListener(v -> setAllPagesSelected(false));
            LinearLayout.LayoutParams clearParams = new LinearLayout.LayoutParams(dp(64), dp(38));
            clearParams.setMargins(dp(8), 0, 0, 0);
            row.addView(clear, clearParams);
        }
        controlCard.addView(row, matchWrap());
    }

    private void buildMergeControlCard() {
        controlCard.removeAllViews();
        LinearLayout row = horizontalRow();
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(10), dp(10), dp(10));
        TextView status = text(mergeItems.isEmpty()
                        ? "添加后可使用箭头调整合并顺序"
                        : "已添加 " + mergeItems.size() + " 个 PDF",
                13, mergeItems.size() >= 2 ? BLUE : TEXT_SECONDARY, false);
        row.addView(status, new LinearLayout.LayoutParams(0, wrap(), 1f));
        TextView clear = smallSecondaryButton("全部清除");
        clear.setEnabled(!mergeItems.isEmpty());
        clear.setAlpha(mergeItems.isEmpty() ? 0.5f : 1f);
        clear.setOnClickListener(v -> {
            mergeItems.clear();
            mergeAdapter.notifyDataSetChanged();
            refreshMergeUi();
        });
        row.addView(clear, new LinearLayout.LayoutParams(dp(88), dp(38)));
        controlCard.addView(row, matchWrap());
    }

    private void attachReorderHelper() {
        ItemTouchHelper.Callback callback = new ItemTouchHelper.SimpleCallback(
                ItemTouchHelper.UP | ItemTouchHelper.DOWN
                        | ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT,
                0
        ) {
            @Override
            public boolean isLongPressDragEnabled() {
                return true;
            }

            @Override
            public boolean isItemViewSwipeEnabled() {
                return false;
            }

            @Override
            public float getMoveThreshold(@NonNull RecyclerView.ViewHolder viewHolder) {
                // 网格中更早切换目标位置，减少必须把缩略图拖到目标中心才移动的僵硬感。
                return 0.28f;
            }

            @Override
            public int getBoundingBoxMargin() {
                // 扩大拖动项与相邻缩略图的命中范围，连续跨越多页时更稳定。
                return dp(12);
            }

            @Override
            public boolean onMove(
                    @NonNull RecyclerView recyclerView,
                    @NonNull RecyclerView.ViewHolder viewHolder,
                    @NonNull RecyclerView.ViewHolder target
            ) {
                int from = viewHolder.getBindingAdapterPosition();
                int to = target.getBindingAdapterPosition();
                if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION || from == to) {
                    return false;
                }

                // 使用 remove + add 表示一次真正的“插入移动”。拖动第 6 页经过第 4 页时，
                // 被跨过的页面顺次后移；不会因为多次 swap 和范围重绑定而丢失拖动项。
                PageItem moving = pageItems.remove(from);
                pageItems.add(to, moving);
                pageAdapter.notifyItemMoved(from, to);

                int changedStart = Math.min(from, to);
                int changedEnd = Math.max(from, to);
                if (dragChangedStart == RecyclerView.NO_POSITION) {
                    dragChangedStart = changedStart;
                    dragChangedEnd = changedEnd;
                } else {
                    dragChangedStart = Math.min(dragChangedStart, changedStart);
                    dragChangedEnd = Math.max(dragChangedEnd, changedEnd);
                }

                // 拖动过程中不能调用 notifyItemRangeChanged：它会重绑定当前 ViewHolder，
                // ItemTouchHelper 会把这次长按拖动提前结束。页码标签在松手后统一刷新。
                refreshActionButton();
                return true;
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
                // 不支持滑动删除。
            }

            @Override
            public void onSelectedChanged(RecyclerView.ViewHolder viewHolder, int actionState) {
                super.onSelectedChanged(viewHolder, actionState);
                if (viewHolder != null && actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                    dragChangedStart = RecyclerView.NO_POSITION;
                    dragChangedEnd = RecyclerView.NO_POSITION;
                    viewHolder.itemView.setScaleX(1.03f);
                    viewHolder.itemView.setScaleY(1.03f);
                    viewHolder.itemView.setElevation(dp(8));
                }
            }

            @Override
            public void clearView(
                    @NonNull RecyclerView recyclerView,
                    @NonNull RecyclerView.ViewHolder viewHolder
            ) {
                super.clearView(recyclerView, viewHolder);
                viewHolder.itemView.setScaleX(1f);
                viewHolder.itemView.setScaleY(1f);
                viewHolder.itemView.setElevation(0f);

                if (dragChangedStart != RecyclerView.NO_POSITION
                        && dragChangedEnd >= dragChangedStart) {
                    int start = Math.max(0, dragChangedStart);
                    int end = Math.min(pageItems.size() - 1, dragChangedEnd);
                    if (end >= start) {
                        pageAdapter.notifyItemRangeChanged(
                                start, end - start + 1, PAYLOAD_ORDER_LABEL
                        );
                    }
                }
                dragChangedStart = RecyclerView.NO_POSITION;
                dragChangedEnd = RecyclerView.NO_POSITION;
            }
        };
        itemTouchHelper = new ItemTouchHelper(callback);
        itemTouchHelper.attachToRecyclerView(recyclerView);
    }

    private void inspectPageSource(Uri uri) {
        closeThumbnailRepository();
        sourceUri = uri;
        sourceName = queryDisplayName(uri);
        sourcePageCount = 0;
        pageItems.clear();
        if (pageAdapter != null) pageAdapter.notifyDataSetChanged();
        buildPageSourceCard();
        buildPageControlCard();
        updateEmptyState();
        refreshActionButton();
        String expectedUri = uri.toString();

        workerExecutor.execute(() -> {
            PdfThumbnailRepository repository = null;
            try {
                repository = new PdfThumbnailRepository(this, uri);
                int pageCount = repository.getPageCount();
                if (pageCount <= 0) throw new IllegalStateException("PDF 没有可管理的页面");
                List<PageItem> loaded = new ArrayList<>(pageCount);
                for (int i = 0; i < pageCount; i++) loaded.add(new PageItem(i));
                PdfThumbnailRepository readyRepository = repository;
                runOnUiThread(() -> {
                    if (sourceUri == null || !expectedUri.equals(sourceUri.toString())) {
                        readyRepository.close();
                        return;
                    }
                    thumbnailRepository = readyRepository;
                    sourcePageCount = pageCount;
                    pageItems.clear();
                    pageItems.addAll(loaded);
                    pageAdapter.notifyDataSetChanged();
                    buildPageSourceCard();
                    buildPageControlCard();
                    updateEmptyState();
                    refreshActionButton();
                });
            } catch (Exception error) {
                if (repository != null) repository.close();
                runOnUiThread(() -> {
                    if (sourceUri != null && expectedUri.equals(sourceUri.toString())) {
                        sourceUri = null;
                        sourceName = "";
                        sourcePageCount = 0;
                        buildPageSourceCard();
                        buildPageControlCard();
                        updateEmptyState();
                        refreshActionButton();
                        showError("读取失败", error);
                    }
                });
            }
        });
    }

    private void addMergeSources(List<Uri> uris) {
        Set<String> existing = new HashSet<>();
        for (MergeItem item : mergeItems) existing.add(item.uri.toString());
        List<MergeItem> added = new ArrayList<>();
        for (Uri uri : uris) {
            if (uri == null || existing.contains(uri.toString())) continue;
            persistReadPermission(uri);
            MergeItem item = new MergeItem(uri, queryDisplayName(uri));
            mergeItems.add(item);
            added.add(item);
            existing.add(uri.toString());
        }
        if (added.isEmpty()) {
            Toast.makeText(this, "没有添加新的 PDF", Toast.LENGTH_SHORT).show();
            return;
        }
        mergeAdapter.notifyDataSetChanged();
        refreshMergeUi();
        for (MergeItem item : added) inspectMergeItem(item);
    }

    private void inspectMergeItem(MergeItem item) {
        workerExecutor.execute(() -> {
            try {
                int pages = PdfThumbnailRepository.countPages(this, item.uri);
                item.pageCount = pages;
                item.readError = false;
            } catch (Exception error) {
                item.pageCount = 0;
                item.readError = true;
            }
            runOnUiThread(() -> {
                if (mergeItems.contains(item)) {
                    mergeAdapter.notifyItemChanged(mergeItems.indexOf(item));
                    refreshMergeUi();
                }
            });
        });
    }

    private void refreshMergeUi() {
        buildMergeSourceCard();
        buildMergeControlCard();
        updateEmptyState();
        refreshActionButton();
    }

    private void resetPageOrder() {
        if (pageItems.isEmpty()) return;
        pageItems.sort((left, right) -> Integer.compare(left.originalIndex, right.originalIndex));
        pageAdapter.notifyDataSetChanged();
        refreshActionButton();
    }

    private void setAllPagesSelected(boolean selected) {
        for (PageItem item : pageItems) item.selected = selected;
        pageAdapter.notifyDataSetChanged();
        buildPageControlCard();
        refreshActionButton();
    }

    private int selectedPageCount() {
        int count = 0;
        for (PageItem item : pageItems) if (item.selected) count++;
        return count;
    }

    private boolean isOrderChanged() {
        for (int i = 0; i < pageItems.size(); i++) {
            if (pageItems.get(i).originalIndex != i) return true;
        }
        return false;
    }

    private void chooseOutput() {
        if (!isOperationReady()) return;
        outputPicker.launch(defaultOutputName());
    }

    private boolean isOperationReady() {
        if (MODE_MERGE.equals(mode)) {
            if (mergeItems.size() < 2) {
                Toast.makeText(this, "请至少添加两个 PDF", Toast.LENGTH_SHORT).show();
                return false;
            }
            for (MergeItem item : mergeItems) {
                if (item.readError) {
                    Toast.makeText(this, "列表中存在无法读取的 PDF", Toast.LENGTH_SHORT).show();
                    return false;
                }
            }
            return true;
        }
        if (sourceUri == null || sourcePageCount <= 0) {
            Toast.makeText(this, "请先选择 PDF", Toast.LENGTH_SHORT).show();
            return false;
        }
        int selected = selectedPageCount();
        if (MODE_DELETE.equals(mode)) {
            if (selected == 0) {
                Toast.makeText(this, "请先选择要删除的页面", Toast.LENGTH_SHORT).show();
                return false;
            }
            if (selected >= sourcePageCount) {
                Toast.makeText(this, "PDF 至少需要保留一页", Toast.LENGTH_SHORT).show();
                return false;
            }
        } else if (MODE_SAVE_SELECTED.equals(mode) && selected == 0) {
            Toast.makeText(this, "请先选择要另存的页面", Toast.LENGTH_SHORT).show();
            return false;
        } else if (MODE_REORDER.equals(mode) && !isOrderChanged()) {
            new AlertDialog.Builder(this)
                    .setTitle("页面顺序未改变")
                    .setMessage("当前页面顺序与原 PDF 相同。仍然可以生成完整副本，是否继续？")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("继续", (dialog, which) -> outputPicker.launch(defaultOutputName()))
                    .show();
            return false;
        }
        return true;
    }

    private void runCurrentOperation(Uri outputUri) {
        if (MODE_MERGE.equals(mode)) {
            runMergeInSeparateProcess(outputUri);
            return;
        }
        startProgress(operationProgressTitle());
        workerExecutor.execute(() -> {
            File temp = null;
            try {
                temp = File.createTempFile("mypdf-pages-", ".pdf", getCacheDir());
                PdfPageManagementService.ResultSummary summary;
                if (MODE_DELETE.equals(mode)) {
                    summary = PdfPageManagementService.deletePages(
                            this, sourceUri, temp, selectedOriginalPages(), cancelled, this::postProgress);
                } else if (MODE_SAVE_SELECTED.equals(mode)) {
                    summary = PdfPageManagementService.saveSelectedPages(
                            this, sourceUri, temp, selectedOriginalPages(), cancelled, this::postProgress);
                } else {
                    summary = PdfPageManagementService.reorderPages(
                            this, sourceUri, temp, currentOrder(), cancelled, this::postProgress);
                }
                postProgress(1, 1, "正在写入保存位置");
                copyTempToUri(temp, outputUri);
                String outputName = queryDisplayName(outputUri);
                addCreatedPdfToHome(outputUri, outputName);
                PdfPageManagementService.ResultSummary finalSummary = summary;
                runOnUiThread(() -> {
                    dismissProgress();
                    showSuccess(outputUri, outputName, finalSummary);
                });
            } catch (Exception error) {
                deleteOutput(outputUri);
                runOnUiThread(() -> {
                    dismissProgress();
                    if (isCancelledError(error)) {
                        Toast.makeText(this, "操作已取消", Toast.LENGTH_SHORT).show();
                    } else {
                        showError("处理失败", error);
                    }
                });
            } finally {
                if (temp != null && temp.exists()) temp.delete();
            }
        });
    }

    private void runMergeInSeparateProcess(Uri outputUri) {
        startProgress(operationProgressTitle());
        activeMergeTaskId = UUID.randomUUID().toString();
        activeMergeOutputUri = outputUri;
        ArrayList<String> sourceStrings = new ArrayList<>(mergeItems.size());
        for (MergeItem item : mergeItems) sourceStrings.add(item.uri.toString());
        postProgress(0, 1, "正在启动独立合并进程");
        try {
            PdfMergeWorkerService.start(
                    this,
                    activeMergeTaskId,
                    sourceStrings,
                    outputUri,
                    mergeResultReceiver
            );
        } catch (Exception error) {
            activeMergeTaskId = null;
            activeMergeOutputUri = null;
            dismissProgress();
            deleteOutput(outputUri);
            showError("无法启动合并任务", error);
        }
    }

    private ResultReceiver createMergeResultReceiver() {
        return new ResultReceiver(new Handler(Looper.getMainLooper())) {
            @Override
            protected void onReceiveResult(int resultCode, Bundle resultData) {
                String taskId = resultData == null
                        ? null
                        : resultData.getString(PdfMergeWorkerService.EXTRA_TASK_ID);
                if (activeMergeTaskId == null || !activeMergeTaskId.equals(taskId)) return;

                if (resultCode == PdfMergeWorkerService.RESULT_PROGRESS) {
                    postProgress(
                            resultData.getInt(PdfMergeWorkerService.EXTRA_CURRENT, 0),
                            resultData.getInt(PdfMergeWorkerService.EXTRA_TOTAL, 0),
                            resultData.getString(PdfMergeWorkerService.EXTRA_MESSAGE)
                    );
                    return;
                }

                Uri outputUri = activeMergeOutputUri;
                activeMergeTaskId = null;
                activeMergeOutputUri = null;
                dismissProgress();

                if (resultCode == PdfMergeWorkerService.RESULT_SUCCESS) {
                    String outputName = resultData.getString(
                            PdfMergeWorkerService.EXTRA_OUTPUT_NAME,
                            outputUri == null ? "合并文档.pdf" : queryDisplayName(outputUri)
                    );
                    PdfPageManagementService.ResultSummary summary =
                            new PdfPageManagementService.ResultSummary();
                    summary.sourceFiles = resultData.getInt(
                            PdfMergeWorkerService.EXTRA_SOURCE_FILES,
                            mergeItems.size()
                    );
                    summary.sourcePages = resultData.getInt(
                            PdfMergeWorkerService.EXTRA_SOURCE_PAGES,
                            0
                    );
                    summary.outputPages = resultData.getInt(
                            PdfMergeWorkerService.EXTRA_OUTPUT_PAGES,
                            summary.sourcePages
                    );
                    if (outputUri != null) {
                        addCreatedPdfToHome(outputUri, outputName);
                        showSuccess(outputUri, outputName, summary);
                    }
                } else if (resultCode == PdfMergeWorkerService.RESULT_CANCELLED) {
                    Toast.makeText(
                            ManagePdfPagesActivity.this,
                            "合并已取消",
                            Toast.LENGTH_SHORT
                    ).show();
                } else {
                    String message = resultData == null
                            ? "独立合并进程异常结束"
                            : resultData.getString(
                                    PdfMergeWorkerService.EXTRA_MESSAGE,
                                    "独立合并进程异常结束"
                            );
                    showErrorMessage("处理失败", message);
                }
            }
        };
    }

    private List<Integer> currentOrder() {
        List<Integer> order = new ArrayList<>(pageItems.size());
        for (PageItem item : pageItems) order.add(item.originalIndex);
        return order;
    }

    private List<Integer> selectedOriginalPages() {
        List<Integer> selected = new ArrayList<>();
        for (PageItem item : pageItems) if (item.selected) selected.add(item.originalIndex);
        return selected;
    }

    private List<Uri> mergeUris() {
        List<Uri> uris = new ArrayList<>(mergeItems.size());
        for (MergeItem item : mergeItems) uris.add(item.uri);
        return uris;
    }

    private void showSuccess(
            Uri outputUri,
            String outputName,
            PdfPageManagementService.ResultSummary summary
    ) {
        String message;
        if (MODE_MERGE.equals(mode)) {
            message = "已合并 " + summary.sourceFiles + " 个 PDF，共 "
                    + summary.outputPages + " 页。";
        } else if (MODE_DELETE.equals(mode)) {
            message = "已删除 " + (summary.sourcePages - summary.outputPages)
                    + " 页，生成的 PDF 共 " + summary.outputPages + " 页。";
        } else if (MODE_SAVE_SELECTED.equals(mode)) {
            message = "已将所选 " + summary.outputPages + " 页保存为新的 PDF。";
        } else {
            message = "页面排序已完成，生成的 PDF 共 " + summary.outputPages + " 页。";
        }
        new AlertDialog.Builder(this)
                .setTitle("PDF 已生成")
                .setMessage(message + "\n\n文件已加入首页列表，原 PDF 未被修改。")
                .setNegativeButton("完成", null)
                .setPositiveButton("打开", (dialog, which) -> openPdf(outputUri, outputName))
                .show();
    }

    private void openPdf(Uri uri, String name) {
        Intent intent = new Intent(this, PdfViewerActivity.class);
        intent.putExtra("pdf_uri", uri.toString());
        intent.putExtra("pdf_path", MainActivity.cleanPath(uri.getPath()));
        intent.putExtra("pdf_name", name == null || name.trim().isEmpty() ? "页面管理结果.pdf" : name);
        startActivity(intent);
    }

    private void startProgress(String title) {
        cancelled.set(false);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(24), dp(8), dp(24), dp(4));
        progressText = text("正在准备……", 14, TEXT_PRIMARY, false);
        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(1000);
        LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(match(), dp(8));
        barParams.setMargins(0, dp(16), 0, dp(4));
        content.addView(progressText, matchWrap());
        content.addView(progressBar, barParams);
        progressDialog = new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(content)
                .setNegativeButton("取消", null)
                .setCancelable(false)
                .create();
        progressDialog.show();
        progressDialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v -> {
            cancelled.set(true);
            if (activeMergeTaskId != null) {
                PdfMergeWorkerService.cancel(this, activeMergeTaskId);
            }
            progressText.setText("正在取消，请稍候……");
            v.setEnabled(false);
        });
    }

    private void postProgress(int current, int total, String message) {
        runOnUiThread(() -> {
            if (progressDialog == null || !progressDialog.isShowing()) return;
            progressText.setText(message == null ? "正在处理……" : message);
            int progress = total <= 0 ? 0 : Math.round(current * 1000f / total);
            progressBar.setProgress(Math.max(0, Math.min(1000, progress)));
        });
    }

    private void dismissProgress() {
        if (progressDialog != null) progressDialog.dismiss();
        progressDialog = null;
    }

    private void updateEmptyState() {
        boolean empty = MODE_MERGE.equals(mode) ? mergeItems.isEmpty() : pageItems.isEmpty();
        emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
        recyclerView.setVisibility(empty ? View.GONE : View.VISIBLE);
        if (empty) {
            emptyView.setText(MODE_MERGE.equals(mode)
                    ? "尚未添加 PDF\n点击上方“添加 PDF”开始"
                    : sourceUri == null
                            ? "尚未选择 PDF\n选择文件后将在此显示页面缩略图"
                            : "正在读取页面缩略图……");
        }
    }

    private void refreshActionButton() {
        boolean enabled;
        if (MODE_MERGE.equals(mode)) {
            enabled = mergeItems.size() >= 2 && !containsMergeReadError();
            actionButton.setText("选择保存位置并合并");
        } else if (MODE_DELETE.equals(mode)) {
            int selected = selectedPageCount();
            enabled = sourcePageCount > 0 && selected > 0 && selected < sourcePageCount;
            actionButton.setText("选择保存位置并删除所选页面");
        } else if (MODE_SAVE_SELECTED.equals(mode)) {
            enabled = sourcePageCount > 0 && selectedPageCount() > 0;
            actionButton.setText("选择保存位置并另存所选页面");
        } else {
            enabled = sourcePageCount > 0;
            actionButton.setText("选择保存位置并生成");
        }
        actionButton.setEnabled(enabled);
        actionButton.setAlpha(enabled ? 1f : 0.62f);
    }

    private boolean containsMergeReadError() {
        for (MergeItem item : mergeItems) if (item.readError) return true;
        return false;
    }

    private String defaultOutputName() {
        String base;
        if (MODE_MERGE.equals(mode)) {
            base = "合并文档-" + new SimpleDateFormat("yyyyMMdd-HHmm", Locale.getDefault())
                    .format(new Date());
        } else {
            base = sourceName == null ? "文档" : sourceName.trim();
            if (base.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
                base = base.substring(0, base.length() - 4);
            }
            if (MODE_DELETE.equals(mode)) base += "-删除页面版";
            else if (MODE_SAVE_SELECTED.equals(mode)) base += "-所选页面";
            else base += "-重排版";
        }
        return base + ".pdf";
    }

    private String operationProgressTitle() {
        if (MODE_DELETE.equals(mode)) return "正在删除页面";
        if (MODE_SAVE_SELECTED.equals(mode)) return "正在另存所选页面";
        if (MODE_MERGE.equals(mode)) return "正在合并 PDF";
        return "正在调整页面顺序";
    }

    private void copyTempToUri(File temp, Uri outputUri) throws Exception {
        try (InputStream input = new FileInputStream(temp);
             OutputStream output = getContentResolver().openOutputStream(outputUri, "w")) {
            if (output == null) throw new IllegalStateException("无法写入所选保存位置");
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (cancelled.get()) throw new IllegalStateException("操作已取消");
                output.write(buffer, 0, read);
            }
            output.flush();
        }
    }

    private void addCreatedPdfToHome(Uri uri, String outputName) {
        DocumentFile file = DocumentFile.fromSingleUri(this, uri);
        String name = outputName;
        if (file != null && file.getName() != null) name = file.getName();
        if (name == null || name.trim().isEmpty()) name = "页面管理结果.pdf";
        long modified = file == null ? System.currentTimeMillis() : file.lastModified();
        String time = new SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault())
                .format(new Date(modified > 0 ? modified : System.currentTimeMillis()));
        String path = MainActivity.cleanPath(uri.getPath());
        new PdfDbHelper(this).insertOrUpdateHomeItem(new PdfItem(uri, name, path, time, false));
    }

    private void deleteOutput(Uri uri) {
        try {
            DocumentFile file = DocumentFile.fromSingleUri(this, uri);
            if (file != null) file.delete();
        } catch (Throwable ignore) {
        }
    }

    private void persistReadPermission(Uri uri) {
        try {
            getContentResolver().takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
            );
        } catch (Exception ignore) {
        }
    }

    private void persistWritePermission(Uri uri) {
        try {
            getContentResolver().takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            );
        } catch (Exception ignore) {
        }
    }

    private void closeThumbnailRepository() {
        PdfThumbnailRepository repository = thumbnailRepository;
        thumbnailRepository = null;
        if (repository != null) repository.close();
    }

    private void showErrorMessage(String title, String message) {
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message == null || message.trim().isEmpty() ? "未知错误" : message)
                .setPositiveButton("知道了", null)
                .show();
    }

    private void showError(String title, Throwable error) {
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(readableError(error))
                .setPositiveButton("知道了", null)
                .show();
    }

    private String readableError(Throwable error) {
        Throwable current = error;
        while (current != null && current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        if (current == null) return "未知错误";
        String message = current.getMessage();
        return message == null || message.trim().isEmpty()
                ? current.getClass().getSimpleName()
                : message;
    }

    private boolean isCancelledError(Throwable error) {
        return cancelled.get() || readableError(error).contains("取消");
    }

    private String queryDisplayName(Uri uri) {
        if (uri == null) return "未命名.pdf";
        try (Cursor cursor = getContentResolver().query(
                uri,
                new String[]{OpenableColumns.DISPLAY_NAME},
                null,
                null,
                null
        )) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    String value = cursor.getString(index);
                    if (value != null && !value.trim().isEmpty()) return value;
                }
            }
        } catch (Exception ignore) {
        }
        return "未命名.pdf";
    }

    private LinearLayout horizontalRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        return row;
    }

    private TextView smallPrimaryButton(String label) {
        TextView view = text(label, 14, Color.WHITE, true);
        view.setGravity(Gravity.CENTER);
        view.setBackgroundResource(R.drawable.bg_creation_primary);
        view.setClickable(true);
        view.setFocusable(true);
        return view;
    }

    private TextView smallSecondaryButton(String label) {
        TextView view = text(label, 13, BLUE, false);
        view.setGravity(Gravity.CENTER);
        view.setBackgroundResource(R.drawable.bg_creation_secondary);
        view.setClickable(true);
        view.setFocusable(true);
        return view;
    }

    private TextView text(String value, int sp, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextColor(color);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (bold) view.setTypeface(view.getTypeface(), android.graphics.Typeface.BOLD);
        return view;
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                value,
                getResources().getDisplayMetrics()
        ));
    }

    private int match() {
        return ViewGroup.LayoutParams.MATCH_PARENT;
    }

    private int wrap() {
        return ViewGroup.LayoutParams.WRAP_CONTENT;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(match(), wrap());
    }

    private static final class PageItem {
        final int originalIndex;
        boolean selected;

        PageItem(int originalIndex) {
            this.originalIndex = originalIndex;
        }
    }

    private static final class MergeItem {
        final Uri uri;
        final String name;
        int pageCount = -1;
        boolean readError;

        MergeItem(Uri uri, String name) {
            this.uri = uri;
            this.name = name == null || name.trim().isEmpty() ? "未命名.pdf" : name;
        }
    }

    private final class PageAdapter extends RecyclerView.Adapter<PageAdapter.Holder> {

        PageAdapter() {
            setHasStableIds(true);
        }

        @Override
        public long getItemId(int position) {
            return pageItems.get(position).originalIndex;
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = getLayoutInflater().inflate(R.layout.item_manage_page, parent, false);
            return new Holder(view);
        }

        @Override
        public void onBindViewHolder(
                @NonNull Holder holder,
                int position,
                @NonNull List<Object> payloads
        ) {
            if (!payloads.isEmpty() && payloads.contains(PAYLOAD_ORDER_LABEL)) {
                bindPageLabels(holder, position, pageItems.get(position));
                return;
            }
            super.onBindViewHolder(holder, position, payloads);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            PageItem item = pageItems.get(position);
            holder.thumbnail.setImageDrawable(null);
            holder.thumbnail.setBackgroundColor(0xFFEEF1F4);
            holder.progress.setVisibility(View.VISIBLE);
            holder.thumbnail.setTag(item.originalIndex);
            holder.badge.setVisibility(item.selected ? View.VISIBLE : View.GONE);
            holder.thumbnail.setAlpha(item.selected ? 0.62f : 1f);
            holder.itemView.setBackgroundResource(item.selected
                    ? R.drawable.bg_manage_page_selected
                    : R.drawable.bg_manage_page_normal);

            bindPageLabels(holder, position, item);

            PdfThumbnailRepository repository = thumbnailRepository;
            if (repository != null) {
                repository.request(item.originalIndex, dp(164), dp(214),
                        new PdfThumbnailRepository.Callback() {
                            @Override
                            public void onLoaded(int pageIndex, Bitmap bitmap) {
                                Object tag = holder.thumbnail.getTag();
                                if (tag instanceof Integer && ((Integer) tag) == pageIndex) {
                                    holder.progress.setVisibility(View.GONE);
                                    holder.thumbnail.setImageBitmap(bitmap);
                                }
                            }

                            @Override
                            public void onFailed(int pageIndex) {
                                Object tag = holder.thumbnail.getTag();
                                if (tag instanceof Integer && ((Integer) tag) == pageIndex) {
                                    holder.progress.setVisibility(View.GONE);
                                    holder.hint.setText("缩略图加载失败");
                                }
                            }
                        });
            }

            holder.itemView.setOnClickListener(v -> {
                if (MODE_REORDER.equals(mode)) return;
                int adapterPosition = holder.getBindingAdapterPosition();
                if (adapterPosition == RecyclerView.NO_POSITION) return;
                PageItem clicked = pageItems.get(adapterPosition);
                clicked.selected = !clicked.selected;
                notifyItemChanged(adapterPosition);
                buildPageControlCard();
                refreshActionButton();
            });
        }

        private void bindPageLabels(Holder holder, int position, PageItem item) {
            if (MODE_REORDER.equals(mode)) {
                holder.label.setText("输出第 " + (position + 1) + " 页");
                holder.hint.setText("原第 " + (item.originalIndex + 1) + " 页 · 长按拖动");
            } else {
                holder.label.setText("第 " + (item.originalIndex + 1) + " 页");
                holder.hint.setText(item.selected ? "已选择" : "点击选择");
            }
        }

        @Override
        public int getItemCount() {
            return pageItems.size();
        }

        final class Holder extends RecyclerView.ViewHolder {
            final ImageView thumbnail;
            final ProgressBar progress;
            final TextView badge;
            final TextView label;
            final TextView hint;

            Holder(@NonNull View itemView) {
                super(itemView);
                thumbnail = itemView.findViewById(R.id.manage_page_thumbnail);
                progress = itemView.findViewById(R.id.manage_page_progress);
                badge = itemView.findViewById(R.id.manage_page_badge);
                label = itemView.findViewById(R.id.manage_page_label);
                hint = itemView.findViewById(R.id.manage_page_hint);
            }
        }
    }

    private final class MergeAdapter extends RecyclerView.Adapter<MergeAdapter.Holder> {

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = getLayoutInflater().inflate(R.layout.item_merge_pdf, parent, false);
            return new Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            MergeItem item = mergeItems.get(position);
            holder.order.setText(String.valueOf(position + 1));
            holder.name.setText(item.name);
            holder.name.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            holder.name.setMaxLines(2);
            if (item.readError) {
                holder.detail.setText("无法读取，请移除或重新选择");
                holder.detail.setTextColor(0xFFD04444);
            } else if (item.pageCount < 0) {
                holder.detail.setText("正在读取页数……");
                holder.detail.setTextColor(TEXT_SECONDARY);
            } else {
                holder.detail.setText(item.pageCount + " 页");
                holder.detail.setTextColor(TEXT_SECONDARY);
            }
            holder.up.setEnabled(position > 0);
            holder.up.setAlpha(position > 0 ? 1f : 0.35f);
            holder.down.setEnabled(position < mergeItems.size() - 1);
            holder.down.setAlpha(position < mergeItems.size() - 1 ? 1f : 0.35f);

            holder.up.setOnClickListener(v -> moveMergeItem(holder.getBindingAdapterPosition(), -1));
            holder.down.setOnClickListener(v -> moveMergeItem(holder.getBindingAdapterPosition(), 1));
            holder.remove.setOnClickListener(v -> removeMergeItem(holder.getBindingAdapterPosition()));
        }

        @Override
        public int getItemCount() {
            return mergeItems.size();
        }

        final class Holder extends RecyclerView.ViewHolder {
            final TextView order;
            final TextView name;
            final TextView detail;
            final TextView up;
            final TextView down;
            final TextView remove;

            Holder(@NonNull View itemView) {
                super(itemView);
                order = itemView.findViewById(R.id.merge_pdf_order);
                name = itemView.findViewById(R.id.merge_pdf_name);
                detail = itemView.findViewById(R.id.merge_pdf_detail);
                up = itemView.findViewById(R.id.merge_pdf_up);
                down = itemView.findViewById(R.id.merge_pdf_down);
                remove = itemView.findViewById(R.id.merge_pdf_remove);
            }
        }
    }

    private void moveMergeItem(int from, int delta) {
        if (from == RecyclerView.NO_POSITION) return;
        int to = from + delta;
        if (to < 0 || to >= mergeItems.size()) return;
        Collections.swap(mergeItems, from, to);
        mergeAdapter.notifyItemMoved(from, to);
        mergeAdapter.notifyItemRangeChanged(Math.min(from, to), 2);
        refreshMergeUi();
    }

    private void removeMergeItem(int position) {
        if (position == RecyclerView.NO_POSITION || position >= mergeItems.size()) return;
        mergeItems.remove(position);
        mergeAdapter.notifyItemRemoved(position);
        mergeAdapter.notifyItemRangeChanged(position, mergeItems.size() - position);
        refreshMergeUi();
    }
}
