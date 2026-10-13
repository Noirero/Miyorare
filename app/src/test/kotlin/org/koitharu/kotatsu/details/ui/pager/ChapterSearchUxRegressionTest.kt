package org.koitharu.kotatsu.details.ui.pager

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class ChapterSearchUxRegressionTest {

	@Test
	fun `manage and reader use one chooser for chapter and note search`() {
		val source = source("main/kotlin/org/koitharu/kotatsu/details/ui/pager/ChapterPagesMenuProvider.kt")
		assertTrue(source.contains("private enum class SearchMode { CHAPTERS, NOTES }"))
		assertTrue(source.contains("showSearchModeChooser(item)"))
		assertTrue(source.contains("context.getString(R.string.search_chapters)"))
		assertTrue(source.contains("context.getString(R.string.chapter_search_notes)"))
		assertTrue(source.contains("menu.findItem(R.id.action_search_notes)?.isVisible = false"))
		assertTrue(source.contains("menu.findItem(R.id.action_search)?.let(::setupSearchItem)"))
	}

	@Test
	fun `switching search mode keeps only one search active and X only clears`() {
		val source = source("main/kotlin/org/koitharu/kotatsu/details/ui/pager/ChapterPagesMenuProvider.kt")
		assertTrue(source.contains("SearchMode.CHAPTERS ->"))
		assertTrue(source.contains("SearchMode.NOTES ->"))
		assertTrue(source.contains("viewModel.exitChapterNoteSearch()"))
		assertTrue(source.contains("viewModel.performChapterSearch(null)"))
		assertTrue(source.contains("viewModel.performChapterNoteSearch(\"\")"))
		assertTrue(source.contains("findViewById<View?>(androidx.appcompat.R.id.search_close_btn)?.setOnClickListener"))
		assertTrue(source.contains("setQuery(\"\", false)"))
		assertTrue(source.contains("expandedItemRef?.get()?.collapseActionView()"))
	}

	@Test
	fun `leaving shared search reconstructs normal search and reverse visibility`() {
		val source = source("main/kotlin/org/koitharu/kotatsu/details/ui/pager/ChapterPagesMenuProvider.kt")
		val prepare = source.substringAfter("override fun onPrepareMenu").substringBefore("override fun onMenuItemSelected")
		assertTrue(prepare.contains("menu.findItem(R.id.action_search)?.isVisible = true"))
		assertTrue(prepare.contains("menu.findItem(R.id.action_reversed)?.apply"))
		assertTrue(prepare.contains("isVisible = true"))
		val collapse = source.substringAfter("override fun onMenuItemActionCollapse").substringBefore("override fun onQueryTextSubmit")
		assertTrue(collapse.contains("expandedItemRef = null"))
		assertTrue(collapse.contains("menuRef?.get()?.let(::onPrepareMenu)"))
		assertTrue(!source.contains("menuVisibilityBeforeSearch"))
		assertTrue(!source.contains("restoreMenuVisibility"))
	}

	@Test
	fun `main details notes search keeps separate exit and clear controls`() {
		val source = source("main/kotlin/org/koitharu/kotatsu/details/ui/DetailsChapterComponents.kt")
		assertTrue(source.contains("IconButton(onClick = onExit)"))
		assertTrue(source.contains("contentDescription = stringResource(R.string.chapter_search_notes_exit)"))
		assertTrue(source.contains("IconButton(onClick = { onQueryChange(\"\") })"))
	}

	private fun source(relative: String): String {
		var dir = File(System.getProperty("user.dir")).absoluteFile
		repeat(8) {
			val candidate = File(dir, "app/src/$relative")
			if (candidate.isFile) return candidate.readText()
			dir = dir.parentFile ?: return@repeat
		}
		error("Unable to locate app/src/$relative")
	}
}
