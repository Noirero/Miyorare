package org.koitharu.kotatsu.favourites.ui.list

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FavouritesPaginationRegressionTest {

	@Test
	fun `database window refresh cannot consume pagination permit`() {
		val source = source("org/koitharu/kotatsu/favourites/ui/list/FavouritesListViewModel.kt")
		val observeBlock = source
			.substringAfter("privatefunobserveFavorites()=combine(")
			.substringBefore("privatefunseparateLocalFromFavourites")

		assertFalse(
			"observeFavorites must not lock pagination because identical visible results can be suppressed",
			observeBlock.contains("isPaginationReady.set(false)"),
		)
		assertTrue(
			source.contains("if(!isPaginationReady.compareAndSet(true,false))return"),
		)
		assertTrue(
			source.contains("distinctUntilChanged().onEach{isPaginationReady.set(true)}"),
		)
	}

	@Test
	fun `fast scroll rechecks pagination bounds after settling`() {
		val source = source("org/koitharu/kotatsu/list/ui/MangaListFragment.kt")
		val stopBlock = source
			.substringAfter("overridefunonFastScrollStop(fastScroller:FastScroller){")
			.substringBefore("privatefuncollectSelectedItems()")

		assertTrue(stopBlock.contains("paginationListener?.postInvalidate(it)"))
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
