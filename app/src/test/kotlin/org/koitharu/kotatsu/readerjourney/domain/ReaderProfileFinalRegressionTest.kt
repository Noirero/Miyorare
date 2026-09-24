package org.koitharu.kotatsu.readerjourney.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ReaderProfileFinalRegressionTest {

	@Test
	fun `reader profile stays device local and showcase stays bounded`() {
		val store = source("kotlin/org/koitharu/kotatsu/readerjourney/domain/ReaderProfileStore.kt")
			.replace(Regex("\\s+"), "")

		assertTrue(store.contains("getSharedPreferences(PREFS_NAME,Context.MODE_PRIVATE)"))
		assertTrue(store.contains("constvalMAX_SHOWCASE=3"))
		assertTrue(store.contains("showcase.distinct().take(MAX_SHOWCASE)"))
		assertFalse(store.contains("importorg.koitharu.kotatsu.core.prefs.AppSettings"))
		assertFalse(store.contains("privatevalsettings:AppSettings"))
	}

	@Test
	fun `reader title and showcase can only use unlocked achievements`() {
		val viewModel = source("kotlin/org/koitharu/kotatsu/stats/ui/StatsViewModel.kt")
			.replace(Regex("\\s+"), "")

		assertTrue(viewModel.contains("selectedTitle=selectedTitle?.takeIf{itinunlocked}"))
		assertTrue(viewModel.contains("showcase=showcase.filter{itinunlocked}"))
	}

	@Test
	fun `profile exposes verified journey stats without duplicate overview hero`() {
		val screen = source("kotlin/org/koitharu/kotatsu/stats/ui/StatsScreen.kt")
			.replace(Regex("\\s+"), "")
		val overview = screen
			.substringAfter("ReaderJourneySection.OVERVIEW->{")
			.substringBefore("ReaderJourneySection.STATISTICS->{")

		assertTrue(screen.contains("stats.journeyCompletedChapters"))
		assertTrue(screen.contains("stats.journeyTitleCount"))
		assertTrue(screen.contains("stats.journeyMangaChapters"))
		assertTrue(screen.contains("stats.journeyNovelChapters"))
		assertTrue(screen.contains("progress.levelFraction"))
		assertFalse(overview.contains("ReaderJourneyHero(stats)"))
	}

	@Test
	fun `achievement identities stay generic and mature safe`() {
		val forbidden = listOf("hentai", "nsfw", "adult", "source", "genre", "tag")
		ReaderAchievementId.entries.forEach { id ->
			val normalized = id.name.lowercase()
			forbidden.forEach { term ->
				assertFalse("Achievement identity must stay generic: ${id.name}", term in normalized)
			}
		}
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
