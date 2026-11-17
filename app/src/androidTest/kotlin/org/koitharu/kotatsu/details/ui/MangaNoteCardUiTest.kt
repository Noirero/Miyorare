package org.koitharu.kotatsu.details.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.koitharu.kotatsu.R

class MangaNoteCardUiTest {
	@get:Rule val compose = createComposeRule()

	@Test fun longNoteDefaultsToPreviewAndTogglesWithoutLosingText() {
		val note = (1..6).joinToString("\n") { "Line $it" }
		showNote(mutableStateOf(note))
		compose.onNodeWithText(label(R.string.details_notes)).assertExists()
		assertTrue(layout(note).isLineEllipsized(3))
		assertEquals(4, layout(note).lineCount)

		compose.onNodeWithText(label(R.string.expand)).performClick()
		assertEquals(6, layout(note).lineCount)
		assertFalse(layout(note).hasVisualOverflow)

		compose.onNodeWithText(label(R.string.collapse)).performClick()
		assertEquals(4, layout(note).lineCount)
		compose.onNodeWithText(label(R.string.expand)).assertExists()
	}

	@Test fun replacingAnExpandedNoteWithShortTextHidesBothControls() {
		val note = mutableStateOf((1..6).joinToString("\n") { "Line $it" })
		showNote(note)
		compose.onNodeWithText(label(R.string.expand)).performClick()

		compose.runOnIdle { note.value = "Short note" }
		assertEquals(1, layout("Short note").lineCount)
		compose.onNodeWithText(label(R.string.expand)).assertDoesNotExist()
		compose.onNodeWithText(label(R.string.collapse)).assertDoesNotExist()
	}

	private fun showNote(note: androidx.compose.runtime.State<String>) {
		compose.setContent {
			MaterialTheme { NoteCard(note.value, MaterialTheme.colorScheme.primary) }
		}
	}

	private fun layout(note: String): TextLayoutResult {
		val results = mutableListOf<TextLayoutResult>()
		compose.onNodeWithText(note, useUnmergedTree = true)
			.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
		return results.single()
	}

	private fun label(id: Int): String = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)
}
