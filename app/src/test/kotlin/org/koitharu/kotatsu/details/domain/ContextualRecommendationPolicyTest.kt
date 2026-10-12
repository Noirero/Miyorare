package org.koitharu.kotatsu.details.domain

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextualRecommendationPolicyTest {

	@Test
	fun `SFW and NSFW candidates never cross`() {
		val seed = signals(genres = setOf("action"), isNsfw = false)
		val adult = signals(genres = setOf("action"), isNsfw = true)
		assertNull(ContextualRecommendationPolicy.score(seed, adult))
	}

	@Test
	fun `known manga and manhwa kinds never cross`() {
		val manga = signals(kind = RelatedContentKind.MANGA, genres = setOf("action"))
		val manhwa = signals(kind = RelatedContentKind.MANHWA, genres = setOf("action"))
		assertNull(ContextualRecommendationPolicy.score(manga, manhwa))
	}

	@Test
	fun `candidate without a shared genre is rejected`() {
		val seed = signals(genres = setOf("action", "fantasy"))
		val unrelated = signals(genres = setOf("romance", "drama"), qualityScore = 9)
		assertNull(ContextualRecommendationPolicy.score(seed, unrelated))
	}

	@Test
	fun `missing genre metadata cannot pretend to be a similar genre`() {
		val seed = signals(genres = setOf("action"))
		assertNull(ContextualRecommendationPolicy.score(seed, signals()))
		assertNull(ContextualRecommendationPolicy.score(signals(), seed))
	}

	@Test
	fun `one shared genre remains eligible`() {
		val seed = signals(genres = setOf("action", "adventure", "fantasy"))
		val candidate = signals(genres = setOf("action", "comedy"))
		assertNotNull(ContextualRecommendationPolicy.score(seed, candidate))
	}

	@Test
	fun `larger genre overlap outranks themes and quality`() {
		val seed = signals(kind = RelatedContentKind.MANGA, genres = setOf("action", "adventure", "fantasy"), semanticTags = setOf("action", "adventure", "fantasy", "dungeon"))
		val twoGenres = signals(kind = RelatedContentKind.MANGA, genres = setOf("action", "fantasy"), semanticTags = setOf("action", "fantasy"))
		val oneGenreWithExtras = signals(kind = RelatedContentKind.MANGA, genres = setOf("action", "romance"), semanticTags = setOf("action", "dungeon", "adventure", "fantasy"), qualityScore = 9)
		assertTrue(ContextualRecommendationPolicy.score(seed, twoGenres)!! > ContextualRecommendationPolicy.score(seed, oneGenreWithExtras)!!)
	}

	@Test
	fun `shared themes break ties after equal genre similarity`() {
		val seed = signals(genres = setOf("action", "fantasy"), semanticTags = setOf("action", "fantasy", "dungeon"))
		val themeMatch = signals(genres = setOf("action"), semanticTags = setOf("action", "dungeon"))
		val qualityOnly = signals(genres = setOf("action"), semanticTags = setOf("action"), qualityScore = 9)
		assertTrue(ContextualRecommendationPolicy.score(seed, themeMatch)!! > ContextualRecommendationPolicy.score(seed, qualityOnly)!!)
	}

	private fun signals(
		kind: RelatedContentKind = RelatedContentKind.UNKNOWN,
		isNsfw: Boolean = false,
		genres: Set<String> = emptySet(),
		semanticTags: Set<String> = emptySet(),
		qualityScore: Int = 0,
	) = RecommendationSignals(
		kind = kind,
		isNsfw = isNsfw,
		genres = genres,
		semanticTags = semanticTags,
		qualityScore = qualityScore,
	)
}
