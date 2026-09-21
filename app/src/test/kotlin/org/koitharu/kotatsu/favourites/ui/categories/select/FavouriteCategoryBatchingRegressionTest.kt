package org.koitharu.kotatsu.favourites.ui.categories.select

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FavouriteCategoryBatchingRegressionTest {

	@Test
	fun `category picker saves all membership changes through one batch API`() {
		val source = source(
			"org/koitharu/kotatsu/favourites/ui/categories/select/FavoriteDialogViewModel.kt",
		)
		val saveBlock = source
			.substringAfter("funsave(openCategoryManagement:Boolean=false){")
			.substringBefore("funprepareCategoryManagement(){")

		assertTrue(saveBlock.contains("favouritesRepository.updateCategoryMemberships("))
		assertFalse(saveBlock.contains("favouritesRepository.addToCategory("))
		assertFalse(saveBlock.contains("favouritesRepository.removeFromCategory("))
	}

	@Test
	fun `batch membership repository performs one atomic save and one gc`() {
		val source = source("org/koitharu/kotatsu/favourites/domain/FavouritesRepository.kt")
		val block = source
			.substringAfter("suspendfunupdateCategoryMemberships(")
			.substringBefore("suspendfunremoveFromFavourites(")

		assertTrue(block.contains("db.withTransaction{"))
		assertTrue(block.contains("getPrivateFavouritesDao().insert(rows)"))
		assertTrue(block.contains("getFavouritesDao().insert(rows)"))
		assertTrue(block.contains("delete(mangaIds,removedCategoryIds)"))
		assertTrue(block.contains("getChaptersDao().gc(mangaIds)"))
		assertTrue(
			"category updates must not reopen a transaction per category",
			block.split("db.withTransaction{").size - 1 == 1,
		)
	}

	@Test
	fun `obsolete per category mutation paths are removed`() {
		val repository = source("org/koitharu/kotatsu/favourites/domain/FavouritesRepository.kt")

		assertFalse(repository.contains("suspendfunaddToCategory("))
		assertFalse(repository.contains("suspendfunremoveFromCategory("))
		assertFalse(repository.contains("recoverToCategory("))
	}

	@Test
	fun `multi selection loads category state with aggregate query`() {
		val source = source(
			"org/koitharu/kotatsu/favourites/ui/categories/select/FavoriteDialogViewModel.kt",
		)
		val mapBlock = source
			.substringAfter("privatesuspendfunmapList(")
			.substringBefore("privatefunrememberCategories(")

		assertTrue(mapBlock.contains("getCategoryCountsForMangaIds(selectedIds,favouriteSpace)"))
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
