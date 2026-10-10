package org.koitharu.kotatsu.favourites.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Architectural boundaries complement the Android state and rebinding tests. */
class DownloadedLocalPipelineRegressionTest {
	@Test fun `ordinary Favourites does not revive physical Local discovery`() {
		val container = source("favourites/ui/container/FavouritesContainerViewModel.kt")
		assertTrue(container.contains("localFavouritesRepository.ensureSnapshotInitialized(favouriteSpace)"))
		assertFalse(container.contains("localFavouritesRepository.ensureInitialized("))
		assertFalse(container.contains("localFavouritesRepository.refresh("))
		val classifier = source("favourites/domain/DownloadedContentClassifier.kt")
		for (forbidden in listOf("LocalMangaParser", "listFiles(", "findSavedManga(", "findSavedMangaIndexedByTitle(")) {
			assertFalse("Interactive status queries must remain index-only: $forbidden", classifier.contains(forbidden))
		}
	}

	@Test fun `compatibility repair is one startup caller outside card rendering`() {
		val startup = source("core/BaseApp.kt")
		assertTrue(startup.contains("legacyFavouriteDownloadReconcilerProvider.get().reconcileOnce()"))
		val repair = source("favourites/domain/LegacyFavouriteDownloadReconciler.kt")
		assertTrue(repair.contains("v3_all_spaces_complete"))
		assertTrue(repair.contains("FavouriteSpace.entries"))
		assertTrue(repair.contains("downloadDestinationStore.readableRoots(space)"))
		assertTrue(repair.contains("getPersistedSnapshot()"))
		assertTrue(repair.contains("rebuildIfRequired()"))
		assertTrue(repair.contains("findSavedMangaIndexedByTitle(remote,roots)"))
		for (forbidden in listOf("getRawListAsFlow", "listFiles(", "getAll()")) {
			assertFalse("Legacy repair must use bounded indexed candidates: $forbidden", repair.contains(forbidden))
		}
		assertFalse(source("favourites/ui/list/FavouritesListViewModel.kt").contains("reconcileOnce("))
	}

	@Test fun `download completion preserves the existing durable ownership before concrete event`() {
		val worker = source("download/ui/worker/DownloadWorker.kt")
		val finish = worker.indexOf("output.finish()")
		val ownership = worker.indexOf("recordDownloadOwnership(mangaDetails.id,task,output.rootFile)", finish)
		val alias = worker.indexOf("rememberDownloadedIdentity(mangaDetails,localManga)", ownership)
		val event = worker.indexOf("localStorageChanges.emit(localManga)", alias)
		assertTrue(finish >= 0 && ownership > finish && alias > ownership && event > alias)
	}

	@Test fun `every icon binding caller commits the final set`() {
		// Card adapters now delegate to the manga-specific badge owner. Verify both callers
		// carry the existing state, and that the actual nested IconsView binding still commits.
		assertTrue(source("list/ui/adapter/MangaGridItemAD.kt").contains(
			"binding.iconsView.bindGrid(item.isSaved,item.isLocalSource,item.isFavorite,item.counter)",
		))
		assertTrue(source("list/ui/adapter/MangaListDetailedItemAD.kt").contains(
			"binding.iconsView.bind(item.isSaved,item.isLocalSource,item.isFavorite)",
		))
		val indicators = source("list/ui/MangaIndicatorsView.kt")
		assertTrue(indicators.contains("funbindGrid(isSaved:Boolean,isLocalSource:Boolean,isFavorite:Boolean,counter:Int){bind(isSaved,isLocalSource,isFavorite)"))
		val binding = indicators.substringAfter("funbind(isSaved:Boolean,isLocalSource:Boolean,isFavorite:Boolean)")
			.substringBefore("funapplySelectionPresentation")
		assertTrue(binding.contains("statusIcons.clearIcons()"))
		assertTrue(binding.contains("if(isLocalSource)statusIcons.addIcon(R.drawable.ic_manga_source)"))
		assertTrue(binding.contains("statusIcons.isVisible=statusIcons.iconsCount>0"))
		for (path in listOf("list/ui/MangaIndicatorsView.kt", "alternatives/ui/AlternativeAD.kt")) {
			val caller = source(path)
			val start = caller.indexOf("clearIcons()")
			val commit = caller.indexOf("iconsCount", start)
			assertTrue("Transactional icon binding needs a final commit in $path", start >= 0 && commit > start)
		}
	}

	private fun source(path: String): String = sequenceOf(
		File("src/main/kotlin/org/koitharu/kotatsu", path),
		File("app/src/main/kotlin/org/koitharu/kotatsu", path),
	).first(File::isFile).readText()
		.replace(Regex("//[^\\r\\n]*"), "")
		.replace(Regex("\\s+"), "")
}
