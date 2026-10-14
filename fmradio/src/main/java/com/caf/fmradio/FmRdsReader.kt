package com.caf.fmradio

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import android.util.Log
import java.io.FileDescriptor

/**
 * RDS decoded from the Si4705, by reading /dev/radio0 directly.
 *
 * WHY NOT THE API WE ALREADY HAVE. `FmReceiver.getPSInfo()` / `getRTInfo()` / `getRTPlusInfo()`
 * are the QTI HCI API, and on this board that talks to the Qualcomm WCN FM core, which has no
 * antenna: it reads RSSI 0 at every frequency in the band. Its RDS callbacks will therefore
 * never fire with anything. The part that receives is the Si4705 on /dev/radio0.
 *
 * WHAT HIBY'S DRIVER ACTUALLY DOES (radio-si4705-common.ko, disassembled 2026-10-09). It is not
 * the standard V4L2 RDS byte stream of three-byte blocks, and every one of these matters:
 *
 *  - RDS reception is OFF until someone poll()s the node for POLLIN. si4705_fops_poll() sends
 *    FM_RDS_STATUS and, if the chip is not in RDS sync, writes FM_RDS_INT_SOURCE=1,
 *    FM_RDS_INT_FIFO_COUNT=4 and FM_RDS_CONFIG=0xFF01 (RDSEN). Nothing else in the driver
 *    enables RDS, and stock FM2 never polls, which is half of why stock never showed RDS.
 *    poll() never reports the node readable; it is only the switch.
 *  - Each read() pops ONE whole group off the chip's FIFO (FM_RDS_STATUS with INTACK) and
 *    copies it out as  A (u16, host order), B (u16, host order), C D (4 bytes, MSB first),
 *    but only when the chip is in sync, nothing was lost, and block D is clean. The driver
 *    ignores the requested length.
 *  - read() returns 0 whether or not it copied anything (it returns copy_to_user's "bytes not
 *    copied"). So new data is detected by pre-filling the buffer and seeing whether it changed.
 *  - The stock driver copies only the first FOUR bytes, A and B, so C and D, which carry
 *    every character of the station name and of RadioText, never arrive. HioneOS patches that
 *    to eight (hioneos/build/patch_si4705_rds.py). On an unpatched driver C and D stay at the
 *    fill pattern, [wholeGroups] goes false, and only PI, PTY and the flags are decoded.
 *
 * WHY RAW SYSCALLS AND NOT File/FileInputStream. The vendor's sepolicy grants this app
 *
 *     allow vendor_fm_app vendor_fm_radio_device:chr_file { ioctl read write open };
 *
 * and nothing else. `getattr` is not in that set, so every call that stats the node is denied
 * and File.exists() says a present node is missing. Os.open/Os.poll/Os.read are bare syscalls
 * that need only what we hold. Nothing here may stat the node.
 *
 * WEAK SIGNAL. The chip only hands over groups whose block D passed, but A to C can still carry
 * corrected-but-wrong bits on a marginal carrier. A station name made of half-right letters is
 * worse than none, so a PS or RT segment is only accepted when the same characters arrive for it
 * twice in a row.
 *
 * Groups handled, which is what a now-playing display needs:
 *   0A/0B  PS, the eight-character station name, and the TA/TP/MS flags
 *   2A/2B  RT, the 64-character radio text, which is where most US stations put "Artist - Title"
 *   3A     ODA announcement, used to discover which group carries RT+
 *   RT+    tagged items inside RT: artist and title, as the station marked them
 */
object FmRdsReader {

    private const val TAG = "FmRdsReader"
    private const val NODE = "/dev/radio0"

    /** RT+ content types a now-playing display cares about (IEC 62106 RT+ class table). */
    const val RTPLUS_ITEM_TITLE = 1
    const val RTPLUS_ITEM_ALBUM = 2
    const val RTPLUS_ITEM_ARTIST = 4
    /** 0 means "no item", which is the only honest way to say a song is not currently tagged. */
    const val RTPLUS_DUMMY = 0
    private const val RTPLUS_AID = 0x4BD7

