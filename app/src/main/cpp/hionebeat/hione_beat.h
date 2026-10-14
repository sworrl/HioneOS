/*
 * HioneOS tempo and beat analysis: QM-DSP's TempoTrackV2 for whole tracks, BTrack for live audio.
 *
 * Copyright (C) 2026 sworrl. Part of HioneOS.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See
 * the GNU General Public License for more details.
 *
 * Both trackers are run at one fixed analysis rate (44100 Hz, 512-sample hop) whatever the file's
 * own rate is. TempoTrackV2's comb filter and BTrack's tempo states are defined in detection-
 * function frames, and BTrack hard-codes 44100 in its tempo maths, so feeding them 48 or 96 kHz
 * audio directly would shift every tempo they report. Everything goes through [Resampler] first.
 *
 * No Android or JNI dependencies here, so the host test harness builds the same code.
 */
#ifndef HIONE_BEAT_H
#define HIONE_BEAT_H

#include <cstdint>
#include <memory>
#include <vector>

class DetectionFunction;
class BTrack;

namespace hionebeat {

constexpr int kRate = 44100;
constexpr int kHop = 512;
constexpr int kWin = 1024;

/** Preferred display range. A tempo outside it is folded by octaves into it. */
constexpr double kFoldMinBpm = 70.0;
constexpr double kFoldMaxBpm = 180.0;

/** Streaming mono polyphase windowed-sinc resampler (Blackman window, 8 zero crossings). */
class Resampler {
public:
    void reset(double inRate, double outRate);
    /** Appends resampled output for [n] input samples. Output k is input time k * in/out exactly. */
    void process(const float* in, size_t n, std::vector<float>& out);
    /** Feeds enough silence to flush the filter tail. */
    void flush(std::vector<float>& out);
    double ratio() const { return ratio_; }

private:
    double ratio_ = 1.0;   // out / in
    double step_ = 1.0;    // in / out
    double fc_ = 1.0;
    double half_ = 8.0;
    bool bypass_ = true;
    int taps_ = 0;
    std::vector<float> buf_;
    double t_ = 0.0;
    std::vector<float> table_;
};

struct OfflineResult {
    bool ok = false;
    /** Folded into [kFoldMinBpm, kFoldMaxBpm). */
    double bpm = 0.0;
    /** Constant-tempo fit before octave folding. */
    double rawBpm = 0.0;
    /** 0..1: how regular the tracked beats are around the fitted period. */
    double confidence = 0.0;
    /** Time of the first grid beat at or after 0 s, on the folded grid. */
    double firstBeatSec = 0.0;
    /** False when the beats do not fit one straight grid; bpm is then the median interval. */
    bool constantTempo = true;
    /** Median of TempoTrackV2's own per-frame tempo curve, unfolded. */
    double medianTempoCurve = 0.0;
    int beatCount = 0;
    double analysedSec = 0.0;
    std::vector<double> beatsSec;
};

/** Whole-track tempo with QM-DSP DetectionFunction (complex spectral difference) + TempoTrackV2. */
class OfflineAnalyzer {
public:
    OfflineAnalyzer(int srcRate, int channels);
    ~OfflineAnalyzer();
    void feedInterleaved(const float* x, int frames);
    OfflineResult finish();

private:
    void pushMono(const float* x, size_t n);
    void drainFrames(bool final);
    int channels_;
    Resampler rs_;
    std::vector<float> mono_;
    std::vector<float> resampled_;
    std::vector<float> pending_;
    size_t pendingPos_ = 0;
    std::vector<double> frame_;
    std::unique_ptr<DetectionFunction> df_;
    std::vector<double> dfOut_;
    int64_t samplesIn_ = 0;
    int srcRate_;
};

/** Fits a constant grid to beat times. Exposed for the host test. */
OfflineResult fitConstantGrid(const std::vector<double>& beatsSec, double analysedSec);

/** Folds [bpm] by octaves into [lo, hi). */
double foldBpm(double bpm, double lo = kFoldMinBpm, double hi = kFoldMaxBpm);

/**
 * Real-time beat tracker: BTrack on 44.1 kHz mono, 512 hop. Beats come back as positions in the
 * caller's own sample clock (source frames since reset), so the caller can map them to audible
 * time with its AudioTrack timestamps.
 */
class LiveTracker {
public:
    explicit LiveTracker(int srcRate);
    ~LiveTracker();
    /** Seeds BTrack's tempo (folded into its 80..160 range). [fix] holds it there. */
    void seed(double bpm, bool fix);
    void unfix();
    /** [mono] at the source rate. Appends beat positions (source frames since reset). */
    void feed(const float* mono, size_t n, std::vector<double>& beatsSrcFrames);
    double tempo() const;
    int srcRate() const { return srcRate_; }

private:
    int srcRate_;
    Resampler rs_;
    std::vector<float> out_;
    std::vector<float> pending_;
    size_t pendingPos_ = 0;
    std::vector<double> hop_;
    std::unique_ptr<BTrack> bt_;
    int64_t hops_ = 0;
};

}  // namespace hionebeat

#endif
