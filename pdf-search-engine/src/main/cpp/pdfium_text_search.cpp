#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>

#include <string>
#include <vector>
#include <algorithm>
#include <cmath>

#define PDFIUM_SEARCH_TAG "PdfiumTextSearch"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, PDFIUM_SEARCH_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, PDFIUM_SEARCH_TAG, __VA_ARGS__)

typedef void* FPDF_DOCUMENT;
typedef void* FPDF_PAGE;
typedef void* FPDF_TEXTPAGE;
typedef int FPDF_BOOL;

static void* g_pdfium_handle = nullptr;
static bool g_pdfium_loaded = false;

typedef void (*FN_FPDF_InitLibrary)();
typedef void (*FN_FPDF_DestroyLibrary)();
typedef FPDF_DOCUMENT (*FN_FPDF_LoadDocument)(const char* file_path, const char* password);
typedef void (*FN_FPDF_CloseDocument)(FPDF_DOCUMENT document);
typedef int (*FN_FPDF_GetPageCount)(FPDF_DOCUMENT document);
typedef FPDF_PAGE (*FN_FPDF_LoadPage)(FPDF_DOCUMENT document, int page_index);
typedef void (*FN_FPDF_ClosePage)(FPDF_PAGE page);
typedef FPDF_BOOL (*FN_FPDF_GetPageSizeByIndex)(FPDF_DOCUMENT document, int page_index, double* width, double* height);

typedef FPDF_TEXTPAGE (*FN_FPDFText_LoadPage)(FPDF_PAGE page);
typedef void (*FN_FPDFText_ClosePage)(FPDF_TEXTPAGE text_page);
typedef int (*FN_FPDFText_CountChars)(FPDF_TEXTPAGE text_page);
typedef unsigned int (*FN_FPDFText_GetUnicode)(FPDF_TEXTPAGE text_page, int index);
typedef FPDF_BOOL (*FN_FPDFText_GetCharBox)(FPDF_TEXTPAGE text_page, int index, double* left, double* right, double* bottom, double* top);

static FN_FPDF_InitLibrary p_FPDF_InitLibrary = nullptr;
static FN_FPDF_DestroyLibrary p_FPDF_DestroyLibrary = nullptr;
static FN_FPDF_LoadDocument p_FPDF_LoadDocument = nullptr;
static FN_FPDF_CloseDocument p_FPDF_CloseDocument = nullptr;
static FN_FPDF_GetPageCount p_FPDF_GetPageCount = nullptr;
static FN_FPDF_LoadPage p_FPDF_LoadPage = nullptr;
static FN_FPDF_ClosePage p_FPDF_ClosePage = nullptr;
static FN_FPDF_GetPageSizeByIndex p_FPDF_GetPageSizeByIndex = nullptr;

static FN_FPDFText_LoadPage p_FPDFText_LoadPage = nullptr;
static FN_FPDFText_ClosePage p_FPDFText_ClosePage = nullptr;
static FN_FPDFText_CountChars p_FPDFText_CountChars = nullptr;
static FN_FPDFText_GetUnicode p_FPDFText_GetUnicode = nullptr;
static FN_FPDFText_GetCharBox p_FPDFText_GetCharBox = nullptr;

static void* load_symbol(const char* name) {
    if (!g_pdfium_handle) return nullptr;
    void* sym = dlsym(g_pdfium_handle, name);
    if (!sym) {
        LOGE("dlsym failed: %s", name);
    }
    return sym;
}

