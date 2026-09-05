package org.koitharu.kotatsu.settings.sources.migration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LibrarySourceOptionTest {

	@Test
	fun `same displayed source merges raw keys and counts`() {
		val merged = mergeLibrarySourceOptions(
			listOf(
				option(key = "LEGACY_HITOMI", title = "Hitomi", count = 2, unavailable = true, sourceId = 123),
				option(key = "MIHON_123:Hitomi", title = "Hitomi", count = 6, unavailable = false, sourceId = 123),
				option(key = "HITOMI_OLD", title = " hitomi ", count = 3, unavailable = true, sourceId = 123),
			),
		)

		assertEquals(1, merged.size)
		assertEquals(setOf("LEGACY_HITOMI", "MIHON_123:Hitomi", "HITOMI_OLD"), merged.single().sourceKeys)
		assertEquals(11, merged.single().mangaCount)
		assertFalse(merged.single().isUnavailable)
	}

	@Test
	fun `same title with different Mihon ids stays separate`() {
		val merged = mergeLibrarySourceOptions(
			listOf(
				option("MIHON_101", "NHentai (English)", 2, false, 101),
				option("MIHON_102", "NHentai (Russian)", 3, false, 102),
			),
		)

		assertEquals(2, merged.size)
	}

	private fun option(
		key: String,
		title: String,
		count: Int,
		unavailable: Boolean,
		sourceId: Long? = null,
	) = LibrarySourceOption(
		key = key,
		sourceKeys = setOf(key),
		title = title,
		mangaCount = count,
		isUnavailable = unavailable,
		iconSourceKey = key,
		iconUrl = null,
		sourceId = sourceId,
	)
}
