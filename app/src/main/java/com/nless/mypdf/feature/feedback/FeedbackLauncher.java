package com.nless.mypdf.feature.feedback;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.widget.Toast;

import com.nless.mypdf.BuildConfig;
import com.nless.mypdf.diagnostics.CrashReportingManager;

import java.util.Locale;

/** 打开公开 GitHub Issues，并预填不包含文档内容的技术信息。 */
public final class FeedbackLauncher {

    private static final String GITHUB_NEW_ISSUE_URL =
            "https://github.com/g-o-d-v/MyPDF/issues/new";

    private FeedbackLauncher() {
    }

    public static void openGitHub(Activity activity) {
        String subject = buildSubject(activity);
        String body = buildFeedbackBody(activity);
        Uri uri = Uri.parse(GITHUB_NEW_ISSUE_URL)
                .buildUpon()
                .appendQueryParameter("title", subject)
                .appendQueryParameter("body", body)
                .build();

        Intent intent = new Intent(Intent.ACTION_VIEW, uri);
        try {
            activity.startActivity(intent);
        } catch (ActivityNotFoundException error) {
            copyToClipboard(
                    activity,
                    "MyPDF GitHub 反馈",
                    GITHUB_NEW_ISSUE_URL + "\n\n" + body,
                    "未找到浏览器，反馈地址和模板已复制"
            );
        }
    }

    private static String buildSubject(Context context) {
        return "[MyPDF 反馈] 请在此补充简短标题（" + getVersionName(context) + "）";
    }

    private static String buildFeedbackBody(Context context) {
        StringBuilder body = new StringBuilder(640);
        body.append("问题描述：\n")
                .append("请在此处描述遇到的问题或建议。\n\n")
                .append("复现步骤：\n")
                .append("1. \n")
                .append("2. \n")
                .append("3. \n\n")
                .append("期望结果：\n\n")
                .append("实际结果：\n\n")
                .append("是否可以稳定复现：是 / 否 / 不确定\n\n")
                .append("—— MyPDF 自动附加的匿名技术信息 ——\n")
                .append("应用版本：").append(getVersionName(context))
                .append(" (").append(getVersionCode(context)).append(")\n")
                .append("构建类型：").append(BuildConfig.BUILD_TYPE).append('\n')
                .append("Android：").append(Build.VERSION.RELEASE)
                .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
                .append("设备：").append(getDeviceName()).append('\n')
                .append("ABI：").append(getAbiSummary()).append('\n')
                .append("系统语言：").append(Locale.getDefault().toLanguageTag()).append('\n')
                .append("匿名崩溃报告：")
                .append(CrashReportingManager.isEnabled(context) ? "已开启" : "未开启")
                .append("\n\n")
                .append("请勿附加 PDF 文件、页面截图、正文、OCR 结果、搜索词、批注内容或密码。\n")
                .append("以上内容尚未自动发送，可在 GitHub 发布前修改或删除。\n");
        return body.toString();
    }

    private static void copyToClipboard(
            Context context,
            String label,
            String text,
            String toastMessage
    ) {
        ClipboardManager clipboard =
                (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText(label, text));
            Toast.makeText(context, toastMessage, Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(context, "无法访问剪贴板", Toast.LENGTH_LONG).show();
        }
    }

    private static String getVersionName(Context context) {
        try {
            PackageInfo info = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0);
            return info.versionName == null ? "unknown" : info.versionName;
        } catch (Exception ignored) {
            return BuildConfig.VERSION_NAME;
        }
    }

    @SuppressWarnings("deprecation")
    private static long getVersionCode(Context context) {
        try {
            PackageInfo info = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                return info.getLongVersionCode();
            }
            return info.versionCode;
        } catch (Exception ignored) {
            return BuildConfig.VERSION_CODE;
        }
    }

    private static String getDeviceName() {
        String manufacturer = safe(Build.MANUFACTURER);
        String model = safe(Build.MODEL);
        if (model.toLowerCase(Locale.ROOT).startsWith(manufacturer.toLowerCase(Locale.ROOT))) {
            return model;
        }
        return manufacturer + " " + model;
    }

    private static String getAbiSummary() {
        String[] abis = Build.SUPPORTED_ABIS;
        if (abis == null || abis.length == 0) {
            return "unknown";
        }
        return String.join(", ", abis);
    }

    private static String safe(String value) {
        if (value == null || value.trim().isEmpty()) {
            return "unknown";
        }
        return value.trim();
    }
}
