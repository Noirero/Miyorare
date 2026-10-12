package org.koitharu.kotatsu.details.ui

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Source-boundary guards, following the existing Details integration regression tests. */
class ChapterPersonalNoteViewerRegressionTest {
	@Test fun noteAndStarKeepSeparateActionsWithoutReplacingTheChapterOpenAction() {
		val row = source("DetailsChapterComponents.kt").substringAfter("internal fun InlineChapterCard(")
		val noteButton = row.substringAfter("if (personal.hasNote) {").substringBefore("val ratingDescription")
		assertTrue(noteButton.contains("IconButton(onClick = onNoteClick)"))
		assertTrue(noteButton.contains("R.string.chapter_personal_view_note"))
		assertFalse(noteButton.contains("onPersonalClick"))
		assertFalse(noteButton.contains("onClick = onClick"))
		assertTrue(row.contains(".clickable(onClick = onClick)"))
		assertTrue(row.contains("onClick = onPersonalClick"))
		assertTrue(source("DetailsExpressiveActivity.kt").contains("onChapterPersonalClick = viewModel::editChapterPersonal"))
	}

	@Test fun groupedAndUngroupedChaptersOpenTheTappedNoteAndDismissClearsTheViewer() {
		val screen = source("DetailsExpressiveScreen.kt")
		assertEquals(2, screen.split("onNoteClick = { noteChapter = chapter }").size - 1)
		assertEquals(2, screen.split("onPersonalClick = { actions.onChapterPersonalClick(chapter) }").size - 1)
		assertEquals(2, screen.split("onClick = { actions.onChapterClick(chapter) }").size - 1)
		assertTrue(screen.contains("remember(manga?.id) { mutableStateOf<ChapterListItem?>(null) }"))
		assertTrue(screen.contains("key(chapter.personalKey)"))
		assertTrue(screen.contains("ChapterPersonalNoteViewer(item = chapter, onDismiss = { noteChapter = null })"))
	}

	@Test fun viewerHasFullScrollableNoteAndCloseUsingTheSameSelectableRendererAsMangaNotes() {
		val viewer = source("ChapterPersonalNoteViewer.kt")
		assertTrue(viewer.contains("val note = item.personalMetadata.note ?: return"))
		assertTrue(viewer.contains("verticalScroll(rememberScrollState())"))
		assertTrue(viewer.contains("ReadOnlyNoteText(note)"))
		assertTrue(viewer.contains("onDismissRequest = onDismiss"))
		assertTrue(viewer.contains("TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }"))
		for (editorOnly in listOf("OutlinedTextField", "onSave", "R.string.save", "clear_note", "ChapterPersonalEditor", "maxLines", "substring", "take(")) {
			assertFalse("Read-only viewer must not contain $editorOnly", viewer.contains(editorOnly))
		}
		val mangaCard = source("DetailsExpressiveScreen.kt").substringAfter("private fun NoteCard(").substringBefore("private fun ExpressiveBackdrop")
		assertTrue(mangaCard.contains("ReadOnlyNoteText(note)"))
		val renderer = source("ReadOnlyNoteText.kt")
		assertTrue(renderer.contains("SelectionContainer {"))
		assertTrue(renderer.contains("text = annotatedNote"))
		assertFalse(renderer.contains("maxLines"))
		assertFalse(source("DetailsExpressiveScreen.kt").contains("NOTE_URL_REGEX"))
		assertFalse(viewer.contains("Regex("))
	}

	private fun source(name: String): String = sequenceOf(
		File("src/main/kotlin/org/koitharu/kotatsu/details/ui", name),
		File("app/src/main/kotlin/org/koitharu/kotatsu/details/ui", name),
	).first(File::isFile).readText()
}
