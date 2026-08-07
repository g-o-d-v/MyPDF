package com.nless.mypdf.diagnostics;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.google.firebase.FirebaseApp;
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
    private static final String TAG = "CrashReporting";

    private CrashReportingManager() {}

    public static void initialize(Context context) {
        Context appContext = context.getApplicationContext();
        boolean enabled = isEnabled(appContext);

        // :pdf_merge 是独立进程。Firebase Android SDK 不支持在非主进程中常规运行。
        // 合并属于核心本地功能，不应因为 Crashlytics 初始化状态而阻塞，因此任何
        // 非主进程都只保留本地诊断上下文，完全跳过 Firebase/Crashlytics。
        if (!ProcessName.isMainProcess()) {
            DiagnosticContext.stopRemoteSync();
            return;
        }

        if (!ensureFirebaseReady(appContext)) {
            DiagnosticContext.stopRemoteSync();
            return;
        }

        FirebaseCrashlytics crashlytics = FirebaseCrashlytics.getInstance();
        crashlytics.setCrashlyticsCollectionEnabled(enabled);

        if (enabled) {
            DiagnosticContext.syncToCrashlytics(appContext);
        } else {
            // 自动收集关闭时，崩溃可能暂存在设备上。每次冷启动都排队删除，
            // 确保用户以后同意时不会补传同意前产生的历史报告。
            crashlytics.deleteUnsentReports();
        }
    }

    /**
     * 确保当前 Android 进程存在默认 FirebaseApp。
     *
     * <p>仅允许主进程使用 Firebase。主进程通常由 FirebaseInitProvider 自动完成初始化；
     * 若初始化时序异常，再使用 initializeApp 兜底。独立 PDF 合并进程直接返回 false，
     * 诊断功能退化为纯本地记录，不能阻塞 PDF 合并等核心功能。</p>
     */
    public static boolean ensureFirebaseReady(Context context) {
        if (!ProcessName.isMainProcess()) return false;
        Context appContext = context.getApplicationContext();
        try {
            try {
                FirebaseApp.getInstance();
                return true;
            } catch (IllegalStateException notInitialized) {
                FirebaseApp initialized = FirebaseApp.initializeApp(appContext);
                if (initialized != null) return true;
                Log.w(TAG, "FirebaseApp initialization returned null in process "
                        + ProcessName.current());
                return false;
            }
        } catch (Throwable error) {
            Log.w(TAG, "Firebase unavailable in process " + ProcessName.current(), error);
            return false;
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
        if (!ensureFirebaseReady(appContext)) return;
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
        if (ensureFirebaseReady(appContext)) {
            FirebaseCrashlytics.getInstance().setCrashlyticsCollectionEnabled(false);
        }
        DiagnosticContext.stopRemoteSync();
    }

    public static void deleteUnsentReports() {
        FirebaseCrashlytics.getInstance().deleteUnsentReports();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
