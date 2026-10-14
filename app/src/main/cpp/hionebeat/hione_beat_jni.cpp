/*
 * JNI bridge for libhionebeat (com.hione.player.bpm.HioneBeatNative).
 *
 * Copyright (C) 2026 sworrl. Part of HioneOS.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 */
#include <jni.h>

#include <vector>

#include "hione_beat.h"

using hionebeat::LiveTracker;
using hionebeat::OfflineAnalyzer;
using hionebeat::OfflineResult;

#define JFN(name) Java_com_hione_player_bpm_HioneBeatNative_##name

extern "C" {

JNIEXPORT jlong JNICALL JFN(offlineCreate)(JNIEnv*, jclass, jint rate, jint channels) {
    if (rate <= 0 || channels <= 0) return 0;
    return reinterpret_cast<jlong>(new OfflineAnalyzer(rate, channels));
}

JNIEXPORT void JNICALL JFN(offlineFeed)(JNIEnv* env, jclass, jlong h, jfloatArray data, jint frames) {
    auto* a = reinterpret_cast<OfflineAnalyzer*>(h);
    if (!a || !data || frames <= 0) return;
    jfloat* p = env->GetFloatArrayElements(data, nullptr);
    if (!p) return;
    a->feedInterleaved(p, frames);
    env->ReleaseFloatArrayElements(data, p, JNI_ABORT);
}

/** [ok, bpm, rawBpm, confidence, firstBeatSec, beatCount, analysedSec, medianTempoCurve] */
JNIEXPORT jdoubleArray JNICALL JFN(offlineFinish)(JNIEnv* env, jclass, jlong h) {
    auto* a = reinterpret_cast<OfflineAnalyzer*>(h);
    if (!a) return nullptr;
    OfflineResult r = a->finish();
    jdouble v[8] = {r.ok ? 1.0 : 0.0, r.bpm, r.rawBpm, r.confidence, r.firstBeatSec,
                    double(r.beatCount), r.analysedSec, r.medianTempoCurve};
    jdoubleArray out = env->NewDoubleArray(8);
    if (out) env->SetDoubleArrayRegion(out, 0, 8, v);
    return out;
}

JNIEXPORT void JNICALL JFN(offlineRelease)(JNIEnv*, jclass, jlong h) {
    delete reinterpret_cast<OfflineAnalyzer*>(h);
}

JNIEXPORT jlong JNICALL JFN(liveCreate)(JNIEnv*, jclass, jint rate) {
    if (rate <= 0) return 0;
    return reinterpret_cast<jlong>(new LiveTracker(rate));
}

JNIEXPORT void JNICALL JFN(liveSeed)(JNIEnv*, jclass, jlong h, jdouble bpm, jboolean fix) {
    auto* t = reinterpret_cast<LiveTracker*>(h);
    if (t) t->seed(bpm, fix == JNI_TRUE);
}

JNIEXPORT void JNICALL JFN(liveUnfix)(JNIEnv*, jclass, jlong h) {
    auto* t = reinterpret_cast<LiveTracker*>(h);
    if (t) t->unfix();
}

/**
 * Feeds [n] mono samples. Writes beat positions (source frames since create) into [beatsOut]
 * and returns how many; a chunk of a few thousand samples holds at most one or two beats.
 */
JNIEXPORT jint JNICALL JFN(liveFeed)(JNIEnv* env, jclass, jlong h, jfloatArray mono, jint n,
                                     jdoubleArray beatsOut) {
    auto* t = reinterpret_cast<LiveTracker*>(h);
    if (!t || !mono || n <= 0) return 0;
    jfloat* p = env->GetFloatArrayElements(mono, nullptr);
    if (!p) return 0;
    std::vector<double> beats;
    t->feed(p, size_t(n), beats);
    env->ReleaseFloatArrayElements(mono, p, JNI_ABORT);
    jint cap = beatsOut ? env->GetArrayLength(beatsOut) : 0;
    jint count = jint(beats.size()) < cap ? jint(beats.size()) : cap;
    if (count > 0) env->SetDoubleArrayRegion(beatsOut, 0, count, beats.data());
    return count;
}

JNIEXPORT jdouble JNICALL JFN(liveTempo)(JNIEnv*, jclass, jlong h) {
    auto* t = reinterpret_cast<LiveTracker*>(h);
    return t ? t->tempo() : 0.0;
}

JNIEXPORT void JNICALL JFN(liveRelease)(JNIEnv*, jclass, jlong h) {
    delete reinterpret_cast<LiveTracker*>(h);
}

}  // extern "C"