static bool load_pdfium_symbols() {
    if (g_pdfium_loaded) return true;

    /*
     * AndroidPdfViewer / pdfium-android 常见库名：libmodpdfium.so
     * 这里同时尝试 libpdfium.so 做兜底。
     */
    g_pdfium_handle = dlopen("libmodpdfium.so", RTLD_NOW);
    if (!g_pdfium_handle) {
        LOGE("dlopen libmodpdfium.so failed: %s", dlerror());
        g_pdfium_handle = dlopen("libpdfium.so", RTLD_NOW);
    }

    if (!g_pdfium_handle) {
        LOGE("dlopen libpdfium.so also failed: %s", dlerror());
        return false;
    }

    p_FPDF_InitLibrary = reinterpret_cast<FN_FPDF_InitLibrary>(load_symbol("FPDF_InitLibrary"));
    p_FPDF_DestroyLibrary = reinterpret_cast<FN_FPDF_DestroyLibrary>(load_symbol("FPDF_DestroyLibrary"));
    p_FPDF_LoadDocument = reinterpret_cast<FN_FPDF_LoadDocument>(load_symbol("FPDF_LoadDocument"));
    p_FPDF_CloseDocument = reinterpret_cast<FN_FPDF_CloseDocument>(load_symbol("FPDF_CloseDocument"));
    p_FPDF_GetPageCount = reinterpret_cast<FN_FPDF_GetPageCount>(load_symbol("FPDF_GetPageCount"));
    p_FPDF_LoadPage = reinterpret_cast<FN_FPDF_LoadPage>(load_symbol("FPDF_LoadPage"));
    p_FPDF_ClosePage = reinterpret_cast<FN_FPDF_ClosePage>(load_symbol("FPDF_ClosePage"));
    p_FPDF_GetPageSizeByIndex = reinterpret_cast<FN_FPDF_GetPageSizeByIndex>(load_symbol("FPDF_GetPageSizeByIndex"));

    p_FPDFText_LoadPage = reinterpret_cast<FN_FPDFText_LoadPage>(load_symbol("FPDFText_LoadPage"));
    p_FPDFText_ClosePage = reinterpret_cast<FN_FPDFText_ClosePage>(load_symbol("FPDFText_ClosePage"));
    p_FPDFText_CountChars = reinterpret_cast<FN_FPDFText_CountChars>(load_symbol("FPDFText_CountChars"));
    p_FPDFText_GetUnicode = reinterpret_cast<FN_FPDFText_GetUnicode>(load_symbol("FPDFText_GetUnicode"));
    p_FPDFText_GetCharBox = reinterpret_cast<FN_FPDFText_GetCharBox>(load_symbol("FPDFText_GetCharBox"));

    bool ok =
            p_FPDF_InitLibrary &&
            p_FPDF_LoadDocument &&
            p_FPDF_CloseDocument &&
            p_FPDF_GetPageCount &&
            p_FPDF_LoadPage &&
            p_FPDF_ClosePage &&
            p_FPDF_GetPageSizeByIndex &&
            p_FPDFText_LoadPage &&
            p_FPDFText_ClosePage &&
            p_FPDFText_CountChars &&
            p_FPDFText_GetUnicode &&
            p_FPDFText_GetCharBox;

    if (!ok) {
        LOGE("PDFium symbols missing");
        return false;
    }

    p_FPDF_InitLibrary();

    g_pdfium_loaded = true;
    LOGI("PDFium symbols loaded");
    return true;
}

static std::string jstring_to_utf8(JNIEnv* env, jstring jstr) {
    if (!jstr) return "";
    const char* chars = env->GetStringUTFChars(jstr, nullptr);
    std::string result = chars ? chars : "";
    if (chars) {
        env->ReleaseStringUTFChars(jstr, chars);
    }
    return result;
}

static std::u32string jstring_to_u32(JNIEnv* env, jstring jstr) {
    std::u32string out;
    if (!jstr) return out;

    jsize len = env->GetStringLength(jstr);
    const jchar* chars = env->GetStringChars(jstr, nullptr);
    if (!chars) return out;

    for (jsize i = 0; i < len; i++) {
        uint32_t c = chars[i];

        /*
         * 处理 UTF-16 surrogate pair。
         */
        if (c >= 0xD800 && c <= 0xDBFF && i + 1 < len) {
            uint32_t high = c;
            uint32_t low = chars[i + 1];
            if (low >= 0xDC00 && low <= 0xDFFF) {
                uint32_t codepoint = 0x10000 + ((high - 0xD800) << 10) + (low - 0xDC00);
                out.push_back(codepoint);
                i++;
                continue;
            }
        }

        out.push_back(c);
    }

    env->ReleaseStringChars(jstr, chars);
    return out;
}

