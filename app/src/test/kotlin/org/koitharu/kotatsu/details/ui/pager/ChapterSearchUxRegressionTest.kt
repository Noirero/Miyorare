package org.koitharu.kotatsu.details.ui.pager

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class ChapterSearchUxRegressionTest {

	@Test
	fun `manage and reader separate chooser action from active search view`() {
		val source = source("main/kotlin/org/koitharu/kotatsu/details/ui/pager/ChapterPagesMenuProvider.kt")
		val menu = source("main/res/menu/opt_chapters.xml")
		assertTrue(source.contains("private enum class SearchMode { CHAPTERS, NOTES }"))
		assertTrue(source.contains("R.id.action_search -> {"))
		assertTrue(source.contains("showSearchModeChooser()"))
		assertTrue(source.contains("menu.findItem(R.id.action_search_notes)?.let { item ->"))
		assertTrue(source.contains("setupSearchItem(item)"))
		assertTrue(!menu.substringAfter("android:id=\"@+id/action_search\"").substringBefore("</item>").contains("actionViewClass"))
		assertTrue(menu.substringAfter("android:id=\"@+id/action_search_notes\"").substringBefore("</item>").contains("actionViewClass=\"androidx.appcompat.widget.SearchView\""))
	}

	@Test
	fun `chooser expands active search exactly once after mode selection`() {
		val source = source("main/kotlin/org/koitharu/kotatsu/details/ui/pager/ChapterPagesMenuProvider.kt")
		val expand = source.substringAfter("override fun onMenuItemActionExpand").substringBefore("override fun onMenuItemActionCollapse")
		assertTrue(!expand.contains("showSearchModeChooser"))
		assertTrue(!expand.contains("return false\n\t\t}"))
		assertTrue(source.contains("searchItem?.isVisible = true"))
		assertTrue(source.contains("searchItem?.expandActionView()"))
		assertTrue(source.contains("activeItem?.itemId == R.id.action_search_notes && activeItem.isActionViewExpanded"))
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
	fun `collapse restores stateless chooser and hides active search item`() {
		val source = source("main/kotlin/org/koitharu/kotatsu/details/ui/pager/ChapterPagesMenuProvider.kt")
		val prepare = source.substringAfter("override fun onPrepareMenu").substringBefore("override fun onMenuItemSelected")
		assertTrue(prepare.contains("val searchExpanded = expandedItemRef?.get()?.itemId == R.id.action_search_notes"))
		assertTrue(prepare.contains("menu.findItem(R.id.action_search)?.isVisible = !searchExpanded"))
		assertTrue(prepare.contains("menu.findItem(R.id.action_search_notes)?.isVisible = searchExpanded"))
		val collapse = source.substringAfter("override fun onMenuItemActionCollapse").substringBefore("override fun onQueryTextSubmit")
		assertTrue(collapse.contains("expandedItemRef = null"))
		assertTrue(collapse.contains("searchMode = null"))
		assertTrue(collapse.contains("item.isVisible = false"))
		assertTrue(collapse.contains("menuRef?.get()?.let(::onPrepareMenu)"))
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
