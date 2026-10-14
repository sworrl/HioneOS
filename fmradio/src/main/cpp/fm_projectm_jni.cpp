// projectM background for the FM tuner. JNI for com.caf.fmradio.FmProjectMNative.
//
// Ported from Hione Music's projectm_native.cpp (app module), cut down to what a background
// needs: one instance, a playlist over a small curated preset dir, PCM straight from the radio,
// and a caller-driven clock so the idle state can run slow instead of at wall-clock speed.
//
// Threading: every call except nativeVersion runs on the GL thread that owns the context. The
// mutex is only there because a surface re-create can briefly overlap the old GL thread's last
// frame with the new one's init (same reason as the app's wrapper).

#include <jni.h>
#include <android/log.h>
#include <cstdint>
#include <mutex>
#include <string>
#include <projectM-4/projectM.h>
#include <projectM-4/playlist.h>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  "FmProjectM", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "FmProjectM", __VA_ARGS__)

#define JNI_EXPORT extern "C" JNIEXPORT __attribute__((visibility("default")))

static projectm_handle          s_pm       = nullptr;
static projectm_playlist_handle s_playlist = nullptr;
static std::mutex               s_lock;
static int                      s_lastPos  = -1;   // restored across a surface re-create
static int                      s_splashFrames = 0;
static int                      s_failures = 0;

static void destroy_locked() {
    if (s_playlist) {
        s_lastPos = (int) projectm_playlist_get_position(s_playlist);
        projectm_playlist_destroy(s_playlist);
        s_playlist = nullptr;
    }
    if (s_pm) {
        projectm_destroy(s_pm);
        s_pm = nullptr;
    }
}

static std::string basename_noext(const char* p) {
    if (!p) return "";
    std::string s(p);
    auto slash = s.find_last_of("/\\");
    if (slash != std::string::npos) s = s.substr(slash + 1);
    auto dot = s.find_last_of('.');
    if (dot != std::string::npos) s = s.substr(0, dot);
    return s;
}

JNI_EXPORT jboolean JNICALL
Java_com_caf_fmradio_FmProjectMNative_nativeInit(JNIEnv*, jobject, jint fps, jint meshW, jint meshH,
                                                 jfloat presetSeconds) {
    std::lock_guard<std::mutex> g(s_lock);
    // A new surface means a new GL context; projectM's GL objects belong to the old one.
    destroy_locked();
    try {
        s_pm = projectm_create();
    } catch (...) { s_pm = nullptr; }
    if (!s_pm) { LOGE("projectm_create() failed (no GLES3 context?)"); return JNI_FALSE; }

    projectm_set_mesh_size(s_pm, (size_t) meshW, (size_t) meshH);
    projectm_set_fps(s_pm, fps);
    projectm_set_aspect_correction(s_pm, true);
    projectm_set_preset_duration(s_pm, presetSeconds);
    projectm_set_soft_cut_duration(s_pm, 3.0);
    // Hard cuts off for the same reason as the app: a beat-triggered cut is a full preset load
    // (shader compile) mid-frame, the most expensive way to change preset on this GPU.
    projectm_set_hard_cut_enabled(s_pm, false);
    projectm_set_hard_cut_duration(s_pm, 120.0);
    projectm_set_hard_cut_sensitivity(s_pm, 3.0f);
    projectm_set_beat_sensitivity(s_pm, 1.0f);
    // The clock is ours (nativeRender passes it in), so idle can run slower than real time.
    projectm_set_frame_time(s_pm, 0.0);

    projectm_set_preset_switch_failed_event_callback(s_pm, [](const char* file, const char* msg, void*) {
        LOGE("PRESET FAILED: %s :: %s", file ? file : "?", msg ? msg : "?");
        s_failures++;
    }, nullptr);

    // Attribution: projectM's own idle preset (the "M" logo) for a moment on a fresh open, as the
    // player does. Skipped on a re-create so it does not reappear every time the surface returns.
    projectm_load_preset_file(s_pm, "idle://", false);
    s_splashFrames = (s_lastPos >= 0) ? 0 : fps * 2;

    s_playlist = projectm_playlist_create(s_pm);
    if (s_playlist) {
        projectm_playlist_set_shuffle(s_playlist, true);
        projectm_playlist_set_retry_count(s_playlist, 5);
    }
    LOGI("projectM up: mesh %dx%d, %d fps, %.0f s per preset", meshW, meshH, fps, presetSeconds);
    return JNI_TRUE;
}

