package com.nless.pdf_search_engine.paddle;

import android.graphics.Bitmap;

public class PaddleOcrNative {

    static {
        try {
            System.loadLibrary("c++_shared");
        } catch (Throwable ignored) {
        }

        try {
            System.loadLibrary("paddle_light_api_shared");
        } catch (Throwable ignored) {
        }

        try {
            System.loadLibrary("opencv_java4");
        } catch (Throwable ignored) {
        }

        System.loadLibrary("Native");
    }

    public native long init(
            String detModelPath,
            String recModelPath,
            String clsModelPath,
            int useOpencl,
            int threadNum,
            String cpuMode
    );

    public native float[] forward(
            long nativePointer,
            Bitmap originalImage,
            int maxSizeLen,
            int runDet,
            int runCls,
            int runRec
    );

    public native void release(long nativePointer);
}
