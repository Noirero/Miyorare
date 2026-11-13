package org.koitharu.kotatsu.favourites.groups.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.SampleData
import org.koitharu.kotatsu.core.model.getTitle

/** Exercises lazy search/selection with a real Compose UI on Android 15. */
@RunWith(AndroidJUnit4::class)
class LibraryGroupAddMembersRuntimeTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun pickerSearchDoesNotSelectHiddenTitles() {
        var added = emptyList<Long>()
        val candidates = listOf(
            LibraryGroupMemberCandidate(SampleData.manga.copy(id = 81301L, title = "Ocean Alpha"), null),
            LibraryGroupMemberCandidate(SampleData.manga.copy(id = 81302L, title = "Forest Beta"), "Other Group"),
        )
        compose.setContent {
            MaterialTheme {
                LibraryGroupAddMembersSheet(candidates, onCancel = {}, onAdd = { added = it })
            }
        }
        compose.onNodeWithText(context.getString(R.string.library_group_select_from_favourites)).assertExists()
        compose.onNode(hasSetTextAction()).performTextInput("Forest")
        compose.onNodeWithText("Forest Beta").assertExists()
        compose.onNodeWithText("Ocean Alpha").assertDoesNotExist()
        compose.onNodeWithText("Forest Beta").performClick()
        compose.onNodeWithText(context.getString(R.string.library_group_selected_count, 1)).assertExists()
        compose.onNodeWithText(context.getString(R.string.library_group_add_to)).performClick()
        compose.runOnIdle { assertEquals(listOf(81302L), added) }
    }

    @Test fun searchOnlyMatchesFavoriteTitlesNotSources() {
        val sourceName = SampleData.manga.source.getTitle(context)
        val candidate = LibraryGroupMemberCandidate(
            SampleData.manga.copy(id = 81303L, title = "A Unique Favourite Title"), null,
        )
        compose.setContent {
            MaterialTheme {
                LibraryGroupAddMembersSheet(listOf(candidate), onCancel = {}, onAdd = {})
            }
        }
        compose.onNodeWithText("A Unique Favourite Title").assertExists()
        compose.onNode(hasSetTextAction()).performTextInput(sourceName)
        compose.onNodeWithText("A Unique Favourite Title").assertDoesNotExist()
    }

    @Test fun emptyPickerCannotSubmit() {
        compose.setContent {
            MaterialTheme {
                LibraryGroupAddMembersSheet(emptyList(), onCancel = {}, onAdd = {})
            }
        }
        compose.onNodeWithText(context.getString(R.string.library_group_no_eligible)).assertExists()
        compose.onNodeWithText(context.getString(R.string.library_group_add_to)).assertIsNotEnabled()
    }
}
