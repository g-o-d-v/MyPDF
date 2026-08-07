package com.nless.mypdf.diagnostics;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.OpenableColumns;

import com.google.firebase.crashlytics.FirebaseCrashlytics;
import com.nless.mypdf.BuildConfig;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * 只记录诊断数据白名单中的粗粒度状态。禁止向本类传入文件名、路径、URI 字符串、
 * 搜索词、密码、批注内容、OCR 文本或异常消息。
 */
public final class DiagnosticContext {
    public static final String UNKNOWN = "unknown";
    private static final String PREFS_PREFIX = "mypdf_diagnostic_context_";
    private static final String SCHEMA = "1";
    private static final Map<String, Long> OPERATION_STARTS = new HashMap<>();

    private static volatile boolean remoteSyncEnabled;

    private static final String[] ALLOWED_KEYS = {
            "app_process", "build_type", "app_abi", "current_screen",
            "current_operation", "operation_stage", "operation_result",
            "document_source", "file_size_bucket", "page_count_bucket",
            "encrypted", "permission_restricted", "ocr_enabled", "ocr_mode",
            "ocr_width", "annotation_type", "save_mode",
            "operation_duration_bucket", "available_memory_bucket",
            "last_exit_reason", "last_exit_process", "large_heap_enabled",
            "native_symbol_status", "diagnostic_schema"
    };

    private DiagnosticContext() {}

    public static void initialize(Context context) {
        initializeSession(context.getApplicationContext());
    }

    public static void initializeSession(Context context) {
        Context appContext = context.getApplicationContext();
        remoteSyncEnabled = ProcessName.isMainProcess()
                && CrashReportingManager.isEnabled(appContext)
                && CrashReportingManager.ensureFirebaseReady(appContext);
        put(appContext, "app_process", ProcessName.current());
        put(appContext, "build_type", BuildConfig.DEBUG ? "debug" : "release");
        put(appContext, "app_abi", Build.SUPPORTED_ABIS.length == 0
                ? UNKNOWN : normalizeAbi(Build.SUPPORTED_ABIS[0]));
        put(appContext, "large_heap_enabled", "true");
        put(appContext, "native_symbol_status", "unknown");
        put(appContext, "diagnostic_schema", SCHEMA);
        put(appContext, "available_memory_bucket", availableMemoryBucket(appContext));
        if (remoteSyncEnabled) syncToCrashlytics(appContext);
    }

    public static void stopRemoteSync() {
        remoteSyncEnabled = false;
    }

