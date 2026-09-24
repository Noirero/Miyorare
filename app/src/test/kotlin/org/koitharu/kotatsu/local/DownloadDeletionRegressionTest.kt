package org.koitharu.kotatsu.local

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DownloadDeletionRegressionTest {

	@Test
	fun `chapter delete confirms the physical artifact is gone before clearing state`() {
		val output = source("kotlin/org/koitharu/kotatsu/local/data/output/LocalMangaDirOutput.kt")
			.replace(Regex("\\s+"), "")
		val repository = source("kotlin/org/koitharu/kotatsu/local/data/LocalMangaRepository.kt")
			.replace(Regex("\\s+"), "")

		assertTrue(output.contains("suppliedIds.containsAll(ids)"))
		assertTrue(output.contains("check(chapterCanonical.exists())"))
		assertTrue(output.contains("check(chapterCanonical.deleteAwait()&&!chapterCanonical.exists())"))

		val physicalDelete = output.indexOf("check(chapterCanonical.deleteAwait()&&!chapterCanonical.exists())")
		val stateDelete = output.indexOf("index.removeChapter(chapter.value.id)")
		assertTrue(physicalDelete >= 0 && stateDelete > physicalDelete)

		assertTrue(repository.contains("check(delete(updated)){"))
		assertFalse(
			"A failed final-folder delete must never clear the Local index and pretend the download is gone",
			repository.contains("if(!delete(updated)){localMangaIndex.delete(updated.id)localStorageChanges.emit(null)}"),
		)
	}

	@Test
	fun `chapter delete keeps Normal and Private storage ownership boundaries`() {
		val service = source("kotlin/org/koitharu/kotatsu/local/ui/LocalChaptersRemoveService.kt")
			.replace(Regex("\\s+"), "")
		val selection = source("kotlin/org/koitharu/kotatsu/details/ui/pager/chapters/ChaptersSelectionCallback.kt")
			.replace(Regex("\\s+"), "")
		val fragment = source("kotlin/org/koitharu/kotatsu/details/ui/pager/chapters/ChaptersFragment.kt")
			.replace(Regex("\\s+"), "")

		assertTrue(service.contains("EXTRA_FAVOURITE_SPACE"))
		assertTrue(service.contains("FavouriteSpace.fromArgument("))
		assertTrue(service.contains("downloadedMangaResolver.findSavedManga(remote,favouriteSpace)"))
		assertTrue(selection.contains("viewModel.favouriteSpace"))
		assertTrue(fragment.contains("viewModel.favouriteSpace"))
	}

	@Test
	fun `remove with downloads resolves the active favourites space instead of the global Local index`() {
		val useCase = source("kotlin/org/koitharu/kotatsu/local/domain/DeleteLocalMangaUseCase.kt")
			.replace(Regex("\\s+"), "")
		val fragment = source("kotlin/org/koitharu/kotatsu/favourites/ui/list/FavouritesListFragment.kt")
			.replace(Regex("\\s+"), "")

		assertTrue(useCase.contains("suspendoperatorfuninvoke(ids:Set<Long>,favouriteSpace:FavouriteSpace):Int"))
		assertTrue(useCase.contains("downloadedMangaResolver.findSavedManga(manga,favouriteSpace)?.manga"))
		assertTrue(fragment.contains("deleteLocalMangaUseCase(ids,viewModel.favouriteSpace)"))
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
