package com.hione.player

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.*

/**
 * High-speed binary library cache designed for 0ms cold start times.
 * Loads 20,000+ tracks from local disk in ~8ms using a compact binary format,
 * completely bypassing ContentResolver and MediaStore on initial UI render.
 */
object FastLibraryStore {
    private const val TAG = "FastLibraryStore"
    private const val CACHE_FILE_NAME = "library_fast_v2.bin"
    private const val MAGIC_HEADER = 0x4D494B55 // "HIONE"
    private const val VERSION = 4 // v3: + discNumber · v4: + disc-image flags / virtual-track clip window

    @Volatile private var memoryCache: List<Track>? = null

    // ---- Full-query de-duplication -------------------------------------------------------------
    // At cold start TWO independent full MediaStore sweeps of the whole library used to run at the
    // same time: MainActivity.queryTracks() (for the UI) and LibraryDaemonService's "initial
    // background sync" (queryTracksFast()). Same ~11k rows, same File.exists() stat per row, same
    // shared coroutine worker pool, same few seconds the app is trying to reach its first frame.
    // Whoever finishes a full sweep stamps it here so the other side can skip a redundant repeat.
    @Volatile private var lastFullQueryAtMs = 0L
    @Volatile private var fullQueryCount = 0

    /** Record that a FULL library query just completed in this process. */
    // ================================================================= generation gate
    //
    // WHY. queryTracks() ran on EVERY launch, unconditionally, and it measured 21 seconds for the
    // MediaStore walk plus 23 seconds for the disc-image pass on 16.6k rows (2026-09-19). The binary
    // cache exists so that a launch does not have to do that, but nothing ever checked whether the
    // cache was still current, so the full walk happened anyway and the app was sluggish for the
    // first 45 seconds of every session. MediaStore keeps a cheap monotonic generation counter per
    // volume that moves whenever its content changes. Stamp it with the cache; if it has not moved,
    // the cache IS the library and the walk is skipped.

    private const val GEN_PREFS = "hione_library_gen"
    private const val KEY_GEN = "mediastore_generation"

