package com.hione.player.remote

import java.util.UUID

/**
 * Wire contract for the "Hione Remote" Bluetooth LE GATT service.
 *
 * The M500 is the GATT PERIPHERAL (server). A phone needs no app: the companion PWA
 * (tools/remote-pwa) connects with Web Bluetooth, pairs with the 6-digit code shown in Settings,
 * then subscribes to now-playing/volume notifications and writes plain-text commands.
 *
 * All characteristic payloads are UTF-8 text. JSON where structured, `verb[:arg]` for commands.
 * Every string here is mirrored verbatim in tools/remote-pwa/app.js — change both together.
 *
 * Base UUID 39c5bbXX-4d49-4b55-9d31-3f8a6e7c2a01: "39c5bb" is Hione teal (#FFAB2E), "4d494b55"
 * is ASCII "HIONE". Fixed for the life of the product so the PWA's filter never has to change.
 */
object HioneRemoteProtocol {
    const val UUID_BASE_PREFIX = "39c5bb"
    const val UUID_BASE_SUFFIX = "-4d49-4b55-9d31-3f8a6e7c2a01"

    /** Primary service advertised in the ADV packet — the PWA's requestDevice() filter. */
    val SERVICE_UUID: UUID = UUID.fromString("39c5bb00$UUID_BASE_SUFFIX")

    /** READ + NOTIFY. Compact JSON, see [nowPlayingKeys]. Requires an authorized connection. */
    val CHAR_NOW_PLAYING: UUID = UUID.fromString("39c5bb01$UUID_BASE_SUFFIX")

    /** WRITE / WRITE_NO_RESPONSE. `verb[:arg]` text, see [Commands]. Requires authorization. */
    val CHAR_COMMAND: UUID = UUID.fromString("39c5bb02$UUID_BASE_SUFFIX")

    /** READ + NOTIFY. `{"vol":0-100,"muted":bool}`. Requires authorization. */
    val CHAR_VOLUME: UUID = UUID.fromString("39c5bb03$UUID_BASE_SUFFIX")

    /**
     * WRITE + READ + NOTIFY. Pairing handshake (never requires authorization):
     *  - client writes `pair:<6 digits>[:<label>]`  → server notifies `ok:<token>` or `err:<why>`
     *  - client writes `auth:<token>`                → server notifies `ok:auth` or `err:<why>`
     *  - client writes `unpair`                      → forgets the token this connection used
     *  - read returns `authorized` / `unauthorized` for the reading connection.
     * The token is a 32-hex-char secret the phone keeps in localStorage; the M500 stores only its
     * SHA-256, so a dump of the M500's prefs can't impersonate a phone.
     */
    val CHAR_PAIR: UUID = UUID.fromString("39c5bb04$UUID_BASE_SUFFIX")

    /** READ. `{"name","model","app","battery","charging","auth":bool}` — no authorization needed. */
    val CHAR_STATUS: UUID = UUID.fromString("39c5bb05$UUID_BASE_SUFFIX")

    /** Bluetooth SIG Client Characteristic Configuration descriptor (enables notifications). */
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /** Local name placed in the scan response so the phone's chooser reads "Hione M500". */
    const val LOCAL_NAME = "Hione M500"

    /** Hard GATT limit for a characteristic value (long reads are chunked by offset). */
    const val MAX_ATTR_BYTES = 512

    /** Notify payload the client should answer with a full READ (payload exceeded the MTU). */
    const val NOTIFY_READ_MARKER = "{\"r\":1}"

    /** Verbs accepted on [CHAR_COMMAND]. Parity with HioneApiRouter's /api/v1/playback routes. */
    object Commands {
        const val PLAY = "play"
        const val PAUSE = "pause"
        const val TOGGLE = "toggle"
        const val NEXT = "next"
        const val PREV = "prev"
        const val SEEK = "seek"        // seek:<ms>
        const val LIKE = "like"        // toggles the heart on the current track
        const val VOL = "vol"          // vol:<0-100>
        const val VOL_UP = "volup"
        const val VOL_DOWN = "voldown"
        const val MUTE = "mute"        // toggles
        const val SHUFFLE = "shuffle"  // shuffle | shuffle:on | shuffle:off
        const val REPEAT = "repeat"    // repeat (cycles off→all→one) | repeat:off|all|one
        const val REFRESH = "refresh"  // re-push now-playing + volume to this client
    }

    /** JSON keys in the now-playing payload (kept short: a 23-byte MTU is still a thing). */
    object NowPlayingKeys {
        const val TITLE = "title"
        const val ARTIST = "artist"
        const val ALBUM = "album"
        const val POS = "pos"          // ms
        const val DUR = "dur"          // ms
        const val PLAYING = "playing"
        const val LIKED = "liked"
        const val QUALITY = "quality"  // e.g. "24/96 FLAC", "" when unknown
        const val SHUFFLE = "shuffle"
        const val REPEAT = "repeat"    // off | all | one
        const val VOL = "vol"          // 0-100
        const val ID = "id"            // track id (Long), -1 when none
        const val ART = "art"          // always "none" over BLE (no art transport); PWA shows a placeholder
        const val AUTH = "auth"        // present (false) only when the reader is not authorized
    }
}
