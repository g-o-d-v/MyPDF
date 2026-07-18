package com.nless.mypdf;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ResultReceiver;
import android.provider.OpenableColumns;

import androidx.annotation.Nullable;
import androidx.documentfile.provider.DocumentFile;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 在私有独立进程中执行 PDF 合并。
 *
 * PdfBox 的对象解析、页面克隆、临时文件以及 GC 都发生在 :pdf_merge 进程，
 * 避免主界面进程因为大文档合并产生长时间 GC 停顿。
 */
public final class PdfMergeWorkerService extends Service {

    static final String ACTION_START = "com.nless.mypdf.action.START_PDF_MERGE";
    static final String ACTION_CANCEL = "com.nless.mypdf.action.CANCEL_PDF_MERGE";

    static final String EXTRA_TASK_ID = "task_id";
    static final String EXTRA_SOURCE_URIS = "source_uris";
    static final String EXTRA_OUTPUT_URI = "output_uri";
    static final String EXTRA_RECEIVER = "result_receiver";
    static final String EXTRA_CURRENT = "current";
    static final String EXTRA_TOTAL = "total";
    static final String EXTRA_MESSAGE = "message";
    static final String EXTRA_SOURCE_FILES = "source_files";
    static final String EXTRA_SOURCE_PAGES = "source_pages";
    static final String EXTRA_OUTPUT_PAGES = "output_pages";
    static final String EXTRA_OUTPUT_NAME = "output_name";

    static final int RESULT_PROGRESS = 1;
    static final int RESULT_SUCCESS = 2;
    static final int RESULT_CANCELLED = 3;
    static final int RESULT_ERROR = 4;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final Object taskLock = new Object();

    private String activeTaskId;

    static void start(
            Context context,
            String taskId,
            ArrayList<String> sourceUris,
            Uri outputUri,
            ResultReceiver receiver
    ) {
        Intent intent = new Intent(context, PdfMergeWorkerService.class);
        intent.setAction(ACTION_START);
        intent.putExtra(EXTRA_TASK_ID, taskId);
        intent.putStringArrayListExtra(EXTRA_SOURCE_URIS, sourceUris);
        intent.putExtra(EXTRA_OUTPUT_URI, outputUri.toString());
        intent.putExtra(EXTRA_RECEIVER, receiver);
        context.startService(intent);
    }

