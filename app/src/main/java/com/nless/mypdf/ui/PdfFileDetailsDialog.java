package com.nless.mypdf.ui;


import com.nless.mypdf.core.PdfSecurityContext;
import com.nless.mypdf.data.PdfDbHelper;
import com.nless.mypdf.data.PdfItem;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Typeface;
import android.net.Uri;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.documentfile.provider.DocumentFile;

import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.PDDocumentCatalog;
import com.tom_roush.pdfbox.pdmodel.PDDocumentInformation;
import com.tom_roush.pdfbox.pdmodel.PDPage;
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle;
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;

/** 首页长按文件后显示的文件、权限和 PDF 元数据信息。 */
final class PdfFileDetailsDialog {

    private static final int COLOR_PRIMARY = 0xFF202124;
    private static final int COLOR_SECONDARY = 0xFF6F7378;
    private static final int COLOR_DIVIDER = 0xFFE1E5EA;
    private static final int COLOR_BLUE = 0xFF0288D1;
    private static final int COLOR_ALLOWED = 0xFF2E7D32;
    private static final int COLOR_DENIED = 0xFFC62828;

    private PdfFileDetailsDialog() {
    }

    static void show(
            MainActivity activity,
            PdfItem item,
            PdfDbHelper dbHelper,
            ExecutorService executor
    ) {
        if (activity == null || item == null || dbHelper == null || executor == null) return;
        AlertDialog progress = createProgressDialog(activity, "正在读取文件详情……");
        progress.show();
        executor.execute(() -> {
            BasicDetails basic = readBasicDetails(activity, item, dbHelper);
            if (item.isFolder || !isPdf(item)) {
                activity.runOnUiThread(() -> {
                    progress.dismiss();
                    showDetailsDialog(activity, basic, null, null);
                });
                return;
            }

            String initialPassword = "";
            PdfSecurityContext.Info cached = PdfSecurityContext.getCached(
                    activity,
                    item.uri,
                    item.path
            );
            if (cached != null) initialPassword = cached.password;
            try {
                PdfDetails pdf = readPdfDetails(activity, item, initialPassword, !TextUtils.isEmpty(initialPassword));
                activity.runOnUiThread(() -> {
                    progress.dismiss();
                    showDetailsDialog(activity, basic, pdf, null);
                });
            } catch (Throwable error) {
                if (PdfSecurityContext.isPasswordError(error)) {
                    activity.runOnUiThread(() -> {
                        progress.dismiss();
                        promptPassword(activity, item, basic, executor);
                    });
                } else {
                    String message = PdfSecurityContext.readableError(error);
                    activity.runOnUiThread(() -> {
                        progress.dismiss();
                        showDetailsDialog(activity, basic, null, message);
                    });
                }
            }
        });
    }

