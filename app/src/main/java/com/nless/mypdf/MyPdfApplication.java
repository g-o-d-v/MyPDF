package com.nless.mypdf;

import android.app.Application;

import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;

public class MyPdfApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        PDFBoxResourceLoader.init(getApplicationContext());
    }
}
