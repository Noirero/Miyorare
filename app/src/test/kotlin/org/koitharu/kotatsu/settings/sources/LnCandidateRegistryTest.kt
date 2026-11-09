package org.koitharu.kotatsu.settings.sources

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LnCandidateRegistryTest {

    private val candidate = """
        {"id":"chikari","name":"Chikari","language":"en","upstreamLanguage":"English",
        "version":"1.0.2","site":"https://chikari.moe",
        "codeUrl":"https://raw.githubusercontent.com/lnreader/lnreader-plugins/plugins/v3.0.0/.js/src/plugins/english/chikari.js",
        "iconUrl":"https://raw.githubusercontent.com/lnreader/lnreader-plugins/plugins/v3.0.0/public/static/src/en/chikari/icon.png",
        "state":"TESTING",
        "checks":{"catalog":"PASS","api":"PASS","runtime":"UNKNOWN","android":"UNKNOWN"},
        "distribution":{"artifact":"UNPUBLISHED","verifiedSha256":null}}
    """.trimIndent()

    private fun registry(item: String = candidate) = """
        {"schemaVersion":1,"kind":"MIYORARE_LNREADER_NOVEL_CANDIDATES",
        "scope":{"languages":["en","id"],"installPolicy":"EXPLICIT_USER_ACTION",
        "unverifiedInstallAllowed":false},
        "upstream":{"repository":"LNReader/lnreader-plugins","branch":"plugins/v3.0.0",
        "indexPath":".dist/plugins.min.json",
        "indexUrl":"https://raw.githubusercontent.com/LNReader/lnreader-plugins/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa/.dist/plugins.min.json",
        "commit":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"},
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
            candidate.replace("\"artifact\":\"UNPUBLISHED\"", "\"artifact\":\"SEALED\""),
            candidate.replace("1.0.2", "latest"),
            candidate.replace("/english/chikari.js", "/french/chikari.js"),
            candidate.replace("https://chikari.moe", "http://chikari.moe"),
            candidate.replace("\"state\":\"TESTING\"", "\"state\":\"INVENTORIED\""),
            candidate.replace("\"state\":\"TESTING\"", "\"state\":\"READY\""),
        )
        for (bad in tests) {
            assertThrows(IllegalArgumentException::class.java) {
                LnCandidateRegistry.parse(registry(bad))
            }
        }
    }

    @Test
    fun publishedReadyNeedsSealedShaAndAllValidationGates() {
        val digest = "a".repeat(64)
        val ready = candidate
            .replace("\"state\":\"TESTING\"", "\"state\":\"READY\"")
            .replace("\"runtime\":\"UNKNOWN\"", "\"runtime\":\"PASS\"")
            .replace("\"android\":\"UNKNOWN\"", "\"android\":\"PASS\"")
            .replace("\"artifact\":\"UNPUBLISHED\",\"verifiedSha256\":null",
                "\"artifact\":\"SEALED\",\"verifiedSha256\":\"$digest\"")
        val parsed = LnCandidateRegistry.parse(registry(ready))
        assertEquals("READY", parsed.candidates.single().state)
        for (invalid in listOf(
            ready.replace(digest, "bad-digest"),
            ready.replace("\"runtime\":\"PASS\"", "\"runtime\":\"UNKNOWN\""),
            ready.replace("\"android\":\"PASS\"", "\"android\":\"FAIL\""),
        )) {
            assertThrows(IllegalArgumentException::class.java) {
                LnCandidateRegistry.parse(registry(invalid))
            }
        }
    }

}
