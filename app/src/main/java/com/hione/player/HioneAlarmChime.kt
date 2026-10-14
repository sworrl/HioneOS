package com.hione.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.tanh

/**
 * The alarm's built-in default sound: a synthesized bell arpeggio, rendered ONCE to a plain
 * 48 kHz / 16-bit / stereo WAV under filesDir and then played through the exact same ExoPlayer
 * path as every library track (bit-perfect DirectPCM sink, no separate ringtone/SoundPool path —
 * see the audio-lockdown rule in PlayerHolder.ensure). Exists so an alarm can NEVER be silent
 * just because the library is empty, unscanned, or the chosen album vanished from the SD card.
 *
 * Bump [VERSION] if the render changes so a stale cached file is regenerated.
 */
object HioneAlarmChime {
    private const val VERSION = 1
    private const val SAMPLE_RATE = 48_000
    private const val CHANNELS = 2
    const val MEDIA_ID = "hione:alarm-chime"

    fun file(ctx: Context): File = File(File(ctx.applicationContext.filesDir, "alarm"), "hione_chime_v$VERSION.wav")

    /** Ready-to-play MediaItem; renders the WAV on first use (~30 ms of math, ~2.7 MB on disk). */
    fun mediaItem(ctx: Context): MediaItem {
        val f = ensureRendered(ctx)
        return MediaItem.Builder()
            .setUri(Uri.fromFile(f))
            .setMediaId(MEDIA_ID)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle("${Mascot.name} Chime")
                    .setArtist("${Mascot.musicAppName} Alarm")
                    .setAlbumTitle("Alarm")
                    .build()
            )
            .build()
    }

    @Synchronized
    fun ensureRendered(ctx: Context): File {
        val f = file(ctx)
        if (f.exists() && f.length() > 44) return f
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, f.name + ".tmp")
        render(tmp)
        if (!tmp.renameTo(f)) { tmp.copyTo(f, overwrite = true); tmp.delete() }
        return f
    }

    // ---- Synthesis -------------------------------------------------------------------------

    private data class Note(val atSec: Double, val hz: Double, val gain: Double, val pan: Double)

    /** E-major pentatonic bell motif, rising then answering, ~7 s per loop (REPEAT_MODE_ALL in the
     *  ring service makes it continuous). Frequencies are equal-tempered from A4 = 440 Hz. */
    private fun score(): List<Note> {
        val e5 = 659.255; val gs5 = 830.609; val b5 = 987.767; val cs6 = 1108.731; val e6 = 1318.510; val gs6 = 1661.219
        val out = ArrayList<Note>()
        var t = 0.15
        fun n(hz: Double, gain: Double = 1.0, pan: Double = 0.0, gap: Double = 0.34) { out += Note(t, hz, gain, pan); t += gap }
        // Phrase 1: ascending call
        n(e5, 0.85, -0.35); n(gs5, 0.9, -0.15); n(b5, 0.95, 0.1); n(e6, 1.0, 0.3, gap = 0.55)
        // Phrase 2: sparkle answer
        n(cs6, 0.8, 0.35, gap = 0.26); n(b5, 0.75, 0.15, gap = 0.26); n(gs6, 0.7, -0.2, gap = 0.4); n(e6, 0.95, 0.0, gap = 0.7)
        // Phrase 3: descending resolve
        n(b5, 0.8, 0.25); n(gs5, 0.8, 0.0); n(e5, 0.9, -0.3, gap = 0.6)
        // Chord tail: E5 + B5 + E6 together, then rest until the loop point
        out += Note(t, e5, 0.7, -0.2); out += Note(t, b5, 0.6, 0.2); out += Note(t, e6, 0.55, 0.0)
        return out
    }

    private fun render(dst: File) {
        val loopSec = 7.0
        val frames = (loopSec * SAMPLE_RATE).toInt()
        val left = FloatArray(frames)
        val right = FloatArray(frames)
        // Bell timbre: fundamental + slightly inharmonic upper partials, each with its own decay.
        val partials = doubleArrayOf(1.0, 2.0, 3.01, 4.2, 5.4)
        val partialGain = doubleArrayOf(1.0, 0.45, 0.22, 0.10, 0.05)
        val partialTau = doubleArrayOf(0.9, 0.55, 0.38, 0.25, 0.18) // seconds
        val notes = score()
        for (note in notes) {
            val start = (note.atSec * SAMPLE_RATE).toInt()
            if (start >= frames) continue
            val lenSec = 2.2
            val len = minOf((lenSec * SAMPLE_RATE).toInt(), frames - start)
            val lg = (1.0 - note.pan.coerceIn(-1.0, 1.0)) * 0.5 + 0.5 // constant-ish power pan
            val rg = (1.0 + note.pan.coerceIn(-1.0, 1.0)) * 0.5 + 0.5
            val attack = (0.004 * SAMPLE_RATE).toInt().coerceAtLeast(1)
            for (i in 0 until len) {
                val tSec = i.toDouble() / SAMPLE_RATE
                var s = 0.0
                for (p in partials.indices) {
                    val f = note.hz * partials[p]
                    if (f > SAMPLE_RATE / 2.2) continue // keep well under Nyquist
                    s += partialGain[p] * exp(-tSec / partialTau[p]) * sin(2.0 * PI * f * tSec)
                }
                val env = if (i < attack) i.toDouble() / attack else 1.0
                val v = s * env * note.gain * 0.28
                left[start + i] += (v * lg).toFloat()
                right[start + i] += (v * rg).toFloat()
            }
        }
        // Soft limiter so overlapping partials never hard-clip, then 16-bit pack.
        val data = ByteArray(frames * CHANNELS * 2)
        var o = 0
        for (i in 0 until frames) {
            val l = (tanh(left[i].toDouble() * 1.4) * 0.85 * 32767.0).toInt().coerceIn(-32768, 32767)
            val r = (tanh(right[i].toDouble() * 1.4) * 0.85 * 32767.0).toInt().coerceIn(-32768, 32767)
            data[o++] = (l and 0xFF).toByte(); data[o++] = ((l shr 8) and 0xFF).toByte()
            data[o++] = (r and 0xFF).toByte(); data[o++] = ((r shr 8) and 0xFF).toByte()
        }
        writeWav(dst, data)
    }

    private fun writeWav(dst: File, pcm: ByteArray) {
        val byteRate = SAMPLE_RATE * CHANNELS * 2
        RandomAccessFile(dst, "rw").use { raf ->
            raf.setLength(0)
            raf.write("RIFF".toByteArray(Charsets.US_ASCII))
            raf.write(le32(36 + pcm.size))
            raf.write("WAVE".toByteArray(Charsets.US_ASCII))
            raf.write("fmt ".toByteArray(Charsets.US_ASCII))
            raf.write(le32(16))
            raf.write(le16(1)) // PCM
            raf.write(le16(CHANNELS))
            raf.write(le32(SAMPLE_RATE))
            raf.write(le32(byteRate))
            raf.write(le16(CHANNELS * 2)) // block align
            raf.write(le16(16)) // bits per sample
            raf.write("data".toByteArray(Charsets.US_ASCII))
            raf.write(le32(pcm.size))
            raf.write(pcm)
        }
    }

    private fun le32(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(), ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte())
    private fun le16(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())
}
