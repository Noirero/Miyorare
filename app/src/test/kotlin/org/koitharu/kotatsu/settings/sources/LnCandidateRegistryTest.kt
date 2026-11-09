package org.koitharu.kotatsu.settings.sources

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import javax.inject.Provider
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

    private fun registry(item: String = candidate, pilotState: String = "TESTING") = """
        {"schemaVersion":1,"kind":"MIYORARE_LNREADER_NOVEL_CANDIDATES",
        "scope":{"languages":["en","id"],"installPolicy":"EXPLICIT_USER_ACTION",
        "unverifiedInstallAllowed":false},
        "upstream":{"repository":"LNReader/lnreader-plugins","branch":"plugins/v3.0.0",
        "indexPath":".dist/plugins.min.json",
        "indexUrl":"https://raw.githubusercontent.com/LNReader/lnreader-plugins/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa/.dist/plugins.min.json",
        "commit":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"},
        "pilot":{"pluginId":"chikari","state":"$pilotState","evidence":{"api":"PASS","android":"UNKNOWN"}},
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
    fun pinnedSourcePacks202ManifestParsesAll198WithUniqueEnIdIdentities() {
        // Pinned test fixture copied byte-for-byte from Source Packs #202
        // @ ee7ccdf958fa7d001b8f08654549a7bf1e5769b5.
        val stream = checkNotNull(javaClass.getResourceAsStream(
            "/lnreader/en-id-candidates-source-packs-202-20261109.json"
        ))
        val source = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        val snapshot = LnCandidateRegistry.parse(source)
        assertEquals("45daf1d08b46dca6efdf6d38f250da95771967d2", snapshot.upstreamCommit)
        assertEquals(198, snapshot.candidates.size)
        assertEquals(198, snapshot.candidates.map { it.id }.toSet().size)
        assertEquals(187, snapshot.candidates.count { it.language == "en" })
        assertEquals(11, snapshot.candidates.count { it.language == "id" })
        assertTrue(snapshot.candidates.all { it.language == "en" || it.language == "id" })
        assertTrue(snapshot.candidates.none { it.state == "READY" })
        assertEquals("TESTING", snapshot.candidates.single { it.id == "chikari" }.state)
        assertEquals(listOf("IDWN.id"), filterLnCandidates(snapshot.candidates, "  idwN.iD  ").map { it.id })
        assertEquals(listOf("bacalightnovel"), filterLnCandidates(snapshot.candidates, "baca light novel").map { it.id })
        assertEquals(listOf("chikari"), filterLnCandidates(snapshot.candidates, "chikari.moe").map { it.id })
        assertTrue(filterLnCandidates(snapshot.candidates, "not_a_real_novel_source_99999").isEmpty())
        assertEquals(snapshot.candidates, filterLnCandidates(snapshot.candidates, " "))
        assertFalse(snapshot.candidates.any { it.language !in setOf("en", "id") })
    }

    @Test
    fun incompleteOrForgedProducerMetadataIsNotRendered() {
        val malformed = listOf(
            registry().replace("\"codeUrl\":", "\"unusedCodeUrl\":"),
            registry().replace("\"iconUrl\":", "\"unusedIconUrl\":"),
            registry().replace("\"indexPath\":\".dist/plugins.min.json\"",
                "\"indexPath\":\".dist/not-the-catalog.json\""),
            registry().replace("\"state\":\"TESTING\"", "\"state\":\"READY\"")
                .replace("\"artifact\":\"UNPUBLISHED\"", "\"artifact\":\"SEALED\""),
            registry().replace("\"pluginId\":\"chikari\"", "\"pluginId\":\"other\""),
            registry().replace("\"state\":\"TESTING\",\"evidence\":",
                "\"state\":\"READY\",\"evidence\":"),
            registry().replace("\"language\":\"en\"", "\"language\":\"ja\""),
            registry().replace("\"verifiedSha256\":null", "\"verifiedSha256\":\"bad\""),
            registry().replace("\"name\":\"Chikari\"", "\"name\":123"),
            registry().replace("\"schemaVersion\":1", "\"schemaVersion\":\"1\""),
            registry().replace("\"unverifiedInstallAllowed\":false",
                "\"unverifiedInstallAllowed\":\"false\""),
            registry().replace("\"api\":\"PASS\",\"android\":\"UNKNOWN\"",
                "\"api\":\"PASS\",\"android\":\"PASS\""),
        )
        malformed.forEach { payload ->
            org.junit.Assert.assertThrows(Exception::class.java) {
                LnCandidateRegistry.parse(payload)
            }
        }
    }

    @Test
    fun unpublishedAndInvalidHttpRegistryHaveExplicitFailures() {
        val http404 = registryForHttp(404, "Not Found")
        val error = org.junit.Assert.assertThrows(LnCandidateRegistryHttpException::class.java) {
            runBlocking { http404.fetch() }
        }
        assertEquals(404, error.statusCode)
        val invalidMetadata = registryForHttp(200, "{\"schemaVersion\":1}")
        org.junit.Assert.assertThrows(Exception::class.java) {
            runBlocking { invalidMetadata.fetch() }
        }
        val oversized = registryForHttp(200, "x".repeat(1024 * 1024 + 1))
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            runBlocking { oversized.fetch() }
        }
        val loaded = registryForHttp(200, registry())
        assertEquals("chikari", runBlocking { loaded.fetch() }.candidates.single().id)
    }

    private fun registryForHttp(status: Int, payload: String): LnCandidateRegistry {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(status)
                .message("Mock")
                .body(payload.toResponseBody())
                .build()
        }.build()
        return LnCandidateRegistry(object : Provider<OkHttpClient> {
            override fun get(): OkHttpClient = client
        })
    }

    @Test
    fun readyNonPilotCandidateNeedsSealedShaAndAllValidationGates() {
        val digest = "a".repeat(64)
        val ready = candidate
            .replace("\"id\":\"chikari\"", "\"id\":\"other-source\"")
            .replace("\"state\":\"TESTING\"", "\"state\":\"READY\"")
            .replace("\"runtime\":\"UNKNOWN\"", "\"runtime\":\"PASS\"")
            .replace("\"android\":\"UNKNOWN\"", "\"android\":\"PASS\"")
            .replace("\"artifact\":\"UNPUBLISHED\",\"verifiedSha256\":null",
                "\"artifact\":\"SEALED\",\"verifiedSha256\":\"$digest\"")
        val parsed = LnCandidateRegistry.parse(registry(candidate + "," + ready))
        assertEquals("READY", parsed.candidates.single { it.id == "other-source" }.state)
        for (invalid in listOf(
            ready.replace(digest, "bad-digest"),
            ready.replace("\"runtime\":\"PASS\"", "\"runtime\":\"UNKNOWN\""),
            ready.replace("\"android\":\"PASS\"", "\"android\":\"FAIL\""),
        )) {
            assertThrows(IllegalArgumentException::class.java) {
                LnCandidateRegistry.parse(registry(candidate + "," + invalid))
            }
        }
    }

}
