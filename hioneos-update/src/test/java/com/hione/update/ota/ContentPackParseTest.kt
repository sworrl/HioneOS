package com.hione.update.ota

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentPackParseTest {
    private val sha = "a".repeat(64)
    private fun manifest(packs: String?) = """
        {"schema_version":1,"channel":"stable","build":"0.3.0","published_at":"2026-10-10T00:00:00Z",
         "apks":[]${if (packs != null) ",\"content_packs\":$packs" else ""}}
    """.trimIndent().toByteArray()

    @Test
    fun olderManifestHasNoPacks() {
        assertTrue(OtaManifest.parse(manifest(null)).contentPacks.isEmpty())
    }

    @Test
    fun parsesAPack() {
        val m = OtaManifest.parse(manifest(
            """[{"id":"hione","kind":"mascot","version":2,"name":"Hione","url":"packs/hione-2.zip","size":123,"sha256":"$sha"},
                {"id":"future","kind":"keyboard","version":1,"url":"x","size":1,"sha256":"$sha"}]"""
        ))
        assertEquals(1, m.contentPacks.size) // the unknown kind is skipped
        val p = m.contentPacks[0]
        assertEquals("hione", p.id); assertEquals(2, p.version); assertEquals("packs/hione-2.zip", p.url)
        assertEquals(123L, p.size); assertEquals(sha, p.sha256); assertNull(p.minUpdaterVersion)
    }

    @Test(expected = IllegalArgumentException::class)
    fun refusesABadId() {
        OtaManifest.parse(manifest("""[{"id":"../x","version":1,"url":"x","size":1,"sha256":"$sha"}]"""))
    }

    @Test(expected = IllegalArgumentException::class)
    fun refusesABadHash() {
        OtaManifest.parse(manifest("""[{"id":"hione","version":1,"url":"x","size":1,"sha256":"zz"}]"""))
    }
}
