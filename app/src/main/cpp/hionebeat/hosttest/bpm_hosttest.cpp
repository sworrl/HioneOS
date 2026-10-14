/*
 * Host harness for the HioneOS tempo code. Reads raw interleaved float32 PCM and prints JSON.
 *
 * Copyright (C) 2026 sworrl. Part of HioneOS. GPL-3.0-or-later.
 *
 *   bpm_hosttest offline <file.f32> <rate> <channels>
 *   bpm_hosttest live    <file.f32> <rate> <channels> [seedBpm] [fix 0|1] [chunkFrames]
 *
 * "live" feeds the file in chunks the size the player's tap delivers, the way the app does, and
 * prints every beat BTrack reports (seconds of source audio) with its tempo at that moment.
 */
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <vector>

#include "hione_beat.h"

static std::vector<float> readF32(const char* path) {
    std::vector<float> v;
    FILE* f = std::fopen(path, "rb");
    if (!f) return v;
    std::fseek(f, 0, SEEK_END);
    long n = std::ftell(f);
    std::fseek(f, 0, SEEK_SET);
    v.resize(size_t(n) / sizeof(float));
    size_t got = std::fread(v.data(), sizeof(float), v.size(), f);
    v.resize(got);
    std::fclose(f);
    return v;
}

int main(int argc, char** argv) {
    if (argc < 5) {
        std::fprintf(stderr, "usage: %s offline|live file.f32 rate channels [seed] [fix] [chunk]\n", argv[0]);
        return 2;
    }
    const char* mode = argv[1];
    std::vector<float> pcm = readF32(argv[2]);
    int rate = std::atoi(argv[3]);
    int ch = std::atoi(argv[4]);
    if (pcm.empty() || rate <= 0 || ch <= 0) {
        std::fprintf(stderr, "bad input\n");
        return 1;
    }
    int frames = int(pcm.size() / size_t(ch));
    auto t0 = std::chrono::steady_clock::now();

    if (std::strcmp(mode, "offline") == 0) {
        hionebeat::OfflineAnalyzer a(rate, ch);
        const int chunk = 4096;
        for (int f = 0; f < frames; f += chunk) {
            int n = std::min(chunk, frames - f);
            a.feedInterleaved(pcm.data() + size_t(f) * ch, n);
        }
        hionebeat::OfflineResult r = a.finish();
        double ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
        std::printf("{\"ok\":%s,\"bpm\":%.4f,\"raw\":%.4f,\"conf\":%.4f,\"first\":%.4f,\"curve\":%.3f,"
                    "\"beats\":%d,\"sec\":%.2f,\"ms\":%.1f,\"beat_times\":[",
                    r.ok ? "true" : "false", r.bpm, r.rawBpm, r.confidence, r.firstBeatSec,
                    r.medianTempoCurve, r.beatCount, r.analysedSec, ms);
        for (size_t i = 0; i < r.beatsSec.size(); i++) std::printf(i ? ",%.4f" : "%.4f", r.beatsSec[i]);
        std::printf("]}\n");
        return 0;
    }

    if (std::strcmp(mode, "live") == 0) {
        double seed = argc > 5 ? std::atof(argv[5]) : 0.0;
        bool fix = argc > 6 ? std::atoi(argv[6]) != 0 : false;
        int chunk = argc > 7 ? std::atoi(argv[7]) : 1024;
        hionebeat::LiveTracker lt(rate);
        if (seed > 0) lt.seed(seed, fix);
        std::vector<float> mono(static_cast<size_t>(chunk));
        std::vector<double> beats;
        std::printf("{\"beats\":[");
        bool first = true;
        for (int f = 0; f < frames; f += chunk) {
            int n = std::min(chunk, frames - f);
            for (int i = 0; i < n; i++) {
                float s = 0;
                for (int c = 0; c < ch; c++) s += pcm[size_t(f + i) * ch + c];
                mono[size_t(i)] = s / float(ch);
            }
            beats.clear();
            lt.feed(mono.data(), size_t(n), beats);
            for (double b : beats) {
                std::printf(first ? "[%.4f,%.3f]" : ",[%.4f,%.3f]", b / rate, lt.tempo());
                first = false;
            }
        }
        double ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
        std::printf("],\"ms\":%.1f,\"sec\":%.2f}\n", ms, double(frames) / rate);
        return 0;
    }
    return 2;
}
