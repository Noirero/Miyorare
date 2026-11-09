package org.koitharu.kotatsu.reader.ui.epub.translation

import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Opt-in paired *live* Google Translate benchmark.
 *
 * Runs the exact production online engine and both Kotlin splitters on one pinned 24-sample
 * corpus, in serial with 750ms pacing, alternating the A/B order between samples. No
 * translation is written to app user caches. Results require independent blind human review.
 */
class NovelOnlineCjkQualityABBenchmarkTest {
    @Test
    fun livePairedTranslationQualityExperiment() = runBlocking {
        assumeTrue(
            "Live Google API experiment requires explicit opt-in",
            System.getenv("MIYORARE_ONLINE_TRANSLATION_AB_LIVE") == "1",
        )
        val jsonText = checkNotNull(javaClass.getResourceAsStream(
            "/novel-translation-quality/ja-zh-ko-v1.json"
        )).bufferedReader(Charsets.UTF_8).use { it.readText() }
        val corpus = Json.parseToJsonElement(jsonText).jsonObject
        val samples = corpus.getValue("samples").jsonArray
        assertEquals(24, samples.size)
        val engine = MiyorareOnlineTranslationEngine(OkHttpClient())
        val blind = ArrayList<JsonObject>(samples.size)
        val mapping = ArrayList<JsonObject>(samples.size)
        var error: String? = null
        var requests = 0
        var elapsedTotal = 0L
        try {
            for ((index, element) in samples.withIndex()) {
                val sample = element.jsonObject
                val id = sample.getValue("id").jsonPrimitive.content
                val language = sample.getValue("sourceLanguage").jsonPrimitive.content
                val source = sample.getValue("text").jsonPrimitive.content
                val original = NovelTranslationSegmenter.split(source, MAX_CHARS)
                val candidate = NovelTranslationSegmenter.splitCjkAware(source, MAX_CHARS)
                assertEquals(source, original.joinToString("") { it.text })
                assertEquals(source, candidate.joinToString("") { it.text })
                val order = if (index % 2 == 0) listOf("legacy", "candidate")
                    else listOf("candidate", "legacy")
                val outcomes = LinkedHashMap<String, JsonObject>(2)
                for (variant in order) {
                    val spans = if (variant == "legacy") original else candidate
                    val startTime = System.nanoTime()
                    val translated = StringBuilder()
                    for (span in spans) {
                        assertTrue(span.text.length <= MAX_CHARS)
                        val result = engine.translate(NovelTranslationRequest(
                            text = span.text,
                            sourceLanguage = language,
                            targetLanguage = "id",
                            style = NovelTranslationStyle.NATURAL,
                            contextAware = false,
                        ))
                        translated.append(preserveBoundaryWhitespace(span.text, result))
                        requests++
                        // A conservative spacing rule for both arms (not burst requests).
                        delay(750L)
                    }
                    val millis = (System.nanoTime() - startTime) / 1_000_000
                    elapsedTotal += millis
                    outcomes[variant] = buildJsonObject {
                        put("translation", translated.toString())
                        put("elapsedMs", millis)
                        put("requestCount", spans.size)
                        put("segmentEndOffsets", buildJsonArray {
                            spans.forEach { add(JsonPrimitive(it.end)) }
                        })
                        put("cjkSentenceBoundaryCount", spans.dropLast(1).count { span ->
                            span.text.last() in charArrayOf('。', '！', '？', '」', '』', '”', '.')
                        })
                    }
                }
                blind += buildJsonObject {
                    put("id", id)
                    put("language", language)
                    put("category", sample.getValue("category").jsonPrimitive.content)
                    put("source", source)
                    put("characterNames", sample.getValue("characterNames"))
                    put("A", checkNotNull(outcomes[order[0]]))
                    put("B", checkNotNull(outcomes[order[1]]))
                }
                mapping += buildJsonObject {
                    put("id", id)
                    put("A", order[0])
                    put("B", order[1])
                }
                println("QUALITY_AB sample=" + id + " compared, oldChunks=" + original.size +
                    ", candidateChunks=" + candidate.size)
            }
        } catch (failure: Exception) {
            error = failure.javaClass.simpleName + ": " + failure.message.orEmpty().take(180)
        } finally {
            val folder = File("build/translation-quality-ab")
            folder.mkdirs()
            val complete = error == null && blind.size == samples.size
            File(folder, "blind-paired-translations.json").writeText(
                buildJsonObject {
                    put("status", if (complete) "COMPLETE_NEEDS_HUMAN_REVIEW" else "INCOMPLETE_NOT_COMPARABLE")
                    put("sampleCount", blind.size)
                    put("provider", "MiyorareOnlineTranslationEngine (Google Translate gtx)")
                    put("maxUtf16Chars", MAX_CHARS)
                    put("requestGapMillis", 750)
                    put("totalRequests", requests)
                    put("elapsedMs", elapsedTotal)
                    put("pairs", JsonArray(blind))
                    if (error != null) put("failure", error)
                }.toString(),
                Charsets.UTF_8,
            )
            File(folder, "unblind-map.json").writeText(
                buildJsonObject {
                    put("status", if (complete) "COMPLETE" else "INCOMPLETE")
                    put("mapping", JsonArray(mapping))
                }.toString(),
                Charsets.UTF_8,
            )
            println("QUALITY_AB RESULT: " + (if (complete) "COMPLETE_NEEDS_HUMAN_REVIEW" else "INCOMPLETE_NOT_COMPARABLE") +
                " samplePairs=" + blind.size + "/24 requests=" + requests + " elapsedMs=" + elapsedTotal +
                if (error != null) " error=" + error else "")
        }
        if (error != null) throw AssertionError("Live translation A/B is not comparable: " + error)
        assertEquals(24, blind.size)
    }

    /** The coordinator applies identical source-edge whitespace preservation to every provider result. */
    private fun preserveBoundaryWhitespace(source: String, translated: String): String {
        val first = source.indexOfFirst { !it.isWhitespace() }
        if (first < 0) return source
        val last = source.indexOfLast { !it.isWhitespace() }
        return buildString {
            append(source, 0, first)
            append(translated.trim())
            append(source, last + 1, source.length)
        }
    }

    private companion object {
        private const val MAX_CHARS = 1600
    }
}
