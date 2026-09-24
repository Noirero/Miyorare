package org.koitharu.kotatsu.readerjourney.domain

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ReaderJourneyPrivacyRegressionTest {

	@Test
	fun `reader journey opt out wins the pause and async persistence race`() {
		val collector = source("kotlin/org/koitharu/kotatsu/readerjourney/domain/ReaderJourneyCollector.kt")
			.replace(Regex("\\s+"), "")

		assertTrue(collector.contains("if(settings.isReaderJourneyEnabled){activeByManga[mangaId]"))
		assertTrue(collector.contains("if(entry.awarded||!settings.isReaderJourneyEnabled)return"))
		assertTrue(
			collector.contains("if(!settings.isReaderJourneyEnabled)return@runCatchingCancellable"),
		)
	}

	@Test
	fun `achievement screen cannot remain selected after journey opt out`() {
		val screen = source("kotlin/org/koitharu/kotatsu/stats/ui/StatsScreen.kt")
			.replace(Regex("\\s+"), "")

		assertTrue(screen.contains("LaunchedEffect(stats.isJourneyEnabled)"))
		assertTrue(
			screen.contains("if(!stats.isJourneyEnabled&&journeySection==ReaderJourneySection.ACHIEVEMENTS){journeySection=ReaderJourneySection.OVERVIEW}"),
		)
	}

	@Test
	fun `incognito and peek discard active stats and journey sessions`() {
		val reader = source("kotlin/org/koitharu/kotatsu/reader/ui/ReaderViewModel.kt")
			.replace(Regex("\\s+"), "")
		val stats = source("kotlin/org/koitharu/kotatsu/stats/domain/StatsCollector.kt")
			.replace(Regex("\\s+"), "")

		assertTrue(reader.contains("if(value){discardCurrentSessionTracking()"))
		assertTrue(reader.contains("statsCollector.discard(mangaId)"))
		assertTrue(reader.contains("readerJourneyCollector.discard(mangaId)"))
		assertTrue(reader.contains("if(isIncognitoMode.value==false&&!isPeekMode.value){"))
		assertTrue(stats.contains("if(entry!=null&&settings.isStatsEnabled){"))
		assertTrue(stats.contains("fundiscard(mangaId:Long){stats.remove(mangaId)"))
		assertTrue(stats.contains("commitJobs[mangaId]?.cancel()"))
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
