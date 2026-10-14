#include <jni.h>
#include <fcntl.h>
#include <unistd.h>
#include <string>
#include <android/log.h>

#define TAG "M500AudioHal"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static bool writeSysfs(const char* path, const char* value) {
    int fd = open(path, O_WRONLY);
    if (fd < 0) {
        LOGW("Failed to open sysfs node %s (errno %d)", path, errno);
        return false;
    }
    ssize_t len = strlen(value);
    ssize_t written = write(fd, value, len);
    close(fd);
    return written == len;
}

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_hione_player_M500AudioHal_nativeSetDigitalFilter(JNIEnv* env, jclass clazz, jint filter) {
    char buf[16];
    snprintf(buf, sizeof(buf), "%d", filter);
    bool ok = writeSysfs("/sys/devices/platform/sa_sound_setting/digital_filter", buf);
    LOGI("Set CS43198 Digital Filter: %d (res=%d)", filter, ok);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_hione_player_M500AudioHal_nativeSetHighPower(JNIEnv* env, jclass clazz, jboolean highPower) {
    const char* val = highPower ? "1" : "0";
    bool ok = writeSysfs("/sys/devices/platform/sa_sound_setting/high_power_mode", val);
    LOGI("Set High Power Gain (+6dB): %d (res=%d)", highPower, ok);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_hione_player_M500AudioHal_nativeSetDreMode(JNIEnv* env, jclass clazz, jboolean dre) {
    const char* val = dre ? "1" : "0";
    bool ok = writeSysfs("/sys/devices/platform/sa_sound_setting/dre_mode", val);
    LOGI("Set DRE Mode (130dB+ SNR): %d (res=%d)", dre, ok);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_hione_player_M500AudioHal_nativeSetPocketLock(JNIEnv* env, jclass clazz, jboolean touchLock, jboolean keyLock, jboolean allowVol) {
    bool ok1 = writeSysfs("/sys/devices/platform/soc/4a88000.i2c/i2c-1/1-005a/hyn_gesture_mode", touchLock ? "1" : "0");
    const char* keyVal = keyLock ? (allowVol ? "sw_user" : "all") : "none";
    bool ok2 = writeSysfs("/sys/devices/platform/soc/soc:gpio_keys_hiby/disabled_keys", keyVal);
    LOGI("Set Pocket Lock: touchLock=%d keyLock=%d allowVol=%d (res1=%d res2=%d)", touchLock, keyLock, allowVol, ok1, ok2);
    return (ok1 || ok2) ? JNI_TRUE : JNI_FALSE;
}


JNIEXPORT jboolean JNICALL
Java_com_hione_player_M500AudioHal_nativeSetOutput(JNIEnv* env, jclass clazz, jstring output) {
    const char* str = env->GetStringUTFChars(output, nullptr);
    bool ok = writeSysfs("/sys/devices/platform/soc/soc:hiby,sound-plat/output", str);
    env->ReleaseStringUTFChars(output, str);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_hione_player_M500AudioHal_nativeSetVolume(JNIEnv* env, jclass clazz, jint volume) {
    char buf[64];
    snprintf(buf, sizeof(buf), "headset double %d", volume);
    bool ok = writeSysfs("/sys/devices/platform/soc/soc:hiby,sound-plat/volume", buf);
    return ok ? JNI_TRUE : JNI_FALSE;
}

} // extern "C"
