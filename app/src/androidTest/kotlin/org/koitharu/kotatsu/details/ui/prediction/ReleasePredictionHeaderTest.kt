package org.koitharu.kotatsu.details.ui.prediction

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.details.domain.NextChapterReleasePrediction
import org.koitharu.kotatsu.details.ui.ChapterHeaderTitle
import java.time.LocalDate

class ReleasePredictionHeaderTest {
	@get:Rule val compose = createComposeRule()
	@Test fun predictionAloneIsClickableAndYieldsToManageOnNarrowHeaders() {
		val context = InstrumentationRegistry.getInstrumentation().targetContext
		val width = mutableStateOf(350.dp)
		var opened = 0
		compose.setContent { MaterialTheme { Row(Modifier.width(width.value)) {
			ChapterHeaderTitle("189 chapters", NextChapterReleasePrediction(LocalDate.of(2026, 10, 10), 1), Modifier.weight(1f)) { opened++ }
			Text("Manage")
		} } }
		val description = context.resources.getQuantityString(R.plurals.chapter_release_accessibility_days, 1, 1L)
		compose.onNodeWithContentDescription(description).performClick()
		assertEquals(1, opened)
		compose.onNodeWithText("Manage").assertIsDisplayed()
		compose.runOnIdle { width.value = 130.dp }
		compose.onNodeWithContentDescription(description).assertDoesNotExist()
		compose.onNodeWithText("Manage").assertIsDisplayed()
	}
}
