package org.koitharu.kotatsu.details.domain

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextualRecommendationPolicyTest {

	@Test
	fun `SFW and NSFW candidates never cross`() {
		val seed = signals(isNsfw = false)
		val adult = signals(isNsfw = true)
		assertNull(ContextualRecommendationPolicy.score(seed, adult))
	}

	@Test
	fun `known manga and manhwa kinds never cross`() {
		val manga = signals(kind = RelatedContentKind.MANGA)
		val manhwa = signals(kind = RelatedContentKind.MANHWA)
		assertNull(ContextualRecommendationPolicy.score(manga, manhwa))
	}

	@Test
	fun `primary genre outweighs unrelated genre and quality`() {
		val seed = signals(
			kind = RelatedContentKind.MANHWA,
			primaryGenre = "action",
			genres = setOf("action", "fantasy"),
			semanticTags = setOf("action", "fantasy", "dungeon"),
		)
		val contextual = signals(
			kind = RelatedContentKind.MANHWA,
			primaryGenre = "action",
			genres = setOf("action"),
			semanticTags = setOf("action", "dungeon"),
			qualityScore = 10,
		)
		val unrelatedHighQuality = signals(
			kind = RelatedContentKind.MANHWA,
			primaryGenre = "romance",
			genres = setOf("romance"),
			semanticTags = setOf("romance"),
			qualityScore = 80,
		)

		val contextualScore = ContextualRecommendationPolicy.score(seed, contextual)!!
		val unrelatedScore = ContextualRecommendationPolicy.score(seed, unrelatedHighQuality)!!
		assertTrue(contextualScore > unrelatedScore)
	}

	@Test
	fun `additional shared themes outrank quality when main context is equal`() {
		val seed = signals(
			kind = RelatedContentKind.MANGA,
			primaryGenre = "action",
			genres = setOf("action"),
			semanticTags = setOf("action", "martial arts", "school life"),
		)
		val themeMatch = signals(
			kind = RelatedContentKind.MANGA,
			primaryGenre = "action",
			genres = setOf("action"),
			semanticTags = setOf("action", "martial arts", "school life"),
			qualityScore = 5,
		)
		val qualityOnly = signals(
			kind = RelatedContentKind.MANGA,
			primaryGenre = "action",
			genres = setOf("action"),
			semanticTags = setOf("action"),
			qualityScore = 80,
		)

		assertTrue(
			ContextualRecommendationPolicy.score(seed, themeMatch)!! >
				ContextualRecommendationPolicy.score(seed, qualityOnly)!!,
		)
	}

	private fun signals(
		kind: RelatedContentKind = RelatedContentKind.UNKNOWN,
		isNsfw: Boolean = false,
		primaryGenre: String? = null,
		genres: Set<String> = emptySet(),
		semanticTags: Set<String> = emptySet(),
		qualityScore: Int = 0,
	) = RecommendationSignals(
		kind = kind,
		isNsfw = isNsfw,
		primaryGenre = primaryGenre,
		genres = genres,
		semanticTags = semanticTags,
		qualityScore = qualityScore,
	)
}
