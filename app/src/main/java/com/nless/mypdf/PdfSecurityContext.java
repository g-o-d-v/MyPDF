package com.nless.mypdf;

import android.content.Context;
import android.net.Uri;
import android.text.TextUtils;

import androidx.documentfile.provider.DocumentFile;

import com.tom_roush.pdfbox.io.MemoryUsageSetting;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission;
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 当前应用进程内的 PDF 解锁状态、权限信息和本地源文件缓存。
 *
 * <p>密码只保存在内存中，不写入数据库或 SharedPreferences。应用进程结束后自动失效。</p>
 */
final class PdfSecurityContext {

    static final class Info {
        final boolean encrypted;
        final boolean ownerPermission;
        final boolean canExtractContent;
        final boolean canModify;
        final boolean canModifyAnnotations;
        final boolean canAssembleDocument;
        final boolean canPrint;
        final int declaredPermissionBits;
        final int pageCount;
        final String password;
        final long sourceLength;
        final long sourceModified;

        Info(
                boolean encrypted,
                boolean ownerPermission,
                boolean canExtractContent,
                boolean canModify,
                boolean canModifyAnnotations,
                boolean canAssembleDocument,
                boolean canPrint,
                int declaredPermissionBits,
                int pageCount,
                String password,
                long sourceLength,
                long sourceModified
        ) {
            this.encrypted = encrypted;
            this.ownerPermission = ownerPermission;
            this.canExtractContent = canExtractContent;
            this.canModify = canModify;
            this.canModifyAnnotations = canModifyAnnotations;
            this.canAssembleDocument = canAssembleDocument;
            this.canPrint = canPrint;
            this.declaredPermissionBits = declaredPermissionBits;
            this.pageCount = pageCount;
            this.password = password == null ? "" : password;
            this.sourceLength = sourceLength;
            this.sourceModified = sourceModified;
        }

        boolean canCopyOrExportText() {
            return !encrypted || canExtractContent;
        }

        boolean canAnnotate() {
            // 批注权限必须独立判断。允许修改页面内容并不代表允许添加批注，
            // 否则“禁止批注、允许修改”的文件仍然可以进入批注模式。
            return !encrypted || canModifyAnnotations;
        }

        boolean canManagePages() {
            return !encrypted || canAssembleDocument || canModify;
        }

        boolean canCreateSearchableLayer() {
            return !encrypted || canModify;
        }

        boolean hasRestrictions() {
            return encrypted && !(canExtractContent
                    && canModify
                    && canModifyAnnotations
                    && canAssembleDocument
                    && canPrint);
        }
    }

    private static final Map<String, Info> CACHE = new ConcurrentHashMap<>();
    private static final long SOURCE_CACHE_LIMIT = 768L * 1024L * 1024L;
    private static final int COPY_BUFFER_SIZE = 1024 * 1024;

    private PdfSecurityContext() {
    }

    static Info getCached(
            Context context,
            Uri uri,
            String fallbackPath
    ) {
        if (context == null || uri == null) return null;
        Info info = CACHE.get(uri.toString());
        if (info == null) return null;
        SourceStamp stamp = sourceStamp(context, uri, fallbackPath);
        if (!stamp.reusable
                || info.sourceLength != stamp.length
                || info.sourceModified != stamp.modified) {
            CACHE.remove(uri.toString());
            return null;
        }
        return info;
    }

    static Info getCached(
            Context context,
            Uri uri,
            String fallbackPath,
            String password
    ) {
        if (context == null || uri == null) return null;
        Info info = CACHE.get(uri.toString());
        if (info == null) return null;
        String expectedPassword = password == null ? "" : password;
        if (!TextUtils.equals(info.password, expectedPassword)) return null;
        SourceStamp stamp = sourceStamp(context, uri, fallbackPath);
        if (!stamp.reusable
                || info.sourceLength != stamp.length
                || info.sourceModified != stamp.modified) {
            CACHE.remove(uri.toString());
            return null;
        }
        return info;
    }

    static void cache(Uri uri, Info info) {
        if (uri != null && info != null) CACHE.put(uri.toString(), info);
    }

    static void clear(Uri uri) {
        if (uri != null) CACHE.remove(uri.toString());
    }

