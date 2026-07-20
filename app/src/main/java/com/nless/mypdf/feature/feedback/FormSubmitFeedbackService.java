package com.nless.mypdf.feature.feedback;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.nless.mypdf.BuildConfig;
import com.nless.mypdf.diagnostics.CrashReportingManager;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 将用户主动填写的反馈通过 FormSubmit AJAX 接口发送到开发者邮箱。
 * 不使用 Firebase Authentication、Cloud Firestore，也不在设备上离线排队。
 */
public final class FormSubmitFeedbackService {

    private static final String TAG = "MyPDFFeedback";
    private static final String ENDPOINT =
            "https://formsubmit.co/ajax/e9cac238f0fb959cc510299b58745436";
    // FormSubmit 的首次激活页面需要一个完整、公开且可访问的跳转地址。
    // 使用开发者公开主页，不依赖尚未发布或可能改名的具体仓库路径。
    private static final String PUBLIC_FEEDBACK_URL =
            "https://github.com/g-o-d-v";
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 15_000;

    private static final ExecutorService NETWORK_EXECUTOR =
            Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "mypdf-formsubmit-feedback");
                thread.setDaemon(true);
                return thread;
            });
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    private FormSubmitFeedbackService() {
    }

    public interface Callback {
        void onSuccess(String feedbackId);

        void onFailure(SubmissionException error);
    }

    public enum FailureReason {
        TIMEOUT,
        NETWORK,
        HTTP_ERROR,
        ACTIVATION_REQUIRED,
        SERVICE_REJECTED,
        INVALID_RESPONSE
    }

    public static final class SubmissionException extends Exception {
        private final FailureReason reason;
        private final int httpCode;

        SubmissionException(FailureReason reason, String message) {
            this(reason, message, -1, null);
        }

        SubmissionException(
                FailureReason reason,
                String message,
                int httpCode,
                Throwable cause
        ) {
            super(message, cause);
            this.reason = reason;
            this.httpCode = httpCode;
        }

        public FailureReason getReason() {
            return reason;
        }

        public int getHttpCode() {
            return httpCode;
        }
    }

    public static void submit(
            Context context,
            String categoryValue,
            String categoryLabel,
            String description,
            String reproductionSteps,
            String contactEmail,
            Callback callback
    ) {
        Context appContext = context.getApplicationContext();
        String feedbackId = createFeedbackId();
        NETWORK_EXECUTOR.execute(() -> {
            try {
                JSONObject jsonParam = buildPayload(
                        appContext,
                        feedbackId,
                        categoryValue,
                        categoryLabel,
                        description,
                        reproductionSteps,
                        contactEmail
                );
                postJson(jsonParam);
                MAIN_HANDLER.post(() -> callback.onSuccess(feedbackId));
            } catch (SubmissionException error) {
                MAIN_HANDLER.post(() -> callback.onFailure(error));
            } catch (Exception error) {
                SubmissionException wrapped = new SubmissionException(
                        FailureReason.NETWORK,
                        "反馈请求失败",
                        -1,
                        error
                );
                MAIN_HANDLER.post(() -> callback.onFailure(wrapped));
            }
        });
    }

    public static String buildDeviceSummary(Context context) {
        return "应用版本：" + versionName(context) + " (" + versionCode(context) + ")\n"
                + "构建类型：" + BuildConfig.BUILD_TYPE + "\n"
                + "Android：" + safe(Build.VERSION.RELEASE)
                + " (API " + Build.VERSION.SDK_INT + ")\n"
                + "设备：" + safe(Build.MANUFACTURER) + " " + safe(Build.MODEL) + "\n"
                + "ABI：" + primaryAbi() + "\n"
                + "系统语言：" + safe(Locale.getDefault().toLanguageTag()) + "\n"
                + "匿名崩溃报告："
                + (CrashReportingManager.isEnabled(context) ? "已开启" : "未开启");
    }

    private static JSONObject buildPayload(
            Context context,
            String feedbackId,
            String categoryValue,
            String categoryLabel,
            String description,
            String reproductionSteps,
            String contactEmail
    ) throws Exception {
        JSONObject jsonParam = new JSONObject();
        jsonParam.put("_subject", "[MyPDF反馈][" + categoryLabel + "] " + feedbackId);
        jsonParam.put("_template", "table");
        jsonParam.put("_captcha", "false");
        // Android 请求没有网页 Referer。_url 仅用于提供公开来源标识。
        // _next 是普通网页表单的“提交成功后跳转地址”，AJAX 请求不需要它；
        // 首次激活必须通过 docs/formsubmit-activate.html 的真实网页表单完成。
        jsonParam.put("_url", PUBLIC_FEEDBACK_URL);

        // 字段名使用固定英文标识，避免用户输入成为参数名。
        jsonParam.put("feedback_id", feedbackId);
        jsonParam.put("category", categoryValue);
        jsonParam.put("category_label", categoryLabel);
        jsonParam.put("description", description);
        jsonParam.put(
                "reproduction_steps",
                reproductionSteps.isEmpty() ? "未提供" : reproductionSteps
        );
        jsonParam.put(
                "contact_email",
                contactEmail.isEmpty() ? "未提供" : contactEmail
        );
        if (!contactEmail.isEmpty()) {
            jsonParam.put("_replyto", contactEmail);
        }

        jsonParam.put("app_version_name", versionName(context));
        jsonParam.put("app_version_code", versionCode(context));
        jsonParam.put("build_type", BuildConfig.DEBUG ? "debug" : "release");
        jsonParam.put("android_version", safe(Build.VERSION.RELEASE));
        jsonParam.put("android_api", Build.VERSION.SDK_INT);
        jsonParam.put("manufacturer", safe(Build.MANUFACTURER));
        jsonParam.put("device_model", safe(Build.MODEL));
        jsonParam.put("abi", primaryAbi());
        jsonParam.put("locale", safe(Locale.getDefault().toLanguageTag()));
        jsonParam.put(
                "crash_reporting_enabled",
                CrashReportingManager.isEnabled(context) ? "true" : "false"
        );
        jsonParam.put("schema_version", "2");
        return jsonParam;
    }

    private static void postJson(JSONObject payload) throws SubmissionException {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(ENDPOINT).openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setDoOutput(true);
            connection.setUseCaches(false);
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", "MyPDF-Android/" + BuildConfig.VERSION_NAME);
            connection.setRequestProperty("Origin", "https://g-o-d-v.github.io");
            connection.setRequestProperty("Referer", "https://g-o-d-v.github.io/");

            byte[] body = payload.toString().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(body.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(body);
                output.flush();
            }

            int statusCode = connection.getResponseCode();
            String responseBody = readResponse(
                    statusCode >= 200 && statusCode < 300
                            ? connection.getInputStream()
                            : connection.getErrorStream()
            );

            if (statusCode < 200 || statusCode >= 300) {
                String serviceMessage = extractMessage(
                        responseBody,
                        "反馈服务返回 HTTP " + statusCode
                );
                Log.w(TAG, "FormSubmit HTTP " + statusCode + ": " + serviceMessage);
                throw new SubmissionException(
                        FailureReason.HTTP_ERROR,
                        serviceMessage,
                        statusCode,
                        null
                );
            }

            if (responseBody.trim().isEmpty()) {
                throw new SubmissionException(
                        FailureReason.INVALID_RESPONSE,
                        "反馈服务返回了空响应"
                );
            }

            JSONObject response = new JSONObject(responseBody);
            Object successValue = response.opt("success");
            boolean success = successValue instanceof Boolean
                    ? (Boolean) successValue
                    : "true".equalsIgnoreCase(String.valueOf(successValue));
            if (!success) {
                String message = response.optString(
                        "message",
                        "反馈服务拒绝了本次提交"
                ).trim();
                if (isActivationRequired(message)) {
                    throw new SubmissionException(
                            FailureReason.ACTIVATION_REQUIRED,
                            message.isEmpty() ? "反馈通道尚未完成首次激活" : message
                    );
                }
                String rejectedMessage = message.isEmpty()
                        ? "反馈服务拒绝了本次提交"
                        : message;
                Log.w(TAG, "FormSubmit rejected request: " + rejectedMessage);
                throw new SubmissionException(
                        FailureReason.SERVICE_REJECTED,
                        rejectedMessage
                );
            }
        } catch (SocketTimeoutException error) {
            throw new SubmissionException(
                    FailureReason.TIMEOUT,
                    "连接反馈服务超时",
                    -1,
                    error
            );
        } catch (SubmissionException error) {
            throw error;
        } catch (IOException error) {
            throw new SubmissionException(
                    FailureReason.NETWORK,
                    "无法连接反馈服务",
                    -1,
                    error
            );
        } catch (Exception error) {
            throw new SubmissionException(
                    FailureReason.INVALID_RESPONSE,
                    "无法解析反馈服务响应",
                    -1,
                    error
            );
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }


    private static boolean isActivationRequired(String message) {
        if (message == null) return false;
        String normalized = message.toLowerCase(Locale.ROOT);
        return normalized.contains("activate")
                || normalized.contains("activation")
                || normalized.contains("not active")
                || normalized.contains("confirm your email")
                || normalized.contains("check your email");
    }

    private static String readResponse(InputStream stream) throws IOException {
        if (stream == null) return "";
        StringBuilder result = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (result.length() > 32_768) break;
                result.append(line);
            }
        }
        return result.toString();
    }

    private static String extractMessage(String responseBody, String fallback) {
        if (responseBody == null || responseBody.trim().isEmpty()) return fallback;
        try {
            String message = new JSONObject(responseBody).optString("message", "").trim();
            if (!message.isEmpty()) return message;
        } catch (Exception ignored) {
        }
        String compact = responseBody.replaceAll("\\s+", " ").trim();
        return compact.length() > 240 ? compact.substring(0, 240) : compact;
    }

    private static String createFeedbackId() {
        SimpleDateFormat formatter = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US);
        formatter.setTimeZone(TimeZone.getTimeZone("UTC"));
        String suffix = UUID.randomUUID()
                .toString()
                .replace("-", "")
                .substring(0, 8)
                .toUpperCase(Locale.ROOT);
        return "MYPDF-" + formatter.format(new Date()) + "-" + suffix;
    }

    private static String versionName(Context context) {
        try {
            PackageInfo info = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0);
            return info.versionName == null ? BuildConfig.VERSION_NAME : info.versionName;
        } catch (Exception ignored) {
            return BuildConfig.VERSION_NAME;
        }
    }

    @SuppressWarnings("deprecation")
    private static long versionCode(Context context) {
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

    private static String primaryAbi() {
        String[] abis = Build.SUPPORTED_ABIS;
        return abis == null || abis.length == 0 ? "unknown" : safe(abis[0]);
    }

    private static String safe(String value) {
        if (value == null || value.trim().isEmpty()) {
            return "unknown";
        }
        String trimmed = value.trim();
        return trimmed.length() > 120 ? trimmed.substring(0, 120) : trimmed;
    }
}
