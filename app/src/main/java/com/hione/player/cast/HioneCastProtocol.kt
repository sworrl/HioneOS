package com.hione.player.cast

/**
 * Wire format between the M500 and the TV companion.
 *
 * ONE SOCKET, BOTH DIRECTIONS. An earlier sketch used a socket for audio and a second for
 * control. That needs the two to be correlated, re-paired after every Wi-Fi blip, and torn down
 * in step, for no benefit: control traffic is a few bytes a second next to a 4.6 Mbit audio
 * stream. Everything is framed on one connection instead, so a dropped link is unambiguous and
 * reconnecting restores both at once.
 *
 * FRAMING. Every frame is:
 *
 *     magic   4 bytes   "HIONE"
 *     type    1 byte    see below
 *     length  4 bytes   big-endian payload length
 *     payload length bytes
 *
 * The magic is on every frame rather than once at connect so a desynced reader can hunt forward
 * and resynchronise instead of having to drop the connection. With PCM on the wire a single
 * mis-framed read would otherwise turn into permanent noise.
 *
 * BYTE ORDER. PCM payloads are passed through EXACTLY as the decoder produced them, little-endian
 * as Android uses throughout. They are not converted to network order: this is a bit-perfect path
 * and a byte swap on each end is both pointless work and a chance to corrupt 24-bit packed audio.
 * The header fields are big-endian because that is the convention for framing, and they are the
 * only fields either side parses as numbers.
 */
object HioneCastProtocol {

    /** Default port. Picked above 1024 so nothing needs privileges, and clear of 8787/8788. */
    const val PORT = 8796

    /** mDNS service type for discovery, so the TV does not need a typed-in address. */
    const val SERVICE_TYPE = "_hionecast._tcp"
    const val TV_SERVICE_TYPE = "_hionetv._tcp"
    const val SERVICE_NAME = "HioneOS Cast"

    val MAGIC = byteArrayOf('M'.code.toByte(), 'I'.code.toByte(), 'K'.code.toByte(), 'U'.code.toByte())
    const val HEADER_SIZE = 9

    /** M500 to TV: the stream's audio format. Sent on connect and on every format change. */
    const val TYPE_FORMAT: Byte = 1

    /** M500 to TV: raw decoded PCM, exactly as it went to the local DAC. */
    const val TYPE_PCM: Byte = 2

    /** M500 to TV: now-playing metadata as JSON (title, artist, album, duration, position, art). */
    const val TYPE_META: Byte = 3

    /** TV to M500: a control command as JSON, e.g. {"cmd":"next"}. */
    const val TYPE_CONTROL: Byte = 4

    /** Either direction: keepalive with no payload, so a dead link is noticed within seconds. */
    const val TYPE_PING: Byte = 5

    /**
     * M500 to TV: the stream deliberately skipped forward because the TV fell behind.
     *
     * Sent rather than silently gluing the two sides of a gap together. The TV can then clear its
     * buffer instead of playing audio that is now seconds stale, and can say so on screen. A
     * mirror that quietly drifts is worse than one that admits it dropped.
     */
    const val TYPE_SKIP: Byte = 6

    /**
     * Both directions, before anything else: the pairing and auth handshake. Nothing else is sent
     * or accepted until it succeeds.
     *
     *     M500 to TV   {"v":1,"server":"<id>","challenge":"<64 hex>"}
     *     TV to M500   {"id":"<tv id>","mac":"<hex HMAC-SHA256(tv token, challenge)>"}   paired TV
     *                  {"code":"123456","name":"<label>"}                                  first time
     *     M500 to TV   {"ok":true}  or  {"ok":true,"id":"..","token":".."} after a code
     *                  {"ok":false,"error":"bad-code|locked|unknown-tv|no-code"}, then close
     *
     * A paired TV never sends its token, only an HMAC over a fresh challenge, so a recorded
     * session can't be replayed. The token itself crosses the network once, at code pairing.
     */
    const val TYPE_AUTH: Byte = 7

    fun header(type: Byte, length: Int): ByteArray {
        val h = ByteArray(HEADER_SIZE)
        System.arraycopy(MAGIC, 0, h, 0, 4)
        h[4] = type
        h[5] = (length ushr 24).toByte()
        h[6] = (length ushr 16).toByte()
        h[7] = (length ushr 8).toByte()
        h[8] = length.toByte()
        return h
    }

    fun lengthOf(h: ByteArray): Int =
        ((h[5].toInt() and 0xFF) shl 24) or
            ((h[6].toInt() and 0xFF) shl 16) or
            ((h[7].toInt() and 0xFF) shl 8) or
            (h[8].toInt() and 0xFF)

    fun magicMatches(h: ByteArray): Boolean =
        h.size >= 4 && h[0] == MAGIC[0] && h[1] == MAGIC[1] && h[2] == MAGIC[2] && h[3] == MAGIC[3]
}
