package org.koitharu.kotatsu.settings.sources

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LnCandidateRegistryTest {

    private val candidate = """
        {"id":"chikari","name":"Chikari","language":"en","upstreamLanguage":"English",
        "version":"1.0.2","site":"https://chikari.moe","state":"TESTING",
        "checks":{"catalog":"PASS","api":"PASS","runtime":"UNKNOWN","android":"UNKNOWN"},
        "distribution":{"artifact":"UNPUBLISHED","verifiedSha256":null}}
    """.trimIndent()

    private fun registry(item: String = candidate) = """
        {"schemaVersion":1,"kind":"MIYORARE_LNREADER_NOVEL_CANDIDATES",
        "scope":{"languages":["en","id"],"installPolicy":"EXPLICIT_USER_ACTION",
        "unverifiedInstallAllowed":false},
        "upstream":{"repository":"LNReader/lnreader-plugins","commit":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"},
        "candidates":[$item]}
    """.trimIndent()

    @Test
    fun pilotRemainsReadOnlyAndSourceIdentityIsStable() {
        val result = LnCandidateRegistry.parse(registry())
        assertEquals("chikari", result.candidates.single().id)
        assertEquals("TESTING", result.candidates.single().state)
        assertEquals("PASS", result.candidates.single().apiCheck)
        assertEquals("UNKNOWN", result.candidates.single().androidCheck)
    }

    @Test
    fun foreignLanguageDuplicateIdentityAndUnverifiedReadyFailClosed() {
        val tests = listOf(
            candidate.replace(""""language":"en"""", """"language":"fr""""),
            candidate + "," + candidate,
            candidate.replace(""""state":"TESTING"""", """"state":"READY""""),
            candidate.replace(""""artifact":"UNPUBLISHED"""", """"artifact":"SEALED"""")
                .replace(""""state":"TESTING"""", """"state":"READY""""),
        )
        for (bad in tests) {
            assertThrows(IllegalArgumentException::class.java) {
                LnCandidateRegistry.parse(registry(bad))
            }
        }
    }
}
