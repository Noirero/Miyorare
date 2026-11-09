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
                    val translatedSegments = ArrayList<String>(spans.size)
                    for (span in spans) {
                        assertTrue(span.text.length <= MAX_CHARS)
                        val result = engine.translate(NovelTranslationRequest(
                            text = span.text,
                            sourceLanguage = language,
                            targetLanguage = "id",
                            style = NovelTranslationStyle.NATURAL,
                            contextAware = false,
                        ))
                        translatedSegments += preserveBoundaryWhitespace(span.text, result)
                        requests++
                        // A conservative spacing rule for both arms (not burst requests).
                        delay(750L)
                    }
                    val translated = reconstructSegmentResults(
                        spans = spans,
                        results = translatedSegments,
                        sourceLanguage = language,
                        // Identical splitter boundaries are controls: do not change their reconstruction.
                        candidateOnly = variant == "candidate" && candidate != original,
                    )
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


    /**
     * Experiment-only reconstruction; the production Coordinator is NOT changed.
     * The baseline keeps its historical no-separator behavior. For CJK candidate
     * splits ending at an explicit sentence mark, add an Indonesian sentence space
     * only if the provider outputs themselves have no boundary whitespace.
     */
    private fun reconstructSegmentResults(
        spans: List<NovelTranslationSegment>,
        results: List<String>,
        sourceLanguage: String,
        candidateOnly: Boolean,
    ): String {
        require(spans.size == results.size)
        return buildString {
            for (index in spans.indices) {
                if (candidateOnly && index > 0 && shouldBridgeIndonesianSentence(
                    previousSource = spans[index - 1].text,
                    nextSource = spans[index].text,
                    previousTranslated = results[index - 1],
                    nextTranslated = results[index],
                    sourceLanguage = sourceLanguage,
                )) {
                    append(' ')
                }
                append(results[index])
            }
        }
    }

    private fun shouldBridgeIndonesianSentence(
        previousSource: String,
        nextSource: String,
        previousTranslated: String,
        nextTranslated: String,
        sourceLanguage: String,
    ): Boolean {
        if (sourceLanguage !in setOf("ja", "zh-CN", "ko")) return false
        if (previousSource.isEmpty() || nextSource.isEmpty() ||
            previousTranslated.isEmpty() || nextTranslated.isEmpty()) return false
        if (previousSource.last().isWhitespace() || nextSource.first().isWhitespace()) return false
        if (previousTranslated.last().isWhitespace() || nextTranslated.first().isWhitespace()) return false

        // Skip close-quotation marks only to inspect whether the source split
        // was *actually* at the end of a sentence, not in the middle of a word.
        val sourceTerminal = previousSource.trimEnd('」', '』', '”', '’', '】', '）', '〉', '》')
            .lastOrNull() ?: return false
        if (sourceTerminal !in "。！？.!?") return false
        if (previousTranslated.last() !in ".!?\"”") return false
        val next = nextTranslated.first()
        return next in 'A'..'Z' || next in 'a'..'z' || next in "\"“‘"
    }

    @Test
    fun candidateOnlyReconstructionBridgesConfirmedCjkSentences() {
        for ((source, left, right) in listOf(
            Triple("ja", "彼は帰った。", "そして眠った。"),
            Triple("zh-CN", "她离开了。", "然后走了。"),
            Triple("ko", "그는 떠났다.", "그리고 돌아왔다."),
        )) {
            val spans = listOf(
                NovelTranslationSegment(0, left.length, left),
                NovelTranslationSegment(left.length, left.length + right.length, right),
            )
            val outputs = listOf("Dia pulang.", "Kemudian tidur.")
            assertEquals("Dia pulang.Kemudian tidur.",
                reconstructSegmentResults(spans, outputs, source, candidateOnly = false))
            assertEquals("Dia pulang. Kemudian tidur.",
                reconstructSegmentResults(spans, outputs, source, candidateOnly = true))
        }
    }

    @Test
    fun candidateOnlyReconstructionDoesNotNormalizeParagraphsWordsOrOtherLanguages() {
        fun join(left: String, right: String, outputLeft: String, outputRight: String, lang: String) =
            reconstructSegmentResults(listOf(
                NovelTranslationSegment(0, left.length, left),
                NovelTranslationSegment(left.length, left.length + right.length, right),
            ), listOf(outputLeft, outputRight), lang, candidateOnly = true)

        assertEquals("Paragraf satu.\n\nParagraf dua.",
            join("最初の段落。\n\n", "次の段落。", "Paragraf satu.\n\n", "Paragraf dua.", "ja"))
        assertEquals("Diarumah", join("途中", "の言葉", "Dia", "rumah", "ja"))
        assertEquals("Dia berkata.;", join("彼は言った。", "次", "Dia berkata.", ";", "ja"))
        assertEquals("One.Two.", join("One.", "Two.", "One.", "Two.", "en"))
        assertEquals("Dia pulang. Kemudian tidur.",
            join("彼は帰った。", "そして眠った。", "Dia pulang. ", "Kemudian tidur.", "ja"))
        assertEquals("Dia pulang.\nKemudian tidur.",
            join("彼は帰った。\n", "そして眠った。", "Dia pulang.\n", "Kemudian tidur.", "ja"))
    }


    @Test
    fun all24CorpusCasesKeepLosslessBoundariesAndIdenticalControls() {
        val input = checkNotNull(javaClass.getResourceAsStream(
            "/novel-translation-quality/ja-zh-ko-v1.json"
        )).bufferedReader(Charsets.UTF_8).use { it.readText() }
        val samples = Json.parseToJsonElement(input).jsonObject.getValue("samples").jsonArray
        assertEquals(24, samples.size)
        var affected = 0
        var controls = 0
        for (sample in samples) {
            val source = sample.jsonObject.getValue("text").jsonPrimitive.content
            val language = sample.jsonObject.getValue("sourceLanguage").jsonPrimitive.content
            val old = NovelTranslationSegmenter.split(source, MAX_CHARS)
            val candidate = NovelTranslationSegmenter.splitCjkAware(source, MAX_CHARS)
            assertEquals(source, old.joinToString("") { it.text })
            assertEquals(source, candidate.joinToString("") { it.text })
            assertTrue(old.all { it.text.length <= MAX_CHARS })
            assertTrue(candidate.all { it.text.length <= MAX_CHARS })
            if (candidate == old) {
                controls++
                val mockTranslations = old.map { "Kalimat berakhir." }
                assertEquals(
                    reconstructSegmentResults(old, mockTranslations, language, candidateOnly = false),
                    reconstructSegmentResults(candidate, mockTranslations, language, candidateOnly = candidate != old),
                )
            } else {
                affected++
            }
        }
        assertEquals(13, affected)
        assertEquals(11, controls)
    }


    /**
     * Frozen independent prospective experiment, kept isolated from production.
     * Source, strata, estimator and acceptance rules are owner-pre-registered by SHA256.
     * Translation provider calls are opt-in and do not touch app caches or BYOK.
     */
    @Test
    fun liveProspectiveCjkAndEnglishEvaluation() = runBlocking {
        assumeTrue(
            "Prospective translation calls require explicit opt-in",
            System.getenv("MIYORARE_ONLINE_TRANSLATION_PROSPECTIVE_LIVE") == "1",
        )
        val prefix = "/novel-translation-quality/"
        fun verifiedResource(name: String, expectedSha256: String): JsonObject {
            val bytes = checkNotNull(javaClass.getResourceAsStream(prefix + name)).use { it.readBytes() }
            val actual = java.security.MessageDigest.getInstance("SHA-256")
                .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
            assertEquals(name + " must match the preregistered freeze", expectedSha256, actual)
            return Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
        }
        val corpus = verifiedResource(
            "prospective_cjk_corpus_frozen.json",
            "01dc0c3fcf8310c5ee1b850025c271e9658216a3d07ab5ed072f9c3873a14de6",
        )
        verifiedResource(
            "prospective_quality_gate_frozen.json",
            "4e530532f2b78b46ea8c9eeb3ce82986e58cff",
        )
        val english = verifiedResource(
            "english_baseline_diagnostic_frozen.json",
            "92d470b3c88ca243829ce3ab1cd0a9dda1ee4c72c7326b77fecc08d68b04d339",
        )
        val samples = corpus.getValue("samples").jsonArray.map { it.jsonObject }
        assertEquals(18, samples.size)
        assertEquals(4, english.getValue("samples").jsonArray.size)
        val rng = java.security.SecureRandom()
        val legacyFirstById = HashMap<String, Boolean>()
        for (language in listOf("ja", "zh-CN", "ko")) {
            val group = samples.filter { it.getValue("sourceLanguage").jsonPrimitive.content == language }
            assertEquals(6, group.size)
            assertEquals(4, group.count { it.getValue("predeclaredStratum").jsonPrimitive.content == "affected" })
            assertEquals(2, group.count { it.getValue("predeclaredStratum").jsonPrimitive.content == "control" })
            val ids = group.map { it.getValue("id").jsonPrimitive.content }.toMutableList()
            java.util.Collections.shuffle(ids, rng)
            ids.forEachIndexed { index, id -> legacyFirstById[id] = index < 3 }
        }
        val engine = MiyorareOnlineTranslationEngine(OkHttpClient())
        val blind = ArrayList<JsonObject>(samples.size)
        val privateMap = ArrayList<JsonObject>(samples.size)
        val englishOutcomes = ArrayList<JsonObject>(4)
        var requests = 0
        var failure: String? = null
        try {
            for (sample in samples) {
                val id = sample.getValue("id").jsonPrimitive.content
                val lang = sample.getValue("sourceLanguage").jsonPrimitive.content
                val source = sample.getValue("text").jsonPrimitive.content
                val old = NovelTranslationSegmenter.split(source, 1600)
                val next = NovelTranslationSegmenter.splitCjkAware(source, 1600)
                val legacyEnds = sample.getValue("legacyEndOffsets").jsonArray.map { it.jsonPrimitive.content.toInt() }
                val candidateEnds = sample.getValue("candidateEndOffsets").jsonArray.map { it.jsonPrimitive.content.toInt() }
                assertEquals(id + " legacy freeze offsets", legacyEnds, old.map { it.end })
                assertEquals(id + " candidate freeze offsets", candidateEnds, next.map { it.end })
                assertEquals(source, old.joinToString("") { it.text })
                assertEquals(source, next.joinToString("") { it.text })
                val affected = sample.getValue("predeclaredStratum").jsonPrimitive.content == "affected"
                assertEquals(id + " affected stratum", affected, legacyEnds != candidateEnds)
                val order = if (legacyFirstById[id] == true) listOf("legacy", "candidate")
                    else listOf("candidate", "legacy")
                val results = LinkedHashMap<String, JsonObject>()
                for (variant in order) {
                    val spans = if (variant == "legacy") old else next
                    val raw = ArrayList<String>(spans.size)
                    val preserved = ArrayList<String>(spans.size)
                    val started = System.nanoTime()
                    for (span in spans) {
                        assertTrue(span.text.length <= 1600)
                        val output = engine.translate(NovelTranslationRequest(
                            text = span.text,
                            sourceLanguage = lang,
                            targetLanguage = "id",
                            style = NovelTranslationStyle.NATURAL,
                            contextAware = false,
                        ))
                        raw += output
                        preserved += preserveBoundaryWhitespace(span.text, output)
                        requests++
                        delay(750L)
                    }
                    val elapsed = (System.nanoTime() - started) / 1_000_000L
                    val stitched = reconstructSegmentResults(
                        spans = spans,
                        results = preserved,
                        sourceLanguage = lang,
                        candidateOnly = variant == "candidate" && affected,
                    )
                    results[variant] = buildJsonObject {
                        put("translation", stitched)
                        put("requestCount", spans.size)
                        put("elapsedMs", elapsed)
                        put("rawProviderSegments", buildJsonArray { raw.forEach { add(JsonPrimitive(it)) } })
                        put("sourceEndOffsets", buildJsonArray { spans.forEach { add(JsonPrimitive(it.end)) } })
                    }
                }
                val x = if (rng.nextBoolean()) "legacy" else "candidate"
                val y = if (x == "legacy") "candidate" else "legacy"
                blind += buildJsonObject {
                    put("id", id)
                    put("language", lang)
                    put("category", sample.getValue("category").jsonPrimitive.content)
                    put("characterNames", sample.getValue("characterNames"))
                    put("source", source)
                    put("X", checkNotNull(results[x]).getValue("translation"))
                    put("Y", checkNotNull(results[y]).getValue("translation"))
                }
                privateMap += buildJsonObject {
                    put("id", id)
                    put("stratum", sample.getValue("predeclaredStratum").jsonPrimitive.content)
                    put("X", x)
                    put("Y", y)
                    put("executionOrder", buildJsonArray { order.forEach { add(JsonPrimitive(it)) } })
                    put("legacy", checkNotNull(results["legacy"]))
                    put("candidate", checkNotNull(results["candidate"]))
                }
            }
            for (sampleElement in english.getValue("samples").jsonArray) {
                val sample = sampleElement.jsonObject
                val id = sample.getValue("id").jsonPrimitive.content
                val source = sample.getValue("text").jsonPrimitive.content
                val spans = NovelTranslationSegmenter.split(source, 1600)
                assertEquals(
                    sample.getValue("segmentEndOffsets").jsonArray.map { it.jsonPrimitive.content.toInt() },
                    spans.map { it.end },
                )
                val outputs = ArrayList<String>()
                val raw = ArrayList<String>()
                val started = System.nanoTime()
                for (span in spans) {
                    val result = engine.translate(NovelTranslationRequest(
                        text = span.text,
                        sourceLanguage = "en",
                        targetLanguage = "id",
                        style = NovelTranslationStyle.NATURAL,
                        contextAware = false,
                    ))
                    raw += result
                    outputs += preserveBoundaryWhitespace(span.text, result)
                    requests++
                    delay(750L)
                }
                englishOutcomes += buildJsonObject {
                    put("id", id)
                    put("source", source)
                    put("category", sample.getValue("category").jsonPrimitive.content)
                    put("translation", outputs.joinToString(""))
                    put("rawProviderSegments", buildJsonArray { raw.forEach { add(JsonPrimitive(it)) } })
                    put("requestCount", spans.size)
                    put("elapsedMs", (System.nanoTime() - started) / 1_000_000L)
                    put("segmentEndOffsets", buildJsonArray { spans.forEach { add(JsonPrimitive(it.end)) } })
                }
            }
            assertEquals(18, blind.size)
            assertEquals(4, englishOutcomes.size)
            assertEquals(66, requests)
        } catch (e: Exception) {
            failure = e.javaClass.simpleName + ": " + e.message.orEmpty().take(240)
        } finally {
            val dir = File("build/translation-quality-ab/prospective")
            dir.mkdirs()
            val complete = failure == null && blind.size == 18 && englishOutcomes.size == 4
            File(dir, "blind-cjk-pairs.json").writeText(buildJsonObject {
                put("status", if (complete) "COMPLETE_NEEDS_BLIND_SCORING" else "INCOMPLETE_NOT_COMPARABLE")
                put("corpusSha256", "01dc0c3fcf8310c5ee1b850025c271e9658216a3d07ab5ed072f9c3873a14de6")
                put("policySha256", "4e530532f2b78b46ea8c9eeb3ce82986e58cff")
                put("pairs", JsonArray(blind))
                if (failure != null) put("failure", checkNotNull(failure))
            }.toString(), Charsets.UTF_8)
            File(dir, "private-mapping-and-operational-evidence.json").writeText(buildJsonObject {
                put("status", if (complete) "COMPLETE" else "INCOMPLETE")
                put("requests", requests)
                put("paired", JsonArray(privateMap))
                if (failure != null) put("failure", checkNotNull(failure))
            }.toString(), Charsets.UTF_8)
            File(dir, "english-baseline-targeted.json").writeText(buildJsonObject {
                put("status", if (englishOutcomes.size == 4) "COMPLETE" else "INCOMPLETE")
                put("englishSha256", "92d470b3c88ca243829ce3ab1cd0a9dda1ee4c72c7326b77fecc08d68b04d339")
                put("samples", JsonArray(englishOutcomes))
            }.toString(), Charsets.UTF_8)
        }
        if (failure != null) throw AssertionError("Prospective A/B incomplete: " + failure)
    }

    private companion object {
        private const val MAX_CHARS = 1600
    }
}
