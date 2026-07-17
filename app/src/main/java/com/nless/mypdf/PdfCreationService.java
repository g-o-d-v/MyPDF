package com.nless.mypdf;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapRegionDecoder;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.net.Uri;

import androidx.exifinterface.media.ExifInterface;

import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.PDDocumentInformation;
import com.tom_roush.pdfbox.pdmodel.PDPage;
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream;
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle;
import com.tom_roush.pdfbox.pdmodel.font.PDFont;
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory;
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 创建 PDF 的底层实现。所有方法都应在后台线程调用。 */
final class PdfCreationService {

    interface ProgressCallback {
        void onProgress(int current, int total, String message);
    }

    enum PageSize {
        FIT_IMAGE("适应图片"),
        A4("A4"),
        A5("A5"),
        LETTER("Letter");

        final String label;
        PageSize(String label) { this.label = label; }
    }

    enum Orientation {
        AUTO("自动"),
        PORTRAIT("纵向"),
        LANDSCAPE("横向");

        final String label;
        Orientation(String label) { this.label = label; }
    }

    enum TextAlignment {
        LEFT("左对齐"),
        CENTER("居中"),
        RIGHT("右对齐");

        final String label;
        TextAlignment(String label) { this.label = label; }
    }

    static final class ImageOptions {
        PageSize pageSize = PageSize.FIT_IMAGE;
        Orientation orientation = Orientation.AUTO;
        float marginPoints = 0f;
        float jpegQuality = 0.85f;
    }

    static final class TextOptions {
        PageSize pageSize = PageSize.A4;
        Orientation orientation = Orientation.PORTRAIT;
        float fontSize = 14f;
        float lineSpacing = 1.5f;
        float marginPoints = 54f;
        TextAlignment alignment = TextAlignment.LEFT;
    }

    static final class BlankOptions {
        PageSize pageSize = PageSize.A4;
        Orientation orientation = Orientation.PORTRAIT;
        int pageCount = 1;
    }

    private enum SupportedImageFormat {
        JPEG,
        PNG,
        WEBP
    }

    private PdfCreationService() {}

    static boolean isSupportedImage(ContentResolver resolver, Uri uri) {
        try {
            return detectSupportedImageFormat(resolver, uri) != null;
        } catch (IOException ignore) {
            return false;
        }
    }

