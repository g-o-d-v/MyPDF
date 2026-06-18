package com.nless.pdf_search_engine.core;

public class PdfSearchError extends Exception {

    public static final int ERROR_UNKNOWN = 0;
    public static final int ERROR_OPEN_PDF_FAILED = 1;
    public static final int ERROR_TEXT_SEARCH_FAILED = 2;
    public static final int ERROR_OCR_FAILED = 3;
    public static final int ERROR_NATIVE_LIBRARY_NOT_FOUND = 4;
    public static final int ERROR_CANCELLED = 5;
    public static final int ERROR_INVALID_ARGUMENT = 6;

    public final int code;

    public PdfSearchError(int code, String message) {
        super(message);
        this.code = code;
    }

    public PdfSearchError(int code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }
}
