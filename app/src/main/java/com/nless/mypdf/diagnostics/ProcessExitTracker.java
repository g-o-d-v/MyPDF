package com.nless.mypdf.diagnostics;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import java.util.List;

/** Reads only coarse Android 11+ process-exit reason and process category. */
public final class ProcessExitTracker {
    private static final String PREFS = "mypdf_process_exit_tracker";
    private static final String KEY_LAST_TIMESTAMP = "last_timestamp";

    private ProcessExitTracker() {}

    public static void captureLatest(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || !ProcessName.isMainProcess()) return;
        try {
            ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (manager == null) return;
            List<ApplicationExitInfo> exits = manager.getHistoricalProcessExitReasons(
                    context.getPackageName(), 0, 12);
            if (exits == null || exits.isEmpty()) return;

            SharedPreferences preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            long consumed = preferences.getLong(KEY_LAST_TIMESTAMP, 0L);
            ApplicationExitInfo newest = null;
            for (ApplicationExitInfo info : exits) {
                if (info.getTimestamp() > consumed
                        && (newest == null || info.getTimestamp() > newest.getTimestamp())) {
                    newest = info;
                }
            }
            if (newest == null) return;

            preferences.edit().putLong(KEY_LAST_TIMESTAMP, newest.getTimestamp()).apply();
            DiagnosticContext.setLastExit(context, mapReason(newest.getReason()),
                    mapProcess(newest.getProcessName()));
        } catch (Throwable ignored) {
            // 退出原因是补充诊断信息，读取失败不能影响应用启动。
        }
    }

    private static String mapProcess(String processName) {
        if (processName == null) return DiagnosticContext.UNKNOWN;
        if ("com.nless.mypdf".equals(processName)) return "main";
        if (processName.endsWith(":pdf_merge")) return "pdf_merge";
        return "other";
    }

    private static String mapReason(int reason) {
        switch (reason) {
            case ApplicationExitInfo.REASON_LOW_MEMORY: return "low_memory";
            case ApplicationExitInfo.REASON_ANR: return "anr";
            case ApplicationExitInfo.REASON_CRASH: return "crash";
            case ApplicationExitInfo.REASON_CRASH_NATIVE: return "native_crash";
            case ApplicationExitInfo.REASON_SIGNALED: return "signaled";
            case ApplicationExitInfo.REASON_USER_REQUESTED:
            case ApplicationExitInfo.REASON_USER_STOPPED: return "user_requested";
            case ApplicationExitInfo.REASON_INITIALIZATION_FAILURE:
            case ApplicationExitInfo.REASON_PERMISSION_CHANGE:
            case ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE:
            case ApplicationExitInfo.REASON_DEPENDENCY_DIED:
            case ApplicationExitInfo.REASON_OTHER:
            case ApplicationExitInfo.REASON_FREEZER:
            case ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE:
            case ApplicationExitInfo.REASON_PACKAGE_UPDATED: return "system";
            default: return DiagnosticContext.UNKNOWN;
        }
    }
}