    static void createImagePdf(
            Context context,
            List<Uri> imageUris,
            File target,
            String title,
            ImageOptions options,
            ProgressCallback callback
    ) throws Exception {
        if (imageUris == null || imageUris.isEmpty()) {
            throw new IllegalArgumentException("请至少选择一张图片");
        }

        ContentResolver resolver = context.getContentResolver();
        try (PDDocument document = new PDDocument()) {
            applyDocumentInfo(document, title);
            int totalImages = imageUris.size();
            for (int imageIndex = 0; imageIndex < totalImages; imageIndex++) {
                Uri uri = imageUris.get(imageIndex);
                ImageInfo info = readImageInfo(resolver, uri);
                List<ImageSlice> slices = buildImageSlices(info, options);
                int sliceCount = slices.size();

                for (int sliceIndex = 0; sliceIndex < sliceCount; sliceIndex++) {
                    ImageSlice slice = slices.get(sliceIndex);
                    String segmentText = sliceCount > 1
                            ? "，分段 " + (sliceIndex + 1) + " / " + sliceCount
                            : "";
                    notifyProgress(
                            callback,
                            imageIndex,
                            totalImages,
                            "正在处理第 " + (imageIndex + 1) + " / " + totalImages
                                    + " 张图片" + segmentText
                    );

                    Bitmap bitmap = decodeImageSlice(
                            resolver,
                            uri,
                            info,
                            slice,
                            qualityTargetWidth(options.jpegQuality)
                    );
                    Bitmap flattened = null;
                    try {
                        Bitmap source = bitmap;
                        if (bitmap.hasAlpha()) {
                            flattened = flattenOnWhite(bitmap);
                            source = flattened;
                        }

                        PDRectangle pageRectangle = resolveImageSlicePageRectangle(info, slice, options);
                        PDPage page = new PDPage(pageRectangle);
                        document.addPage(page);

                        float margin = Math.max(0f, options.marginPoints);
                        float availableWidth = Math.max(1f, pageRectangle.getWidth() - margin * 2f);
                        float availableHeight = Math.max(1f, pageRectangle.getHeight() - margin * 2f);
                        float scale = Math.min(
                                availableWidth / source.getWidth(),
                                availableHeight / source.getHeight()
                        );
                        float drawWidth = source.getWidth() * scale;
                        float drawHeight = source.getHeight() * scale;
                        float x = (pageRectangle.getWidth() - drawWidth) / 2f;
                        float y;
                        if (sliceCount > 1 && options.pageSize != PageSize.FIT_IMAGE) {
                            // 固定纸张中的长图分段从页顶开始连续排布，最后一页只在底部留白。
                            y = pageRectangle.getHeight() - margin - drawHeight;
                        } else {
                            y = (pageRectangle.getHeight() - drawHeight) / 2f;
                        }

                        PDImageXObject image = JPEGFactory.createFromImage(
                                document,
                                source,
                                options.jpegQuality
                        );
                        try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                            stream.drawImage(image, x, y, drawWidth, drawHeight);
                        }
                    } finally {
                        if (flattened != null && flattened != bitmap && !flattened.isRecycled()) {
                            flattened.recycle();
                        }
                        if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
                    }
                }
                notifyProgress(
                        callback,
                        imageIndex + 1,
                        totalImages,
                        "已完成第 " + (imageIndex + 1) + " / " + totalImages + " 张图片"
                );
            }
            notifyProgress(callback, totalImages, totalImages, "正在保存 PDF");
            document.save(target);
        }
    }

    static void createTextPdf(
            String text,
            File target,
            String title,
            TextOptions options,
            ProgressCallback callback
    ) throws Exception {
        String normalized = text == null ? "" : text
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .replace("\t", "    ")
                .replace("\u0000", "");
        if (normalized.trim().isEmpty()) {
            throw new IllegalArgumentException("请输入要转换的文本");
        }

        try (PDDocument document = new PDDocument();
             PdfSystemFontResolver fontResolver = new PdfSystemFontResolver(document, "PdfCreateTextFont")) {
            applyDocumentInfo(document, title);
            PDFont font = fontResolver.resolve(normalized, false);
            PDRectangle pageRectangle = resolveFixedPageRectangle(options.pageSize, options.orientation);
            float fontSize = Math.max(8f, Math.min(36f, options.fontSize));
            float lineHeight = fontSize * Math.max(1.05f, options.lineSpacing);
            float margin = Math.max(18f, options.marginPoints);
            float contentWidth = pageRectangle.getWidth() - margin * 2f;
            if (contentWidth < fontSize * 2f) {
                throw new IllegalArgumentException("页面边距过大，无法排版文本");
            }

            List<String> lines = wrapText(normalized, font, fontSize, contentWidth);
            int total = Math.max(1, lines.size());
            PDPage page = null;
            PDPageContentStream stream = null;
            float baseline = 0f;
            try {
                for (int i = 0; i < lines.size(); i++) {
                    if (stream == null || baseline < margin + fontSize) {
                        if (stream != null) stream.close();
                        page = new PDPage(pageRectangle);
                        document.addPage(page);
                        stream = new PDPageContentStream(document, page);
                        baseline = pageRectangle.getHeight() - margin - fontSize;
                    }

                    String line = lines.get(i);
                    if (!line.isEmpty()) {
                        float lineWidth = stringWidth(font, line, fontSize);
                        float x;
                        if (options.alignment == TextAlignment.CENTER) {
                            x = margin + Math.max(0f, (contentWidth - lineWidth) / 2f);
                        } else if (options.alignment == TextAlignment.RIGHT) {
                            x = margin + Math.max(0f, contentWidth - lineWidth);
                        } else {
                            x = margin;
                        }
                        stream.beginText();
                        stream.setFont(font, fontSize);
                        stream.newLineAtOffset(x, baseline);
                        stream.showText(line);
                        stream.endText();
                    }
                    baseline -= lineHeight;
                    if (i == 0 || i == lines.size() - 1 || i % 20 == 0) {
                        notifyProgress(callback, i + 1, total,
                                "正在排版文本 " + (i + 1) + " / " + total);
                    }
                }
            } finally {
                if (stream != null) stream.close();
            }

            notifyProgress(callback, total, total, "正在保存 PDF");
            document.save(target);
        }
    }

    static void createBlankPdf(
            File target,
            String title,
            BlankOptions options,
            ProgressCallback callback
    ) throws Exception {
        int count = Math.max(1, Math.min(200, options.pageCount));
        PDRectangle pageRectangle = resolveFixedPageRectangle(options.pageSize, options.orientation);
        try (PDDocument document = new PDDocument()) {
            applyDocumentInfo(document, title);
            for (int i = 0; i < count; i++) {
                document.addPage(new PDPage(pageRectangle));
                if (i == 0 || i == count - 1 || i % 20 == 0) {
                    notifyProgress(callback, i + 1, count,
                            "正在创建页面 " + (i + 1) + " / " + count);
                }
            }
            document.save(target);
        }
    }

    private static void applyDocumentInfo(PDDocument document, String title) {
        PDDocumentInformation info = document.getDocumentInformation();
        if (title != null && !title.trim().isEmpty()) info.setTitle(removePdfSuffix(title.trim()));
        info.setCreator("MyPDF");
        info.setProducer("MyPDF / PdfBox-Android");
    }

    private static String removePdfSuffix(String name) {
        return name.toLowerCase(Locale.ROOT).endsWith(".pdf")
                ? name.substring(0, name.length() - 4)
                : name;
    }

    private static void notifyProgress(ProgressCallback callback, int current, int total, String message) {
        if (callback != null) callback.onProgress(current, total, message);
    }

    private static final float FIT_IMAGE_PAGE_WIDTH = 595f;
    private static final float MAX_FIT_IMAGE_SEGMENT_RATIO = 4f;
    private static final float MAX_PDF_PAGE_POINTS = 14400f;

    private static final class ImageInfo {
        final int rawWidth;
        final int rawHeight;
        final int rotation;
        final int displayWidth;
        final int displayHeight;

        ImageInfo(int rawWidth, int rawHeight, int rotation) {
            this.rawWidth = rawWidth;
            this.rawHeight = rawHeight;
            this.rotation = rotation;
            boolean swapped = rotation == 90 || rotation == 270;
            this.displayWidth = swapped ? rawHeight : rawWidth;
            this.displayHeight = swapped ? rawWidth : rawHeight;
        }
    }

    private static final class ImageSlice {
        final int top;
        final int bottom;

        ImageSlice(int top, int bottom) {
            this.top = top;
            this.bottom = bottom;
        }

        int height() {
            return Math.max(1, bottom - top);
        }
    }

    private static SupportedImageFormat detectSupportedImageFormat(
            ContentResolver resolver,
            Uri uri
    ) throws IOException {
        if (resolver == null || uri == null) return null;

        byte[] header = new byte[12];
        int length = 0;
        try (InputStream input = resolver.openInputStream(uri)) {
            if (input == null) return null;
            while (length < header.length) {
                int read = input.read(header, length, header.length - length);
                if (read < 0) break;
                if (read == 0) continue;
                length += read;
            }
        }

        if (length >= 3
                && (header[0] & 0xFF) == 0xFF
                && (header[1] & 0xFF) == 0xD8
                && (header[2] & 0xFF) == 0xFF) {
            return SupportedImageFormat.JPEG;
        }

        if (length >= 8
                && (header[0] & 0xFF) == 0x89
                && header[1] == 0x50
                && header[2] == 0x4E
                && header[3] == 0x47
                && header[4] == 0x0D
                && header[5] == 0x0A
                && header[6] == 0x1A
                && header[7] == 0x0A) {
            return SupportedImageFormat.PNG;
        }

        if (length >= 12
                && header[0] == 'R'
                && header[1] == 'I'
                && header[2] == 'F'
                && header[3] == 'F'
                && header[8] == 'W'
                && header[9] == 'E'
                && header[10] == 'B'
                && header[11] == 'P') {
            return SupportedImageFormat.WEBP;
        }

        return null;
    }

    private static ImageInfo readImageInfo(ContentResolver resolver, Uri uri) throws IOException {
        if (detectSupportedImageFormat(resolver, uri) == null) {
            throw new IOException("图片格式不受支持。仅支持 JPG/JPEG、PNG、WebP，不支持 GIF。");
        }

        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream input = resolver.openInputStream(uri)) {
            if (input == null) throw new IOException("无法打开图片");
            BitmapFactory.decodeStream(input, null, bounds);
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw new IOException("无法读取图片尺寸");
        }
        return new ImageInfo(bounds.outWidth, bounds.outHeight, readExifRotation(resolver, uri));
    }

    private static List<ImageSlice> buildImageSlices(ImageInfo info, ImageOptions options) {
        int maxSliceHeight;
        if (options.pageSize == PageSize.FIT_IMAGE) {
            float margin = Math.max(0f, options.marginPoints);
            float availableWidth = Math.max(1f, FIT_IMAGE_PAGE_WIDTH - margin * 2f);
            int byReadableRatio = Math.max(
                    1,
                    Math.round(info.displayWidth * MAX_FIT_IMAGE_SEGMENT_RATIO)
            );
            int byPdfLimit = Math.max(
                    1,
                    (int) Math.floor(
                            info.displayWidth
                                    * Math.max(1f, MAX_PDF_PAGE_POINTS - margin * 2f)
                                    / availableWidth
                    )
            );
            maxSliceHeight = Math.min(byReadableRatio, byPdfLimit);
        } else {
            PDRectangle page = resolveFixedPageRectangleForImage(info, options);
            float margin = Math.max(0f, options.marginPoints);
            float availableWidth = Math.max(1f, page.getWidth() - margin * 2f);
            float availableHeight = Math.max(1f, page.getHeight() - margin * 2f);
            maxSliceHeight = Math.max(
                    1,
                    (int) Math.floor(info.displayWidth * availableHeight / availableWidth)
            );
            // 普通照片仍保持一张图片一页；只有明显超出纸张比例的长图才分段。
            if (info.displayHeight <= Math.round(maxSliceHeight * 1.08f)) {
                maxSliceHeight = info.displayHeight;
            }
        }

        List<ImageSlice> result = new ArrayList<>();
        for (int top = 0; top < info.displayHeight; top += maxSliceHeight) {
            result.add(new ImageSlice(top, Math.min(info.displayHeight, top + maxSliceHeight)));
        }
        if (result.isEmpty()) result.add(new ImageSlice(0, info.displayHeight));
        return result;
    }

    private static PDRectangle resolveImageSlicePageRectangle(
            ImageInfo info,
            ImageSlice slice,
            ImageOptions options
    ) {
        if (options.pageSize != PageSize.FIT_IMAGE) {
            return resolveFixedPageRectangleForImage(info, options);
        }

        float margin = Math.max(0f, options.marginPoints);
        float width = FIT_IMAGE_PAGE_WIDTH;
        float availableWidth = Math.max(1f, width - margin * 2f);
        float height = margin * 2f
                + availableWidth * slice.height() / Math.max(1f, info.displayWidth);
        height = Math.max(72f, Math.min(MAX_PDF_PAGE_POINTS, height));

        if (options.orientation == Orientation.PORTRAIT && width > height) {
            float temp = width;
            width = height;
            height = temp;
        } else if (options.orientation == Orientation.LANDSCAPE && height > width) {
            float temp = width;
            width = height;
            height = temp;
        }
        return new PDRectangle(width, height);
    }

    private static PDRectangle resolveFixedPageRectangleForImage(
            ImageInfo info,
            ImageOptions options
    ) {
        Orientation orientation = options.orientation;
        if (orientation == Orientation.AUTO) {
            orientation = info.displayWidth > info.displayHeight
                    ? Orientation.LANDSCAPE
                    : Orientation.PORTRAIT;
        }
        return resolveFixedPageRectangle(options.pageSize, orientation);
    }

    private static PDRectangle resolveFixedPageRectangle(PageSize size, Orientation orientation) {
        PDRectangle base;
        if (size == PageSize.A5) base = PDRectangle.A5;
        else if (size == PageSize.LETTER) base = PDRectangle.LETTER;
        else base = PDRectangle.A4;

        boolean landscape = orientation == Orientation.LANDSCAPE;
        return landscape
                ? new PDRectangle(base.getHeight(), base.getWidth())
                : new PDRectangle(base.getWidth(), base.getHeight());
    }

    private static int qualityTargetWidth(float quality) {
        if (quality >= 0.94f) return 1600;
        if (quality >= 0.84f) return 1200;
        return 900;
    }

    private static Bitmap decodeImageSlice(
            ContentResolver resolver,
            Uri uri,
            ImageInfo info,
            ImageSlice slice,
            int targetDisplayWidth
    ) throws IOException {
        Rect rawRect = mapDisplaySliceToRawRect(info, slice);
        BitmapRegionDecoder decoder = null;
        Bitmap decoded = null;
        try (InputStream input = resolver.openInputStream(uri)) {
            if (input == null) throw new IOException("无法打开图片");
            decoder = BitmapRegionDecoder.newInstance(input, false);
            if (decoder == null) throw new IOException("图片格式不支持分段读取");

            BitmapFactory.Options decodeOptions = new BitmapFactory.Options();
            decodeOptions.inPreferredConfig = Bitmap.Config.ARGB_8888;
            decodeOptions.inSampleSize = calculateRegionSampleSize(
                    info.displayWidth,
                    targetDisplayWidth
            );
            decoded = decoder.decodeRegion(rawRect, decodeOptions);
        } catch (OutOfMemoryError error) {
            throw new IOException("图片过大，内存不足。请降低图片质量后重试。", error);
        } finally {
            if (decoder != null) decoder.recycle();
        }
        if (decoded == null) throw new IOException("图片分段解码失败");

        Bitmap oriented = rotateBitmap(decoded, info.rotation);
        if (oriented != decoded && !decoded.isRecycled()) decoded.recycle();

        if (oriented.getWidth() > Math.round(targetDisplayWidth * 1.20f)) {
            int scaledHeight = Math.max(
                    1,
                    Math.round(oriented.getHeight()
                            * targetDisplayWidth / (float) oriented.getWidth())
            );
            Bitmap scaled = Bitmap.createScaledBitmap(
                    oriented,
                    targetDisplayWidth,
                    scaledHeight,
                    true
            );
            if (scaled != oriented && !oriented.isRecycled()) oriented.recycle();
            oriented = scaled;
        }
        return oriented;
    }

    private static Rect mapDisplaySliceToRawRect(ImageInfo info, ImageSlice slice) {
        int top = Math.max(0, Math.min(info.displayHeight - 1, slice.top));
        int bottom = Math.max(top + 1, Math.min(info.displayHeight, slice.bottom));
        Rect rect;
        if (info.rotation == 90) {
            rect = new Rect(top, 0, bottom, info.rawHeight);
        } else if (info.rotation == 180) {
            rect = new Rect(0, info.rawHeight - bottom, info.rawWidth, info.rawHeight - top);
        } else if (info.rotation == 270) {
            rect = new Rect(info.rawWidth - bottom, 0, info.rawWidth - top, info.rawHeight);
        } else {
            rect = new Rect(0, top, info.rawWidth, bottom);
        }
        rect.left = Math.max(0, Math.min(info.rawWidth - 1, rect.left));
        rect.top = Math.max(0, Math.min(info.rawHeight - 1, rect.top));
        rect.right = Math.max(rect.left + 1, Math.min(info.rawWidth, rect.right));
        rect.bottom = Math.max(rect.top + 1, Math.min(info.rawHeight, rect.bottom));
        return rect;
    }

    private static int calculateRegionSampleSize(int displayWidth, int targetWidth) {
        int sample = 1;
        int minimumUsefulWidth = Math.max(1, Math.round(targetWidth * 0.75f));
        while (displayWidth / (sample * 2) >= minimumUsefulWidth) sample *= 2;
        return Math.max(1, sample);
    }

    private static Bitmap rotateBitmap(Bitmap source, int rotation) {
        if (rotation == 0) return source;
        Matrix matrix = new Matrix();
        matrix.postRotate(rotation);
        return Bitmap.createBitmap(
                source,
                0,
                0,
                source.getWidth(),
                source.getHeight(),
                matrix,
                true
        );
    }

    private static int readExifRotation(ContentResolver resolver, Uri uri) {
        try (InputStream input = resolver.openInputStream(uri)) {
            if (input == null) return 0;
            ExifInterface exif = new ExifInterface(input);
            int orientation = exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
            );
            if (orientation == ExifInterface.ORIENTATION_ROTATE_90) return 90;
            if (orientation == ExifInterface.ORIENTATION_ROTATE_180) return 180;
            if (orientation == ExifInterface.ORIENTATION_ROTATE_270) return 270;
        } catch (Exception ignore) {
        }
        return 0;
    }

    private static Bitmap flattenOnWhite(Bitmap source) {
        Bitmap target = Bitmap.createBitmap(source.getWidth(), source.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(target);
        canvas.drawColor(Color.WHITE);
        canvas.drawBitmap(source, 0f, 0f, null);
        return target;
    }

    private static List<String> wrapText(String text, PDFont font, float fontSize, float maxWidth)
            throws IOException {
        List<String> result = new ArrayList<>();
        String[] paragraphs = text.split("\n", -1);
        for (String paragraph : paragraphs) {
            if (paragraph.isEmpty()) {
                result.add("");
                continue;
            }
            wrapParagraph(paragraph, font, fontSize, maxWidth, result);
        }
        return result;
    }

    private static void wrapParagraph(
            String paragraph,
            PDFont font,
            float fontSize,
            float maxWidth,
            List<String> output
    ) throws IOException {
        StringBuilder current = new StringBuilder();
        float currentWidth = 0f;
        int lastBreak = -1;

        for (int offset = 0; offset < paragraph.length();) {
            int cp = paragraph.codePointAt(offset);
            String part = new String(Character.toChars(cp));
            offset += Character.charCount(cp);
            int oldLength = current.length();
            current.append(part);
            currentWidth += stringWidth(font, part, fontSize);
            if (Character.isWhitespace(cp)) lastBreak = oldLength;

            if (currentWidth > maxWidth) {
                String line;
                String remainder;
                if (lastBreak > 0) {
                    line = stripTrailing(current.substring(0, lastBreak));
                    remainder = stripLeading(current.substring(lastBreak));
                } else if (oldLength > 0) {
                    line = current.substring(0, oldLength);
                    remainder = part;
                } else {
                    line = part;
                    remainder = "";
                }
                output.add(line);
                current.setLength(0);
                current.append(remainder);
                currentWidth = stringWidth(font, remainder, fontSize);
                lastBreak = findLastWhitespaceIndex(current);
            }
        }
        if (current.length() > 0) output.add(stripTrailing(current.toString()));
    }

    private static int findLastWhitespaceIndex(CharSequence text) {
        for (int i = text.length() - 1; i >= 0; i--) {
            if (Character.isWhitespace(text.charAt(i))) return i;
        }
        return -1;
    }

    private static String stripLeading(String value) {
        int index = 0;
        while (index < value.length() && Character.isWhitespace(value.charAt(index))) index++;
        return value.substring(index);
    }

    private static String stripTrailing(String value) {
        int index = value.length();
        while (index > 0 && Character.isWhitespace(value.charAt(index - 1))) index--;
        return value.substring(0, index);
    }

    private static float stringWidth(PDFont font, String value, float fontSize) throws IOException {
        if (value == null || value.isEmpty()) return 0f;
        return font.getStringWidth(value) / 1000f * fontSize;
    }
}
