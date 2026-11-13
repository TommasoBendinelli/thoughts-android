#include <jni.h>
#include <fpdfview.h>
#include <fpdf_transformpage.h>
#include <cmath>
#include <mutex>
#include <string>
#include <list>
extern "C" {
#include "../synctex/synctex_parser.h"
}
// Immutable content-addressed files; two scanners bound the native cache.
static std::mutex scannerMutex;
struct Scanner {
    std::string path;
    synctex_scanner_p value;
    ~Scanner() { if (value) synctex_scanner_free(value); }
};
static std::list<Scanner> scanners;
extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_shockwave_pdfium_PdfiumCore_referenceSyncTex(JNIEnv* env, jclass, jstring output, jint page, jfloat x, jfloat y) {
    if (!output || page < 1 || !std::isfinite(x) || !std::isfinite(y)) return nullptr;
    const char* raw = env->GetStringUTFChars(output, nullptr);
    if (!raw) return nullptr;
    std::string path(raw); env->ReleaseStringUTFChars(output, raw);
    std::lock_guard<std::mutex> guard(scannerMutex);
    auto it = scanners.begin();
    for (; it != scanners.end(); ++it) if (it->path == path) break;
    if (it == scanners.end()) {
        auto scanner = synctex_scanner_new_with_output_file(path.c_str(), nullptr, 1);
        if (!scanner) return nullptr;
        scanners.emplace_front(); scanners.front().path = path; scanners.front().value = scanner;
        if (scanners.size() > 2) scanners.pop_back();
    } else scanners.splice(scanners.begin(), scanners, it);
    auto scanner = scanners.front().value;
    if (synctex_edit_query(scanner, page, x, y) <= 0) return nullptr;
    jobjectArray result = env->NewObjectArray(64, env->FindClass("java/lang/String"), nullptr);
    int i = 0;
    while (auto node = synctex_scanner_next_result(scanner)) {
        const char* name = synctex_scanner_get_name(scanner, synctex_node_tag(node));
        int line = synctex_node_line(node);
        if (!name || line < 1) continue;
        std::string item = std::to_string(line) + "\t" + name;
        auto value = env->NewStringUTF(item.c_str());
        env->SetObjectArrayElement(result, i++, value); env->DeleteLocalRef(value);
        if (i == 64) break;
    }
    return result;
}
extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_shockwave_pdfium_PdfiumCore_nativeSourcePoint(JNIEnv* env, jobject, jlong pointer, jfloat x, jfloat y) {
    auto page = reinterpret_cast<FPDF_PAGE>(pointer);
    float left, bottom, right, top;
    if (!page || !std::isfinite(x) || !std::isfinite(y) ||
        !FPDFPage_GetMediaBox(page, &left, &bottom, &right, &top)) return nullptr;
    // Text rectangles are already unrotated PDF user-space coordinates, including
    // CropBox offsets. Do not apply display rotation or viewer margin crop again.
    float point[] = {x - left, top - y};
    auto result = env->NewFloatArray(2); env->SetFloatArrayRegion(result, 0, 2, point); return result;
}