static uint32_t lower_ascii(uint32_t c) {
    if (c >= U'A' && c <= U'Z') {
        return c + 32;
    }
    return c;
}

static std::u32string lower_ascii_string(const std::u32string& input) {
    std::u32string out;
    out.reserve(input.size());
    for (uint32_t c : input) {
        out.push_back(lower_ascii(c));
    }
    return out;
}

struct CharInfo {
    uint32_t unicode;
    int charIndex;
};

struct RectD {
    double left;
    double top;
    double right;
    double bottom;
    bool valid;
};

static RectD get_char_rect(FPDF_TEXTPAGE textPage, int charIndex) {
    RectD r{};
    r.valid = false;

    double left = 0;
    double right = 0;
    double bottom = 0;
    double top = 0;

    if (!p_FPDFText_GetCharBox(textPage, charIndex, &left, &right, &bottom, &top)) {
        return r;
    }

    if (right <= left || top <= bottom) {
        return r;
    }

    r.left = left;
    r.right = right;
    r.bottom = bottom;
    r.top = top;
    r.valid = true;

    return r;
}

static void union_rect(RectD& base, const RectD& add) {
    if (!add.valid) return;

    if (!base.valid) {
        base = add;
        return;
    }

    base.left = std::min(base.left, add.left);
    base.right = std::max(base.right, add.right);
    base.bottom = std::min(base.bottom, add.bottom);
    base.top = std::max(base.top, add.top);
}

static void push_rect_result(
        std::vector<float>& out,
        int pageIndex,
        const RectD& r,
        double pageWidth,
        double pageHeight
) {
    if (!r.valid) return;

    out.push_back(static_cast<float>(pageIndex));
    out.push_back(static_cast<float>(r.left));
    out.push_back(static_cast<float>(r.top));
    out.push_back(static_cast<float>(r.right));
    out.push_back(static_cast<float>(r.bottom));
    out.push_back(static_cast<float>(pageWidth));
    out.push_back(static_cast<float>(pageHeight));
}

static void collect_match_rects(
        FPDF_TEXTPAGE textPage,
        const std::vector<CharInfo>& chars,
        int matchStart,
        int matchEnd,
        int pageIndex,
        double pageWidth,
        double pageHeight,
        std::vector<float>& out
) {
    RectD current{};
    current.valid = false;

    double currentCenterY = 0;
    double currentHeight = 0;

    for (int i = matchStart; i < matchEnd && i < static_cast<int>(chars.size()); i++) {
        RectD r = get_char_rect(textPage, chars[i].charIndex);
        if (!r.valid) continue;

        double centerY = (r.top + r.bottom) / 2.0;
        double height = std::max(1.0, r.top - r.bottom);

        if (!current.valid) {
            current = r;
            currentCenterY = centerY;
            currentHeight = height;
            continue;
        }

        double dy = std::fabs(centerY - currentCenterY);
        double threshold = std::max(currentHeight, height) * 0.85;

        /*
         * 如果关键词跨行，拆成多个高亮框。
         */
        if (dy > threshold) {
            /*
             * 适当扩张，让高亮更像背景。
             */
            double padX = std::max(1.0, (current.right - current.left) * 0.03);
            double padY = std::max(1.0, (current.top - current.bottom) * 0.18);

            current.left -= padX;
            current.right += padX;
            current.bottom -= padY;
            current.top += padY;

            push_rect_result(out, pageIndex, current, pageWidth, pageHeight);

            current = r;
            currentCenterY = centerY;
            currentHeight = height;
        } else {
            union_rect(current, r);
            currentCenterY = (currentCenterY + centerY) / 2.0;
            currentHeight = std::max(currentHeight, height);
        }
    }

    if (current.valid) {
        double padX = std::max(1.0, (current.right - current.left) * 0.03);
        double padY = std::max(1.0, (current.top - current.bottom) * 0.18);

        current.left -= padX;
        current.right += padX;
        current.bottom -= padY;
        current.top += padY;

        push_rect_result(out, pageIndex, current, pageWidth, pageHeight);
    }
}

