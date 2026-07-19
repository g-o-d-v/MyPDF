package com.nless.mypdf;

import android.content.Context;
import android.net.Uri;
import android.text.TextUtils;

import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.PDDocumentCatalog;
import com.tom_roush.pdfbox.pdmodel.PDDocumentInformation;
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission;
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy;

import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;

/** PDF 密码、权限和元数据处理。 */
public class PdfProtectionService {

    public interface ProgressCallback {
        void onProgress(String message);
    }

    public static class InspectResult {
        public boolean encrypted;
        public boolean requiresOpenPassword;
        public boolean ownerPermission;
        public boolean hasRestrictions;
        public boolean permissionsKnown;
        public boolean allowPrint;
        public boolean allowCopy;
        public boolean allowModify;
        public boolean allowAnnotate;
        public int pageCount;
        public String summary;
    }

    public static class PermissionOptions {
        public boolean allowPrint;
        public boolean allowCopy;
        public boolean allowModify;
        public boolean allowAnnotate;
    }

    private final Context context;

    public PdfProtectionService(Context context) {
        this.context = context.getApplicationContext();
    }

    public InspectResult inspect(Uri uri) {
        InspectResult result = new InspectResult();
        result.encrypted = false;
        result.requiresOpenPassword = false;
        result.ownerPermission = true;
        result.hasRestrictions = false;
        result.permissionsKnown = false;
        result.allowPrint = true;
        result.allowCopy = true;
        result.allowModify = true;
        result.allowAnnotate = true;
        result.pageCount = -1;
        result.summary = "无法读取页数";
        if (uri == null) return result;
        try {
            PdfSecurityContext.Info info = PdfSecurityContext.inspect(context, uri, null, "");
            result.encrypted = info.encrypted;
            result.requiresOpenPassword = false;
            result.ownerPermission = info.ownerPermission;
            result.hasRestrictions = info.hasRestrictions();
            result.permissionsKnown = true;
            result.allowPrint = info.canPrint;
            result.allowCopy = info.canExtractContent;
            result.allowModify = info.canModify;
            result.allowAnnotate = info.canModifyAnnotations;
            result.pageCount = info.pageCount;
            result.summary = info.pageCount + " 页 · " + (info.encrypted ? "已加密" : "未加密");
            if (result.hasRestrictions) result.summary += " · 有权限限制";
        } catch (Exception error) {
            if (PdfSecurityContext.isPasswordError(error)) {
                result.encrypted = true;
                result.requiresOpenPassword = true;
                result.ownerPermission = false;
                result.summary = "已加密，处理时需要输入当前密码";
            } else {
                result.summary = PdfSecurityContext.readableError(error);
            }
        }
        return result;
    }

    public void setOrRemovePassword(
            Uri sourceUri,
            String currentPassword,
            boolean removePassword,
            String openPassword,
            File output,
            AtomicBoolean cancelled,
            ProgressCallback callback
    ) throws Exception {
        if (callback != null) callback.onProgress("正在读取 PDF");
        try (PDDocument document = PdfSecurityContext.loadDocument(
                context,
                sourceUri,
                null,
                nullToEmpty(currentPassword),
                "pdfbox-protection-password"
        )) {
            ensureNotCancelled(cancelled);
            boolean encrypted = document.isEncrypted();
            AccessPermission currentAccess = document.getCurrentAccessPermission();
            boolean ownerAccess = currentAccess == null || currentAccess.isOwnerPermission();
            AccessPermission declared = declaredPermissions(document);
            boolean hasRestrictions = PdfSecurityContext.hasDeclaredRestrictions(document);
            if (hasRestrictions) {
                throw new IllegalStateException("该 PDF 存在权限限制。本页面只处理打开密码，请先在“权限限制”中恢复全部权限");
            }

            if (removePassword) {
                if (!encrypted || TextUtils.isEmpty(currentPassword)) {
                    throw new IllegalStateException("该 PDF 没有打开密码，无需移除");
                }
                if (!ownerAccess) {
                    throw new IllegalStateException("当前密码只能打开文档，不能移除密码");
                }
                document.setAllSecurityToBeRemoved(true);
                ensureNotCancelled(cancelled);
                if (callback != null) callback.onProgress("正在移除打开密码");
                saveDocument(document, output);
                PdfSecurityContext.validateProtectedFile(context, output, "");
                return;
            }

            if (TextUtils.isEmpty(openPassword)) {
                throw new IllegalStateException("请填写新的打开密码");
            }
            if (encrypted && !ownerAccess) {
                throw new IllegalStateException("当前密码只能打开文档，不能修改密码");
            }

            if (callback != null) callback.onProgress("正在设置打开密码");
            applyPolicy(document, openPassword, openPassword, declared);
            ensureNotCancelled(cancelled);
            if (callback != null) callback.onProgress("正在保存受保护副本");
            saveDocument(document, output);
            PdfSecurityContext.validateProtectedFile(context, output, openPassword);
        }
    }

