package org.koitharu.kotatsu.settings.sources.migration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LibrarySourceOptionTest {

	@Test
	fun `same canonical source merges raw keys and counts`() {
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

	@Test
	fun `same display title without verified alias stays separate`() {
		val merged = mergeLibrarySourceOptions(
			listOf(
				option("TSUKI:UMA:uma:Example", "Example", 2, false, canonicalKey = "provider:tsuki:UMA:uma:Example"),
				option("SOME_LEGACY_EXAMPLE", "Example", 3, true, canonicalKey = "kotatsu:SOME_LEGACY_EXAMPLE"),
			),
		)

		assertEquals(2, merged.size)
	}

	@Test
	fun `family presentation keeps every exact source variant`() {
		val merged = mergeLibrarySourceOptions(listOf(
			option("MIHON_101:E-Hentai", "E-Hentai (EN)", 2, false, 101, "family:ehentai"),
			option("MIHON_102:E-Hentai", "E-Hentai (JA)", 3, false, 102, "family:ehentai"),
			option("MIHON_103:E-Hentai", "E-Hentai (ZH)", 4, false, 103, "family:ehentai"),
		))
		val family = merged.single()
		assertEquals(3, family.variants.size)
		assertEquals(9, family.mangaCount)
		assertEquals(
			setOf("MIHON_101:E-Hentai", "MIHON_102:E-Hentai", "MIHON_103:E-Hentai"),
			family.variants.mapTo(hashSetOf()) { it.key },
		)
		assertEquals(3, family.sourceKeys.size)
	}

	private fun option(
		key: String,
		title: String,
		count: Int,
		unavailable: Boolean,
		sourceId: Long? = null,
		canonicalKey: String = sourceId?.let { "catalogue:$it" } ?: "stored:$key",
	) = LibrarySourceOption(
		key = key,
		sourceKeys = setOf(key),
		title = title,
		mangaCount = count,
		isUnavailable = unavailable,
		iconSourceKey = key,
		iconUrl = null,
		sourceId = sourceId,
		canonicalKey = canonicalKey,
	)
}