    static void cancel(Context context, String taskId) {
        if (taskId == null || taskId.trim().isEmpty()) return;
        Intent intent = new Intent(context, PdfMergeWorkerService.class);
        intent.setAction(ACTION_CANCEL);
        intent.putExtra(EXTRA_TASK_ID, taskId);
        context.startService(intent);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        String action = intent.getAction();
        if (ACTION_CANCEL.equals(action)) {
            String taskId = intent.getStringExtra(EXTRA_TASK_ID);
            boolean matched;
            synchronized (taskLock) {
                matched = taskId != null && taskId.equals(activeTaskId);
                if (matched) cancelled.set(true);
            }
            if (!matched) stopSelf(startId);
            return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(action)) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        String taskId = intent.getStringExtra(EXTRA_TASK_ID);
        ArrayList<String> sourceStrings = intent.getStringArrayListExtra(EXTRA_SOURCE_URIS);
        String outputString = intent.getStringExtra(EXTRA_OUTPUT_URI);
        ResultReceiver receiver = readReceiver(intent);

        if (taskId == null || sourceStrings == null || outputString == null || receiver == null) {
            sendError(receiver, taskId, "合并任务参数不完整");
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        synchronized (taskLock) {
            if (activeTaskId != null) {
                sendError(receiver, taskId, "已有 PDF 合并任务正在运行");
                return START_NOT_STICKY;
            }
            activeTaskId = taskId;
            cancelled.set(false);
        }

        executor.execute(() -> runMergeTask(
                taskId,
                sourceStrings,
                Uri.parse(outputString),
                receiver
        ));
        return START_NOT_STICKY;
    }

    private void runMergeTask(
            String taskId,
            List<String> sourceStrings,
            Uri outputUri,
            ResultReceiver receiver
    ) {
        File temp = null;
        try {
            ArrayList<Uri> sourceUris = new ArrayList<>(sourceStrings.size());
            for (String value : sourceStrings) sourceUris.add(Uri.parse(value));

            temp = File.createTempFile("mypdf-merge-worker-", ".pdf", getCacheDir());
            PdfPageManagementService.ResultSummary summary =
                    PdfPageManagementService.mergePdfs(
                            this,
                            sourceUris,
                            temp,
                            cancelled,
                            (current, total, message) -> sendProgress(
                                    receiver,
                                    taskId,
                                    current,
                                    total,
                                    message
                            )
                    );

            ensureNotCancelled();
            sendProgress(receiver, taskId, 0, 1, "正在写入保存位置");
            copyTempToUri(temp, outputUri, receiver, taskId);
            ensureNotCancelled();

            String outputName = queryDisplayName(outputUri);

            Bundle result = baseBundle(taskId);
            result.putInt(EXTRA_SOURCE_FILES, summary.sourceFiles);
            result.putInt(EXTRA_SOURCE_PAGES, summary.sourcePages);
            result.putInt(EXTRA_OUTPUT_PAGES, summary.outputPages);
            result.putString(EXTRA_OUTPUT_URI, outputUri.toString());
            result.putString(EXTRA_OUTPUT_NAME, outputName);
            receiver.send(RESULT_SUCCESS, result);
        } catch (Exception error) {
            deleteOutput(outputUri);
            if (cancelled.get() || safeMessage(error).contains("取消")) {
                receiver.send(RESULT_CANCELLED, baseBundle(taskId));
            } else {
                sendError(receiver, taskId, safeMessage(error));
            }
        } finally {
            if (temp != null && temp.exists() && !temp.delete()) temp.deleteOnExit();
            synchronized (taskLock) {
                if (taskId.equals(activeTaskId)) activeTaskId = null;
            }
            stopSelf();
        }
    }

    private void copyTempToUri(
            File temp,
            Uri outputUri,
            ResultReceiver receiver,
            String taskId
    ) throws Exception {
        long totalBytes = Math.max(1L, temp.length());
        long copied = 0L;
        try (InputStream input = new FileInputStream(temp);
             OutputStream output = getContentResolver().openOutputStream(outputUri, "w")) {
            if (output == null) throw new IllegalStateException("无法写入所选保存位置");
            byte[] buffer = new byte[128 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                ensureNotCancelled();
                output.write(buffer, 0, read);
                copied += read;
                int current = (int) Math.min(1000L, copied * 1000L / totalBytes);
                sendProgress(receiver, taskId, current, 1000, "正在写入保存位置");
            }
            output.flush();
        }
    }

    private void ensureNotCancelled() throws Exception {
        if (cancelled.get() || Thread.currentThread().isInterrupted()) {
            throw new Exception("操作已取消");
        }
    }

    private void sendProgress(
            ResultReceiver receiver,
            String taskId,
            int current,
            int total,
            String message
    ) {
        Bundle data = baseBundle(taskId);
        data.putInt(EXTRA_CURRENT, current);
        data.putInt(EXTRA_TOTAL, total);
        data.putString(EXTRA_MESSAGE, message);
        receiver.send(RESULT_PROGRESS, data);
    }

    private void sendError(ResultReceiver receiver, String taskId, String message) {
        if (receiver == null) return;
        Bundle data = baseBundle(taskId);
        data.putString(EXTRA_MESSAGE, message);
        receiver.send(RESULT_ERROR, data);
    }

    private Bundle baseBundle(String taskId) {
        Bundle data = new Bundle();
        data.putString(EXTRA_TASK_ID, taskId);
        return data;
    }

    @SuppressWarnings("deprecation")
    private ResultReceiver readReceiver(Intent intent) {
        if (Build.VERSION.SDK_INT >= 33) {
            return intent.getParcelableExtra(EXTRA_RECEIVER, ResultReceiver.class);
        }
        return intent.getParcelableExtra(EXTRA_RECEIVER);
    }

    private String queryDisplayName(Uri uri) {
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
        return "合并文档.pdf";
    }

    private void deleteOutput(Uri uri) {
        try {
            DocumentFile file = DocumentFile.fromSingleUri(this, uri);
            if (file != null) file.delete();
        } catch (Throwable ignore) {
        }
    }

    private String safeMessage(Throwable error) {
        if (error == null) return "未知错误";
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.trim().isEmpty()
                ? current.getClass().getSimpleName()
                : message.trim();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        cancelled.set(true);
        executor.shutdownNow();
        super.onDestroy();
    }
}