    /** Fill pattern for C and D: still there after a read means the driver only copied A and B. */
    private const val CD_FILL: Byte = 0xA5.toByte()
    const val GROUP_BYTES = 8

    data class Rds(
        /** Programme identification, the station's unique code. */
        val pi: Int? = null,
        /** Call letters decoded from [pi] by the RBDS rule, when it is one of the computed ranges. */
        val callSign: String? = null,
        /** Programme type, the genre code; the name depends on RDS vs RBDS. */
        val pty: Int? = null,
        /** Station name, eight characters. */
        val ps: String = "",
        /** Radio text, up to 64 characters. */
        val rt: String = "",
        /** Artist as RT+ tagged it, not as something guessed out of [rt]. */
        val rtPlusArtist: String? = null,
        /** Title as RT+ tagged it. */
        val rtPlusTitle: String? = null,
        /** Traffic programme / traffic announcement flags. */
        val tp: Boolean = false,
        val ta: Boolean = false,
        /** Music/speech flag: true = music, false = speech. Broadcast by the station, not inferred. */
        val musicSpeech: Boolean? = null,
        /** Groups received since the last tune, so the UI can show it warming up. */
        val groups: Int = 0,
    )

    @Volatile private var reported = false

    /**
     * Whether the driver delivers whole groups. Null until a group has arrived; false means
     * the stock driver's four-byte copy, i.e. PI and PTY only, never names or text.
     */
    @Volatile var wholeGroups: Boolean? = null
        private set

    // ------------------------------------------------------------------ I/O

    /** Open the node for reading, with no stat of any kind. Null with one logged reason on failure. */
    fun open(): FileDescriptor? = try {
        Os.open(NODE, OsConstants.O_RDONLY or OsConstants.O_NONBLOCK, 0)
    } catch (e: ErrnoException) {
        fail("open($NODE): ${OsConstants.errnoName(e.errno)}" +
            if (e.errno == OsConstants.EACCES)
                " - sepolicy allows ioctl/read/write/open on this node but not getattr, so check " +
                "that nothing in this path stats it"
            else "")
        null
    } catch (t: Throwable) {
        fail("${t.javaClass.simpleName}: ${t.message}")
        null
    }

    /**
     * Switch RDS reception on. The driver does this inside poll() when the chip is out of sync,
     * so call it after every tune and whenever groups stop arriving. Cheap: one I2C command.
     */
    fun arm(fd: FileDescriptor) {
        try {
            synchronized(FmV4L2.chipLock) {
                Os.poll(arrayOf(StructPollfd().apply { this.fd = fd; events = OsConstants.POLLIN.toShort() }), 0)
            }
        } catch (e: ErrnoException) {
            Log.w(TAG, "RDS arm (poll) failed: ${OsConstants.errnoName(e.errno)}")
        }
    }

    /**
     * Pop one group into [buf] (at least [GROUP_BYTES] long). Returns true when a group arrived,
     * false when the chip had nothing, and throws [ErrnoException] when the node is unusable.
     */
    fun readGroup(fd: FileDescriptor, buf: ByteArray): Boolean {
        buf[0] = 0; buf[1] = 0                     // PI 0 is not a valid code, so 0 = untouched
        for (i in 4 until GROUP_BYTES) buf[i] = CD_FILL
        try {
            synchronized(FmV4L2.chipLock) { Os.read(fd, buf, 0, GROUP_BYTES) }
        } catch (e: ErrnoException) {
            if (e.errno == OsConstants.EAGAIN || e.errno == OsConstants.EINTR) return false
            throw e
        }
        return buf[0].toInt() != 0 || buf[1].toInt() != 0
    }

    fun close(fd: FileDescriptor) {
        runCatching { Os.close(fd) }
    }

    private fun fail(why: String) {
        if (!reported) { reported = true; Log.w(TAG, "RDS unavailable: $why") }
    }

    // ------------------------------------------------------------------ decoding

