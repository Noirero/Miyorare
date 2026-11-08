package org.koitharu.kotatsu.settings.sources

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import org.koitharu.kotatsu.core.network.BaseHttpClient
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/** Read-only Source Pack candidate inventory. Never an executable plugin installer. */
data class LnCandidate(
    val id: String,
    val name: String,
    val language: String,
    val version: String,
    val site: String,
    val state: String,
    val apiCheck: String,
    val runtimeCheck: String,
    val androidCheck: String,
)

data class LnCandidateSnapshot(
    val upstreamCommit: String,
    val candidates: List<LnCandidate>,
)

@Singleton
class LnCandidateRegistry @Inject constructor(
    @BaseHttpClient private val client: OkHttpClient,
) {
    /** Fetch only when the user enters/refreshes the Source Packs candidate screen. */
    suspend fun fetch(): LnCandidateSnapshot = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(CANDIDATE_URL).build()
        val bytes = client.newCall(request).execute().use { response ->
            require(response.isSuccessful) { "Candidate registry HTTP " + response.code }
            val declaredSize = response.body.contentLength()
            require(declaredSize < 0 || declaredSize in 1..MAX_REGISTRY_BYTES) {
                "Candidate registry exceeds safety limit"
            }
            val output = ByteArrayOutputStream()
            response.body.byteStream().use { input ->
                val chunk = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val n = input.read(chunk)
                    if (n < 0) break
                    if (n == 0) continue
                    require(output.size().toLong() + n <= MAX_REGISTRY_BYTES) {
                        "Candidate registry exceeds safety limit"
                    }
                    output.write(chunk, 0, n)
                }
            }
            require(output.size() > 0) { "Candidate registry is empty" }
            output.toByteArray()
        }
        parse(bytes.toString(Charsets.UTF_8))
    }

    companion object {
        private const val MAX_REGISTRY_BYTES = 1024L * 1024L
        private const val CANDIDATE_URL =
            "https://raw.githubusercontent.com/Noirero/Miyorare-Source-Packs/main/registry/lnreader/en-id-candidates.v1.json"
        private val SOURCE_ID = Regex("^[a-zA-Z0-9_.-]+$")
        private val GIT_SHA = Regex("^[0-9a-f]{40}$")
        private val STATES = setOf("INVENTORIED", "TESTING", "READY", "PROBLEM")
        private val CHECKS = setOf("UNKNOWN", "PASS", "FAIL")

        /** Public for targeted JVM tests; untrusted metadata never authorizes plugin execution. */
        fun parse(payload: String): LnCandidateSnapshot {
            val root = Json.parseToJsonElement(payload).jsonObject
            require(root.int("schemaVersion") == 1)
            require(root.text("kind") == "MIYORARE_LNREADER_NOVEL_CANDIDATES")
            val scope = root.getValue("scope").jsonObject
            require(scope.getValue("languages").jsonArray.map { it.jsonPrimitive.content } == listOf("en", "id"))
            require(scope.text("installPolicy") == "EXPLICIT_USER_ACTION")
            require(scope.getValue("unverifiedInstallAllowed").jsonPrimitive.content == "false")
            val upstream = root.getValue("upstream").jsonObject
            require(upstream.text("repository") == "LNReader/lnreader-plugins")
            val commit = upstream.text("commit")
            require(GIT_SHA.matches(commit)) { "Candidate registry upstream commit is not pinned" }
            val items = root.getValue("candidates").jsonArray
            require(items.isNotEmpty() && items.size <= 1000)
            val ids = HashSet<String>(items.size)
            val candidates = items.map { item ->
                val entry = item.jsonObject
                val id = entry.text("id")
                require(SOURCE_ID.matches(id) && ids.add(id)) { "Duplicate or invalid candidate identity" }
                val language = entry.text("language")
                val declared = entry.text("upstreamLanguage")
                require(
                    (language == "en" && declared == "English") ||
                        (language == "id" && declared == "Bahasa Indonesia")
                ) { "Unsupported LNReader candidate language" }
                val state = entry.text("state")
                require(state in STATES) { "Unknown candidate state" }
                val checks = entry.getValue("checks").jsonObject
                val api = checks.text("api")
                val runtime = checks.text("runtime")
                val android = checks.text("android")
                require(listOf(api, runtime, android, checks.text("catalog")).all { it in CHECKS })
                val distribution = entry.getValue("distribution").jsonObject
                val artifact = distribution.text("artifact")
                require(artifact in setOf("UNPUBLISHED", "SEALED"))
                if (artifact == "UNPUBLISHED") require(state != "READY") {
                    "Unpublished candidate cannot be READY"
                }
                if (state == "READY") require(
                    artifact == "SEALED" &&
                        listOf(api, runtime, android).all { it == "PASS" }
                ) { "Incomplete readiness evidence" }
                LnCandidate(
                    id = id,
                    name = entry.text("name"),
                    language = language,
                    version = entry.text("version"),
                    site = entry.text("site"),
                    state = state,
                    apiCheck = api,
                    runtimeCheck = runtime,
                    androidCheck = android,
                )
            }
            // No raw JS URL or candidate metadata is used as an installation authority.
            return LnCandidateSnapshot(commit, candidates.sortedWith(compareBy({ it.language }, { it.name.lowercase() })))
        }

        private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content.also {
            require(it.isNotBlank()) { "Missing candidate metadata: $key" }
        }

        private fun JsonObject.int(key: String) = text(key).toInt()
    }
}
