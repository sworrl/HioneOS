package com.hione.player.bpm

import java.io.File

/**
 * Small entry points kept for callers that only have a path (the data-verbose track facts strip).
 *
 * The 0.3.0 analyzer that lived here (dual-band envelope autocorrelation over 32 s from a quarter
 * of the way in) was replaced by [HioneTempoAnalyzer], which runs QM-DSP's TempoTrackV2 over the
 * whole track and caches the result per file. The accuracy test that compares the two is
 * app/src/main/cpp/hionebeat/hosttest/bpm_eval.py.
 */
object HioneBpmAnalyzer {

    /**
     * The track's tempo when one is known with enough confidence to show, else null. Uses the
     * cache, then the file's tag, then a full analysis. Blocking: call off the main thread.
     */
    fun analyze(path: String): Float? {
        val ctx = HioneTempoAnalyzer.appContext ?: return null
        val row = HioneTempoAnalyzer.resolve(ctx, path) ?: return null
        return HioneTempoAnalyzer.shownBpm(row).takeIf { it > 0f }
    }

    /**
     * The file's own BPM tag (ID3 TBPM / Vorbis BPM / MP4 tmpo) via jaudiotagger: real metadata
     * written by whoever tagged the file, not a measurement. Null when absent or implausible.
     */
    fun readTaggedBpm(path: String): Float? = runCatching {
        val f = File(path)
        if (!f.isFile) return null
        val tag = org.jaudiotagger.audio.AudioFileIO.read(f).tag ?: return null
        val raw = tag.getFirst(org.jaudiotagger.tag.FieldKey.BPM)?.trim().orEmpty()
        val v = raw.replace(',', '.').toFloatOrNull() ?: return null
        if (v in 40f..300f) v else null
    }.getOrNull()
}
