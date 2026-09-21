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
	fun `User Agent imports legacy preference once and removes the old key`() {
		val manager = source("kotlin/org/koitharu/kotatsu/core/network/UserAgentManager.kt")
			.replace(Regex("\\s+"), "")
		val settings = source("kotlin/org/koitharu/kotatsu/core/prefs/AppSettings.kt")
			.replace(Regex("\\s+"), "")

		assertTrue(manager.contains("vallegacyOverride=prefs.getString(AppSettings.KEY_MIHON_USER_AGENT"))
		assertFalse(manager.contains("putString(AppSettings.KEY_MIHON_USER_AGENT"))
		assertTrue(manager.contains("remove(AppSettings.KEY_MIHON_USER_AGENT)"))
		assertFalse(settings.contains("valmihonUserAgentOverride:"))
	}



	@Test
	fun `startup update schedulers remain unique across repeated cold starts`() {
		val extension = source("kotlin/org/koitharu/kotatsu/extensions/install/ExtensionUpdateWorker.kt")
			.replace(Regex("\\s+"), "")
		val sourcePack = source("kotlin/org/koitharu/kotatsu/tsuki/MiyorareSourcePackUpdateWorker.kt")
			.replace(Regex("\\s+"), "")

		for (worker in listOf(extension, sourcePack)) {
			assertTrue(worker.contains("enqueueUniquePeriodicWork("))
			assertTrue(worker.contains("ExistingPeriodicWorkPolicy.UPDATE"))
			assertTrue(worker.contains("enqueueUniqueWork("))
			assertTrue(worker.contains("ExistingWorkPolicy.KEEP"))
			assertTrue(worker.contains("IMMEDIATE_WORK_NAME"))
			assertTrue(worker.contains("PERIODIC_WORK_NAME"))
		}
		assertTrue(extension.contains("constvalIMMEDIATE_WORK_NAME=\"extension_auto_updates_now\""))
		assertTrue(sourcePack.contains("constvalIMMEDIATE_WORK_NAME=\"miyorare_source_pack_auto_updates_now\""))
	}

	@Test
	fun `cold start favourites renders before optional card and cover enrichment`() {
		val viewModel = source("kotlin/org/koitharu/kotatsu/favourites/ui/list/FavouritesListViewModel.kt")
			.replace(Regex("\\s+"), "")
		val fragment = source("kotlin/org/koitharu/kotatsu/favourites/ui/list/FavouritesListFragment.kt")
			.replace(Regex("\\s+"), "")

		assertTrue(viewModel.contains("privateconstvalDATABASE_WINDOW_INITIAL=PAGE_SIZE"))
		assertTrue(viewModel.contains("settings.allFavoritesSortOrder"))
		assertTrue(viewModel.contains("scheduleCardEnrichment(enrichmentKey)"))
		assertTrue(viewModel.contains("matchingEnrichment?.snapshot?:emptyCardSnapshot"))
		val mapList = viewModel.substringAfter("privatesuspendfunList<Manga>.mapList(")
			.substringBefore("privatefunsearchWithLibraryGroups")
		assertFalse(
			"Visible favourites must not wait for Room unread/history enrichment before the first frame",
			mapList.contains("unreadCounter.getSnapshot("),
		)

		assertTrue(fragment.contains("Semaphore(2)"))
		assertTrue(fragment.contains("RecyclerView.SCROLL_STATE_IDLE"))
		assertTrue(fragment.contains("postDelayed(coverPrefetchRunnable,COVER_PREFETCH_IDLE_DELAY_MS)"))
		assertTrue(fragment.contains("privateconstvalCOVER_PREFETCH_BATCH=12"))
	}

	@Test
	fun `favourites scrolling never starts filesystem download reconciliation`() {
		val classifier = source("kotlin/org/koitharu/kotatsu/favourites/domain/DownloadedContentClassifier.kt")
			.replace(Regex("\\s+"), "")
		val viewModel = source("kotlin/org/koitharu/kotatsu/favourites/ui/list/FavouritesListViewModel.kt")
			.replace(Regex("\\s+"), "")
		val destinationStore = source("kotlin/org/koitharu/kotatsu/download/domain/DownloadDestinationStore.kt")
			.replace(Regex("\\s+"), "")

		assertTrue(classifier.contains("suspendfungetKnownDownloadedIds("))
		assertFalse(classifier.contains("getDownloadedIdsExact"))
		assertFalse(classifier.contains("findSavedMangaInRoot"))
		assertFalse(classifier.contains("findMangaById"))
		assertFalse(classifier.contains("listFiles("))
		assertFalse(classifier.contains("canonicalPath"))
		assertFalse(classifier.contains("CoroutineScope("))
		assertFalse(destinationStore.contains("isLegacyIndexMigrationRequired"))
		assertFalse(destinationStore.contains("markLegacyIndexMigrationComplete"))
		assertFalse(viewModel.contains("getDownloadedIdsExact"))
		assertTrue(viewModel.contains("valdeltaIds=key.ids.drop(reusedCount)"))
		assertTrue(viewModel.contains("previous?.snapshot?.merge(deltaSnapshot)"))
	}

	@Test
	fun `library cover retention is large and image work is concurrency bounded`() {
		val appModule = source("kotlin/org/koitharu/kotatsu/core/AppModule.kt")
			.replace(Regex("\\s+"), "")
		val coil = source("kotlin/org/koitharu/kotatsu/core/util/ext/Coil.kt")
			.replace(Regex("\\s+"), "")

		assertTrue(coil.contains("diskCacheKey(mangaCoverDiskCacheKey(manga.id))"))
		assertTrue(appModule.contains(".maxSizePercent(0.10)"))
		assertTrue(appModule.contains(".minimumMaxSizeBytes(256L*1024L*1024L)"))
		assertTrue(appModule.contains(".maximumMaxSizeBytes(2L*1024L*1024L*1024L)"))
		assertTrue(appModule.contains(".fetcherCoroutineContext(Dispatchers.IO.limitedParallelism(8))"))
		assertTrue(appModule.contains(".decoderCoroutineContext(Dispatchers.IO.limitedParallelism(3))"))
	}

	@Test
	fun `explore startup keeps package metadata and plugin discovery off the main render path`() {
		val repository = source("kotlin/org/koitharu/kotatsu/explore/data/MangaSourcesRepository.kt")
			.replace(Regex("\\s+"), "")
		val viewModel = source("kotlin/org/koitharu/kotatsu/explore/ui/ExploreViewModel.kt")
			.replace(Regex("\\s+"), "")
		val adapter = source("kotlin/org/koitharu/kotatsu/explore/ui/adapter/ExploreAdapter.kt")
			.replace(Regex("\\s+"), "")
		val delegates = source("kotlin/org/koitharu/kotatsu/explore/ui/adapter/ExploreAdapterDelegates.kt")
			.replace(Regex("\\s+"), "")
		val activity = source("kotlin/org/koitharu/kotatsu/main/ui/MainActivity.kt")
			.replace(Regex("\\s+"), "")

		assertTrue(repository.contains("returnflow{manager.initialize()"))
		assertTrue(repository.contains("emitAll("))
		assertFalse(viewModel.contains("sourcesRepository.reloadMihonSources()"))
		assertTrue(viewModel.contains("valsummary=source.getSummary(appContext)"))
		assertTrue(adapter.contains("sources.partition{it.isMiyorareSource}"))
		assertFalse(adapter.contains("getSummary(context)"))
		assertFalse(delegates.contains("getSummary(context)"))
		assertTrue(delegates.contains("item.summary.toCompactExploreSourceSummary()"))
		assertTrue(activity.contains("postDelayed(exploreWarmupRunnable,EXPLORE_WARMUP_IDLE_DELAY_MS)"))
		assertTrue(activity.contains("postDelayed(backgroundWarmupRunnable,BACKGROUND_WARMUP_IDLE_DELAY_MS)"))
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
