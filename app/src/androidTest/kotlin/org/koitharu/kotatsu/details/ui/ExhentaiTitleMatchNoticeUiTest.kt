package org.koitharu.kotatsu.details.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.koitharu.kotatsu.R

/** Android 15 Compose semantics for the actual Details notice and its explanatory sheet. */
class ExhentaiTitleMatchNoticeUiTest {
	@get:Rule val compose = createComposeRule()
	private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

	@Test fun noticeExplainsReadOnlyStatusOnTapAndCanBeDismissed() {
		showNotice()
		compose.onNodeWithTag("exhentai_title_match_notice").assertExists().assertHasClickAction().performClick()
		compose.onNodeWithText(context.getString(R.string.exhentai_title_match_info_title)).assertExists()
		compose.onNodeWithText(context.getString(R.string.exhentai_title_match_info_body)).assertExists()
		compose.onNodeWithText(context.getString(R.string.exhentai_title_match_info_protection)).assertExists()
		compose.onNodeWithText(context.getString(R.string.close)).performClick()
		compose.waitForIdle()
		compose.onNodeWithText(context.getString(R.string.exhentai_title_match_info_title)).assertDoesNotExist()
	}

	@Test fun narrowDetailsRowStillProvidesAccessibleInformationAction() {
		showNotice(narrow = true)
		compose.onNodeWithTag("exhentai_title_match_notice").assertExists().assertHasClickAction()
		compose.onNodeWithText(context.getString(R.string.exhentai_title_match_status)).assertExists()
		compose.onNodeWithTag("exhentai_title_match_notice").performClick()
		compose.onNodeWithText(context.getString(R.string.exhentai_title_match_info_title)).assertExists()
	}

	private fun showNotice(narrow: Boolean = false) {
		compose.setContent {
			MaterialTheme {
				if (narrow) Box(Modifier.width(280.dp)) { ExhentaiTitleMatchNotice() }
				else ExhentaiTitleMatchNotice()
			}
		}
	}
}