    /** Every external volume's generation, joined, so an SD-card change is seen too. */
    fun currentGeneration(context: Context): String = try {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            android.provider.MediaStore.getExternalVolumeNames(context).sorted().joinToString("|") { v ->
                v + "=" + runCatching { android.provider.MediaStore.getGeneration(context, v) }.getOrDefault(-1L)
            }
        } else ""
    } catch (_: Throwable) { "" }

    fun stampGeneration(context: Context) {
        val g = currentGeneration(context)
        if (g.isNotEmpty()) context.getSharedPreferences(GEN_PREFS, Context.MODE_PRIVATE).edit().putString(KEY_GEN, g).apply()
    }

    /** True when MediaStore has not changed since the cache was written, so a full walk is waste. */
    fun cacheIsCurrent(context: Context): Boolean {
        val now = currentGeneration(context)
        if (now.isEmpty()) return false
        val stamped = context.getSharedPreferences(GEN_PREFS, Context.MODE_PRIVATE).getString(KEY_GEN, null)
        return stamped == now
    }

    fun noteFullQuery(count: Int) {
        fullQueryCount = count
        lastFullQueryAtMs = android.os.SystemClock.elapsedRealtime()
    }

    /** Milliseconds since the last full library query in this process ([Long.MAX_VALUE] if none). */
    fun msSinceFullQuery(): Long {
        val at = lastFullQueryAtMs
        return if (at == 0L) Long.MAX_VALUE else android.os.SystemClock.elapsedRealtime() - at
    }

    /** Track count the last full library query produced (0 if none yet). */
    fun lastFullQueryCount(): Int = fullQueryCount

    /**
     * Fast synchronous disk read. Called during cold start initialization.
     * Returns pre-cached track list instantly (<10ms for 20k tracks).
     */
    /** The in-memory copy if a load has already happened, without touching disk. Main-thread safe. */
    fun peek(): List<Track>? = memoryCache

    /**
     * Dedupes the strings that repeat across a library: artist, album, album artist, mime type.
     * 17k tracks carry maybe 2k distinct artists and 4k distinct albums, and without this every
     * track held its own copy of each. Cutting that halves the library's heap footprint, which is
     * what a rescan doubles (old list + new list live together), and the rescan is exactly where
     * the app went into a page-fault thrash and got ANR-killed on 2026-09-19. Bounded so a
     * pathological library cannot turn the interner itself into a leak.
     */
    private val intern = java.util.concurrent.ConcurrentHashMap<String, String>()
    fun intern(v: String): String {
        if (v.length > 200) return v
        intern[v]?.let { return it }
        if (intern.size < 60_000) intern[v] = v
        return v
    }

    fun loadSync(context: Context): List<Track>? {
        memoryCache?.let { return it }

        val file = File(context.filesDir, CACHE_FILE_NAME)
        if (!file.exists() || file.length() < 12) return null

        val start = System.currentTimeMillis()
        try {
            // One read of the whole file (4MB for 17k tracks), then parse from memory. The
            // per-field reads below are then pure CPU with no syscalls behind them.
            val bytes = file.readBytes()
            DataInputStream(java.io.ByteArrayInputStream(bytes)).use { dis ->
                val magic = dis.readInt()
                if (magic != MAGIC_HEADER) return null
                val ver = dis.readInt()
                if (ver != VERSION) return null

                val count = dis.readInt()
                if (count <= 0 || count > 200_000) return null

                val list = ArrayList<Track>(count)
                for (i in 0 until count) {
                    val id = dis.readLong()
                    val title = dis.readUTF()
                    val artist = intern(dis.readUTF())
                    val album = intern(dis.readUTF())
                    val durationMs = dis.readLong()
                    val sizeBytes = dis.readLong()
                    val bitrateKbps = dis.readInt()
                    val mime = intern(dis.readUTF())
                    val path = dis.readUTF()
                    val year = dis.readInt()
                    val albumId = dis.readLong()
                    val trackNumber = dis.readInt()
                    val albumArtist = intern(dis.readUTF())
                    val dateAddedSec = dis.readLong()
                    val discNumber = dis.readInt()
                    val isDiscImage = dis.readBoolean()
                    val cuePath = dis.readUTF()
                    val parentId = dis.readLong()
                    val clipStartMs = dis.readLong()
                    val clipEndMs = dis.readLong()

                    list.add(
                        Track(
                            id = id,
                            title = title,
                            artist = artist,
                            album = album,
                            durationMs = durationMs,
                            sizeBytes = sizeBytes,
                            bitrateKbps = bitrateKbps,
                            mime = mime,
                            path = path,
                            year = year,
                            albumId = albumId,
                            trackNumber = trackNumber,
                            albumArtist = albumArtist,
                            dateAddedSec = dateAddedSec,
                            discNumber = discNumber,
                            isDiscImage = isDiscImage,
                            cuePath = cuePath,
                            parentId = parentId,
                            clipStartMs = clipStartMs,
                            clipEndMs = clipEndMs
                        )
                    )
                }

                memoryCache = list
                val elapsed = System.currentTimeMillis() - start
                Log.i(TAG, "Loaded ${list.size} tracks from binary cache in ${elapsed}ms")
                return list
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Cache read failed or corrupt, will rebuild from MediaStore", e)
            file.delete()
            return null
        }
    }

    /**
     * Persists library to binary disk cache asynchronously.
     */
    suspend fun saveAsync(context: Context, tracks: List<Track>) = withContext(Dispatchers.IO) {
        if (tracks.isEmpty()) return@withContext
        memoryCache = tracks

        val tempFile = File(context.filesDir, "$CACHE_FILE_NAME.tmp")
        val targetFile = File(context.filesDir, CACHE_FILE_NAME)

        try {
            val start = System.currentTimeMillis()
            DataOutputStream(BufferedOutputStream(FileOutputStream(tempFile), 65536)).use { dos ->
                dos.writeInt(MAGIC_HEADER)
                dos.writeInt(VERSION)
                dos.writeInt(tracks.size)

                for (t in tracks) {
                    dos.writeLong(t.id)
                    dos.writeUTF(t.title)
                    dos.writeUTF(t.artist)
                    dos.writeUTF(t.album)
                    dos.writeLong(t.durationMs)
                    dos.writeLong(t.sizeBytes)
                    dos.writeInt(t.bitrateKbps)
                    dos.writeUTF(t.mime)
                    dos.writeUTF(t.path)
                    dos.writeInt(t.year)
                    dos.writeLong(t.albumId)
                    dos.writeInt(t.trackNumber)
                    dos.writeUTF(t.albumArtist)
                    dos.writeLong(t.dateAddedSec)
                    dos.writeInt(t.discNumber)
                    dos.writeBoolean(t.isDiscImage)
                    dos.writeUTF(t.cuePath)
                    dos.writeLong(t.parentId)
                    dos.writeLong(t.clipStartMs)
                    dos.writeLong(t.clipEndMs)
                }
                dos.flush()
            }

            if (tempFile.exists()) {
                tempFile.renameTo(targetFile)
            }
            val elapsed = System.currentTimeMillis() - start
            Log.i(TAG, "Saved ${tracks.size} tracks to binary cache in ${elapsed}ms")
            // Library changed: radio likes we were missing may be here now (wanted/WantedStore).
            com.hione.player.wanted.WantedStore.onLibraryChanged(context, tracks)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to write library cache", e)
            tempFile.delete()
        }
    }
}
