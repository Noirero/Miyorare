package org.koitharu.kotatsu.details.ui.pager

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class ChapterSearchUxRegressionTest {

	@Test
	fun `shared chapter searches keep one active session and X only clears`() {
		val source = source("main/kotlin/org/koitharu/kotatsu/details/ui/pager/ChapterPagesMenuProvider.kt")
		assertTrue(source.contains("if (expandedItemRef?.get() != null) return false"))
		assertTrue(source.contains("if (viewModel.chapterNotesQuery.value == null) viewModel.performChapterNoteSearch(\"\")"))
		assertTrue(source.contains("viewModel.exitChapterNoteSearch()"))
		assertTrue(source.contains("findViewById<View?>(androidx.appcompat.R.id.search_close_btn)?.setOnClickListener"))
		assertTrue(source.contains("setQuery(\"\", false)"))
		assertTrue(source.contains("focusSearchInput()"))
		assertTrue(source.contains("expandedItemRef?.get()?.collapseActionView()"))
	}

	@Test
	fun `reader and manage retain their intended search ownership`() {
		val source = source("main/kotlin/org/koitharu/kotatsu/details/ui/pager/ChapterPagesMenuProvider.kt")
		assertTrue(source.contains("item.isVisible = viewModel is ReaderViewModel"))
		assertTrue(source.contains("menu.findItem(R.id.action_search)?.let(::setupSearchItem)"))
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