extern "C"
JNIEXPORT jfloatArray JNICALL
Java_com_nless_pdf_1search_1engine_pdfium_PdfiumTextNative_nativeSearch(
        JNIEnv* env,
        jobject thiz,
        jstring j_pdf_path,
        jstring j_keyword,
        jint j_start_page,
        jint j_end_page
) {
    std::vector<float> result;

    if (!load_pdfium_symbols()) {
        LOGE("load pdfium symbols failed");
        return env->NewFloatArray(0);
    }

    std::string pdfPath = jstring_to_utf8(env, j_pdf_path);
    std::u32string keyword = jstring_to_u32(env, j_keyword);

    if (pdfPath.empty() || keyword.empty()) {
        return env->NewFloatArray(0);
    }

    std::u32string lowerKeyword = lower_ascii_string(keyword);

    FPDF_DOCUMENT document = p_FPDF_LoadDocument(pdfPath.c_str(), nullptr);
    if (!document) {
        LOGE("FPDF_LoadDocument failed: %s", pdfPath.c_str());
        return env->NewFloatArray(0);
    }

    int pageCount = p_FPDF_GetPageCount(document);
    if (pageCount <= 0) {
        p_FPDF_CloseDocument(document);
        return env->NewFloatArray(0);
    }

    int startPage = std::max(0, static_cast<int>(j_start_page));
    int endPage = static_cast<int>(j_end_page);

    if (endPage < 0 || endPage >= pageCount) {
        endPage = pageCount - 1;
    }

    if (startPage > endPage) {
        p_FPDF_CloseDocument(document);
        return env->NewFloatArray(0);
    }

    LOGI("search pdf pageCount=%d start=%d end=%d", pageCount, startPage, endPage);

    for (int pageIndex = startPage; pageIndex <= endPage; pageIndex++) {
        double pageWidth = 0;
        double pageHeight = 0;

        if (!p_FPDF_GetPageSizeByIndex(document, pageIndex, &pageWidth, &pageHeight)) {
            continue;
        }

        FPDF_PAGE page = p_FPDF_LoadPage(document, pageIndex);
        if (!page) {
            continue;
        }

        FPDF_TEXTPAGE textPage = p_FPDFText_LoadPage(page);
        if (!textPage) {
            p_FPDF_ClosePage(page);
            continue;
        }

        int charCount = p_FPDFText_CountChars(textPage);

        std::vector<CharInfo> chars;
        chars.reserve(std::max(0, charCount));

        std::u32string pageText;
        pageText.reserve(std::max(0, charCount));

        for (int i = 0; i < charCount; i++) {
            uint32_t unicode = p_FPDFText_GetUnicode(textPage, i);

            if (unicode == 0) continue;

            /*
             * PDFium 可能返回换行、控制字符。
             * 第一版保留空格，过滤常见换行。
             */
            if (unicode == '\r' || unicode == '\n') {
                continue;
            }

            uint32_t normalized = lower_ascii(unicode);

            pageText.push_back(normalized);
            chars.push_back(CharInfo{normalized, i});
        }

        if (!pageText.empty() && pageText.size() >= lowerKeyword.size()) {
            size_t pos = 0;

            while (true) {
                auto found = pageText.find(lowerKeyword, pos);
                if (found == std::u32string::npos) {
                    break;
                }

                int matchStart = static_cast<int>(found);
                int matchEnd = static_cast<int>(found + lowerKeyword.size());

                collect_match_rects(
                        textPage,
                        chars,
                        matchStart,
                        matchEnd,
                        pageIndex,
                        pageWidth,
                        pageHeight,
                        result
                );

                pos = found + std::max<size_t>(1, lowerKeyword.size());
            }
        }

        p_FPDFText_ClosePage(textPage);
        p_FPDF_ClosePage(page);
    }

    p_FPDF_CloseDocument(document);

    if (result.empty()) {
        return env->NewFloatArray(0);
    }

    jfloatArray arr = env->NewFloatArray(static_cast<jsize>(result.size()));
    env->SetFloatArrayRegion(arr, 0, static_cast<jsize>(result.size()), result.data());
    return arr;
}
