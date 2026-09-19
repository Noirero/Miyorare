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