    static Info inspect(Context context, Uri uri, String fallbackPath, String password) throws Exception {
        SourceStamp stamp = sourceStamp(context, uri, fallbackPath);
        File localSource = materializeSource(context, uri, fallbackPath);
        try (PDDocument document = loadFile(
                localSource,
                password,
                memoryUsage(context, "pdfbox-security-inspect", localSource.length())
        )) {
            boolean encrypted = document.isEncrypted();
            AccessPermission effective = document.getCurrentAccessPermission();
            boolean owner = effective == null || effective.isOwnerPermission();
            int declaredBits;
            AccessPermission declared;
            if (encrypted && document.getEncryption() != null) {
                declaredBits = document.getEncryption().getPermissions();
                declared = new AccessPermission(declaredBits);
            } else {
                declared = AccessPermission.getOwnerAccessPermission();
                declaredBits = declared.getPermissionBytes();
            }
            AccessPermission permission = encrypted ? declared : effective;
            if (permission == null) permission = declared;
            Info info = new Info(
                    encrypted,
                    owner,
                    permission.canExtractContent(),
                    permission.canModify(),
                    permission.canModifyAnnotations(),
                    permission.canAssembleDocument(),
                    permission.canPrint(),
                    declaredBits,
                    document.getNumberOfPages(),
                    password,
                    stamp.length,
                    stamp.modified
            );
            cache(uri, info);
            return info;
        }
    }

