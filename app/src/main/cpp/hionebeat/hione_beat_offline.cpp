/*
 * HioneOS whole-track tempo (QM-DSP TempoTrackV2). See hione_beat.h.
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
 * The offline settings (complex spectral difference, 512 step, 1024 window, drop the first two
 * detection-function values) follow the QM Vamp beat tracker and Mixxx's
 * AnalyzerQueenMaryBeats, which both drive TempoTrackV2 this way.
 */
#include "hione_beat.h"

#include <algorithm>
#include <cmath>
#include <numeric>

#include "dsp/onsets/DetectionFunction.h"
#include "dsp/tempotracking/TempoTrackV2.h"

namespace hionebeat {

namespace {
constexpr int kZero = 8;          // zero crossings each side
constexpr int kPhases = 256;      // polyphase resolution, phases per input sample

/**
 * Offset from a tracked beat to the onset it marks, measured with the synthetic drum test
 * (hosttest/bpm_eval.py, "phase_ms"): grid beats came out 12.5 ms late at every tempo and at
 * 44.1, 48 and 96 kHz. Detection-function value i is a window centred on i * kHop, and the
 * complex-SD rise lands about one hop after the hit.
 */
constexpr double kOfflinePhaseSec = -0.0125;

double median(std::vector<double> v) {
    if (v.empty()) return 0.0;
    size_t m = v.size() / 2;
    std::nth_element(v.begin(), v.begin() + m, v.end());
    double hi = v[m];
    if (v.size() % 2 == 1) return hi;
    double lo = *std::max_element(v.begin(), v.begin() + m);
    return 0.5 * (lo + hi);
}
}  // namespace

// ------------------------------------------------------------------------------------ Resampler

void Resampler::reset(double inRate, double outRate) {
    ratio_ = outRate / inRate;
    step_ = inRate / outRate;
    bypass_ = std::fabs(ratio_ - 1.0) < 1e-9;
    fc_ = std::min(1.0, ratio_);
    half_ = kZero / fc_;
    buf_.clear();
    t_ = 0.0;
    if (bypass_) return;
    // Polyphase table: kPhases fractional positions, taps_ coefficients each, every phase
    // normalised to unity gain. Output k uses the phase nearest its fractional input position
    // (1/256 of a sample, far below anything a tempo tracker can see).
    taps_ = 2 * int(std::ceil(half_));
    const int h = taps_ / 2;
    table_.assign(size_t(kPhases) * size_t(taps_), 0.0f);
    for (int p = 0; p < kPhases; p++) {
        double frac = double(p) / kPhases;
        double sum = 0;
        for (int j = 0; j < taps_; j++) {
            double x = frac + double(h - 1 - j);   // t - input index
            double u = std::fabs(x) * fc_;          // zero-crossing units
            double v = 0;
            if (u < kZero) {
                double sn = u < 1e-9 ? 1.0 : std::sin(M_PI * u) / (M_PI * u);
                double w = 0.42 + 0.5 * std::cos(M_PI * u / kZero) + 0.08 * std::cos(2.0 * M_PI * u / kZero);
                v = sn * w;
            }
            table_[size_t(p) * taps_ + size_t(j)] = float(v);
            sum += v;
        }
        if (sum > 1e-9)
            for (int j = 0; j < taps_; j++) table_[size_t(p) * taps_ + size_t(j)] = float(table_[size_t(p) * taps_ + size_t(j)] / sum);
    }
    // Pre-roll: the first output is centred on input sample 0, which needs h samples before it.
    buf_.assign(size_t(h), 0.0f);
    t_ = double(h);
}

void Resampler::process(const float* in, size_t n, std::vector<float>& out) {
    if (bypass_) {
        out.insert(out.end(), in, in + n);
        return;
    }
    buf_.insert(buf_.end(), in, in + n);
    const int h = taps_ / 2;
    const long size = long(buf_.size());
    while (true) {
        long base = long(std::floor(t_));
        long first = base - h + 1;
        if (first + taps_ > size) break;
        int p = int((t_ - double(base)) * kPhases + 0.5);
        if (p >= kPhases) { p = 0; base += 1; first += 1; if (first + taps_ > size) break; }
        const float* c = &table_[size_t(p) * taps_];
        const float* x = &buf_[size_t(first)];
        float acc = 0.0f;
        for (int j = 0; j < taps_; j++) acc += c[j] * x[j];
        out.push_back(acc);
        t_ += step_;
    }
    // Keep only the history the next output still needs.
    long drop = long(std::floor(t_)) - h;
    if (drop > 4096) {
        buf_.erase(buf_.begin(), buf_.begin() + drop);
        t_ -= double(drop);
    }
}

void Resampler::flush(std::vector<float>& out) {
    if (bypass_) return;
    std::vector<float> zeros(size_t(std::ceil(half_)) + 2, 0.0f);
    process(zeros.data(), zeros.size(), out);
}

// ---------------------------------------------------------------------------------- folding / fit

double foldBpm(double bpm, double lo, double hi) {
    if (!(bpm > 0.0) || !std::isfinite(bpm)) return 0.0;
    while (bpm >= hi) bpm *= 0.5;
    while (bpm < lo) bpm *= 2.0;
    return bpm;
}

OfflineResult fitConstantGrid(const std::vector<double>& beats, double analysedSec) {
    OfflineResult r;
    r.analysedSec = analysedSec;
    r.beatCount = int(beats.size());
    r.beatsSec = beats;
    if (beats.size() < 8) return r;

    std::vector<double> ibi;
    ibi.reserve(beats.size());
    for (size_t i = 1; i < beats.size(); i++) ibi.push_back(beats[i] - beats[i - 1]);
    double p0 = median(ibi);
    if (!(p0 > 0.05)) return r;

    // Beat numbers: count whole periods between consecutive beats so a skipped or doubled beat
    // does not shift the rest of the numbering.
    std::vector<double> n(beats.size(), 0.0);
    for (size_t i = 1; i < beats.size(); i++) {
        double k = std::round(ibi[i - 1] / p0);
        if (k < 1.0) k = 1.0;
        n[i] = n[i - 1] + k;
    }

    // Least squares b = phi + P n, twice: the second pass drops beats more than 15 % of a period
    // off the first line (fills, a held break, a tracker slip).
    double P = p0, phi = beats[0];
    std::vector<char> use(beats.size(), 1);
    for (int pass = 0; pass < 3; pass++) {
        double sn = 0, sb = 0, snn = 0, snb = 0, cnt = 0;
        for (size_t i = 0; i < beats.size(); i++) {
            if (!use[i]) continue;
            sn += n[i]; sb += beats[i]; snn += n[i] * n[i]; snb += n[i] * beats[i]; cnt += 1;
        }
        double den = cnt * snn - sn * sn;
        if (cnt < 4 || std::fabs(den) < 1e-12) break;
        P = (cnt * snb - sn * sb) / den;
        phi = (sb - P * sn) / cnt;
        if (pass == 2) break;
        for (size_t i = 0; i < beats.size(); i++) {
            double res = beats[i] - (phi + P * n[i]);
            use[i] = std::fabs(res) < 0.15 * P;
        }
    }
    if (!(P > 0.05)) return r;

    // Residual spread of the inliers and the standard error of P.
    double ss = 0, cnt = 0, nm = 0;
    for (size_t i = 0; i < beats.size(); i++) if (use[i]) { nm += n[i]; cnt += 1; }
    nm = cnt > 0 ? nm / cnt : 0;
    double snn = 0;
    for (size_t i = 0; i < beats.size(); i++) {
        if (!use[i]) continue;
        double res = beats[i] - (phi + P * n[i]);
        ss += res * res;
        snn += (n[i] - nm) * (n[i] - nm);
    }
    double rms = cnt > 2 ? std::sqrt(ss / (cnt - 2)) : P;
    double seP = (cnt > 2 && snn > 0) ? rms / std::sqrt(snn) : P;

    // A track whose tempo changes between sections (or drifts, played live) has no single
    // straight line through its beats: the least-squares period lands between the sections, the
    // beats wander more than 12 % of a period off the line, or most of them miss it entirely.
    // Then the typical beat interval, the tempo most of the song is at, is the honest number.
    const bool constant = std::fabs(P - p0) <= 0.015 * p0 && rms <= 0.12 * P &&
                          cnt >= 0.6 * double(beats.size());
    if (!constant) {
        // Mean of the intervals near the median: beats sit on 11.6 ms detection frames, so the
        // median alone is quantised to about 1 % at 130 BPM, and the mean of many is not.
        double sum = 0;
        int k = 0;
        for (double d : ibi) if (std::fabs(d - p0) <= 0.05 * p0) { sum += d; k++; }
        P = k >= 4 ? sum / k : p0;
        double acc = 0, c = 0;
        for (size_t i = 0; i < beats.size() && i < 16; i++) { acc += beats[i] - P * n[i]; c += 1; }
        phi = c > 0 ? acc / c : beats[0];
    }

    double raw = 60.0 / P;
    double seBpm = constant ? 60.0 * seP / (P * P) : 0.0;
    // Most produced music sits on a whole-number tempo. Snap when the fit cannot tell the
    // difference anyway, then refit the phase for the snapped period.
    double snapped = std::round(raw);
    if (constant && std::fabs(raw - snapped) <= std::max(0.05, 2.0 * seBpm)) {
        raw = snapped;
        P = 60.0 / raw;
        double acc = 0;
        for (size_t i = 0; i < beats.size(); i++) if (use[i]) acc += beats[i] - P * n[i];
        phi = cnt > 0 ? acc / cnt : phi;
    }

    // Regularity: share of inter-beat intervals within 5 % of the period.
    int regular = 0;
    for (double d : ibi) if (std::fabs(d - P) <= 0.05 * P) regular++;
    double c1 = double(regular) / double(ibi.size());
    if (constant) {
        // Grid fit: a steady track stays within a few percent of one period across its length.
        double rel = rms / P;
        double c2 = std::exp(-(rel / 0.08) * (rel / 0.08));
        double inlierShare = cnt / double(beats.size());
        r.confidence = c1 * (0.6 + 0.4 * c2) * std::min(1.0, inlierShare / 0.8);
    } else {
        // Real beats, but no one grid: the tempo is right for most of the song, the phase from
        // the file is not to be trusted (the live tracker supplies it).
        r.confidence = c1 * 0.8;
    }
    r.confidence = std::max(0.0, std::min(1.0, r.confidence));
    r.constantTempo = constant;

    r.rawBpm = raw;
    r.bpm = foldBpm(raw);
    double Pf = 60.0 / r.bpm;
    double first = std::fmod(phi, Pf);
    if (first < 0) first += Pf;
    r.firstBeatSec = first;
    r.ok = true;
    return r;
}

// ------------------------------------------------------------------------------ OfflineAnalyzer

OfflineAnalyzer::OfflineAnalyzer(int srcRate, int channels)
    : channels_(std::max(1, channels)), srcRate_(srcRate) {
    rs_.reset(double(srcRate), double(kRate));
    DFConfig c;
    c.stepSize = kHop;
    c.frameLength = kWin;
    c.DFType = DF_COMPLEXSD;
    c.dbRise = 3;
    c.adaptiveWhitening = false;
    c.whiteningRelaxCoeff = -1;
    c.whiteningFloor = -1;
    df_.reset(new DetectionFunction(c));
    frame_.resize(kWin);
    // Centre the first analysis window on sample 0, so detection-function value i is the window
    // centred on i * kHop.
    pending_.assign(kWin / 2, 0.0f);
}

OfflineAnalyzer::~OfflineAnalyzer() = default;

void OfflineAnalyzer::feedInterleaved(const float* x, int frames) {
    if (frames <= 0) return;
    mono_.resize(size_t(frames));
    const float g = 1.0f / float(channels_);
    for (int f = 0; f < frames; f++) {
        float s = 0;
        for (int c = 0; c < channels_; c++) s += x[size_t(f) * channels_ + c];
        mono_[size_t(f)] = s * g;
    }
    samplesIn_ += frames;
    pushMono(mono_.data(), mono_.size());
}

void OfflineAnalyzer::pushMono(const float* x, size_t n) {
    resampled_.clear();
    rs_.process(x, n, resampled_);
    pending_.insert(pending_.end(), resampled_.begin(), resampled_.end());
    drainFrames(false);
}

void OfflineAnalyzer::drainFrames(bool final) {
    while (pending_.size() - pendingPos_ >= size_t(kWin)) {
        for (int i = 0; i < kWin; i++) frame_[size_t(i)] = pending_[pendingPos_ + size_t(i)];
        dfOut_.push_back(df_->processTimeDomain(frame_.data()));
        pendingPos_ += kHop;
    }
    if (final && pending_.size() > pendingPos_) {
        size_t left = pending_.size() - pendingPos_;
        if (left > size_t(kHop)) {
            std::fill(frame_.begin(), frame_.end(), 0.0);
            for (size_t i = 0; i < left && i < size_t(kWin); i++) frame_[i] = pending_[pendingPos_ + i];
            dfOut_.push_back(df_->processTimeDomain(frame_.data()));
        }
    }
    if (pendingPos_ > (1u << 16)) {
        pending_.erase(pending_.begin(), pending_.begin() + long(pendingPos_));
        pendingPos_ = 0;
    }
}

OfflineResult OfflineAnalyzer::finish() {
    resampled_.clear();
    rs_.flush(resampled_);
    pending_.insert(pending_.end(), resampled_.begin(), resampled_.end());
    drainFrames(true);

    double analysedSec = srcRate_ > 0 ? double(samplesIn_) / double(srcRate_) : 0.0;

    int nonZero = int(dfOut_.size());
    while (nonZero > 0 && dfOut_[size_t(nonZero - 1)] <= 0.0) --nonZero;
    // As the Vamp plugin and Mixxx do: drop the first two values, which can read the start of
    // the signal itself as an onset.
    constexpr int kSkip = 2;
    if (nonZero - kSkip < 64) {
        OfflineResult r;
        r.analysedSec = analysedSec;
        return r;
    }
    std::vector<double> df(dfOut_.begin() + kSkip, dfOut_.begin() + nonZero);
    std::vector<double> beatPeriod(df.size(), 0.0);
    std::vector<double> tempi;
    TempoTrackV2 tt(float(kRate), kHop);
    tt.calculateBeatPeriod(df, beatPeriod, tempi);
    std::vector<double> beats;
    tt.calculateBeats(df, beatPeriod, beats);

    std::vector<double> beatsSec;
    beatsSec.reserve(beats.size());
    for (double b : beats) beatsSec.push_back((b + kSkip) * kHop / double(kRate) + kOfflinePhaseSec);

    OfflineResult r = fitConstantGrid(beatsSec, analysedSec);
    std::vector<double> tp;
    tp.reserve(tempi.size());
    for (double t : tempi) if (t > 0) tp.push_back(t);
    r.medianTempoCurve = median(tp);
    return r;
}

}  // namespace hionebeat
