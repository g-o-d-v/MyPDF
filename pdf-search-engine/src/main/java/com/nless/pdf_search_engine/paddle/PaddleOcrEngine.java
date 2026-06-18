package com.nless.pdf_search_engine.paddle;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.util.Log;

import com.nless.pdf_search_engine.ocr.OcrPageResult;
import com.nless.pdf_search_engine.ocr.OcrTextBlock;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

public class PaddleOcrEngine {

    private static final String TAG = "PaddleOcrEngine";
    private static final Object OCR_LOCK = new Object();

    private static volatile PaddleOcrEngine instance;

    private final PaddleOcrNative nativeApi = new PaddleOcrNative();

    private long nativePointer = 0;
    private final List<String> wordDict = new ArrayList<>();

    private PaddleOcrEngine() {
    }

    public static PaddleOcrEngine getInstance() {
        if (instance == null) {
            synchronized (PaddleOcrEngine.class) {
                if (instance == null) {
                    instance = new PaddleOcrEngine();
                }
            }
        }
        return instance;
    }

    public synchronized void initEngine(Context context) {
        if (nativePointer != 0) return;

        String detModel = copyAssetToFiles(context, "det.nb");
        String recModel = copyAssetToFiles(context, "rec.nb");
        String clsModel = copyAssetToFiles(context, "cls.nb");
        String dictPath = copyAssetToFiles(context, "ppocr_keys_v1.txt");

        if (detModel == null || recModel == null || clsModel == null || dictPath == null) {
            Log.e(TAG, "模型或字典复制失败");
            return;
        }

        loadDictionary(dictPath);

        try {
            nativePointer = nativeApi.init(
                    detModel,
                    recModel,
                    clsModel,
                    0,
                    4,
                    "LITE_POWER_HIGH"
            );
            Log.i(TAG, "Paddle OCR init success, pointer=" + nativePointer);
        } catch (Exception e) {
            Log.e(TAG, "初始化 Paddle OCR 失败", e);
        }
    }

    public synchronized void release() {
        if (nativePointer != 0) {
            try {
                nativeApi.release(nativePointer);
            } catch (Exception e) {
                Log.e(TAG, "release OCR failed", e);
            }
            nativePointer = 0;
        }
    }

    public OcrPageResult recognizePage(Context context, Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled()) return null;

        if (nativePointer == 0) {
            initEngine(context.getApplicationContext());
        }

        if (nativePointer == 0) {
            return null;
        }

        Bitmap input = null;

        try {
            Bitmap argbBitmap = ensureArgb8888(bitmap);

            /*
             * PDF 页面 OCR 不建议盲目放大 1.5 倍。
             * 如果 PdfRenderer 已经渲染到 1400~1800px 宽，再放大容易内存暴涨。
             * 所以这里先不放大。
             */
            input = argbBitmap;

            float[] resultArray;
            synchronized (OCR_LOCK) {
                resultArray = nativeApi.forward(
                        nativePointer,
                        input,
                        960,
                        1,
                        0,
                        1
                );
            }

            if (resultArray == null || resultArray.length == 0) {
                return new OcrPageResult(input.getWidth(), input.getHeight(), new ArrayList<>());
            }

            List<OcrTextBlock> blocks = parseFloatArrayToBlocks(resultArray);
            return new OcrPageResult(input.getWidth(), input.getHeight(), blocks);

        } catch (Exception e) {
            Log.e(TAG, "OCR 识别失败", e);
            return null;
        } finally {
            if (input != null && input != bitmap && !input.isRecycled()) {
                input.recycle();
            }
        }
    }

    public OcrPageResult recognizePage(
            Context context,
            Bitmap bitmap,
            float pageWidth,
            float pageHeight
    ) {
        OcrPageResult base = recognizePage(context, bitmap);

        if (base == null) {
            return null;
        }

        return new OcrPageResult(
                base.bitmapWidth,
                base.bitmapHeight,
                pageWidth,
                pageHeight,
                base.blocks
        );
    }


    private Bitmap ensureArgb8888(Bitmap src) {
        if (src.getConfig() == Bitmap.Config.ARGB_8888) {
            return src;
        }
        return src.copy(Bitmap.Config.ARGB_8888, false);
    }

    private Bitmap scaleBitmap(Bitmap src, float scale) {
        Matrix matrix = new Matrix();
        matrix.postScale(scale, scale);
        return Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), matrix, true);
    }

    private List<OcrTextBlock> parseFloatArrayToBlocks(float[] floatArr) {
        List<OcrTextBlock> blocks = new ArrayList<>();

        int i = 0;
        while (i < floatArr.length) {
            if (i + 3 > floatArr.length) break;

            int pointNum = (int) floatArr[i++];
            int wordNum = (int) floatArr[i++];
            float score = floatArr[i++];

            if (pointNum <= 0 || wordNum < 0) break;
            if (i + pointNum * 2 > floatArr.length) break;

            float minX = Float.MAX_VALUE;
            float minY = Float.MAX_VALUE;
            float maxX = -Float.MAX_VALUE;
            float maxY = -Float.MAX_VALUE;

            for (int p = 0; p < pointNum; p++) {
                float x = floatArr[i++];
                float y = floatArr[i++];

                minX = Math.min(minX, x);
                minY = Math.min(minY, y);
                maxX = Math.max(maxX, x);
                maxY = Math.max(maxY, y);
            }

            if (i + wordNum > floatArr.length) break;

            StringBuilder text = new StringBuilder();
            for (int w = 0; w < wordNum; w++) {
                int index = (int) floatArr[i++];
                if (index >= 0 && index < wordDict.size()) {
                    text.append(wordDict.get(index));
                }
            }

            /*
             * cls_label + cls_score
             */
            if (i + 2 <= floatArr.length) {
                i += 2;
            } else {
                break;
            }

            String blockText = text.toString();
            if (!blockText.trim().isEmpty()
                    && minX != Float.MAX_VALUE
                    && minY != Float.MAX_VALUE
                    && maxX > minX
                    && maxY > minY) {

                blocks.add(new OcrTextBlock(
                        blockText,
                        new RectF(minX, minY, maxX, maxY),
                        score
                ));
            }
        }

        return blocks;
    }

    private void loadDictionary(String path) {
        wordDict.clear();
        wordDict.add("blank");

        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            String line;
            while ((line = br.readLine()) != null) {
                wordDict.add(line);
            }
            wordDict.add(" ");
        } catch (Exception e) {
            Log.e(TAG, "加载 OCR 字典失败", e);
        }
    }

    private String copyAssetToFiles(Context context, String assetFileName) {
        File dir = new File(context.getFilesDir(), "paddle_ocr");
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }

        File outFile = new File(dir, assetFileName);

        if (outFile.exists() && outFile.length() > 1024) {
            return outFile.getAbsolutePath();
        }

        try (InputStream is = context.getAssets().open(assetFileName);
             FileOutputStream fos = new FileOutputStream(outFile)) {

            byte[] buffer = new byte[8192];
            int length;
            while ((length = is.read(buffer)) > 0) {
                fos.write(buffer, 0, length);
            }
            fos.flush();
            return outFile.getAbsolutePath();

        } catch (Exception e) {
            Log.e(TAG, "复制 asset 失败：" + assetFileName, e);
            if (outFile.exists()) {
                //noinspection ResultOfMethodCallIgnored
                outFile.delete();
            }
            return null;
        }
    }

}
