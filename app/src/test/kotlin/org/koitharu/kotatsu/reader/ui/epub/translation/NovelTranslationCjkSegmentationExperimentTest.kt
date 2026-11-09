package org.koitharu.kotatsu.reader.ui.epub.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

/** Structural experiment only; translation-quality uplift is not yet proven. */
class NovelTranslationCjkSegmentationExperimentTest {
    private fun assertLossless(text: String, limit: Int) {
        val chunks = NovelTranslationSegmenter.splitCjkAware(text, limit)
        assertEquals(text, chunks.joinToString("") { it.text })
        var cursor = 0
        chunks.forEach { chunk ->
            assertEquals(cursor, chunk.start)
            assertEquals(chunk.text, text.substring(chunk.start, chunk.end))
            assertTrue(chunk.start < chunk.end && chunk.text.length <= limit)
            if (chunk.end < text.length) {
                assertTrue(!(
                    Character.isHighSurrogate(text[chunk.end - 1]) &&
                        Character.isLowSurrogate(text[chunk.end])
                ))
                assertTrue(!(text[chunk.end - 1] == '\r' && text[chunk.end] == '\n'))
            }
            cursor = chunk.end
        }
        assertEquals(text.length, cursor)
    }

    @Test
    fun cjkWithoutWhitespaceUsesSentenceBoundaries() {
        val japanese = "美咲は窓を開けた。風が強くても、約束を守ろうと決めた。「待って」と彼女は言った。"
        val mandarin = "林晨合上日记。雨还没有停，可他已经决定去车站。「别害怕。」苏宁轻声说。"
        val korean = "지윤은 조용히 편지를 접었다.비가 내려도 약속한 시간에 역으로 갔다.민서는 문을 열었다."
        for (text in listOf(japanese.repeat(76), mandarin.repeat(85), korean.repeat(65))) {
            val before = NovelTranslationSegmenter.split(text, 1600)
            val after = NovelTranslationSegmenter.splitCjkAware(text, 1600)
            assertLossless(text, 1600)
            assertTrue(before.size > 1 && after.size > 1)
            assertTrue(after.dropLast(1).any { segment ->
                segment.text.last() in charArrayOf('。', '！', '？', '」', '』', '”', '.')
            })
        }
    }

    @Test
    fun dialogueQuotesAndParagraphBoundariesArePreserved() {
        val dialogue = "「もう遅い。」彼女は静かに答えた。".repeat(120)
        val chunks = NovelTranslationSegmenter.splitCjkAware(dialogue, 1600)
        assertLossless(dialogue, 1600)
        assertTrue(chunks.dropLast(1).none {
            it.text.endsWith('。') && dialogue[it.end] == '」'
        })
        for (sep in listOf("\r\n\r\n", "\n\n", "\u2029")) {
            val text = "背景".repeat(440) + sep + "故事".repeat(900)
            assertLossless(text, 1600)
            val first = NovelTranslationSegmenter.splitCjkAware(text, 1600).first()
            assertTrue("paragraph terminator lost", first.text.endsWith(sep))
        }
    }

    @Test
    fun UnicodeSplitsAreLosslessAndBounded() {
        for (text in listOf(
            "言".repeat(1599) + "😀" + "言".repeat(300),
            "가".repeat(1599) + "e\u0301" + "나".repeat(300),
            "가".repeat(1599) + "👩\u200D💻" + "나".repeat(300),
            "言".repeat(1599) + "\r\n" + "言".repeat(400),
        )) assertLossless(text, 1600)
        assertThrows(IllegalArgumentException::class.java) {
            NovelTranslationSegmenter.splitCjkAware("😀", 1)
        }
    }

    @Test
    fun EnglishAndShortTextPreserveLegacyBehavior() {
        val english = ("The detective checked the notebook. " +
            "She waited for the last train before leaving.\n").repeat(55)
        assertEquals(NovelTranslationSegmenter.split(english, 1600),
            NovelTranslationSegmenter.splitCjkAware(english, 1600))
        for (short in listOf("Hello.", "こんにちは。", "你好！", "안녕하세요.", "", "😀")) {
            assertEquals(NovelTranslationSegmenter.split(short, 1600),
                NovelTranslationSegmenter.splitCjkAware(short, 1600))
        }
    }

    @Test
    fun VersionedOnlineSemanticKeyDoesNotReuseOldEntry() {
        val provider = NovelTranslationProvider(
            engine = object : NovelTranslationEngine {
                override suspend fun translate(request: NovelTranslationRequest) = request.text
            },
            capabilities = NovelTranslationProviderCapabilities(
                providerId = MIYORARE_ONLINE_PROVIDER_ID, maxBatchChars = 1600,
                maxSingleRequestChars = 1600, credentialRequired = false
            ),
            semanticIdentity = NovelTranslationProviderSemanticIdentity(
                configurationFingerprint = novelTranslationDigest("miyorare-online", "online-v1"),
                contractVersion = "online-v1", circuitKey = MIYORARE_ONLINE_PROVIDER_ID
            )
        )
        val selection = NovelTranslationSelection(
            engine = NovelTranslationEngineKind.ONLINE,
            sourceLanguage = "ja",
            targetLanguage = "id",
            style = NovelTranslationStyle.NATURAL,
            contextAware = false,
        )
        val old = buildNovelTranslationSemanticKey(provider, selection, "日本語の文章。", "", "")
        val future = provider.copy(semanticIdentity = provider.semanticIdentity.copy(
            configurationFingerprint = novelTranslationDigest("miyorare-online", "online-v2-cjk"),
            contractVersion = "online-v2-cjk"
        ))
        val fresh = buildNovelTranslationSemanticKey(future, selection, "日本語の文章。", "", "")
        assertNotEquals(old, fresh)
    }
}
