package com.caf.fmradio

import android.util.Log
import qcom.fmradio.FmReceiverJNI

/**
 * HiBy's direct V4L2 hooks on /dev/radio0, wrapped so a missing or renamed native never takes
 * the tuner down with it.
 *
 * These live in the same `qcom.fmradio.jar` as the HCI API but are HiBy additions, so they are
 * resolved at runtime from the framework and could in principle not exist on a future build.
 * Every call here reports unavailability as null/false rather than throwing, and each distinct
 * failure is logged exactly once so a broken hook is visible without flooding logcat.
 *
 * The important one is [setMute]. The HiBy driver comes up muted and `FmReceiver.setMuteMode()`
 * does not clear it: that goes over HCI to the FM core, while the audio actually passes through
 * the V4L2 layer's own mute. Stock FM2 mutes here while it builds the route and unmutes 300 ms
 * later, which is also where its lack of a power-on pop comes from.
 */
object FmV4L2 {

    private const val TAG = "FmV4L2"

    private val reported = java.util.Collections.synchronizedSet(HashSet<String>())

    /**
     * One conversation with the chip at a time.
     *
     * HiBy's driver takes no lock of its own (radio-si4705-common.ko imports no mutex at all),
     * and a Si47xx command is a write, a wait for CTS, then a read of the response. Two threads
     * interleaving those read each other's responses. That was measured on 0.1.16: with the RDS
     * reader, the signal poll and the HCI callback thread all on the chip, every second signal
     * read came back all zeros. Everything in this process that touches /dev/radio0, including
     * [FmRdsReader], goes through this lock.
     */
    val chipLock = Any()

    private fun <T> guard(what: String, block: () -> T): T? = try {
        synchronized(chipLock) { block() }
    } catch (t: Throwable) {
        if (reported.add(what)) {
            Log.w(TAG, "$what unavailable: ${t.javaClass.simpleName}: ${t.message}")
        }
        null
    }

    /** 1 = muted. Returns true when the driver accepted the call. */
    fun setMute(muted: Boolean): Boolean =
        guard("setV4L2RadioFmMute") { FmReceiverJNI.setV4L2RadioFmMute(if (muted) 1 else 0) } != null

    fun isMuted(): Boolean? = guard("getV4L2RadioFmMute") { FmReceiverJNI.getV4L2RadioFmMute() != 0 }

    /**
     * Tune through the V4L2 layer, which is how stock FM2 tunes on this device.
     *
     * This matters more than it looks. `FmReceiver.setStation()` goes over HCI and reports a
     * TuneStatus callback, so it looks like it worked — but after one, `getV4L2RadioFrequency()`
     * still read the bottom of the band. Stock's own tune() takes the HiBy branch and calls
     * this instead. Both are issued now, and the diagnostics panel shows the V4L2 read-back so
     * the two can be compared rather than assumed equal.
     */
    fun setTunedKHz(khz: Int): Boolean =
        guard("setV4L2RadioFrequency") { FmReceiverJNI.setV4L2RadioFrequency(khz * 16) } != null

    /**
     * Set the tuner part's own receive volume. The range is 0..[RADIO_VOLUME_MAX], not 0..63.
     *
     * The driver registers V4L2_CID_AUDIO_VOLUME with a range of 0..15 and passes the value
     * straight through to the chip's RX_VOLUME (`value & 0x3f`), so a write of 63 is clamped
     * by the v4l2 core to 15 and every build that "set it to maximum" was really setting 15.
     * Writing the real control maximum says what is happening instead of relying on a clamp.
     */
    const val RADIO_VOLUME_MAX = 15

    fun setRadioVolume(v: Int): Boolean =
        guard("setV4L2RadioFmVolume") {
            FmReceiverJNI.setV4L2RadioFmVolume(v.coerceIn(0, RADIO_VOLUME_MAX))
        } != null

    /**
     * The driver's channel-mode control. Stock toggles it between 0 and 1 and re-issues the
     * frequency straight afterwards, which is the only JNI entry point stock uses that we did
     * not. What it means on HiBy's driver is undocumented: mono/stereo is the obvious reading,
     * but on a part whose digital output has to be configured before it drives the I2S bus at
     * all, an audio-mode control is worth trying. Exposed through the debug hook so it can be
     * swept rather than guessed.
     */
    fun setChannelMode(mode: Int): Boolean =
        guard("setV4L2RadioChannelMode") { FmReceiverJNI.setV4L2RadioChannelMode(mode) } != null

