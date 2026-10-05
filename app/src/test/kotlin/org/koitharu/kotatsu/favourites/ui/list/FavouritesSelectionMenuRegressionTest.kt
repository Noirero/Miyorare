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
		assertTrue(source.contains("menu.add(Menu.NONE,MODERN_SELECTION_MORE_ID,MODERN_SELECTION_MORE_ORDER,R.string.more)"))
		assertFalse(source.contains("menu.add(Menu.NONE,MODERN_SELECTION_MORE_ID,Int.MAX_VALUE,R.string.more)"))
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
		val hiddenBlock = source.substringAfter("overridefunonHiddenChanged(hidden:Boolean){").substringBefore("overridefunonActionModeStarted")
		assertTrue(hiddenBlock.contains("attachTabsToAppBar()"))
		assertTrue(hiddenBlock.contains("installFavouriteSearchHandler()"))
		assertTrue(hiddenBlock.contains("onContentTypeChanged(contentTypeStore.selectedType.value)"))
		assertFalse(hiddenBlock.contains("scrollToPositionWithOffset(0,0)"))
		assertFalse(hiddenBlock.contains("scrollToPosition(0)"))
	}

	@Test
	fun `favourites source metadata enrichment never blocks first render`() {
		val quickFilter = source("org/koitharu/kotatsu/favourites/domain/FavoritesListQuickFilter.kt")
		val viewModel = source("org/koitharu/kotatsu/favourites/ui/list/FavouritesListViewModel.kt")
		val sourceOptions = quickFilter.substringAfter("privatesuspendfungetSourceOptions():List<ListFilterOption.Source>{").substringBefore("@AssistedFactory")
		assertTrue(sourceOptions.isNotEmpty())
		assertFalse(sourceOptions.contains("mihonExtensionManager.ensureReady()"))
		assertTrue(sourceOptions.contains("getMihonMangaSources()"))
		assertTrue(viewModel.contains("quickFilter.sourceMetadataReady"))
	}

	@Test
	fun `similar title rejection preferences stay scoped by favourite space`() {
		val scanner = source("org/koitharu/kotatsu/favourites/domain/LibraryDuplicateScanUseCase.kt")
		assertTrue(scanner.contains("library_scan_ignored_pairs_"))
		assertTrue(scanner.contains("space.dbValue"))
	}

	private fun source(relativePath: String): String {
		return (sequenceOf(File("src/main/kotlin", relativePath), File("app/src/main/kotlin", relativePath))
			.firstOrNull(File::isFile)?.readText() ?: error("Cannot find production source: $relativePath"))
			.replace(Regex("""//[^\r\n]*"""), "")
			.replace(Regex("""\s+"""), "")
	}
}