    static File createDecryptedTemp(
            Context context,
            Uri uri,
            String fallbackPath,
            String password,
            String prefix
    ) throws Exception {
        File directory = new File(context.getCacheDir(), "unlocked-pdf");
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IOException("无法创建临时解锁目录");
        }
        File output = File.createTempFile(prefix == null ? "unlocked-" : prefix, ".pdf", directory);
        File localSource = materializeSource(context, uri, fallbackPath);
        boolean success = false;
        try (PDDocument document = loadFile(
                localSource,
                password,
                memoryUsage(context, "pdfbox-security-unlock", localSource.length())
        )) {
            document.setAllSecurityToBeRemoved(true);
            document.save(output);
            success = true;
            return output;
        } finally {
            if (!success && output.exists()) output.delete();
        }
    }

    static PDDocument loadDocument(
            Context context,
            Uri uri,
            String fallbackPath,
            String password,
            String scratchName
    ) throws Exception {
        File localSource = materializeSource(context, uri, fallbackPath);
        return loadFile(
                localSource,
                password,
                memoryUsage(context, scratchName, localSource.length())
        );
    }

    static PDDocument load(InputStream input, String password, MemoryUsageSetting memoryUsageSetting)
            throws Exception {
        if (TextUtils.isEmpty(password)) {
            return PDDocument.load(input, memoryUsageSetting);
        }
        return PDDocument.load(input, password, memoryUsageSetting);
    }

    private static PDDocument loadFile(
            File file,
            String password,
            MemoryUsageSetting memoryUsageSetting
    ) throws Exception {
        if (TextUtils.isEmpty(password)) {
            return PDDocument.load(file, memoryUsageSetting);
        }
        return PDDocument.load(file, password, memoryUsageSetting);
    }

    static InputStream openInput(Context context, Uri uri, String fallbackPath) throws IOException {
        File local = directFile(uri, fallbackPath);
        if (local != null) return new BufferedInputStream(new FileInputStream(local), COPY_BUFFER_SIZE);
        InputStream input = null;
        if (uri != null && "content".equalsIgnoreCase(uri.getScheme())) {
            input = context.getContentResolver().openInputStream(uri);
        }
        if (input == null) throw new IOException("无法读取所选 PDF 文件");
        return new BufferedInputStream(input, COPY_BUFFER_SIZE);
    }

    /**
     * 将 SAF 的顺序流只复制一次到应用缓存，后续检查、解密和保存都用文件随机访问。
     */
    static File materializeSource(Context context, Uri uri, String fallbackPath) throws IOException {
        File direct = directFile(uri, fallbackPath);
        if (direct != null) return direct;
        if (uri == null) throw new IOException("无法读取所选 PDF 文件");

        DocumentFile documentFile = DocumentFile.fromSingleUri(context, uri);
        long expectedLength = documentFile == null ? -1L : documentFile.length();
        long modified = documentFile == null ? 0L : documentFile.lastModified();
        String key = uri + "|" + expectedLength + "|" + modified;

        File directory = new File(context.getCacheDir(), "pdf-source-cache");
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IOException("无法创建 PDF 源文件缓存");
        }
        File output = new File(directory, sha256(key) + ".pdf");
        boolean metadataReliable = expectedLength >= 0L && modified > 0L;
        if (metadataReliable
                && output.isFile()
                && output.length() > 0
                && output.length() == expectedLength) {
            output.setLastModified(System.currentTimeMillis());
            return output;
        }

        File temp = new File(directory, output.getName() + ".part");
        if (temp.exists()) temp.delete();
        try (InputStream rawInput = context.getContentResolver().openInputStream(uri)) {
            if (rawInput == null) throw new IOException("无法读取所选 PDF 文件");
            try (BufferedInputStream input = new BufferedInputStream(rawInput, COPY_BUFFER_SIZE);
                 BufferedOutputStream fileOutput = new BufferedOutputStream(
                         new FileOutputStream(temp, false), COPY_BUFFER_SIZE)) {
                byte[] buffer = new byte[COPY_BUFFER_SIZE];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read > 0) fileOutput.write(buffer, 0, read);
                }
                fileOutput.flush();
            }
        } catch (Throwable error) {
            temp.delete();
            if (error instanceof IOException) throw (IOException) error;
            throw new IOException("复制 PDF 到本地缓存失败", error);
        }
        if (!temp.renameTo(output)) {
            copyFile(temp, output);
            temp.delete();
        }
        pruneSourceCache(directory, output);
        return output;
    }

    static MemoryUsageSetting memoryUsage(Context context, String name) {
        return memoryUsage(context, name, -1L);
    }

    static MemoryUsageSetting memoryUsage(Context context, String name, long sourceLength) {
        File directory = new File(context.getCacheDir(), name == null ? "pdfbox-security" : name);
        if (!directory.exists()) directory.mkdirs();
        long memoryLimit;
        if (sourceLength > 0 && sourceLength <= 96L * 1024L * 1024L) {
            memoryLimit = 64L * 1024L * 1024L;
        } else if (sourceLength > 0 && sourceLength <= 384L * 1024L * 1024L) {
            memoryLimit = 48L * 1024L * 1024L;
        } else {
            memoryLimit = 32L * 1024L * 1024L;
        }
        return MemoryUsageSetting.setupMixed(memoryLimit).setTempDir(directory);
    }

    static boolean isPasswordError(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof InvalidPasswordException
                    || "PdfPasswordException".equals(current.getClass().getSimpleName())) {
                return true;
            }
            String message = current.getMessage();
            if (message != null) {
                String lower = message.toLowerCase();
                if (lower.contains("cannot decrypt")
                        || lower.contains("password is incorrect")
                        || lower.contains("invalid password")
                        || lower.contains("password required")
                        || lower.contains("password was not supplied")) {
                    return true;
                }
            }
            if (current.getCause() == current) break;
            current = current.getCause();
        }
        return false;
    }

    static boolean hasDeclaredRestrictions(PDDocument document) {
        if (document == null || !document.isEncrypted() || document.getEncryption() == null) return false;
        AccessPermission permission = new AccessPermission(document.getEncryption().getPermissions());
        return !(permission.canExtractContent()
                && permission.canModify()
                && permission.canModifyAnnotations()
                && permission.canAssembleDocument()
                && permission.canPrint());
    }

    static void protectFileWithPolicy(
            Context context,
            File file,
            String userPassword,
            String ownerPassword,
            int declaredPermissionBits
    ) throws Exception {
        if (file == null || !file.isFile()) throw new IOException("待保护 PDF 不存在");
        File replacement = File.createTempFile("reprotected-policy-", ".pdf", file.getParentFile());
        try {
            try (PDDocument document = PDDocument.load(
                    file,
                    memoryUsage(context, "pdfbox-security-reprotect-policy", file.length())
            )) {
                AccessPermission permission = new AccessPermission(declaredPermissionBits);
                com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy policy =
                        new com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy(
                                ownerPassword == null ? "" : ownerPassword,
                                userPassword == null ? "" : userPassword,
                                permission
                        );
                policy.setPermissions(permission);
                policy.setEncryptionKeyLength(128);
                policy.setPreferAES(true);
                if (document.getVersion() < 1.6f) document.setVersion(1.6f);
                document.protect(policy);
                document.save(replacement);
            }
            copyFile(replacement, file);
        } finally {
            if (replacement.exists()) replacement.delete();
        }
    }

    static void protectFileWithPassword(Context context, File file, String password) throws Exception {
        if (file == null || !file.isFile()) throw new IOException("待保护 PDF 不存在");
        File replacement = File.createTempFile("reprotected-", ".pdf", file.getParentFile());
        try {
            try (PDDocument document = PDDocument.load(
                    file,
                    memoryUsage(context, "pdfbox-security-reprotect", file.length())
            )) {
                AccessPermission permission = AccessPermission.getOwnerAccessPermission();
                com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy policy =
                        new com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy(
                                password == null ? "" : password,
                                password == null ? "" : password,
                                permission
                        );
                policy.setPermissions(permission);
                policy.setEncryptionKeyLength(128);
                policy.setPreferAES(true);
                if (document.getVersion() < 1.6f) document.setVersion(1.6f);
                document.protect(policy);
                document.save(replacement);
            }
            copyFile(replacement, file);
        } finally {
            if (replacement.exists()) replacement.delete();
        }
    }

    static void validateProtectedFile(
            Context context,
            File file,
            String userPassword
    ) throws Exception {
        if (file == null || !file.isFile() || file.length() <= 0L) {
            throw new IOException("生成的 PDF 文件为空");
        }
        try (PDDocument ignored = loadFile(
                file,
                userPassword == null ? "" : userPassword,
                memoryUsage(context, "pdfbox-security-validate", file.length())
        )) {
            if (ignored.getNumberOfPages() <= 0) {
                throw new IOException("生成的 PDF 没有有效页面");
            }
        }
    }

    static String readableError(Throwable error) {
        Throwable current = error;
        while (current != null && current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        if (current == null) return "未知错误";
        if (current instanceof InvalidPasswordException || isPasswordError(current)) {
            return "密码不正确，无法打开 PDF";
        }
        String message = current.getMessage();
        if (message == null || message.trim().isEmpty()) return current.getClass().getSimpleName();
        if (message.contains("PDF contains an encryption dictionary")) {
            return "该 PDF 仍处于加密状态，当前操作未正确处理密码或权限";
        }
        return message;
    }

    private static SourceStamp sourceStamp(Context context, Uri uri, String fallbackPath) {
        File direct = directFile(uri, fallbackPath);
        if (direct != null) {
            return new SourceStamp(direct.length(), direct.lastModified(), true);
        }
        if (context != null && uri != null && "content".equalsIgnoreCase(uri.getScheme())) {
            try {
                DocumentFile file = DocumentFile.fromSingleUri(context, uri);
                if (file != null) {
                    long length = file.length();
                    long modified = file.lastModified();
                    // 某些文件提供器不返回修改时间。此时无法可靠判断同一 URI
                    // 是否已被覆盖，因此不复用旧权限缓存，避免复制/导出判断过期。
                    boolean reusable = length >= 0L && modified > 0L;
                    return new SourceStamp(length, modified, reusable);
                }
            } catch (Throwable ignore) {
            }
        }
        return new SourceStamp(-1L, 0L, false);
    }

    private static final class SourceStamp {
        final long length;
        final long modified;
        final boolean reusable;

        SourceStamp(long length, long modified, boolean reusable) {
            this.length = length;
            this.modified = modified;
            this.reusable = reusable;
        }
    }

    private static File directFile(Uri uri, String fallbackPath) {
        if (uri != null && uri.getPath() != null && !"content".equalsIgnoreCase(uri.getScheme())) {
            File file = new File(uri.getPath());
            if (file.isFile()) return file;
        }
        if (!TextUtils.isEmpty(fallbackPath)) {
            File file = new File(fallbackPath);
            if (file.isFile()) return file;
        }
        return null;
    }

    private static void copyFile(File source, File destination) throws IOException {
        try (BufferedInputStream input = new BufferedInputStream(
                new FileInputStream(source), COPY_BUFFER_SIZE);
             BufferedOutputStream output = new BufferedOutputStream(
                     new FileOutputStream(destination, false), COPY_BUFFER_SIZE)) {
            byte[] buffer = new byte[COPY_BUFFER_SIZE];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) output.write(buffer, 0, read);
            }
            output.flush();
        }
    }

    private static String sha256(String value) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder output = new StringBuilder(bytes.length * 2);
            for (byte item : bytes) output.append(String.format(java.util.Locale.ROOT, "%02x", item));
            return output.toString();
        } catch (Exception error) {
            throw new IOException("无法生成 PDF 缓存标识", error);
        }
    }

    private static void pruneSourceCache(File directory, File keep) {
        File[] files = directory.listFiles(file -> file.isFile() && file.getName().endsWith(".pdf"));
        if (files == null || files.length == 0) return;
        Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed());
        long total = 0L;
        int keptCount = 0;
        for (File file : files) {
            if (file.equals(keep)) {
                total += file.length();
                keptCount++;
                continue;
            }
            total += file.length();
            keptCount++;
            if (keptCount > 12 || total > SOURCE_CACHE_LIMIT) {
                file.delete();
            }
        }
    }
}
