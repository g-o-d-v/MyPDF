package com.nless.mypdf.ui;

import android.app.Activity;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.widget.Toast;

import androidx.annotation.Nullable;

import java.util.Locale;

/**
 * 系统“打开方式”入口。
 *
 * <p>Android 文件管理器和其他应用通过 ACTION_VIEW + content:// URI 打开 PDF 时，
 * 系统不会提供 MyPDF 内部使用的 pdf_uri/pdf_path/pdf_name extras。本 Activity 只负责
 * 接收外部 Intent、保留 URI 授权并转换参数，然后立即跳转到 PdfViewerActivity。</p>
 */
public final class PdfOpenActivity extends Activity {

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        forwardPdf(getIntent());
    }

    private void forwardPdf(Intent sourceIntent) {
        if (sourceIntent == null || !Intent.ACTION_VIEW.equals(sourceIntent.getAction())) {
            finishWithMessage("没有收到可打开的 PDF 文件");
            return;
        }

        Uri uri = sourceIntent.getData();
        if (uri == null) {
            ClipData clipData = sourceIntent.getClipData();
            if (clipData != null && clipData.getItemCount() > 0) {
                uri = clipData.getItemAt(0).getUri();
            }
        }
        if (uri == null) {
            finishWithMessage("无法获取 PDF 文件地址");
            return;
        }

        String scheme = uri.getScheme();
        if (!ContentResolver.SCHEME_CONTENT.equalsIgnoreCase(scheme)
                && !ContentResolver.SCHEME_FILE.equalsIgnoreCase(scheme)) {
            finishWithMessage("MyPDF 当前仅支持打开本地 PDF 文件");
            return;
        }

        String fileName = queryDisplayName(uri);
        String mimeType = sourceIntent.getType();
        if (mimeType == null || mimeType.trim().isEmpty()) {
            try {
                mimeType = getContentResolver().getType(uri);
            } catch (Throwable ignored) {
                mimeType = null;
            }
        }
        if (!looksLikePdf(mimeType, fileName)) {
            finishWithMessage("所选文件不是受支持的 PDF");
            return;
        }

        int grantFlags = sourceIntent.getFlags()
                & (Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        tryPersistPermission(uri, sourceIntent.getFlags(), grantFlags);

        String path;
        if (ContentResolver.SCHEME_FILE.equalsIgnoreCase(scheme)) {
            path = uri.getPath();
        } else {
            path = MainActivity.cleanPath(uri.getPath());
        }
        if (path == null) path = "";

        Intent viewer = new Intent(this, PdfViewerActivity.class);
        viewer.setData(uri);
        viewer.putExtra("pdf_uri", uri.toString());
        viewer.putExtra("pdf_path", path);
        viewer.putExtra("pdf_name", fileName);
        if (grantFlags != 0) viewer.addFlags(grantFlags);

        startActivity(viewer);
        finish();
    }

    private void tryPersistPermission(Uri uri, int sourceFlags, int grantFlags) {
        if (!ContentResolver.SCHEME_CONTENT.equalsIgnoreCase(uri.getScheme())) return;
        if ((sourceFlags & Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) == 0) return;
        if (grantFlags == 0) return;
        try {
            getContentResolver().takePersistableUriPermission(uri, grantFlags);
        } catch (SecurityException ignored) {
            // 普通 ACTION_VIEW 来源经常只提供临时授权；临时授权仍足够本次阅读。
        }
    }

    private String queryDisplayName(Uri uri) {
        if (ContentResolver.SCHEME_CONTENT.equalsIgnoreCase(uri.getScheme())) {
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
                        if (value != null && !value.trim().isEmpty()) {
                            return value.trim();
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        }

        String segment = uri.getLastPathSegment();
        if (segment != null) {
            segment = Uri.decode(segment);
            int colon = segment.lastIndexOf(':');
            if (colon >= 0 && colon < segment.length() - 1) {
                segment = segment.substring(colon + 1);
            }
            if (!segment.trim().isEmpty()) return segment.trim();
        }
        return "未命名.pdf";
    }

    private boolean looksLikePdf(String mimeType, String fileName) {
        if ("application/pdf".equalsIgnoreCase(mimeType)
                || "application/x-pdf".equalsIgnoreCase(mimeType)) {
            return true;
        }
        return fileName != null
                && fileName.toLowerCase(Locale.ROOT).endsWith(".pdf");
    }

    private void finishWithMessage(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
        finish();
    }
}
