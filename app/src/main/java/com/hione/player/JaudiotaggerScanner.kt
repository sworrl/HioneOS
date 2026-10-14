package com.hione.player

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import android.util.Log
import java.io.File

object JaudiotaggerScanner {
    fun fallbackParseAndInsert(context: Context, path: String) {
        try {
            val f = File(path)
            if (!f.exists()) return
            
            val ext = path.substringAfterLast('.').lowercase()
            val exotic = listOf("dsf", "dff", "ape", "wv", "mpc", "tta", "tak", "sacd", "iso")
            // Even if not exotic, if MediaStore missed metadata, jaudiotagger might catch it.
            // But doing it for all files is slow. Let's do it for all just to be safe, or 
            // if we want to be fast, just do it if it's exotic or MediaScanner returned null URI.
            
            val f2 = org.jaudiotagger.audio.AudioFileIO.read(f)
            val tag = f2.tag
            val header = f2.audioHeader
            
            val title = tag?.getFirst(org.jaudiotagger.tag.FieldKey.TITLE)?.takeIf { it.isNotBlank() } ?: f.nameWithoutExtension
            val artist = tag?.getFirst(org.jaudiotagger.tag.FieldKey.ARTIST)?.takeIf { it.isNotBlank() } ?: "<unknown>"
            val album = tag?.getFirst(org.jaudiotagger.tag.FieldKey.ALBUM)?.takeIf { it.isNotBlank() } ?: "<unknown>"
            val durationMs = header?.trackLength?.times(1000L) ?: 0L
            val trackNo = tag?.getFirst(org.jaudiotagger.tag.FieldKey.TRACK)?.toIntOrNull() ?: 0
            
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DATA, path)
                put(MediaStore.Audio.Media.TITLE, title)
                put(MediaStore.Audio.Media.ARTIST, artist)
                put(MediaStore.Audio.Media.ALBUM, album)
                put(MediaStore.Audio.Media.DURATION, durationMs)
                put(MediaStore.Audio.Media.TRACK, trackNo)
                put(MediaStore.Audio.Media.IS_MUSIC, 1)
                put(MediaStore.Audio.Media.MIME_TYPE, "audio/$ext")
                put(MediaStore.Audio.Media.SIZE, f.length())
                put(MediaStore.Audio.Media.DATE_MODIFIED, f.lastModified() / 1000)
                put(MediaStore.Audio.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
            }
            
            val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            val updated = context.contentResolver.update(uri, values, "${MediaStore.Audio.Media.DATA}=?", arrayOf(path))
            if (updated == 0) {
                context.contentResolver.insert(uri, values)
            }
        } catch (e: Exception) {
            Log.e("JaudiotaggerScanner", "jaudiotagger fallback failed for $path", e)
        }
    }
}
