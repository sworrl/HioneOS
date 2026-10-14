/*
 * Minimal stand-in for libsamplerate's src_simple(), see samplerate.h.
 *
 * Copyright (C) 2026 sworrl. Part of HioneOS.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 */
#include "samplerate.h"
#include <math.h>
#include <string.h>

#define SHIM_ZERO_CROSSINGS 8

static double shim_sinc(double x) {
    if (fabs(x) < 1e-9) return 1.0;
    return sin(M_PI * x) / (M_PI * x);
}

int src_simple(SRC_DATA *d, int converter_type, int channels) {
    (void) converter_type;
    if (!d || !d->data_in || !d->data_out || channels != 1 || d->src_ratio <= 0.0) return 1;
    long nIn = d->input_frames;
    long nOut = (long) floor(nIn * d->src_ratio);
    if (nOut > d->output_frames) nOut = d->output_frames;
    if (nOut < 0) nOut = 0;

    if (fabs(d->src_ratio - 1.0) < 1e-12) {
        long n = nIn < d->output_frames ? nIn : d->output_frames;
        memcpy(d->data_out, d->data_in, (size_t) n * sizeof(float));
        d->input_frames_used = n;
        d->output_frames_gen = n;
        return 0;
    }

    /* Low-pass at the lower of the two Nyquist rates, Blackman-windowed sinc. */
    double fc = d->src_ratio < 1.0 ? d->src_ratio : 1.0;
    double half = SHIM_ZERO_CROSSINGS / fc;
    for (long k = 0; k < nOut; k++) {
        double t = k / d->src_ratio;
        long i0 = (long) ceil(t - half);
        long i1 = (long) floor(t + half);
        double acc = 0.0, wsum = 0.0;
        for (long i = i0; i <= i1; i++) {
            if (i < 0 || i >= nIn) continue;
            double x = t - (double) i;
            double u = x / half; /* -1..1 */
            double w = 0.42 + 0.5 * cos(M_PI * u) + 0.08 * cos(2.0 * M_PI * u);
            double h = fc * shim_sinc(fc * x) * w;
            acc += h * d->data_in[i];
            wsum += h;
        }
        d->data_out[k] = (float) (wsum > 1e-12 ? acc / wsum : 0.0);
    }
    d->input_frames_used = nIn;
    d->output_frames_gen = nOut;
    return 0;
}