    private val psChars = CharArray(8) { ' ' }
    private val psLast = arrayOfNulls<String>(4)
    private val rtChars = CharArray(64) { ' ' }
    private val rtLast = arrayOfNulls<String>(16)
    private var rtLength = 64
    private var rtPlusArtist: String? = null
    private var rtPlusTitle: String? = null
    /** Group type code (type << 1 | version) that carries RT+, from a 3A announcement. */
    private var rtPlusGroup: Int? = null
    private var groups = 0
    private var lastRtAb: Int = -1
    private var loggedRaw = 0

    @Volatile var state = Rds()
        private set

    /** Reset the accumulated text when the station changes; none of it survives a retune. */
    @Synchronized
    fun newStation() {
        psChars.fill(' '); psLast.fill(null)
        rtChars.fill(' '); rtLast.fill(null); rtLength = 64
        rtPlusArtist = null
        rtPlusTitle = null
        rtPlusGroup = null
        groups = 0
        lastRtAb = -1
        loggedRaw = 0
        state = Rds()
    }

    /** Decode one group as the driver delivers it. Returns true when the decoded state changed. */
    @Synchronized
    fun offerGroup(buf: ByteArray): Boolean {
        fun u(i: Int) = buf[i].toInt() and 0xFF
        val a = u(0) or (u(1) shl 8)
        val b = u(2) or (u(3) shl 8)
        val whole = (4 until GROUP_BYTES).any { buf[it] != CD_FILL }
        if (whole && wholeGroups != true) {
            wholeGroups = true
            Log.i(TAG, "driver delivers whole RDS groups (patched)")
        } else if (!whole && wholeGroups == null && groups >= 20) {
            wholeGroups = false
            Log.w(TAG, "driver delivers blocks A and B only (stock four-byte copy): " +
                "PI and PTY, no station name or text")
        }
        val c = if (whole) (u(4) shl 8) or u(5) else null
        val d = if (whole) (u(6) shl 8) or u(7) else null
        if (loggedRaw < 6) {
            loggedRaw++
            Log.i(TAG, "group raw A=%04X B=%04X C=%s D=%s".format(a, b,
                c?.let { "%04X".format(it) } ?: "-", d?.let { "%04X".format(it) } ?: "-"))
        }
        return decodeGroup(a, b, c, d)
    }

    private fun decodeGroup(a: Int, b: Int, c: Int?, d: Int?): Boolean {
        groups++
        val groupType = (b shr 12) and 0x0F
        val version = (b shr 11) and 0x01          // 0 = A, 1 = B
        val tp = (b shr 10) and 0x01 == 1
        val pty = (b shr 5) and 0x1F
        var flags = state.copy(tp = tp)

        when (groupType) {
            0 -> {                                  // 0A / 0B: programme service name
                flags = flags.copy(ta = (b shr 4) and 0x01 == 1, musicSpeech = (b shr 3) and 0x01 == 1)
                if (d != null) {
                    val seg = b and 0x03
                    val pair = charOf(d shr 8).toString() + charOf(d)
                    if (psLast[seg] == pair) {       // the same two characters twice: accept
                        psChars[seg * 2] = pair[0]; psChars[seg * 2 + 1] = pair[1]
                    }
                    psLast[seg] = pair
                }
            }
            2 -> if (d != null) {                   // 2A / 2B: radio text
                val ab = (b shr 4) and 0x01
                if (lastRtAb != -1 && ab != lastRtAb) {   // A/B flip: a new message, drop the old
                    rtChars.fill(' '); rtLast.fill(null); rtLength = 64
                    rtPlusArtist = null; rtPlusTitle = null
                }
                lastRtAb = ab
                val seg = b and 0x0F
                val chars = if (version == 0 && c != null)
                    charArrayOf(charOf(c shr 8), charOf(c), charOf(d shr 8), charOf(d))
                else charArrayOf(charOf(d shr 8), charOf(d))
                val base = seg * chars.size
                val text = String(chars)
                if (rtLast[seg] == text) {
                    for (i in chars.indices) if (base + i < 64) {
                        // 0x0D ends the message early; everything after it is not text.
                        if (chars[i] == '\r') { rtLength = minOf(rtLength, base + i); break }
                        rtChars[base + i] = chars[i]
                    }
                }
                rtLast[seg] = text
            }
            3 -> if (version == 0 && c != null && d != null) {   // 3A: which group carries RT+
                if (d == RTPLUS_AID) {
                    val code = b and 0x1F
                    if (rtPlusGroup != code) {
                        rtPlusGroup = code
                        Log.i(TAG, "station carries RT+ in group ${code shr 1}${if (code and 1 == 0) "A" else "B"}")
                    }
                }
            }
            else -> if (c != null && d != null && rtPlusGroup == ((groupType shl 1) or version)) {
                decodeRtPlus(b, c, d)
            }
        }

        val pi = if (a != 0) a else state.pi
        val next = flags.copy(
            pi = pi, callSign = pi?.let { rbdsCallSign(it) }, pty = pty,
            ps = String(psChars).trim(), rt = String(rtChars, 0, rtLength).trim(),
            rtPlusArtist = rtPlusArtist, rtPlusTitle = rtPlusTitle, groups = groups,
        )
        val changed = next.copy(groups = 0) != state.copy(groups = 0)
        state = next
        return changed
    }

