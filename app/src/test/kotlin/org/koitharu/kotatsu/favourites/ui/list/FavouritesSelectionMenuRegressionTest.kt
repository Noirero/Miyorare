package org.koitharu.kotatsu.favourites.ui.list

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FavouritesSelectionMenuRegressionTest {

	@Test
	fun `modern selection More item uses a valid AppCompat menu category`() {
		val source = source("org/koitharu/kotatsu/favourites/ui/list/FavouritesListFragment.kt")

		assertTrue(source.contains("MODERN_SELECTION_MORE_ORDER=0xFFFF"))
		assertTrue(
			source.contains(
				"menu.add(Menu.NONE,MODERN_SELECTION_MORE_ID,MODERN_SELECTION_MORE_ORDER,R.string.more)",
			),
		)
		assertFalse(
			"Int.MAX_VALUE sets invalid AppCompat menu category bits",
			source.contains("menu.add(Menu.NONE,MODERN_SELECTION_MORE_ID,Int.MAX_VALUE,R.string.more)"),
		)
	}

	@Test
	fun `download deletion stays scoped to the active favourite space`() {
		val source = source("org/koitharu/kotatsu/favourites/ui/list/FavouritesListFragment.kt")

		assertTrue(source.contains("deleteLocalMangaUseCase(ids,viewModel.favouriteSpace)"))
		assertFalse(source.contains("deleteLocalMangaUseCase(ids)"))
	}

	@Test
	fun `similar title scan uses result mode instead of legacy review dialogs`() {
		val source = source("org/koitharu/kotatsu/favourites/ui/list/FavouritesListFragment.kt")

		assertTrue(source.contains("viewModel.enterSimilarTitleScanMode()"))
		assertTrue(source.contains("SimilarTitleScanHeaderPayload"))
		assertFalse(source.contains("showLibraryScanCandidate("))
	}

	@Test
	fun `ordinary favourites tab return does not force recycler to top`() {
		val source = source("org/koitharu/kotatsu/favourites/ui/container/FavouritesContainerFragment.kt")
		val hiddenBlock = source
			.substringAfter("overridefunonHiddenChanged(hidden:Boolean){")
			.substringBefore("overridefunonActionModeStarted")

		assertTrue(hiddenBlock.contains("attachTabsToAppBar()"))
		assertTrue(hiddenBlock.contains("installFavouriteSearchHandler()"))
		assertTrue(hiddenBlock.contains("onContentTypeChanged(contentTypeStore.selectedType.value)"))
		assertFalse(hiddenBlock.contains("scrollToPositionWithOffset(0,0)"))
		assertFalse(hiddenBlock.contains("scrollToPosition(0)"))
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
