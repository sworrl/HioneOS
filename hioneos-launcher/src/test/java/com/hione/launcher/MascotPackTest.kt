package com.hione.launcher

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MascotPackTest {
    private fun packJson(id: String): JSONObject {
        // Gradle runs unit tests from the module directory.
        val f = listOf("../../hioneos/mascot/packs/$id/pack.json", "../hioneos/mascot/packs/$id/pack.json")
            .map { File(it) }.first { it.isFile }
        return JSONObject(f.readText())
    }

    @After fun reset() = Mascot.setForTest(MascotPacks.HIONE)

    @Test fun builtInPacksMatchTheirPackJson() {
        assertEquals(MascotPacks.HIONE, MascotPack.fromJson(packJson("hione"), source = "builtin"))
        assertEquals(MascotPacks.HIONE, MascotPack.fromJson(packJson("hione"), source = "builtin"))
        assertEquals(MascotPacks.HIONE.hueRules, MascotPack.fromJson(packJson("hione")).hueRules)
    }

    @Test fun hioneNames() {
        val h = MascotPacks.HIONE
        assertEquals("Hione Hits", h.musicAppName)
        assertEquals("HioneOS", h.osName)
        assertEquals("Oki Hione", h.fullName)
        assertEquals("Hione", h.labelAlias)
    }

    @Test fun switchingPacksChangesNamesAndColors() {
        Mascot.setForTest(MascotPacks.HIONE)
        assertEquals("HioneOS", Mascot.osName)
        assertEquals("Hione Music", Mascot.musicAppName)
        assertTrue(Mascot.isHione)
        assertTrue(Mascot.hasHioneLore)
        assertEquals(Color(0xFF39C5BB), Mascot.color(0xFF39C5BB))

        Mascot.setForTest(MascotPacks.HIONE)
        assertEquals("HioneOS", Mascot.osName)
        assertEquals("Hione Hits", Mascot.musicAppName)
        assertFalse(Mascot.isHione)
        assertFalse(Mascot.hasHioneLore)
        // table: teal -> amber, alpha kept
        assertEquals(Color(0xFFFFAB2E), Mascot.color(0xFF39C5BB))
        assertEquals(0x80FFAB2E.toInt(), Mascot.argb(0x8039C5BB.toInt()))
        assertEquals(Color(0x338EC5FF), Mascot.color(0x33FF2A85))
        // unlisted, outside every hue rule: unchanged (a green status color, pure red)
        assertEquals(Color(0xFF00E676), Mascot.color(0xFF00E676))
        assertEquals(Color(0xFFFF1744), Mascot.color(0xFFFF1744))
        // hue rule: a teal glow not in the table takes the amber hue, keeps its lightness
        val glow = Mascot.argb(0xFF56F0E0.toInt())
        val hsl = MascotPack.rgbToHsl(glow and 0xFFFFFF)
        val src = MascotPack.rgbToHsl(0x56F0E0)
        assertEquals(MascotPack.rgbToHsl(0xFFAB2E)[0], hsl[0], 1.5f)
        assertEquals(src[2], hsl[2], 0.01f)
        // teal-black background -> gunmetal-ish (low lightness kept, hue of the secondary role)
        val bg = MascotPack.rgbToHsl(Mascot.argb(0xFF0F2B2E.toInt()) and 0xFFFFFF)
        assertEquals(MascotPack.rgbToHsl(0x2B2F38)[0], bg[0], 2f)
        // the memo gives the same answer twice
        assertEquals(glow, Mascot.argb(0xFF56F0E0.toInt()))

        Mascot.setForTest(MascotPacks.HIONE)
        assertEquals(Color(0xFF56F0E0).toArgb(), Mascot.argb(0xFF56F0E0.toInt()))
    }

    @Test fun fillTokens() {
        Mascot.setForTest(MascotPacks.HIONE)
        assertEquals("Welcome to HioneOS. Hione Hits is ready, Hione.",
            Mascot.fill("Welcome to {os_name}. {music_app_name} is ready, {mascot_name}."))
        assertEquals("隠岐氷音 · Oki Hione", Mascot.fill("{full_name_ja} · {full_name}"))
        assertEquals("no tokens", Mascot.fill("no tokens"))
        assertEquals("Hione Music", MascotPacks.HIONE.fill("{music_app_name}"))
        assertEquals("leek", Mascot.lore(hione = "negi", other = "leek"))
    }

    @Test fun downloadedPackDefaults() {
        val p = MascotPack.fromJson(JSONObject("""{"id":"neo","name":"Neo","palette":{"primary":"#112233"},"replaces":{"#FFAB2E":"primary","#FF5FA2":"#445566"}}"""))
        assertEquals("NeoOS", p.osName)
        assertEquals("Neo Music", p.musicAppName)
        assertEquals("Plain", p.labelAlias)
        assertEquals("download", p.source)
        assertEquals(0xFF112233.toInt(), p.mapArgb(0xFF39C5BB.toInt()))
        assertEquals(0x80445566.toInt(), p.mapArgb(0x80FF5FA2.toInt()))
    }

    @Test fun hslRoundTrip() {
        for (rgb in listOf(0x39C5BB, 0xFFAB2E, 0x8EC5FF, 0x111216, 0xFFFFFF, 0x000000, 0xFF0000)) {
            val h = MascotPack.rgbToHsl(rgb)
            val back = MascotPack.hslToRgb(h[0], h[1], h[2])
            for (sh in intArrayOf(16, 8, 0)) {
                assertEquals(((rgb shr sh) and 0xFF).toFloat(), ((back shr sh) and 0xFF).toFloat(), 1.01f)
            }
        }
    }
}
