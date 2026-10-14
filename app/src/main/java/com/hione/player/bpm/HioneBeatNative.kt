package com.hione.player.bpm

import android.util.Log

/**
 * JNI surface of libhionebeat (app/src/main/cpp/hionebeat).
 *
 * Offline: QM-DSP's TempoTrackV2 over a whole decoded track (the analyzer Mixxx uses).
 * Live: BTrack over the PCM the player is writing to its AudioTrack.
 *
 * Both are GPL code from Queen Mary University of London; see ATTRIBUTIONS.md. Everything runs at
 * a fixed 44.1 kHz analysis rate inside the library, so callers pass audio at its own rate.
 */
object HioneBeatNative {
    private const val TAG = "HioneBeatNative"

    /** False when the library failed to load. Every caller must check it and fall back to "no tempo". */
    val available: Boolean by lazy {
        try {
            System.loadLibrary("hionebeat")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "libhionebeat failed to load, tempo analysis is off", t)
            false
        }
    }

    @JvmStatic external fun offlineCreate(sampleRate: Int, channels: Int): Long
    @JvmStatic external fun offlineFeed(handle: Long, interleaved: FloatArray, frames: Int)
    /** [ok, bpm, rawBpm, confidence, firstBeatSec, beatCount, analysedSec, medianTempoCurve] */
    @JvmStatic external fun offlineFinish(handle: Long): DoubleArray?
    @JvmStatic external fun offlineRelease(handle: Long)

    @JvmStatic external fun liveCreate(sampleRate: Int): Long
    @JvmStatic external fun liveSeed(handle: Long, bpm: Double, fix: Boolean)
    @JvmStatic external fun liveUnfix(handle: Long)
    /** Returns how many beat positions (source frames since create) were written to [beatsOut]. */
    @JvmStatic external fun liveFeed(handle: Long, mono: FloatArray, n: Int, beatsOut: DoubleArray): Int
    @JvmStatic external fun liveTempo(handle: Long): Double
    @JvmStatic external fun liveRelease(handle: Long)
}
