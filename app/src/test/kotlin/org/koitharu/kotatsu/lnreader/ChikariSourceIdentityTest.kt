package org.koitharu.kotatsu.lnreader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.koitharu.kotatsu.lnreader.model.LnMangaSource
import org.koitharu.kotatsu.lnreader.model.LnPlugin

class ChikariSourceIdentityTest {

    private fun chikari(version: String, owner: String?) = LnMangaSource(
        LnPlugin(
            id = "chikari",
            name = "Chikari",
            site = "https://chikari.moe",
            lang = "English",
            version = version,
            iconUrl = "",
            imageRequestInit = null,
            filters = null,
            pluginSettings = null,
            hasParsePage = true,
            hasResolveUrl = true,
            storeId = owner,
        ),
    )

    @Test
    fun officialCompanionKeepsExistingLnReaderSourceIdentity() {
        val original = chikari("1.0.2", null)
        val updated = chikari("1.0.2.1", "miyorare-en")
        assertEquals("LN_chikari", original.name)
        assertEquals(original.name, updated.name)
        assertEquals(original, updated)
        assertEquals(original.hashCode(), updated.hashCode())
        assertNotEquals("CHIKARI", updated.name)
    }
}
