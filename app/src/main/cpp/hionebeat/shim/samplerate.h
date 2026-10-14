/*
 * Minimal stand-in for the one libsamplerate call BTrack makes.
 *
 * Copyright (C) 2026 sworrl. Part of HioneOS.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 *
 * BTrack resamples its onset-detection buffer to 512 points with src_simple() before every tempo
 * update. HioneOS always runs BTrack with a 512-sample hop, so that buffer is already 512 points and
 * the ratio is exactly 1.0. This shim copies in that case and does windowed-sinc interpolation for
 * any other ratio, so the full libsamplerate (and its multi-megabyte coefficient tables) is not
 * needed. The struct layout and names match libsamplerate's public API for the fields BTrack uses.
 */
#ifndef HIONE_SAMPLERATE_SHIM_H
#define HIONE_SAMPLERATE_SHIM_H

#ifdef __cplusplus
extern "C" {
#endif

typedef struct {
    const float *data_in;
    float *data_out;
    long input_frames, output_frames;
    long input_frames_used, output_frames_gen;
    int end_of_input;
    double src_ratio;
} SRC_DATA;

enum {
    SRC_SINC_BEST_QUALITY = 0,
    SRC_SINC_MEDIUM_QUALITY = 1,
    SRC_SINC_FASTEST = 2,
    SRC_ZERO_ORDER_HOLD = 3,
    SRC_LINEAR = 4
};

/* Mono only (channels must be 1). Returns 0 on success. */
int src_simple(SRC_DATA *data, int converter_type, int channels);

#ifdef __cplusplus
}
#endif

#endif