    public void applyPermissions(
            Uri sourceUri,
            String currentOpenPassword,
            String managementPassword,
            PermissionOptions options,
            File output,
            AtomicBoolean cancelled,
            ProgressCallback callback
    ) throws Exception {
        if (TextUtils.isEmpty(managementPassword)) {
            throw new IllegalStateException("请填写权限管理密码");
        }
        if (callback != null) callback.onProgress("正在读取 PDF");
        try (PDDocument document = PdfSecurityContext.loadDocument(
                context,
                sourceUri,
                null,
                nullToEmpty(currentOpenPassword),
                "pdfbox-protection-permissions"
        )) {
            ensureNotCancelled(cancelled);
            boolean encrypted = document.isEncrypted();
            AccessPermission currentAccess = document.getCurrentAccessPermission();
            boolean ownerAccess = currentAccess == null || currentAccess.isOwnerPermission();
            if (encrypted && !ownerAccess) {
                try (PDDocument adminDocument = PdfSecurityContext.loadDocument(
                        context,
                        sourceUri,
                        null,
                        managementPassword,
                        "pdfbox-protection-permissions-admin"
                )) {
                    AccessPermission adminAccess = adminDocument.getCurrentAccessPermission();
                    if (adminAccess == null || !adminAccess.isOwnerPermission()) {
                        throw new IllegalStateException("权限管理密码不正确");
                    }
                }
            }

            boolean restricted = !options.allowPrint
                    || !options.allowCopy
                    || !options.allowModify
                    || !options.allowAnnotate;
            if (restricted && !TextUtils.isEmpty(currentOpenPassword)
                    && TextUtils.equals(currentOpenPassword, managementPassword)) {
                throw new IllegalStateException("权限管理密码不能与打开密码相同，否则权限限制可能失效");
            }

            if (callback != null) callback.onProgress("正在写入权限限制");
            AccessPermission permission = new AccessPermission();
            permission.setCanPrint(options.allowPrint);
            permission.setCanPrintFaithful(options.allowPrint);
            permission.setCanExtractContent(options.allowCopy);
            permission.setCanExtractForAccessibility(true);
            permission.setCanModify(options.allowModify);
            permission.setCanModifyAnnotations(options.allowAnnotate);
            permission.setCanFillInForm(options.allowAnnotate || options.allowModify);
            permission.setCanAssembleDocument(options.allowModify);
            applyPolicy(document, managementPassword, nullToEmpty(currentOpenPassword), permission);
            ensureNotCancelled(cancelled);
            if (callback != null) callback.onProgress("正在保存受限制副本");
            saveDocument(document, output);
            PdfSecurityContext.validateProtectedFile(context, output, currentOpenPassword);
        }
    }

    public void clearMetadata(
            Uri sourceUri,
            String currentPassword,
            boolean clearInfo,
            boolean clearXmp,
            File output,
            AtomicBoolean cancelled,
            ProgressCallback callback
    ) throws Exception {
        if (callback != null) callback.onProgress("正在读取 PDF");
        try (PDDocument document = PdfSecurityContext.loadDocument(
                context,
                sourceUri,
                null,
                nullToEmpty(currentPassword),
                "pdfbox-protection-metadata"
        )) {
            ensureNotCancelled(cancelled);
            boolean encrypted = document.isEncrypted();
            AccessPermission currentAccess = document.getCurrentAccessPermission();
            boolean ownerAccess = currentAccess == null || currentAccess.isOwnerPermission();
            if (encrypted && !ownerAccess && !currentAccess.canModify()) {
                throw new IllegalStateException("当前密码没有修改文档信息的权限");
            }
            AccessPermission declared = declaredPermissions(document);
            if (PdfSecurityContext.hasDeclaredRestrictions(document)) {
                throw new IllegalStateException("该 PDF 存在权限限制。请先恢复全部权限，再清除元数据");
            }

            if (callback != null) callback.onProgress("正在清理文档信息");
            if (clearInfo) {
                document.setDocumentInformation(new PDDocumentInformation());
            }
            if (clearXmp) {
                PDDocumentCatalog catalog = document.getDocumentCatalog();
                if (catalog != null) catalog.setMetadata(null);
            }

            if (encrypted) {
                String password = nullToEmpty(currentPassword);
                if (TextUtils.isEmpty(password)) {
                    throw new IllegalStateException("加密 PDF 需要填写当前密码后才能保存清理结果");
                }
                applyPolicy(document, password, password, declared);
            }
            ensureNotCancelled(cancelled);
            if (callback != null) callback.onProgress("正在保存清理后的副本");
            saveDocument(document, output);
            PdfSecurityContext.validateProtectedFile(
                    context,
                    output,
                    encrypted ? nullToEmpty(currentPassword) : ""
            );
        }
    }

    private AccessPermission declaredPermissions(PDDocument document) {
        if (document.isEncrypted() && document.getEncryption() != null) {
            return new AccessPermission(document.getEncryption().getPermissions());
        }
        return AccessPermission.getOwnerAccessPermission();
    }

    private void applyPolicy(
            PDDocument document,
            String ownerPassword,
            String userPassword,
            AccessPermission permission
    ) throws Exception {
        StandardProtectionPolicy policy = new StandardProtectionPolicy(
                nullToEmpty(ownerPassword),
                nullToEmpty(userPassword),
                permission
        );
        policy.setPermissions(permission);
        policy.setEncryptionKeyLength(128);
        // AES-128 + PDF 1.6 比 RC4-128 更适合现代桌面阅读器。
        policy.setPreferAES(true);
        if (document.getVersion() < 1.6f) document.setVersion(1.6f);
        document.protect(policy);
    }

    private void saveDocument(PDDocument document, File output) throws Exception {
        File parent = output.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        // save(File) 内部使用 BufferedOutputStream，避免逐小块写盘。
        document.save(output);
    }

    private void ensureNotCancelled(AtomicBoolean cancelled) {
        if (cancelled != null && cancelled.get()) {
            throw new IllegalStateException("处理已取消");
        }
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
