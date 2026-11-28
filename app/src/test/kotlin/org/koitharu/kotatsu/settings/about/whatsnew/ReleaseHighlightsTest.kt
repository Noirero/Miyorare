package org.koitharu.kotatsu.settings.about.whatsnew

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ReleaseHighlightsTest {
    private val current = ReleaseIdentity("1.4.30", 300)
    private val previous = ReleaseIdentity("1.4.29", 290)

    @Test fun upgradeOnlyAndMonotonicAcknowledgement() {
        assertFalse(WhatsNewEligibility.shouldShow(current, null, false))
        assertTrue(WhatsNewEligibility.shouldRecordBaseline(null, false))
        assertFalse(WhatsNewEligibility.shouldRecordBaseline(null, true))
        assertTrue(WhatsNewEligibility.shouldShow(current, null, true))
        assertTrue(WhatsNewEligibility.shouldShow(current, previous, true))
        // A durable earlier identity is authoritative even if package timestamps match.
        assertTrue(WhatsNewEligibility.shouldShow(current, previous, false))
        assertFalse(WhatsNewEligibility.shouldShow(current, current, false))
        assertFalse(WhatsNewEligibility.shouldShow(current, current, true))
        assertFalse(WhatsNewEligibility.shouldShow(current.copy(code = 301), current, true))
        assertFalse(WhatsNewEligibility.shouldShow(previous, current, true))
    }

    private fun fixture(): JSONObject = JSONObject("""{"schemaVersion":1,"version":"1.4.30","contentId":"debug-fixture-1","fixture":true,"summary":{"en":"Sample highlights for testing the update experience.","id":"Contoh sorotan untuk menguji pengalaman setelah update."},"entries":[{"id":"transitions","category":"NEW","title":{"en":"Screen transitions","id":"Transisi layar"},"description":{"en":"Choose an animation for switching main tabs.","id":"Pilih animasi perpindahan tab utama."},"destination":"SCREEN_TRANSITIONS"},{"id":"journey","category":"NEW","title":{"en":"Reader Journey","id":"Perjalanan membaca"},"description":{"en":"Open your reading journey.","id":"Buka perjalanan membacamu."},"destination":"READER_JOURNEY"},{"id":"sources","category":"IMPROVED","title":{"en":"Source management","id":"Pengelolaan sumber"},"description":{"en":"Browse installed reading sources.","id":"Jelajahi sumber bacaan terpasang."},"destination":"SOURCES_EXTENSIONS"},{"id":"fixture-fix","category":"FIXED","title":{"en":"Example stability improvement","id":"Contoh peningkatan stabilitas"},"description":{"en":"This sample is not an official release note.","id":"Contoh ini bukan catatan release resmi."}}]}""")

    @Test fun catalogMatchesInstalledVersionAndRejectsFixtureInProduction() {
        val raw = fixture().toString()
        assertNull(ReleaseCatalogParser.parse(raw, current.version, false))
        assertNull(ReleaseCatalogParser.parse(raw, previous.version, true))
        val parsed = requireNotNull(ReleaseCatalogParser.parse(raw, current.version, true))
        assertEquals(FeatureDestination.SCREEN_TRANSITIONS, parsed.entries.first().destination)
        assertEquals("Transisi layar", parsed.entries.first().title.forLanguage("id"))
        assertEquals("Transisi layar", parsed.entries.first().title.forLanguage("in"))
        assertEquals("Screen transitions", parsed.entries.first().title.forLanguage("ja"))
        assertNull(parsed.entries.last().destination)
    }

    @Test fun malformedMetadataFailsClosed() {
        val badDestination = fixture()
        badDestination.getJSONArray("entries").getJSONObject(0).put("destination", "java.lang.Runtime")
        assertNull(ReleaseCatalogParser.parse(badDestination.toString(), current.version, true))
        val missingLocale = fixture()
        missingLocale.getJSONObject("summary").remove("id")
        assertNull(ReleaseCatalogParser.parse(missingLocale.toString(), current.version, true))
        val duplicate = fixture()
        duplicate.getJSONArray("entries").getJSONObject(1).put("id", "transitions")
        assertNull(ReleaseCatalogParser.parse(duplicate.toString(), current.version, true))
        assertNull(ReleaseCatalogParser.parse("not JSON", current.version, true))
    }
}