    private static void promptPassword(
            MainActivity activity,
            PdfItem item,
            BasicDetails basic,
            ExecutorService executor
    ) {
        EditText input = new EditText(activity);
        input.setSingleLine(true);
        input.setHint("请输入当前打开密码");
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setPadding(dp(activity, 12), dp(activity, 10), dp(activity, 12), dp(activity, 10));

        LinearLayout container = new LinearLayout(activity);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(dp(activity, 24), dp(activity, 6), dp(activity, 24), 0);
        container.addView(input, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("PDF 已设置打开密码")
                .setMessage("输入当前打开密码后，可查看页数、权限和文档元数据。密码只用于本次读取。")
                .setView(container)
                .setNegativeButton("仅查看文件信息", (d, which) ->
                        showDetailsDialog(activity, basic, null, "需要正确密码才能读取 PDF 详细信息"))
                .setPositiveButton("读取", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    String password = input.getText().toString();
                    if (TextUtils.isEmpty(password)) {
                        input.setError("请输入当前打开密码");
                        return;
                    }
                    dialog.dismiss();
                    loadWithPassword(activity, item, basic, password, executor);
                }));
        dialog.show();
    }

    private static void loadWithPassword(
            MainActivity activity,
            PdfItem item,
            BasicDetails basic,
            String password,
            ExecutorService executor
    ) {
        AlertDialog progress = createProgressDialog(activity, "正在读取权限和元数据……");
        progress.show();
        executor.execute(() -> {
            try {
                PdfDetails pdf = readPdfDetails(activity, item, password, true);
                activity.runOnUiThread(() -> {
                    progress.dismiss();
                    showDetailsDialog(activity, basic, pdf, null);
                });
            } catch (Throwable error) {
                activity.runOnUiThread(() -> {
                    progress.dismiss();
                    if (PdfSecurityContext.isPasswordError(error)) {
                        Toast.makeText(activity, "密码不正确", Toast.LENGTH_SHORT).show();
                        promptPassword(activity, item, basic, executor);
                    } else {
                        showDetailsDialog(
                                activity,
                                basic,
                                null,
                                PdfSecurityContext.readableError(error)
                        );
                    }
                });
            }
        });
    }

    private static BasicDetails readBasicDetails(
            Context context,
            PdfItem item,
            PdfDbHelper dbHelper
    ) {
        BasicDetails result = new BasicDetails();
        result.name = safe(item.name);
        result.type = item.isFolder ? "文件夹" : (isPdf(item) ? "PDF 文档" : "文件");
        result.location = !TextUtils.isEmpty(item.path)
                ? item.path
                : (item.uri == null ? "未知" : item.uri.toString());
        result.favorite = dbHelper.isFavorite(item.path, item.name);
        result.addedTime = item.time;
        result.appModified = false;
        result.appModifiedTime = "无";
        result.sourceModifiedTime = "未知";
        result.size = item.isFolder ? "文件夹" : "未知";

        DocumentFile file = item.isFolder
                ? DocumentFile.fromTreeUri(context, item.uri)
                : DocumentFile.fromSingleUri(context, item.uri);
        if (file != null && file.exists()) {
            if (!item.isFolder) result.size = formatFileSize(file.length());
            if (file.lastModified() > 0L) {
                result.sourceModifiedTime = formatDate(new Date(file.lastModified()));
            }
        }

        Cursor cursor = null;
        try {
            cursor = dbHelper.getReadableDatabase().rawQuery(
                    "SELECT createTime, lastModified FROM items WHERE uri=?",
                    new String[]{item.uri.toString()}
            );
            if (cursor.moveToFirst()) {
                long createTime = cursor.getLong(cursor.getColumnIndexOrThrow("createTime"));
                long modifiedTime = cursor.getLong(cursor.getColumnIndexOrThrow("lastModified"));
                if (createTime > 0L) result.addedTime = formatDate(new Date(createTime));
                if (modifiedTime > 0L) {
                    result.appModified = true;
                    result.appModifiedTime = formatDate(new Date(modifiedTime));
                }
            }
        } catch (Throwable ignore) {
        } finally {
            if (cursor != null) cursor.close();
        }
        return result;
    }

    private static PdfDetails readPdfDetails(
            Context context,
            PdfItem item,
            String password,
            boolean openPasswordRequired
    ) throws Exception {
        try (PDDocument document = PdfSecurityContext.loadDocument(
                context,
                item.uri,
                item.path,
                password,
                "pdfbox-file-details"
        )) {
            PdfDetails result = new PdfDetails();
            result.pageCount = document.getNumberOfPages();
            result.version = String.format(Locale.US, "%.1f", document.getVersion());
            result.encrypted = document.isEncrypted();
            result.openPasswordRequired = result.encrypted && openPasswordRequired;

            AccessPermission permission;
            if (result.encrypted && document.getEncryption() != null) {
                permission = new AccessPermission(document.getEncryption().getPermissions());
            } else {
                permission = AccessPermission.getOwnerAccessPermission();
            }
            result.canPrint = permission.canPrint();
            result.canPrintFaithful = permission.canPrintFaithful();
            result.canCopy = permission.canExtractContent();
            result.canAccessibility = permission.canExtractForAccessibility();
            result.canModify = permission.canModify();
            result.canAnnotate = permission.canModifyAnnotations();
            result.canFillForms = permission.canFillInForm();
            result.canAssemble = permission.canAssembleDocument();
            result.hasRestrictions = result.encrypted && !(result.canPrint
                    && result.canPrintFaithful
                    && result.canCopy
                    && result.canAccessibility
                    && result.canModify
                    && result.canAnnotate
                    && result.canFillForms
                    && result.canAssemble);

            if (result.encrypted && result.openPasswordRequired && result.hasRestrictions) {
                result.protectionStatus = "打开密码 + 权限限制";
            } else if (result.encrypted && result.openPasswordRequired) {
                result.protectionStatus = "打开密码保护";
            } else if (result.encrypted && result.hasRestrictions) {
                result.protectionStatus = "权限限制（打开时无需密码）";
            } else if (result.encrypted) {
                result.protectionStatus = "已加密（未发现权限限制）";
            } else {
                result.protectionStatus = "未保护";
            }

            if (result.pageCount > 0) {
                PDPage firstPage = document.getPage(0);
                PDRectangle box = firstPage.getCropBox();
                if (box == null) box = firstPage.getMediaBox();
                if (box != null) {
                    float width = box.getWidth();
                    float height = box.getHeight();
                    int rotation = Math.abs(firstPage.getRotation()) % 360;
                    if (rotation == 90 || rotation == 270) {
                        float temp = width;
                        width = height;
                        height = temp;
                    }
                    result.firstPageSize = formatPageSize(width, height);
                }
            }
            if (TextUtils.isEmpty(result.firstPageSize)) result.firstPageSize = "未知";

            PDDocumentInformation info = document.getDocumentInformation();
            if (info != null) {
                result.title = emptyAsNone(info.getTitle());
                result.author = emptyAsNone(info.getAuthor());
                result.subject = emptyAsNone(info.getSubject());
                result.keywords = emptyAsNone(info.getKeywords());
                result.creator = emptyAsNone(info.getCreator());
                result.producer = emptyAsNone(info.getProducer());
                result.createdTime = formatCalendar(info.getCreationDate());
                result.modifiedTime = formatCalendar(info.getModificationDate());
            }
            PDDocumentCatalog catalog = document.getDocumentCatalog();
            result.hasXmp = catalog != null && catalog.getMetadata() != null;
            return result;
        }
    }

    private static void showDetailsDialog(
            Context context,
            BasicDetails basic,
            PdfDetails pdf,
            String pdfError
    ) {
        ScrollView scrollView = new ScrollView(context);
        scrollView.setFillViewport(false);
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(context, 22), dp(context, 6), dp(context, 22), dp(context, 18));
        scrollView.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        addSection(content, context, "文件信息");
        addRow(content, context, "名称", basic.name, COLOR_PRIMARY);
        addRow(content, context, "类型", basic.type, COLOR_PRIMARY);
        addRow(content, context, "存储位置", basic.location, COLOR_PRIMARY);
        addRow(content, context, "文件大小", basic.size, COLOR_PRIMARY);
        addRow(content, context, "记录时间", emptyAsUnknown(basic.addedTime), COLOR_PRIMARY);
        addRow(content, context, "文件修改时间", basic.sourceModifiedTime, COLOR_PRIMARY);
        addRow(content, context, "应用内修改", basic.appModified
                ? "是 · " + basic.appModifiedTime
                : "否", COLOR_PRIMARY);
        addRow(content, context, "收藏", basic.favorite ? "是" : "否", COLOR_PRIMARY);

        if (pdf != null) {
            addSection(content, context, "PDF 信息");
            addRow(content, context, "页数", pdf.pageCount + " 页", COLOR_PRIMARY);
            addRow(content, context, "PDF 版本", "PDF " + pdf.version, COLOR_PRIMARY);
            addRow(content, context, "首页尺寸", pdf.firstPageSize, COLOR_PRIMARY);
            addRow(content, context, "保护状态", pdf.protectionStatus,
                    pdf.encrypted ? COLOR_BLUE : COLOR_PRIMARY);
            addRow(content, context, "打开密码", pdf.openPasswordRequired
                    ? "需要"
                    : "不需要", pdf.openPasswordRequired ? COLOR_DENIED : COLOR_ALLOWED);

            addSection(content, context, "权限");
            addPermissionRow(content, context, "打印", printStatus(pdf));
            addPermissionRow(content, context, "复制与文本提取", allowed(pdf.canCopy));
            addPermissionRow(content, context, "辅助功能文本提取", allowed(pdf.canAccessibility));
            addPermissionRow(content, context, "修改内容", allowed(pdf.canModify));
            addPermissionRow(content, context, "添加或修改批注", allowed(pdf.canAnnotate));
            addPermissionRow(content, context, "填写表单", allowed(pdf.canFillForms));
            addPermissionRow(content, context, "页面组合", allowed(pdf.canAssemble));
            if (pdf.hasRestrictions) {
                addNote(content, context, "PDF 权限依赖阅读器主动遵守，并不等同于 DRM。", 0xFFFFF8E1, 0xFF8D6E00);
            }

            addSection(content, context, "文档元数据");
            addRow(content, context, "标题", emptyAsNone(pdf.title), COLOR_PRIMARY);
            addRow(content, context, "作者", emptyAsNone(pdf.author), COLOR_PRIMARY);
            addRow(content, context, "主题", emptyAsNone(pdf.subject), COLOR_PRIMARY);
            addRow(content, context, "关键词", emptyAsNone(pdf.keywords), COLOR_PRIMARY);
            addRow(content, context, "创建程序", emptyAsNone(pdf.creator), COLOR_PRIMARY);
            addRow(content, context, "PDF 生成程序", emptyAsNone(pdf.producer), COLOR_PRIMARY);
            addRow(content, context, "PDF 创建时间", emptyAsNone(pdf.createdTime), COLOR_PRIMARY);
            addRow(content, context, "PDF 修改时间", emptyAsNone(pdf.modifiedTime), COLOR_PRIMARY);
            addRow(content, context, "XMP 元数据", pdf.hasXmp ? "存在" : "无", COLOR_PRIMARY);
        } else if (!TextUtils.isEmpty(pdfError)) {
            addSection(content, context, "PDF 信息");
            addNote(content, context, pdfError, 0xFFFFF3E0, COLOR_DENIED);
        }

        new AlertDialog.Builder(context)
                .setTitle("文件详情")
                .setView(scrollView)
                .setPositiveButton("关闭", null)
                .show();
    }

    private static void addSection(LinearLayout parent, Context context, String title) {
        if (parent.getChildCount() > 0) addDivider(parent, context);
        TextView view = new TextView(context);
        view.setText(title);
        view.setTextColor(COLOR_BLUE);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        view.setPadding(0, dp(context, 14), 0, dp(context, 8));
        parent.addView(view, matchWrap());
    }

    private static void addRow(
            LinearLayout parent,
            Context context,
            String label,
            String value,
            int valueColor
    ) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(context, 7), 0, dp(context, 7));

        TextView labelView = new TextView(context);
        labelView.setText(label);
        labelView.setTextColor(COLOR_SECONDARY);
        labelView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);

        TextView valueView = new TextView(context);
        valueView.setText(emptyAsUnknown(value));
        valueView.setTextColor(valueColor);
        valueView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        valueView.setTextIsSelectable(true);
        valueView.setPadding(0, dp(context, 3), 0, 0);

        row.addView(labelView, matchWrap());
        row.addView(valueView, matchWrap());
        parent.addView(row, matchWrap());
    }

    private static void addPermissionRow(
            LinearLayout parent,
            Context context,
            String label,
            String status
    ) {
        addRow(parent, context, label, status,
                status.startsWith("允许") ? COLOR_ALLOWED : COLOR_DENIED);
    }

    private static void addNote(
            LinearLayout parent,
            Context context,
            String message,
            int background,
            int textColor
    ) {
        TextView note = new TextView(context);
        note.setText(message);
        note.setTextColor(textColor);
        note.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        note.setLineSpacing(0f, 1.16f);
        note.setBackgroundColor(background);
        note.setPadding(dp(context, 12), dp(context, 10), dp(context, 12), dp(context, 10));
        LinearLayout.LayoutParams params = matchWrap();
        params.topMargin = dp(context, 6);
        parent.addView(note, params);
    }

    private static void addDivider(LinearLayout parent, Context context) {
        View divider = new View(context);
        divider.setBackgroundColor(COLOR_DIVIDER);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(context, 1)
        );
        params.topMargin = dp(context, 10);
        parent.addView(divider, params);
    }

    private static AlertDialog createProgressDialog(Context context, String message) {
        LinearLayout content = new LinearLayout(context);
        content.setGravity(Gravity.CENTER_VERTICAL);
        content.setPadding(dp(context, 24), dp(context, 10), dp(context, 24), dp(context, 10));
        ProgressBar progressBar = new ProgressBar(context);
        TextView text = new TextView(context);
        text.setText(message);
        text.setTextColor(COLOR_PRIMARY);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
        );
        textParams.leftMargin = dp(context, 16);
        content.addView(progressBar, new LinearLayout.LayoutParams(dp(context, 32), dp(context, 32)));
        content.addView(text, textParams);
        return new AlertDialog.Builder(context)
                .setTitle("文件详情")
                .setView(content)
                .setCancelable(false)
                .create();
    }

    private static String printStatus(PdfDetails pdf) {
        if (!pdf.canPrint) return "禁止";
        if (!pdf.canPrintFaithful) return "允许（仅低质量）";
        return "允许";
    }

    private static String allowed(boolean value) {
        return value ? "允许" : "禁止";
    }

    private static String formatPageSize(float widthPt, float heightPt) {
        float widthMm = widthPt * 25.4f / 72f;
        float heightMm = heightPt * 25.4f / 72f;
        String orientation = widthPt > heightPt ? "横向" : "纵向";
        return String.format(
                Locale.getDefault(),
                "%.0f × %.0f pt（%.0f × %.0f mm，%s）",
                widthPt,
                heightPt,
                widthMm,
                heightMm,
                orientation
        );
    }

    private static String formatFileSize(long bytes) {
        if (bytes < 0L) return "未知";
        if (bytes >= 1024L * 1024L * 1024L) {
            return String.format(Locale.getDefault(), "%.2f GB", bytes / (1024f * 1024f * 1024f));
        }
        if (bytes >= 1024L * 1024L) {
            return String.format(Locale.getDefault(), "%.2f MB", bytes / (1024f * 1024f));
        }
        if (bytes >= 1024L) {
            return String.format(Locale.getDefault(), "%.2f KB", bytes / 1024f);
        }
        return bytes + " B";
    }

    private static String formatDate(Date date) {
        if (date == null) return "无";
        return new SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()).format(date);
    }

    private static String formatCalendar(Calendar calendar) {
        return calendar == null ? "无" : formatDate(calendar.getTime());
    }

    private static String safe(String value) {
        return TextUtils.isEmpty(value) ? "未知" : value;
    }

    private static String emptyAsNone(String value) {
        return TextUtils.isEmpty(value) ? "无" : value.trim();
    }

    private static String emptyAsUnknown(String value) {
        return TextUtils.isEmpty(value) ? "未知" : value;
    }

    private static boolean isPdf(PdfItem item) {
        return item != null
                && !item.isFolder
                && item.name != null
                && item.name.toLowerCase(Locale.ROOT).endsWith(".pdf");
    }

    private static int dp(Context context, int value) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                value,
                context.getResources().getDisplayMetrics()
        ));
    }

    private static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
    }

    private static final class BasicDetails {
        String name;
        String type;
        String location;
        String size;
        String addedTime;
        String sourceModifiedTime;
        boolean appModified;
        String appModifiedTime;
        boolean favorite;
    }

    private static final class PdfDetails {
        int pageCount;
        String version;
        String firstPageSize;
        boolean encrypted;
        boolean openPasswordRequired;
        boolean hasRestrictions;
        String protectionStatus;
        boolean canPrint;
        boolean canPrintFaithful;
        boolean canCopy;
        boolean canAccessibility;
        boolean canModify;
        boolean canAnnotate;
        boolean canFillForms;
        boolean canAssemble;
        String title = "无";
        String author = "无";
        String subject = "无";
        String keywords = "无";
        String creator = "无";
        String producer = "无";
        String createdTime = "无";
        String modifiedTime = "无";
        boolean hasXmp;
    }
}