JNI_EXPORT jint JNICALL
Java_com_caf_fmradio_FmProjectMNative_nativeLoadPresets(JNIEnv* env, jobject, jstring dir) {
    std::lock_guard<std::mutex> g(s_lock);
    if (!s_playlist || !dir) return 0;
    const char* path = env->GetStringUTFChars(dir, nullptr);
    if (!path) return 0;
    uint32_t added = 0;
    try {
        projectm_playlist_clear(s_playlist);
        added = projectm_playlist_add_path(s_playlist, path, false, false);
    } catch (...) { LOGE("add_path threw"); }
    env->ReleaseStringUTFChars(dir, path);
    LOGI("playlist: %u presets", added);
    if (added == 0) return 0;
    projectm_playlist_set_shuffle(s_playlist, true);
    try {
        if (s_lastPos >= 0 && (uint32_t) s_lastPos < added) {
            projectm_playlist_set_position(s_playlist, (uint32_t) s_lastPos, true);
        } else if (s_splashFrames <= 0) {
            projectm_playlist_play_next(s_playlist, true);
        }
    } catch (...) { LOGE("initial preset threw"); }
    return (jint) added;
}

JNI_EXPORT void JNICALL
Java_com_caf_fmradio_FmProjectMNative_nativeResize(JNIEnv*, jobject, jint w, jint h) {
    std::lock_guard<std::mutex> g(s_lock);
    if (s_pm && w > 0 && h > 0) projectm_set_window_size(s_pm, (size_t) w, (size_t) h);
}

/** Mono 16-bit PCM from the radio. projectM duplicates the channel internally. */
JNI_EXPORT void JNICALL
Java_com_caf_fmradio_FmProjectMNative_nativeFeedPcm(JNIEnv* env, jobject, jshortArray pcm, jint count) {
    if (!pcm || count <= 0) return;
    std::lock_guard<std::mutex> g(s_lock);
    if (!s_pm) return;
    jsize n = env->GetArrayLength(pcm);
    if (count > n) count = n;
    // Critical: no copy, and the GL thread only holds it for the duration of the add.
    void* p = env->GetPrimitiveArrayCritical(pcm, nullptr);
    if (!p) return;
    projectm_pcm_add_int16(s_pm, static_cast<const int16_t*>(p), (unsigned int) count, PROJECTM_MONO);
    env->ReleasePrimitiveArrayCritical(pcm, p, JNI_ABORT);
}

JNI_EXPORT void JNICALL
Java_com_caf_fmradio_FmProjectMNative_nativeRender(JNIEnv*, jobject, jdouble timeSec) {
    std::lock_guard<std::mutex> g(s_lock);
    if (!s_pm) return;
    if (s_splashFrames > 0 && --s_splashFrames == 0 && s_playlist) {
        try { projectm_playlist_play_next(s_playlist, true); } catch (...) { LOGE("splash hand-off threw"); }
    }
    projectm_set_frame_time(s_pm, timeSec);
    try { projectm_opengl_render_frame(s_pm); }
    catch (...) { LOGE("render threw"); }
}

JNI_EXPORT void JNICALL
Java_com_caf_fmradio_FmProjectMNative_nativeNext(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> g(s_lock);
    if (!s_playlist || s_splashFrames > 0) return;
    try { projectm_playlist_play_next(s_playlist, false); } catch (...) { LOGE("next threw"); }
}

JNI_EXPORT jstring JNICALL
Java_com_caf_fmradio_FmProjectMNative_nativePresetName(JNIEnv* env, jobject) {
    std::lock_guard<std::mutex> g(s_lock);
    if (!s_playlist || s_splashFrames > 0) return env->NewStringUTF("");
    std::string name;
    try {
        char* path = projectm_playlist_item(s_playlist, projectm_playlist_get_position(s_playlist));
        name = basename_noext(path);
        if (path) projectm_playlist_free_string(path);
    } catch (...) {}
    return env->NewStringUTF(name.c_str());
}

JNI_EXPORT jint JNICALL
Java_com_caf_fmradio_FmProjectMNative_nativeFailures(JNIEnv*, jobject) { return s_failures; }

JNI_EXPORT void JNICALL
Java_com_caf_fmradio_FmProjectMNative_nativeDestroy(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> g(s_lock);
    destroy_locked();
}

JNI_EXPORT jstring JNICALL
Java_com_caf_fmradio_FmProjectMNative_nativeVersion(JNIEnv* env, jobject) {
    std::string out;
    try {
        char* v = projectm_get_version_string();
        if (v) { out = v; projectm_free_string(v); }
    } catch (...) {}
    return env->NewStringUTF(out.c_str());
}
