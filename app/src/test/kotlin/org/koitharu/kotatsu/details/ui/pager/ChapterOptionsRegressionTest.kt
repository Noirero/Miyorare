package org.koitharu.kotatsu.details.ui.pager

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ChapterOptionsRegressionTest {

	@Test
	fun `chapter filters stay in memory after the existing snapshot is mapped`() {
		val source = source("kotlin/org/koitharu/kotatsu/details/ui/pager/ChaptersPagesViewModel.kt")
			.replace(Regex("\\s+"), "")

		assertTrue(source.contains("isDownloadedOnly=false"))
		assertTrue(source.contains("applyChapterOptions(options)"))
		assertTrue(source.contains("!options.downloadedOnly||item.isDownloaded"))
		assertTrue(source.contains("!options.unreadOnly||item.isUnread"))
		assertTrue(source.contains("!options.bookmarkedOnly||item.isBookmarked"))
		assertTrue(source.contains("!options.newOnly||item.isNew"))
		assertFalse(source.contains("applyChapterOptions(options).reload("))
	}

	@Test
	fun `scanlator filter falls back when a source has no alternate branches`() {
		val source = source("kotlin/org/koitharu/kotatsu/details/ui/pager/ChaptersPagesViewModel.kt")
			.replace(Regex("\\s+"), "")
		val sheet = source("kotlin/org/koitharu/kotatsu/details/ui/pager/ChapterOptionsSheet.kt")
			.replace(Regex("\\s+"), "")

		assertTrue(source.contains("valchapterScanlatorOptions=combine(chapterMappingDetails,selectedBranch)"))
		assertTrue(source.contains("scanlator==null||item.chapter.scanlator?.trim()==scanlator"))
		assertTrue(source.contains("selectedScanlator.value=null"))
		assertTrue(sheet.contains("branches.isNotEmpty()->ChapterGroupRow("))
		assertTrue(sheet.contains("scanlators.isNotEmpty()->ChapterGroupRow("))
	}

	@Test
	fun `sort modes keep source fallback and unknown metadata stable`() {
		val source = source("kotlin/org/koitharu/kotatsu/details/ui/pager/ChaptersPagesViewModel.kt")
			.replace(Regex("\\s+"), "")

		assertTrue(source.contains("ChapterSortMode.SOURCE"))
		assertTrue(source.contains("ChapterSortMode.NUMBER"))
		assertTrue(source.contains("ChapterSortMode.UPLOAD_DATE"))
		assertTrue(source.contains("ChapterSortMode.ALPHABETICAL"))
		assertTrue(source.contains("left.index.compareTo(right.index)"))
		assertTrue(source.contains("left==null->1"))
		assertTrue(source.contains("right==null->-1"))
	}

	@Test
	fun `alphabetical sort uses locale collation and keeps stable fallback`() {
		val source = source("kotlin/org/koitharu/kotatsu/details/ui/pager/ChaptersPagesViewModel.kt")
			.replace(Regex("\\s+"), "")
		val sheet = source("kotlin/org/koitharu/kotatsu/details/ui/pager/ChapterOptionsSheet.kt")
			.replace(Regex("\\s+"), "")

		assertTrue(source.contains("left.compareToWithCollator(right)"))
		assertTrue(source.contains("left.index.compareTo(right.index)"))
		assertTrue(sheet.contains("rotate(if(options.descending)180felse0f)"))
	}

	@Test
	fun `defaults are isolated by content type and favourite space`() {
		val source = source("kotlin/org/koitharu/kotatsu/details/ui/pager/ChapterListOptions.kt")
			.replace(Regex("\\s+"), "")

		assertTrue(source.contains("if(isNovel)\"novel\"else\"manga\""))
		assertTrue(source.contains("if(space==FavouriteSpace.PRIVATE)\"private\"else\"normal\""))
		assertTrue(source.contains("\"details_chapter_options_v1_\${content}_\${workspace}\""))
		assertFalse(source.contains("selectedBranch"))
	}

	@Test
	fun `novel defaults use the full content domain not epub only`() {
		val source = source("kotlin/org/koitharu/kotatsu/details/ui/pager/ChaptersPagesViewModel.kt")
			.replace(Regex("\\s+"), "")

		assertTrue(source.contains("manga.idtomanga.isNovelContent"))
		assertTrue(source.contains("setDefault(favouriteSpace,manga.isNovelContent,chapterListOptions.value)"))
		assertTrue(source.contains("getDefault(favouriteSpace,manga.isNovelContent)"))
		assertFalse(source.contains("manga.isEpub"))
	}

	@Test
	fun `details defaults do not leak into Reader chapter sheet`() {
		val base = source("kotlin/org/koitharu/kotatsu/details/ui/pager/ChaptersPagesViewModel.kt")
			.replace(Regex("\\s+"), "")
		val details = source("kotlin/org/koitharu/kotatsu/details/ui/DetailsViewModel.kt")
			.replace(Regex("\\s+"), "")
		val reader = source("kotlin/org/koitharu/kotatsu/reader/ui/ReaderViewModel.kt")
			.replace(Regex("\\s+"), "")
		val menu = source("kotlin/org/koitharu/kotatsu/details/ui/pager/ChapterPagesMenuProvider.kt")
			.replace(Regex("\\s+"), "")

		assertTrue(base.contains("chapterListOptionsStore:ChapterListOptionsStore?=null"))
		assertTrue(base.contains("chapterListOptionsStore?.let{store->"))
		assertTrue(details.contains("chapterListOptionsStore=chapterListOptionsStore"))
		assertFalse(reader.contains("ChapterListOptionsStore"))
		assertFalse(reader.contains("chapterListOptionsStore="))
		assertTrue(menu.contains("if(viewModelisReaderViewModel){settings.isChaptersReverse="))
		assertTrue(menu.contains("if(viewModelisReaderViewModel){settings.isChaptersGridView="))
	}

	@Test
	fun `Details chapter toolbar opens the requested option tab and keeps more actions outside the sheet`() {
		val header = source("kotlin/org/koitharu/kotatsu/details/ui/DetailsChapterComponents.kt")
			.replace(Regex("\\s+"), "")
		val screen = source("kotlin/org/koitharu/kotatsu/details/ui/DetailsExpressiveScreen.kt")
			.replace(Regex("\\s+"), "")
		val activity = source("kotlin/org/koitharu/kotatsu/details/ui/DetailsExpressiveActivity.kt")
			.replace(Regex("\\s+"), "")
		val sheet = source("kotlin/org/koitharu/kotatsu/details/ui/pager/ChapterOptionsSheet.kt")
			.replace(Regex("\\s+"), "")

		assertTrue(header.contains("onClick=onFilter"))
		assertTrue(header.contains("onClick=onSort"))
		assertTrue(header.contains("onClick=onDisplay"))
		assertTrue(header.contains("R.drawable.ic_more_vert"))
		assertTrue(header.contains("onManage()"))
		assertTrue(header.contains("onSetDefault()"))
		assertTrue(header.contains("onReset()"))
		assertTrue(screen.contains("ChapterOptionsTab.FILTER"))
		assertTrue(screen.contains("ChapterOptionsTab.SORT"))
		assertTrue(screen.contains("ChapterOptionsTab.DISPLAY"))
		assertTrue(activity.contains("chapterOptionsInitialTab.value=tab"))
		assertTrue(activity.contains("initialTab=chapterOptionsInitialTab.value"))
		assertTrue(sheet.contains("remember(initialTab){mutableStateOf(initialTab)}"))
		assertFalse(sheet.contains("R.drawable.ic_more_vert"))
		assertFalse(sheet.contains("onSetDefault:()->Unit"))
		assertFalse(sheet.contains("onReset:()->Unit"))
		assertTrue(screen.contains("if(details.isLoaded||historyInfo.totalChapters>0||chapters.isNotEmpty())"))
	}

	@Test
	fun `details and manage chapters share one option state`() {
		val activity = source("kotlin/org/koitharu/kotatsu/details/ui/DetailsExpressiveActivity.kt")
			.replace(Regex("\\s+"), "")
		val menu = source("kotlin/org/koitharu/kotatsu/details/ui/pager/ChapterPagesMenuProvider.kt")
			.replace(Regex("\\s+"), "")

		assertTrue(activity.contains("viewModel.chapterListOptions.collectAsState()"))
		assertTrue(activity.contains("ChapterOptionsSheet("))
		assertTrue(menu.contains("viewModel.chapterListOptions.value"))
		assertTrue(menu.contains("viewModel.setDownloadedOnly"))
		assertTrue(menu.contains("viewModel.setChaptersGridView"))
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
