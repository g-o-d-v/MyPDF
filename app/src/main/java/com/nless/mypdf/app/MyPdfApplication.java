package com.nless.mypdf.app;

import android.app.Application;

import com.nless.mypdf.diagnostics.CrashReportingManager;
import com.nless.mypdf.diagnostics.DiagnosticContext;
import com.nless.mypdf.diagnostics.ProcessExitTracker;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;

public class MyPdfApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        PDFBoxResourceLoader.init(getApplicationContext());
        CrashReportingManager.initialize(this);
        DiagnosticContext.initialize(this);
        DiagnosticContext.registerActivityTracking(this);
        ProcessExitTracker.captureLatest(this);
        DiagnosticContext.startOperation(this, "app_start");
        DiagnosticContext.finishOperation(this, "app_start", true);
    }
}
