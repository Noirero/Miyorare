package org.koitharu.kotatsu.details.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ExtensionDetailsStages234RegressionTest {

	@Test
	fun `Stage 2 probes cache state before materializing chapter rows`() {
		val repository = source("org/koitharu/kotatsu/core/parser/MangaDataRepository.kt")
		val dao = source("org/koitharu/kotatsu/core/db/dao/ChaptersDao.kt")

		assertTrue(dao.contains("abstractsuspendfunhasAny(mangaId:Long):Boolean"))
		assertTrue(repository.contains("valstored=db.getMangaDao().find(mangaId)?:returnnull"))
		assertTrue(repository.contains("hasPersistedChapterSnapshot(stored.manga.chaptersInitialized,mangaId)"))
		assertTrue(repository.contains("initialized||db.getChaptersDao().hasAny(mangaId)"))
		assertFalse(
			"findMangaById must not materialize chapters before confirming a manga/cache snapshot",
			repository.substringAfter("suspendfunfindMangaById")
				.substringBefore("suspendfunfindMangaByPublicUrl")
				.startsWith("(mangaId:Long,withChapters:Boolean):Manga?{valchapters="),
		)
	}

	@Test
	fun `Stage 2 keeps Extension list metadata as immediate Details state`() {
		val details = source("org/koitharu/kotatsu/details/ui/DetailsViewModel.kt")

		assertTrue(details.contains("valinitialDetails=(navigationManga?:intent.manga)?.let(::MangaDetails)"))
		assertTrue(details.contains("mangaDetails.value=initialDetails"))
	}

	@Test
	fun `Stage 3 defers indexed local enrichment for Details but not Reader`() {
		val loader = source("org/koitharu/kotatsu/details/domain/DetailsLoadUseCase.kt")

		assertTrue(loader.contains("deferLocalLookup=!preferLocalBeforeInitialSnapshot"))
		assertTrue(loader.contains("vallocalLookup=async{"))
		assertTrue(loader.contains("downloadedMangaResolver.findSavedManga(manga,favouriteSpace)"))
		assertFalse(loader.contains("preferIndexed"))
		assertTrue(loader.contains("initialSavedManga?:findSavedManga()"))
		assertTrue(loader.contains("if(preferLocalBeforeCached){"))
		assertTrue(loader.contains("valsavedManga=findSavedManga()"))
	}

	@Test
	fun `Stage 4 persists source result before publication and keeps Room Flow ownership`() {
		val loader = source("org/koitharu/kotatsu/details/domain/DetailsLoadUseCase.kt")
		val details = source("org/koitharu/kotatsu/details/ui/DetailsViewModel.kt")

		val store = loader.indexOf("mangaDataRepository.storeManga(")
		val success = loader.indexOf("Result.success(remoteDetails)", startIndex = store)
		val publish = loader.indexOf("valremoteDetails=remoteResult.getOrThrow()", startIndex = success)
		assertTrue(store >= 0)
		assertTrue(success > store)
		assertTrue(publish > success)

		assertTrue(details.contains("mangaDataRepository.observeChapters(mangaId)"))
		assertFalse(details.contains(".drop(1)"))
		assertTrue(details.contains(".mapLatest{chapters->syncCachedChaptersWhenLoadIdle(chapters)}"))
		assertFalse(details.contains("TABLE_CHAPTERS"))
		assertFalse(details.contains("invalidationTracker.createFlow("))
	}

	private fun source(relativePath: String): String {
		return (
			sequenceOf(
				File("src/main/kotlin", relativePath),
				File("app/src/main/kotlin", relativePath),
			).firstOrNull(File::isFile)?.readText()
				?: error("Cannot find production source: $relativePath")
			)
			.replace(Regex("""//[^\r\n]*"""), "")
			.replace(Regex("""\s+"""), "")
	}
}
