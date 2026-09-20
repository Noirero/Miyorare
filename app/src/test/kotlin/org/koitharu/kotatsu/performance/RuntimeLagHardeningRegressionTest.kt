package org.koitharu.kotatsu.performance

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RuntimeLagHardeningRegressionTest {

	@Test
	fun `reader prefetch cannot occupy every foreground page-load slot`() {
		val source = source("kotlin/org/koitharu/kotatsu/reader/domain/PageLoader.kt")
			.replace(Regex("\\s+"), "")

		assertTrue(source.contains("privatevalsemaphore=Semaphore(4)"))
		assertTrue(source.contains("privatevalprefetchSemaphore=Semaphore(PREFETCH_MAX_PARALLELISM)"))
		assertTrue(source.contains("privateconstvalPREFETCH_MAX_PARALLELISM=2"))
		assertTrue(source.contains("if(isPrefetch){prefetchSemaphore.withPermit{"))
		assertTrue(source.contains("loadPageWithPermit(page,progress,isPrefetch=true,skipCache=skipCache)"))
	}

	@Test
	fun `adaptive update reuses one source-health snapshot per source in a selection window`() {
		val source = source("kotlin/org/koitharu/kotatsu/tracker/domain/SmartUpdatePolicy.kt")
			.replace(Regex("\\s+"), "")

		assertTrue(source.contains("valsourceHealth=HashMap<String,SourceHealthRepository.State>()"))
		assertTrue(source.contains("sourceHealth.getOrPut(tracking.manga.source.name)"))
		assertTrue(source.contains("sourceHealthRepository.snapshotForScheduling(tracking.manga.source).state"))
	}


	@Test
	fun `per manga reader profile owns the first ReaderSettings emission`() {
		val settings = source("kotlin/org/koitharu/kotatsu/reader/ui/config/ReaderSettings.kt")
			.replace(Regex("\\s+"), "")
		val reader = source("kotlin/org/koitharu/kotatsu/reader/ui/ReaderViewModel.kt")
			.replace(Regex("\\s+"), "")

		assertTrue(settings.contains("@AssistedinitialMangaId:Long"))
		assertTrue(settings.contains("profileStore.get(initialMangaId)"))
		assertTrue(settings.contains("funcreate(mangaId:Flow<Long>,initialMangaId:Long):Producer"))
		assertFalse(
			"Reader must not publish global settings first and apply the manga profile afterwards",
			settings.contains("MediatorStateFlow<ReaderSettings>(ReaderSettings(settings,null,null))"),
		)
		assertTrue(reader.contains("initialMangaId=intent.mangaId"))
	}

	@Test
	fun `manual tracker refresh semantics do not depend on foreground promotion`() {
		val worker = source("kotlin/org/koitharu/kotatsu/tracker/work/TrackWorker.kt")
			.replace(Regex("\\s+"), "")

		assertTrue(worker.contains("trySetForeground()"))
		assertTrue(worker.contains("doWorkImpl(isFullRun=TAG_ONESHOTintags)"))
		assertFalse(
			"Foreground-service promotion is not the source of truth for a manual full refresh",
			worker.contains("isForeground&&TAG_ONESHOTintags"),
		)
	}

	@Test
	fun `source migration prepares durable metadata before Room and cleans it after commit`() {
		val migration = source("kotlin/org/koitharu/kotatsu/alternatives/domain/MigrateUseCase.kt")
			.replace(Regex("\\s+"), "")
		val profiles = source("kotlin/org/koitharu/kotatsu/reader/ui/config/MangaReaderProfileStore.kt")
			.replace(Regex("\\s+"), "")
		val notes = source("kotlin/org/koitharu/kotatsu/details/data/MangaNotesRepository.kt")
			.replace(Regex("\\s+"), "")

		val profilePrepare = migration.indexOf("mangaReaderProfileStore.prepareMove(oldDetails.id,newDetails.id)")
		val notePrepare = migration.indexOf("mangaNotesRepository.prepareMove(oldDetails.id,newDetails.id)")
		val room = migration.indexOf("database.withTransaction{")
		val profileFinish = migration.indexOf("mangaReaderProfileStore.finishPreparedMove(oldDetails.id,newDetails.id)")
		val noteFinish = migration.indexOf("mangaNotesRepository.finishPreparedMove(oldDetails.id,newDetails.id)")

		assertTrue(profilePrepare >= 0 && profilePrepare < room)
		assertTrue(notePrepare >= 0 && notePrepare < room)
		assertTrue(profileFinish > room)
		assertTrue(noteFinish > room)
		assertTrue(migration.contains("mangaReaderProfileStore.rollbackPreparedMove(newDetails.id)"))
		assertTrue(migration.contains("mangaNotesRepository.rollbackPreparedMove(newDetails.id)"))
		assertFalse(migration.contains("mangaReaderProfileStore.move("))
		assertFalse(migration.contains("mangaNotesRepository.move("))

		assertTrue(profiles.contains("funprepareMove(oldMangaId:Long,newMangaId:Long):Boolean"))
		assertTrue(profiles.contains(".commit()"))
		assertTrue(notes.contains("funprepareMove(oldMangaId:Long,newMangaId:Long):Boolean"))
		assertTrue(notes.contains(".commit()"))
	}

	@Test
	fun `User Agent imports the legacy value once without mirroring runtime state back`() {
		val manager = source("kotlin/org/koitharu/kotatsu/core/network/UserAgentManager.kt")
			.replace(Regex("\\s+"), "")
		val settings = source("kotlin/org/koitharu/kotatsu/core/prefs/AppSettings.kt")
			.replace(Regex("\\s+"), "")

		assertTrue(manager.contains("vallegacyOverride=prefs.getString(AppSettings.KEY_MIHON_USER_AGENT,null)"))
		assertFalse(
			"Legacy User-Agent preference must not remain a second runtime source of truth",
			manager.contains("putString(AppSettings.KEY_MIHON_USER_AGENT"),
		)
		assertFalse(settings.contains("valmihonUserAgentOverride:String?"))
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
