package com.nless.mypdf.diagnostics;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.firebase.crashlytics.FirebaseCrashlytics;

/**
 * Crashlytics 用户同意状态的唯一读写入口。
 *
 * <p>Manifest 中始终默认关闭自动收集。用户首次同意时先请求删除设备上可能存在的
 * 历史未发送报告，再开启自动收集，降低补传同意前崩溃的风险。</p>
 */
public final class CrashReportingManager {
    private static final String PREFS = "crash_reporting_preferences";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_CHOICE_MADE = "choice_made";

    private CrashReportingManager() {}

    public static void initialize(Context context) {
        Context appContext = context.getApplicationContext();
        FirebaseCrashlytics crashlytics = FirebaseCrashlytics.getInstance();
        boolean enabled = isEnabled(appContext);
        crashlytics.setCrashlyticsCollectionEnabled(enabled);

        if (enabled) {
            DiagnosticContext.syncToCrashlytics(appContext);
        } else {
            // 自动收集关闭时，崩溃可能暂存在设备上。每次冷启动都排队删除，
            // 确保用户以后同意时不会补传同意前产生的历史报告。
            crashlytics.deleteUnsentReports();
        }
    }

    public static boolean isEnabled(Context context) {
        return prefs(context).getBoolean(KEY_ENABLED, false);
    }

    public static boolean hasUserChoice(Context context) {
        return prefs(context).getBoolean(KEY_CHOICE_MADE, false);
    }

    public static void enableAfterConsent(Context context) {
        Context appContext = context.getApplicationContext();
        FirebaseCrashlytics crashlytics = FirebaseCrashlytics.getInstance();

        // 先保持关闭并请求删除历史缓存，再清理同意前的本地诊断上下文。
        crashlytics.setCrashlyticsCollectionEnabled(false);
        crashlytics.deleteUnsentReports();
        DiagnosticContext.clearForNewConsent(appContext);

        prefs(appContext).edit()
                .putBoolean(KEY_ENABLED, true)
                .putBoolean(KEY_CHOICE_MADE, true)
                .commit();

        crashlytics.setCrashlyticsCollectionEnabled(true);
        DiagnosticContext.initializeSession(appContext);
        DiagnosticContext.recordScreen(appContext, "settings");
        DiagnosticContext.recordOperation(appContext, "crash_reporting", "finished", "success");
    }

    public static void disable(Context context) {
        Context appContext = context.getApplicationContext();
        prefs(appContext).edit()
                .putBoolean(KEY_ENABLED, false)
                .putBoolean(KEY_CHOICE_MADE, true)
                .commit();
        FirebaseCrashlytics.getInstance().setCrashlyticsCollectionEnabled(false);
        DiagnosticContext.stopRemoteSync();
    }

    public static void deleteUnsentReports() {
        FirebaseCrashlytics.getInstance().deleteUnsentReports();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
