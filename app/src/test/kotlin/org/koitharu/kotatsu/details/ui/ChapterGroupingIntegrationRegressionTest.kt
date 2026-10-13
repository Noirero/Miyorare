package org.koitharu.kotatsu.details.ui

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Guard the Amain2 boundaries most likely to be lost when adapting the beta donor. */
class ChapterGroupingIntegrationRegressionTest {
	@Test fun presentationRetainsAmain2SearchBeforeDownloadOverlayAndDoesNotReplaceOptions() {
		val source = source("pager/ChaptersPagesViewModel.kt")
		val pipeline = source.substringAfter("internal val chapterPresentation =").substringBefore("val quickFilter")
		assertTrue(pipeline.indexOf("applyChapterOptions(options)") < pipeline.indexOf("filterChapterSearch(query.first, query.second)"))
		assertTrue(pipeline.indexOf("filterChapterSearch(query.first, query.second)") < pipeline.indexOf("withDownloading("))
		assertTrue(pipeline.indexOf("withDownloading(") < pipeline.indexOf("groupChapterRanges("))
		assertTrue(pipeline.contains("isGrid = options.grid && manga.toManga().isEpub.not()"))
		assertTrue(source.contains("if (getMangaOrNull()?.source == LocalMangaSource) return"))
		assertTrue(source.contains("if (isSmartLocal) return"))
	}

	@Test fun switchingAndCollapsingUnifiedSearchKeepsChapterAndNoteQueriesSeparate() {
		val menu = source("pager/ChapterPagesMenuProvider.kt")
		val collapse = menu.substringAfter("override fun onMenuItemActionCollapse").substringBefore("override fun onQueryTextSubmit")
		assertTrue(collapse.contains("viewModel.performChapterSearch(null)"))
		assertTrue(collapse.contains("viewModel.exitChapterNoteSearch()"))
		assertTrue(collapse.contains("searchMode = null"))
		val switchMode = menu.substringAfter("private fun activateSearchMode").substringBefore("private fun SearchView.focusSearchInput")
		assertTrue(switchMode.contains("SearchMode.CHAPTERS ->"))
		assertTrue(switchMode.contains("viewModel.exitChapterNoteSearch()"))
		assertTrue(switchMode.contains("viewModel.performChapterSearch(\"\")"))
		assertTrue(switchMode.contains("SearchMode.NOTES ->"))
		assertTrue(switchMode.contains("viewModel.performChapterSearch(null)"))
		assertTrue(switchMode.contains("viewModel.performChapterNoteSearch(\"\")"))
		val vm = source("pager/ChaptersPagesViewModel.kt")
		val noteMode = vm.substringAfter("fun performChapterNoteSearch").substringBefore("fun getMangaOrNull")
		assertFalse(noteMode.contains("chaptersQuery.value ="))
	}

	@Test fun wholeChapterSectionCollapseIsSeparateFromSavedRangeChoicesAndManageActions() {
		val screen = source("DetailsExpressiveScreen.kt")
		assertTrue(screen.contains("rememberSaveable(manga?.id) { mutableStateOf(true) }"))
		assertTrue(screen.contains("onToggleExpanded = { chaptersExpanded = !chaptersExpanded }"))
		assertTrue(screen.contains("if (chaptersExpanded && chapterRanges.isNotEmpty())"))
		assertTrue(screen.contains("if (range.expanded) items("))
		val vm = source("DetailsViewModel.kt")
		val toggle = vm.substringAfter("fun toggleChapterRange").substringBefore("private val performanceTrace")
		assertFalse(toggle.contains("chapterListOptions"))
		assertFalse(toggle.contains("chaptersExpanded"))
		val manage = source("pager/chapters/ChaptersFragment.kt")
		assertTrue(manage.contains("if (!item.canDownload) return@ChaptersAdapter"))
		assertTrue(manage.contains("viewModel.deleteSmartLocalChapters(setOf(item.chapter.id))"))
		assertTrue(manage.contains("ListSelectionController("))
		assertTrue(manage.contains("ranges.withRangeHeaders(decorateVolumeHeaders(list))"))
	}

	private fun source(name: String): String = sequenceOf(
		File("src/main/kotlin/org/koitharu/kotatsu/details/ui", name),
		File("app/src/main/kotlin/org/koitharu/kotatsu/details/ui", name),
	).first(File::isFile).readText()
}
