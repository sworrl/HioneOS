/*
 * HioneOS live beat tracking (BTrack). See hione_beat.h.
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
 */
#include "hione_beat.h"

#include <cmath>

// BTrack and QM-DSP both declare a global `enum WindowType`, so they live in separate files.
#include "BTrack.h"

namespace hionebeat {

namespace {
/**
 * Offset from the hop BTrack flags as a beat to the onset, from the synthetic drum test: its
 * beats land 0 to 17 ms after the hit depending on how the tempo falls on BTrack's whole-hop
 * beat period, about 11 ms on average. Seconds.
 */
constexpr double kLivePhaseSec = -0.011;
}  // namespace

// --------------------------------------------------------------------------------- LiveTracker

LiveTracker::LiveTracker(int srcRate) : srcRate_(srcRate > 0 ? srcRate : kRate) {
    rs_.reset(double(srcRate_), double(kRate));
    hop_.resize(kHop);
    bt_.reset(new BTrack(kHop, kWin));
}

LiveTracker::~LiveTracker() = default;

void LiveTracker::seed(double bpm, bool fix) {
    if (!(bpm > 0.0)) return;
    double b = foldBpm(bpm, 80.0, 160.0);
    bt_->setTempo(b);
    if (fix) bt_->fixTempo(b);
}

void LiveTracker::unfix() { bt_->doNotFixTempo(); }

double LiveTracker::tempo() const { return bt_->getCurrentTempoEstimate(); }

void LiveTracker::feed(const float* mono, size_t n, std::vector<double>& beatsSrcFrames) {
    out_.clear();
    rs_.process(mono, n, out_);
    pending_.insert(pending_.end(), out_.begin(), out_.end());
    const double toSrc = double(srcRate_) / double(kRate);
    while (pending_.size() - pendingPos_ >= size_t(kHop)) {
        for (int i = 0; i < kHop; i++) hop_[size_t(i)] = pending_[pendingPos_ + size_t(i)];
        pendingPos_ += kHop;
        bt_->processAudioFrame(hop_.data());
        hops_++;
        if (bt_->beatDueInCurrentFrame()) {
            // The hop just processed ends at sample hops_ * kHop of the 44.1 kHz stream.
            double at44 = double(hops_) * kHop + kLivePhaseSec * kRate;
            beatsSrcFrames.push_back(at44 * toSrc);
        }
    }
    if (pendingPos_ > 8192) {
        pending_.erase(pending_.begin(), pending_.begin() + long(pendingPos_));
        pendingPos_ = 0;
    }
}

}  // namespace hionebeat
