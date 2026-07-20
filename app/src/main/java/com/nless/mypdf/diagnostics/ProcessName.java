package com.nless.mypdf.diagnostics;

import android.app.Application;

public final class ProcessName {
    private ProcessName() {}

    public static String current() {
        String process = Application.getProcessName();
        if (process == null) return DiagnosticContext.UNKNOWN;
        if ("com.nless.mypdf".equals(process)) return "main";
        if (process.endsWith(":pdf_merge")) return "pdf_merge";
        return "other";
    }

    public static boolean isMainProcess() {
        return "main".equals(current());
    }
}
