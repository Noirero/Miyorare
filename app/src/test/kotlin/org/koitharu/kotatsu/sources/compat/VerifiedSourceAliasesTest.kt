package org.koitharu.kotatsu.sources.compat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VerifiedSourceAliasesTest {

    @Test
    fun `verified keiyoushi uma and official identities share one canonical id`() {
        val cases = listOf(
            Triple(2677079941490683989L, "miyorare-id", "BACAMI"),
            Triple(3639673976007021338L, "miyorare-id", "KIRYUU"),
            Triple(4838485846640015979L, "miyorare-id", "KOMIKU"),
            Triple(6247824327199706550L, "miyorare-en", "ASURASCANS"),
            Triple(626267698662819838L, "miyorare-en", "AQUAMANGA"),
            Triple(7422099479605463706L, "miyorare-en", "BATCAVE"),
        )

        for ((sourceId, pluginId, sourceName) in cases) {
            val expected = CanonicalSourceId("miyorare:$pluginId:$sourceName")
            val mihon = VerifiedSourceAliases.canonicalize(
                StoredSourceIdentity.direct("MIHON_$sourceId:Keiyoushi"),
            )
            val uma = VerifiedSourceAliases.canonicalize(
                StoredSourceIdentity.direct("TSUKI:UMA:uma:$sourceName"),
            )
            val official = VerifiedSourceAliases.canonicalize(
                StoredSourceIdentity.direct("TSUKI:MIYORARE:$pluginId:$sourceName"),
            )

            assertEquals(expected, mihon.canonicalId)
            assertEquals(expected, uma.canonicalId)
            assertEquals(expected, official.canonicalId)
            assertTrue(mihon.isAlias)
            assertTrue(uma.isAlias)
            assertFalse(official.isAlias)
        }
    }

    @Test
    fun `unverified providers stay isolated`() {
        val mihon = StoredSourceIdentity.direct("MIHON_999999:Example")
        val uma = StoredSourceIdentity.direct("TSUKI:UMA:uma:EXAMPLE")

        assertEquals(mihon, VerifiedSourceAliases.canonicalize(mihon))
        assertEquals(uma, VerifiedSourceAliases.canonicalize(uma))
        assertFalse(mihon.canonicalId == uma.canonicalId)
    }

    @Test
    fun `verified aliases are unique by catalogue id and provider key`() {
        assertEquals(
            VerifiedSourceAliases.entries.size,
            VerifiedSourceAliases.entries.map { it.mihonSourceId }.toSet().size,
        )
        assertEquals(
            VerifiedSourceAliases.entries.size,
            VerifiedSourceAliases.entries.map { it.umaStoredName }.toSet().size,
        )
        assertEquals(
            VerifiedSourceAliases.entries.size,
            VerifiedSourceAliases.entries.map { it.officialStoredName }.toSet().size,
        )
    }
}