    fun channelMode(): Int? = guard("getV4L2RadioChannelMode") { FmReceiverJNI.getV4L2RadioChannelMode() }

    /** Frequency the driver believes it is on, in kHz. V4L2 counts in 1/16 kHz units. */
    fun tunedKHz(): Int? = guard("getV4L2RadioFrequency") {
        val raw = FmReceiverJNI.getV4L2RadioFrequency()
        if (raw <= 0) null else raw / 16
    }

    /**
     * One signal reading straight off the tuner. Element order is stock FM2's own labelling of
     * the same array; anything the array is too short to contain comes back null rather than 0,
     * because 0 is a legitimate value for most of these.
     *
     * Element 6 is NOT what stock calls it. The stock driver put FM_RSQ_STATUS's VALID bit
     * there; HioneOS's patched driver (hioneos/build/patch_si4705_rds.py) puts the chip's stereo
     * report there instead, as `pilot << 3 | STBLEND / 16`, because the stock driver reads
     * the pilot and blend and then throws them away, leaving nothing in userspace that can
     * tell stereo from mono.
     */
    data class Reading(
        val signal: Int?,
        val rssi: Int?,
        val snr: Int?,
        val multipath: Int?,
        val freqOffset: Int?,
        val freqKHz: Int?,
        /** A stereo pilot is present on this carrier. Says the station is stereo, not that we are. */
        val pilot: Boolean?,
        /**
         * How far the chip has blended toward stereo, 0 (full mono) to 100 (full stereo), to
         * the nearest 16 %. This is the one that answers "am I hearing stereo".
         */
        val stereoBlendPct: Int?,
        /**
         * The driver's frequency readback as it arrives: 1/16 kHz units cut to 16 bits, so
         * 101.9 MHz reads 57536. [FmSoftSeek.unwrapKHz] turns it back into kHz given a guess.
         */
        val freqRaw16: Int? = null,
    ) {
        /** Locked, by the driver's own test for it (its audmode): any RSSI and a nonzero SNR. */
        val valid: Boolean? get() = if (rssi == null || snr == null) null else rssi > 1 && snr != 0

        /** Stereo as heard: pilot present and the blend more than half way to stereo. */
        val stereo: Boolean? get() = if (pilot == null) null else pilot && (stereoBlendPct ?: 0) >= 50

        companion object { val EMPTY = Reading(null, null, null, null, null, null, null, null) }
    }

    fun signal(): Reading? = guard("getV4L2RadioFmSignal") {
        val a = FmReceiverJNI.getV4L2RadioFmSignal() ?: return@guard null
        fun at(i: Int): Int? = if (i < a.size) a[i] else null
        // A torn read: RSSI, SNR, multipath and offset all exactly zero. The noise floor on
        // this tuner never reads below 2, so this is a response that never arrived, not a
        // measurement, and passing it on made the meter flick to nothing every other second.
        if ((1..4).all { at(it) == 0 }) return@guard null
        Reading(
            signal = at(0),
            rssi = at(1),
            snr = at(2),
            multipath = at(3),
            freqOffset = at(4),
            // The readback is truncated to 16 bits (see freqRaw16), so raw / 16 is only a real
            // frequency when it happens to land in the chip's range. Otherwise say unknown.
            freqKHz = at(5)?.let { it / 16 }?.takeIf { it in 64_000..108_000 },
            pilot = at(6)?.let { it and 0x8 != 0 },
            // Three bits of STBLEND's seven; 6 is the top step (96-100 %), so call it 100.
            stereoBlendPct = at(6)?.let { ((it and 0x7) * 16).let { p -> if (p >= 96) 100 else p } },
            freqRaw16 = at(5)?.let { it and 0xFFFF },
        )
    }

    /**
     * Tune to [khz], wait [settleMs], read the signal: one transaction, all under [chipLock].
     *
     * Seek and scan are built from this. Holding the lock for the whole step keeps the RDS
     * reader and the signal poll from landing between the tune and the read, where they would
     * either read a channel that has not settled or pull RDS off a station we are only passing.
     * It runs on the tuner thread, which is the only thing that blocks while it sleeps.
     */
    fun probe(khz: Int, settleMs: Long): Reading? = guard("probe") {
        FmReceiverJNI.setV4L2RadioFrequency(khz * 16)
        if (settleMs > 0) Thread.sleep(settleMs)
        signal()
    }
}
