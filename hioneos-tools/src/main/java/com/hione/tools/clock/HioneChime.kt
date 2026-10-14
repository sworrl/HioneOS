package com.hione.tools.clock

import android.content.Context
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * A short synthesized bell arpeggio, written once to device-protected storage as a WAV.
 *
 * It exists because the system alarm sounds live behind MediaProvider, which is not available
 * before the first unlock after a reboot (direct boot) and can be missing or broken on a
 * customized image. An alarm must make a sound no matter what, so this is the last fallback,
 * and it is also offered as a ringtone choice of its own.
 */
object HioneChime {
    private const val RATE = 44_100

    fun file(ctx: Context): File? = runCatching {
        val dir = ctx.applicationContext.createDeviceProtectedStorageContext().filesDir
        val f = File(dir, "hione_chime_v1.wav")
        if (!f.exists() || f.length() < 1000) render(f)
        f
    }.getOrNull()

    private fun render(out: File) {
        // F# major arpeggio up and back, in the register of a music box.
        val notes = doubleArrayOf(739.99, 932.33, 1108.73, 1479.98, 1108.73, 1479.98)
        val noteLen = 0.17
        val tail = 1.1
        val total = (RATE * (notes.size * noteLen + tail)).toInt()
        val pcm = DoubleArray(total)
        notes.forEachIndexed { i, f ->
            val start = (i * noteLen * RATE).toInt()
            val len = (RATE * 1.4).toInt()
            for (n in 0 until len) {
                val idx = start + n
                if (idx >= total) break
                val t = n.toDouble() / RATE
                val env = exp(-t * 4.2) * (1 - exp(-t * 900)) // soft attack, bell decay
                val v = sin(2 * PI * f * t) + 0.35 * sin(2 * PI * f * 2.0 * t) * exp(-t * 6) + 0.12 * sin(2 * PI * f * 3.01 * t) * exp(-t * 9)
                pcm[idx] += v * env * 0.33
            }
        }
        val tmp = File(out.parentFile, out.name + ".tmp")
        RandomAccessFile(tmp, "rw").use { raf ->
            raf.setLength(0)
            val dataLen = total * 2
            fun i32(v: Int) { raf.write(v and 0xFF); raf.write(v shr 8 and 0xFF); raf.write(v shr 16 and 0xFF); raf.write(v shr 24 and 0xFF) }
            fun i16(v: Int) { raf.write(v and 0xFF); raf.write(v shr 8 and 0xFF) }
            raf.writeBytes("RIFF"); i32(36 + dataLen); raf.writeBytes("WAVE")
            raf.writeBytes("fmt "); i32(16); i16(1); i16(1); i32(RATE); i32(RATE * 2); i16(2); i16(16)
            raf.writeBytes("data"); i32(dataLen)
            val buf = ByteArray(dataLen)
            for (i in 0 until total) {
                val s = (pcm[i].coerceIn(-1.0, 1.0) * 32767).toInt()
                buf[i * 2] = (s and 0xFF).toByte(); buf[i * 2 + 1] = (s shr 8 and 0xFF).toByte()
            }
            raf.write(buf)
        }
        tmp.renameTo(out)
    }
}
