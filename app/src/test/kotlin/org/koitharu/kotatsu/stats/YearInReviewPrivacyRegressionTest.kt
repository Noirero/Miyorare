package org.koitharu.kotatsu.stats

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import org.koitharu.kotatsu.stats.domain.YearInReview

class YearInReviewPrivacyRegressionTest {

	@Test
	fun `year in review model cannot carry identifying content metadata`() {
		val names = YearInReview::class.java.declaredFields
			.map { it.name.lowercase() }

		listOf("source", "genre", "tag", "cover", "rating", "mature").forEach { forbidden ->
			assertFalse(
				"Year in Review share model must not contain identifying field: $forbidden",
				names.any { forbidden in it },
			)
		}
		assertFalse("Year in Review must not contain an identifying title field", "title" in names)
		assertTrue("year" in names)
		assertTrue("totalduration" in names)
		assertTrue("chapters" in names)
		assertTrue("activeDays".lowercase() in names)
	}

	@Test
	fun `share renderer accepts aggregate review only`() {
		val renderer = source("kotlin/org/koitharu/kotatsu/stats/share/YearInReviewShareCard.kt")
			.replace(Regex("\\s+"), "")

		assertTrue(renderer.contains("funrenderToShareUri(context:Context,review:YearInReview)"))
		assertFalse(renderer.contains("review:ReadingStats"))
		assertFalse(renderer.contains("review:StatsRecord"))
		assertFalse(renderer.contains("StatsMatureMode"))
	}

	@Test
	fun `repository review uses calendar year bounds and ignores dashboard privacy mode`() {
		val repository = source("kotlin/org/koitharu/kotatsu/stats/data/StatsRepository.kt")
			.replace(Regex("\\s+"), "")
		val reviewMethod = repository
			.substringAfter("suspendfungetYearInReview(year:Int):YearInReview")
			.substringBefore("suspendfungetChapterReadingStats")

		assertTrue(reviewMethod.contains("LocalDate.of(year,1,1)"))
		assertTrue(reviewMethod.contains("LocalDate.of(year+1,1,1)"))
		assertFalse(reviewMethod.contains("matureMode"))
		assertFalse(reviewMethod.contains("buildGenreInsights"))
	}

	private fun source(relativePath: String): String {
		return sequenceOf(
			File("src/main", relativePath),
			File("app/src/main", relativePath),
		)
			.firstOrNull(File::isFile)
			?.readText()
			?: error("Cannot find production source: $relativePath")
	}
}