    public static void registerActivityTracking(Application application) {
        application.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityCreated(Activity activity, Bundle state) {}
            @Override public void onActivityStarted(Activity activity) {}
            @Override public void onActivityResumed(Activity activity) {
                recordScreen(application, screenName(activity));
            }
            @Override public void onActivityPaused(Activity activity) {}
            @Override public void onActivityStopped(Activity activity) {}
            @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) {}
            @Override public void onActivityDestroyed(Activity activity) {}
        });
    }

    public static void recordScreen(Context context, String screen) {
        String safe = allowed(screen,
                "home", "pdf_viewer", "settings", "feedback", "tools", "create_pdf",
                "export_convert", "manage_pages", "protection", "other");
        put(context, "current_screen", safe);
        log(context, "screen_open:" + safe);
    }

    public static void startOperation(Context context, String operation) {
        String safe = operation(operation);
        synchronized (OPERATION_STARTS) {
            OPERATION_STARTS.put(safe, SystemClock.elapsedRealtime());
        }
        put(context, "current_operation", safe);
        put(context, "operation_stage", "started");
        put(context, "operation_result", "running");
        put(context, "operation_duration_bucket", UNKNOWN);
        put(context, "available_memory_bucket", availableMemoryBucket(context));
        log(context, "operation_start:" + safe);
    }

    public static void stageOperation(Context context, String stage) {
        String safe = allowed(stage, "started", "reading", "rendering", "ocr",
                "writing", "validating", "saving", "finished", "failed", "cancelled");
        put(context, "operation_stage", safe);
        log(context, "operation_stage:" + safe);
    }

    public static void finishOperation(Context context, String operation, boolean success) {
        String safe = operation(operation);
        long duration = -1L;
        synchronized (OPERATION_STARTS) {
            Long start = OPERATION_STARTS.remove(safe);
            if (start != null) duration = SystemClock.elapsedRealtime() - start;
        }
        put(context, "current_operation", safe);
        put(context, "operation_stage", success ? "finished" : "failed");
        put(context, "operation_result", success ? "success" : "failed");
        put(context, "operation_duration_bucket", durationBucket(duration));
        put(context, "available_memory_bucket", availableMemoryBucket(context));
        log(context, (success ? "operation_finish:" : "operation_failed:") + safe);
    }

    public static void cancelOperation(Context context, String operation) {
        String safe = operation(operation);
        synchronized (OPERATION_STARTS) {
            OPERATION_STARTS.remove(safe);
        }
        put(context, "current_operation", safe);
        put(context, "operation_stage", "cancelled");
        put(context, "operation_result", "failed");
        log(context, "operation_failed:" + safe);
    }

    public static void recordOperation(
            Context context,
            String operation,
            String stage,
            String result
    ) {
        put(context, "current_operation", operation(operation));
        put(context, "operation_stage", allowed(stage, "started", "reading", "rendering",
                "ocr", "writing", "validating", "saving", "finished", "failed", "cancelled"));
        put(context, "operation_result", allowed(result, "running", "success", "failed"));
    }

    public static void setDocumentProfile(
            Context context,
            Uri uri,
            String source,
            int pageCount,
            boolean encrypted,
            boolean permissionRestricted
    ) {
        put(context, "document_source", allowed(source, "saf", "app_copy", UNKNOWN));
        put(context, "file_size_bucket", fileSizeBucket(readSize(context, uri)));
        put(context, "page_count_bucket", pageCountBucket(pageCount));
        put(context, "encrypted", Boolean.toString(encrypted));
        put(context, "permission_restricted", Boolean.toString(permissionRestricted));
    }

    public static void setSearchProfile(Context context, boolean ocrEnabled, String mode, int width) {
        put(context, "ocr_enabled", Boolean.toString(ocrEnabled));
        put(context, "ocr_mode", allowed(mode, "smart", "ocr_only", "text_only", UNKNOWN));
        put(context, "ocr_width", width == 960 || width == 1280 || width == 1440
                ? Integer.toString(width) : UNKNOWN);
    }

    public static void setAnnotationProfile(Context context, String type, String saveMode) {
        put(context, "annotation_type", allowed(type, "highlight", "underline", "strikeout",
                "ink", "none", UNKNOWN));
        put(context, "save_mode", allowed(saveMode, "overwrite", "save_as", UNKNOWN));
    }

    public static void setLastExit(Context context, String reason, String process) {
        put(context, "last_exit_reason", allowed(reason, "low_memory", "anr", "crash",
                "native_crash", "signaled", "user_requested", "system", UNKNOWN));
        put(context, "last_exit_process", allowed(process, "main", "pdf_merge", "other", UNKNOWN));
        log(context, "process_exit_detected:" + allowed(reason, "low_memory", "anr", "crash",
                "native_crash", "signaled", "user_requested", "system", UNKNOWN));
    }

    public static void markNativeSymbols(String status) {
        Context context = AppContextHolder.get();
        if (context != null) put(context, "native_symbol_status",
                allowed(status, "uploaded", "partial", UNKNOWN));
    }

    public static void syncToCrashlytics(Context context) {
        if (!ProcessName.isMainProcess()
                || !CrashReportingManager.isEnabled(context)
                || !CrashReportingManager.ensureFirebaseReady(context)) return;
        remoteSyncEnabled = true;
        SharedPreferences preferences = prefs(context);
        FirebaseCrashlytics crashlytics = FirebaseCrashlytics.getInstance();
        for (String key : ALLOWED_KEYS) {
            String value = preferences.getString(key, UNKNOWN);
            crashlytics.setCustomKey(key, value == null ? UNKNOWN : value);
        }
    }

    public static void clearForNewConsent(Context context) {
        Context appContext = context.getApplicationContext();
        clearPrefs(appContext, "main");
        clearPrefs(appContext, "pdf_merge");
        clearPrefs(appContext, "other");
        clearPrefs(appContext, UNKNOWN);
        synchronized (OPERATION_STARTS) {
            OPERATION_STARTS.clear();
        }
        remoteSyncEnabled = false;
    }

    private static void put(Context context, String key, String value) {
        if (!isAllowedKey(key)) return;
        Context appContext = context.getApplicationContext();
        AppContextHolder.set(appContext);
        String safeValue = value == null || value.length() > 128 ? UNKNOWN : value;
        prefs(appContext).edit().putString(key, safeValue).apply();
        if (remoteSyncEnabled && CrashReportingManager.isEnabled(appContext)) {
            FirebaseCrashlytics.getInstance().setCustomKey(key, safeValue);
        }
    }

    private static void log(Context context, String event) {
        if (!remoteSyncEnabled || !CrashReportingManager.isEnabled(context)) return;
        if (event == null || event.length() > 96 || !event.matches("[a-z0-9_:-]+")) return;
        FirebaseCrashlytics.getInstance().log(event);
    }

    private static SharedPreferences prefs(Context context) {
        Context appContext = context.getApplicationContext();
        return appContext.getSharedPreferences(
                PREFS_PREFIX + ProcessName.current(), Context.MODE_PRIVATE);
    }

    private static void clearPrefs(Context context, String process) {
        context.getSharedPreferences(PREFS_PREFIX + process, Context.MODE_PRIVATE)
                .edit().clear().commit();
    }

    private static boolean isAllowedKey(String key) {
        for (String allowed : ALLOWED_KEYS) if (allowed.equals(key)) return true;
        return false;
    }

    private static String operation(String value) {
        return allowed(value,
                "app_start", "crash_reporting", "pdf_open", "pdf_search",
                "annotation_save", "create_pdf", "export_images", "export_text",
                "create_searchable_pdf", "manage_pages", "pdf_merge",
                "pdf_protection", "clear_search_cache", "feedback_submit", UNKNOWN);
    }

    private static String allowed(String value, String... allowed) {
        if (value != null) {
            for (String candidate : allowed) if (candidate.equals(value)) return value;
        }
        return UNKNOWN;
    }

    private static String screenName(Activity activity) {
        String name = activity.getClass().getSimpleName();
        switch (name) {
            case "MainActivity": return "home";
            case "PdfViewerActivity": return "pdf_viewer";
            case "SettingsActivity": return "settings";
            case "FeedbackActivity": return "feedback";
            case "ToolCategoryActivity": return "tools";
            case "CreatePdfActivity": return "create_pdf";
            case "ExportConvertActivity": return "export_convert";
            case "ManagePdfPagesActivity": return "manage_pages";
            case "PdfProtectionActivity": return "protection";
            default: return "other";
        }
    }

    private static long readSize(Context context, Uri uri) {
        if (uri == null) return -1L;
        if ("file".equals(uri.getScheme()) && uri.getPath() != null) {
            try {
                File file = new File(uri.getPath());
                return file.isFile() ? file.length() : -1L;
            } catch (Throwable ignored) {
                return -1L;
            }
        }
        try (Cursor cursor = context.getContentResolver().query(
                uri, new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (index >= 0 && !cursor.isNull(index)) return cursor.getLong(index);
            }
        } catch (Throwable ignored) {}
        return -1L;
    }

    private static String fileSizeBucket(long bytes) {
        if (bytes < 0) return UNKNOWN;
        long mb = 1024L * 1024L;
        if (bytes < mb) return "under_1_mb";
        if (bytes < 10L * mb) return "1_10_mb";
        if (bytes < 50L * mb) return "10_50_mb";
        if (bytes < 100L * mb) return "50_100_mb";
        if (bytes < 250L * mb) return "100_250_mb";
        if (bytes < 500L * mb) return "250_500_mb";
        return "over_500_mb";
    }

    private static String pageCountBucket(int pages) {
        if (pages <= 0) return UNKNOWN;
        if (pages <= 10) return "1_10";
        if (pages <= 30) return "11_30";
        if (pages <= 100) return "31_100";
        if (pages <= 300) return "101_300";
        if (pages <= 1000) return "301_1000";
        return "over_1000";
    }

    private static String durationBucket(long millis) {
        if (millis < 0) return UNKNOWN;
        if (millis < 1000L) return "under_1_s";
        if (millis < 3000L) return "1_3_s";
        if (millis < 10_000L) return "3_10_s";
        if (millis < 30_000L) return "10_30_s";
        if (millis < 60_000L) return "30_60_s";
        return "over_60_s";
    }

    private static String availableMemoryBucket(Context context) {
        try {
            ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (manager == null) return UNKNOWN;
            ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
            manager.getMemoryInfo(info);
            long mb = info.availMem / (1024L * 1024L);
            if (mb < 64) return "under_64_mb";
            if (mb < 128) return "64_128_mb";
            if (mb < 256) return "128_256_mb";
            if (mb < 512) return "256_512_mb";
            return "over_512_mb";
        } catch (Throwable ignored) {
            return UNKNOWN;
        }
    }

    private static String normalizeAbi(String abi) {
        if ("arm64-v8a".equals(abi)) return "arm64-v8a";
        return UNKNOWN;
    }

    private static final class AppContextHolder {
        private static volatile Context context;
        static void set(Context value) { context = value; }
        static Context get() { return context; }
    }
}
