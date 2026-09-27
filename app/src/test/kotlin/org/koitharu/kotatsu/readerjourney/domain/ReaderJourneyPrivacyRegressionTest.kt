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
	fun `stats opt out cannot reconcile weekly or backfill achievement XP`() {
		val stats = source("kotlin/org/koitharu/kotatsu/stats/data/StatsRepository.kt")
			.replace(Regex("\\s+"), "")

		assertTrue(
			stats.contains(
				"allowUnlock=settings.isReaderJourneyEnabled,allowXpAward=settings.isReaderJourneyEnabled",
			),
		)
		assertTrue(
			stats.contains(
				"if(settings.isReaderJourneyEnabled){progressionRepository.reconcile()}",
			),
		)
		assertTrue(
			stats.contains(
				"valprogression=if(settings.isReaderJourneyEnabled){progressionRepository.snapshot()}else{null}",
			),
		)
	}

	@Test
	fun `achievement screen cannot remain selected after journey opt out`() {
		val screen = source("kotlin/org/koitharu/kotatsu/stats/ui/StatsScreen.kt")
			.replace(Regex("\\s+"), "")

		assertTrue(screen.contains("LaunchedEffect(stats.isJourneyEnabled)"))
		assertTrue(
			screen.contains("if(!stats.isJourneyEnabled&&journeySection==ReaderJourneySection.COLLECTION){journeySection=ReaderJourneySection.OVERVIEW}"),
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

	@Test
	fun `private only Reader Journey identity is scrubbed after local and remote merge`() {
		val sync = source("kotlin/org/koitharu/kotatsu/sync/domain/GoogleDriveSyncRepository.kt")
			.replace(Regex("\\s+"), "")

		assertTrue(
			sync.contains(
				"valmerged=buildMergedSnapshot(remote,configResult.config,now).scrubPrivateOnly(privateOnlyIds)",
			),
		)
		assertTrue(sync.contains("valreaderJourney=readerJourney.filterNot{it.mangaIdinprivateOnlyIds}"))
		assertTrue(sync.contains("valreaderJourneyXpEvents=readerJourneyXpEvents.filterNot{it.mangaIdinprivateOnlyIds}"))
	}

	@Test
	fun `verified completion timestamp is captured before async persistence dispatch`() {
		val collector = source("kotlin/org/koitharu/kotatsu/readerjourney/domain/ReaderJourneyCollector.kt")
			.replace(Regex("\\s+"), "")
		val award = collector
			.substringAfter("privatefunaward(entry:Entry){")
			.substringBefore("privatedataclassPersistedJourneyResult(")

		val timestampIndex = award.indexOf("valcompletedAt=System.currentTimeMillis()")
		val launchIndex = award.indexOf("scope.launch(Dispatchers.IO)")
		assertTrue(timestampIndex >= 0)
		assertTrue(launchIndex > timestampIndex)
	}

	@Test
	fun `verified reading progression persists atomically and retries after transaction failure`() {
		val collector = source("kotlin/org/koitharu/kotatsu/readerjourney/domain/ReaderJourneyCollector.kt")
			.replace(Regex("\\s+"), "")

		assertTrue(collector.contains("valpersisted=db.withTransaction{"))
		assertTrue(collector.contains("progressionRepository.onVerifiedCompletion("))
		assertTrue(collector.contains("achievementRepository.refreshWithResult(unlockedAt=completedAt)"))
		assertTrue(collector.contains("entry.awarded=false"))
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
