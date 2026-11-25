package org.koitharu.kotatsu.details.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.sources.compat.EhentaiSourceFamily

class ExhentaiPresenceIndicatorTest {
    @Test
    fun standardCandidatesCanRepresentTheOnlineChapterWithoutReadingArchive() {
        listOf("Chapter.cbz", "Chapter_abcdef.cbz", "Chapter_4276381.cbz", "Chapter (2).CBZ")
            .forEach { assertTrue(it, isStandardExhentaiPresencePath("/unavailable/gallery/$it")) }
    }

    @Test
    fun renamedAndUnrecognizedCandidatesDoNotMarkTheOriginalChapter() {
        listOf("Chapter_Baru.cbz", "Other.cbz", "Chapter.zip", "Chapter.cbz.tmp")
            .forEach { assertFalse(it, isStandardExhentaiPresencePath("/gallery/$it")) }
    }

    @Test
    fun onlyMainAndGekkoUsePresenceProjection() {
        assertTrue(supportsExhentaiPresenceIndicator(EhentaiSourceFamily.OFFICIAL_SOURCE_NAME))
        assertTrue(supportsExhentaiPresenceIndicator("TSUKI:GEKKOUSHI:gekkoushi:EXHENTAI"))
        assertFalse(supportsExhentaiPresenceIndicator("TSUKI:OTHER:other:EXHENTAI"))
    }
}
