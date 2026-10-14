package com.hione.update.mascot

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PackFilesTest {
    @Test
    fun safeRelRejectsEscapes() {
        assertEquals("art/avatar_512.png", PackFiles.safeRel("art/avatar_512.png"))
        for (bad in listOf("", "/etc/passwd", "../x", "a/../../b", "a/./b", "a//b", "a\\b", "a\u0000b", "..", ".", "a/")) {
            assertNull("should refuse '$bad'", PackFiles.safeRel(bad))
        }
    }

    private val hione = JSONObject(
        """{"id":"hione","art":{"avatar":"art/avatar_512.png","lockscreen":"res/drawable/hione_boot_splash.webp",
           "wallpaper":{"summer":"art/seasons/summer.webp","default":"drawable:hione_wallpaper"}},
           "sounds":{"clips":{"battery_low":"voice/raw/battery_low.ogg","evil":"../../etc/x"}}}"""
    )
    private val files = setOf(
        "pack.json", "art/avatar_512.png", "art/seasons/summer.webp", "res/drawable/hione_boot_splash.webp",
        "res/drawable-nodpi/hione_wallpaper.webp", "res/drawable/hione_wallpaper.webp", "res/drawable-nodpi/hione_wall_00.webp",
        "res/icons-xxxhdpi/ic_fm_hione.png", "voice/raw/battery_low.ogg", "voice/raw/charging.ogg",
    )

    @Test
    fun artSlotsAndDrawableNames() {
        assertEquals("art/avatar_512.png", PackFiles.resolveArt(hione, "avatar", files::contains))
        assertEquals("art/seasons/summer.webp", PackFiles.resolveArt(hione, "wallpaper.summer", files::contains))
        // drawable: slot, nodpi preferred over plain drawable
        assertEquals("res/drawable-nodpi/hione_wallpaper.webp", PackFiles.resolveArt(hione, "wallpaper.default", files::contains))
        assertEquals("res/drawable-nodpi/hione_wall_00.webp", PackFiles.resolveArt(hione, "hione_wall_00", files::contains))
        assertEquals("res/icons-xxxhdpi/ic_fm_hione.png", PackFiles.resolveArt(hione, "ic_fm_hione", files::contains))
        assertNull(PackFiles.resolveArt(hione, "wallpaper.winter", files::contains))
        assertNull(PackFiles.resolveArt(hione, "../pack", files::contains))
    }

    @Test
    fun voiceClips() {
        assertEquals("voice/raw/battery_low.ogg", PackFiles.resolveVoice(hione, "battery_low", files::contains))
        assertEquals("voice/raw/charging.ogg", PackFiles.resolveVoice(hione, "charging", files::contains))
        assertNull(PackFiles.resolveVoice(hione, "evil", files::contains))
        assertNull(PackFiles.resolveVoice(hione, "../charging", files::contains))
    }

    private fun zipOf(vararg entries: Pair<String, ByteArray>): File {
        val f = Files.createTempFile("pack", ".zip").toFile()
        ZipOutputStream(f.outputStream()).use { z ->
            for ((n, b) in entries) {
                z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry()
            }
        }
        return f
    }

    @Test
    fun unzipKeepsFilesInside() {
        val tmp = Files.createTempDirectory("unz").toFile()
        val dest = File(tmp, "p")
        PackFiles.unzip(zipOf("pack.json" to "{}".toByteArray(), "art/a.png" to ByteArray(10)), dest)
        assertEquals(setOf("pack.json", "art/a.png"), PackFiles.listTree(dest))
        tmp.deleteRecursively()
    }

    @Test
    fun unzipRefusesSlipAndBombs() {
        for (bad in listOf("../evil", "/abs", "a/../../evil", "a\\..\\evil")) {
            val tmp = Files.createTempDirectory("unz").toFile()
            try {
                PackFiles.unzip(zipOf("pack.json" to "{}".toByteArray(), bad to "x".toByteArray()), File(tmp, "p"))
                fail("should refuse $bad")
            } catch (e: IOException) {
                assertFalse(File(tmp, "evil").exists())
            } finally { tmp.deleteRecursively() }
        }
        val tmp = Files.createTempDirectory("unz").toFile()
        try {
            PackFiles.unzip(zipOf("big.bin" to ByteArray(5000)), File(tmp, "p"), maxBytes = 4000)
            fail("should refuse an oversized pack")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("bigger"))
        } finally { tmp.deleteRecursively() }
    }
}
