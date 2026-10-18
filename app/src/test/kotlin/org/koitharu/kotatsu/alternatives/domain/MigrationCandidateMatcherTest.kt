package org.koitharu.kotatsu.alternatives.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.core.model.MangaSource
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter

class MigrationCandidateMatcherTest {

	private val matcher = MigrationCandidateMatcher()

	@Test
	fun `exact title plus matching author is ready without title-only trust`() {
		val seed = manga(1, "Miyorare Hero", "OLD", authors = setOf("Aki Sora"))
		val candidate = manga(2, "Miyorare Hero", "NEW", authors = setOf("Aki Sora"))

		val result = matcher.assess(seed, listOf(observation(candidate, seed.title)))

		assertEquals(MigrationMatchState.READY, result.state)
		assertEquals(candidate.id, result.best?.manga?.id)
		assertTrue(checkNotNull(result.best).evidence.authorMatch)
	}

	@Test
	fun `title alone is reviewable but never auto migrates`() {
		val seed = manga(1, "Shared Name", "OLD")
		val candidate = manga(2, "Shared Name", "NEW")

		val result = matcher.assess(seed, listOf(observation(candidate, seed.title)))

		assertEquals(MigrationMatchState.NEEDS_REVIEW, result.state)
		assertTrue(checkNotNull(result.best).evidence.exactTitleMatch)
		assertTrue(!checkNotNull(result.best).evidence.autoEligible)
	}

	@Test
	fun `same title different author with more chapters cannot beat corroborated identity`() {
		val seed = manga(
			id = 1,
			title = "Return of the Mage",
			sourceName = "OLD",
			authors = setOf("Author One"),
			chapterNumbers = (1..20).map(Int::toFloat),
		)
		val correct = manga(
			id = 2,
			title = "Return of the Mage",
			sourceName = "GOOD",
			authors = setOf("Author One"),
			chapterNumbers = (1..18).map(Int::toFloat),
		)
		val wrong = manga(
			id = 3,
			title = "Return of the Mage",
			sourceName = "BAD",
			authors = setOf("Different Author"),
			chapterNumbers = (1..80).map(Int::toFloat),
		)

		val result = matcher.assess(
			seed,
			listOf(observation(wrong, seed.title), observation(correct, seed.title)),
		)

		assertEquals(MigrationMatchState.READY, result.state)
		assertEquals(correct.id, result.best?.manga?.id)
		val wrongEvidence = result.candidates.single { it.manga.id == wrong.id }.evidence
		assertTrue(wrongEvidence.authorConflict)
		assertTrue(!wrongEvidence.autoEligible)
	}

	@Test
	fun `alias-only discovery can become ready when author and chapters corroborate`() {
		val seed = manga(
			id = 1,
			title = "Yuusha no Kikan",
			sourceName = "OLD",
			altTitles = setOf("The Hero Returns"),
			authors = setOf("Kana Mori"),
			chapterNumbers = (1..12).map(Int::toFloat),
		)
		val candidate = manga(
			id = 2,
			title = "Hero Returned Again",
			sourceName = "NEW",
			authors = setOf("Kana Mori"),
			chapterNumbers = (1..12).map(Int::toFloat),
		)

		val result = matcher.assess(seed, listOf(observation(candidate, "The Hero Returns")))

		assertEquals(MigrationMatchState.READY, result.state)
		assertTrue(checkNotNull(result.best).evidence.matchedSeedQuery)
		assertTrue(checkNotNull(result.best).evidence.strongChapterMatch)
	}

	@Test
	fun `two equally strong replacements stay in review`() {
		val seed = manga(1, "Twin Source", "OLD", authors = setOf("Same Author"))
		val first = manga(2, "Twin Source", "A_SOURCE", authors = setOf("Same Author"))
		val second = manga(3, "Twin Source", "B_SOURCE", authors = setOf("Same Author"))

		val result = matcher.assess(
			seed,
			listOf(observation(second, seed.title), observation(first, seed.title)),
		)

		assertEquals(MigrationMatchState.NEEDS_REVIEW, result.state)
		assertEquals(listOf(first.id, second.id), result.candidates.map { it.manga.id })
	}

	@Test
	fun `arrival order cannot change the decision or winner`() {
		val seed = manga(
			1,
			"Deterministic Story",
			"OLD",
			authors = setOf("Writer"),
			chapterNumbers = (1..10).map(Int::toFloat),
		)
		val strong = manga(
			2,
			"Deterministic Story",
			"STRONG",
			authors = setOf("Writer"),
			chapterNumbers = (1..10).map(Int::toFloat),
		)
		val weak = manga(3, "Deterministic Stories", "WEAK")
		val forward = listOf(observation(strong, seed.title), observation(weak, seed.title))
		val reverse = forward.reversed()

		val first = matcher.assess(seed, forward)
		val second = matcher.assess(seed, reverse)

		assertEquals(first.state, second.state)
		assertEquals(first.best?.manga?.id, second.best?.manga?.id)
		assertEquals(first.candidates.map { it.manga.id }, second.candidates.map { it.manga.id })
	}

	@Test
	fun `unrelated candidate without search provenance is no replacement`() {
		val seed = manga(1, "Original Story", "OLD", authors = setOf("Writer"))
		val unrelated = manga(2, "Completely Different", "NEW", authors = setOf("Someone Else"))

		val result = matcher.assess(seed, listOf(MigrationCandidateObservation(unrelated)))

		assertEquals(MigrationMatchState.NO_REPLACEMENT, result.state)
		assertEquals(null, result.best)
		assertTrue(result.candidates.isEmpty())
	}

	@Test
	fun `chapter structure contradiction blocks automatic migration`() {
		val seed = manga(
			1,
			"Numbered Work",
			"OLD",
			authors = setOf("Writer"),
			chapterNumbers = listOf(1f, 2f, 3f, 4f),
		)
		val candidate = manga(
			2,
			"Numbered Work",
			"NEW",
			authors = setOf("Writer"),
			chapterNumbers = listOf(101f, 102f, 103f, 104f),
		)

		val result = matcher.assess(seed, listOf(observation(candidate, seed.title)))

		assertEquals(MigrationMatchState.NEEDS_REVIEW, result.state)
		assertTrue(checkNotNull(result.best).evidence.chapterConflict)
		assertTrue(!checkNotNull(result.best).evidence.autoEligible)
	}

	private fun observation(manga: Manga, query: String) = MigrationCandidateObservation(
		manga = manga,
		matchedQueries = setOf(query),
	)

	private fun manga(
		id: Long,
		title: String,
		sourceName: String,
		altTitles: Set<String> = emptySet(),
		authors: Set<String> = emptySet(),
		chapterNumbers: List<Float> = emptyList(),
	): Manga {
		val source = MangaSource(sourceName)
		val chapters = chapterNumbers.mapIndexed { index, number ->
			MangaChapter(
				id = id * 1_000L + index,
				title = "Chapter $number",
				number = number,
				volume = 1,
				url = "/$id/chapter/$number",
				scanlator = null,
				uploadDate = 0L,
				branch = null,
				source = source,
			)
		}
		return Manga(
			id = id,
			title = title,
			altTitles = altTitles,
			url = "/manga/$id",
			publicUrl = "https://${sourceName.lowercase()}.example/manga/$id",
			rating = -1f,
			contentRating = null,
			coverUrl = "",
			tags = emptySet(),
			state = null,
			authors = authors,
			source = source,
			chapters = chapters,
		)
	}
}