    /**
     * RT+ (IEC 62106-6). Two tagged items, each a content type plus a start and length that
     * index into the current radio text, packed across blocks B, C and D:
     *
     *   B  bit 4 toggle, bit 3 running, bits 2-0 = type 1 high
     *   C  bits 15-13 type 1 low, 12-7 start 1, 6-1 length 1, bit 0 type 2 high
     *   D  bits 15-11 type 2 low, 10-5 start 2, 4-0 length 2
     *
     * Lengths are "additional characters", so an item covers start..start+length inclusive.
     */
    private fun decodeRtPlus(b: Int, c: Int, d: Int) {
        val running = (b shr 3) and 0x01 == 1
        val type1 = ((b and 0x07) shl 3) or ((c shr 13) and 0x07)
        val start1 = (c shr 7) and 0x3F
        val len1 = (c shr 1) and 0x3F
        val type2 = ((c and 0x01) shl 5) or ((d shr 11) and 0x1F)
        val start2 = (d shr 5) and 0x3F
        val len2 = d and 0x1F
        if (!running) { rtPlusArtist = null; rtPlusTitle = null; return }
        val rt = String(rtChars, 0, rtLength)
        fun slice(start: Int, len: Int): String? {
            if (start >= rt.length) return null
            return rt.substring(start, minOf(start + len + 1, rt.length)).trim().ifEmpty { null }
        }
        for ((type, start, len) in listOf(Triple(type1, start1, len1), Triple(type2, start2, len2))) {
            when (type) {
                RTPLUS_ITEM_TITLE -> slice(start, len)?.let { rtPlusTitle = it }
                RTPLUS_ITEM_ARTIST -> slice(start, len)?.let { rtPlusArtist = it }
            }
        }
    }

    /** RDS uses its own character table; the printable ASCII range is the same, the rest is not. */
    private fun charOf(v: Int): Char {
        val ch = v and 0xFF
        return if (ch == 0x0D) '\r' else if (ch in 0x20..0x7E) ch.toChar() else ' '
    }

    /**
     * Call letters from an RBDS PI code (NRSC-4-B, North America), for the computed ranges:
     * K stations 0x1000-0x54A7, W stations 0x54A8-0x994F, with the two compressed forms
     * (AFxx = xx00, Axyz = x0yz) expanded first. Three-letter heritage calls and nationally
     * linked codes come from a lookup table we do not ship, so those return null rather than a
     * made-up name.
     */
    fun rbdsCallSign(piIn: Int): String? {
        var pi = piIn and 0xFFFF
        if (pi shr 8 == 0xAF) pi = (pi and 0xFF) shl 8
        else if (pi shr 12 == 0xA) pi = ((pi and 0x0F00) shl 4) or (pi and 0x00FF)
        val (prefix, n) = when (pi) {
            in 0x1000..0x54A7 -> 'K' to pi - 0x1000
            in 0x54A8..0x994F -> 'W' to pi - 0x54A8
            else -> return null
        }
        return "$prefix${'A' + n / 676}${'A' + (n % 676) / 26}${'A' + n % 26}"
    }
}
