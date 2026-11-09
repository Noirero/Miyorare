package org.koitharu.kotatsu.settings.sources

import android.view.ContextThemeWrapper
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.koitharu.kotatsu.R

/** Android UI coverage of 198 lazy rows; never contacts LNReader or installs plugins. */
class LnCandidateRegistryScreenTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val themedContext = ContextThemeWrapper(context, R.style.Theme_Kotatsu)

    private val sample = LnCandidateSnapshot(
        upstreamCommit = "a".repeat(40),
        candidates = ((1..186).map { index ->
            candidate("en$index", "English Novel $index", "en")
        } + candidate("chikari", "Chikari", "en") +
            (1..11).map { index ->
                candidate("id$index", "Indonesia Novel $index", "id")
            })
    )

    private fun candidate(id: String, name: String, language: String) = LnCandidate(
        id = id, name = name, language = language, version = "1.0.0",
        site = "https://example.invalid/$id", state = "INVENTORIED",
        apiCheck = "UNKNOWN", runtimeCheck = "UNKNOWN", androidCheck = "UNKNOWN",
    )

    private fun show(
        snapshot: LnCandidateSnapshot? = sample,
        loading: Boolean = false,
        error: String? = null,
        onRefresh: () -> Unit = {},
    ) {
        compose.setContent {
            CompositionLocalProvider(LocalContext provides themedContext) {
                MaterialTheme {
                    LnCandidateRegistryScreen(
                        snapshot = snapshot, loading = loading,
                        errorMessage = error, onRefresh = onRefresh,
                    )
                }
            }
        }
    }

    @Test
    fun all198RecordsAreSearchableAndLastIndonesianRowRendersWhenScrolled() {
        assertEquals(198, sample.candidates.size)
        assertEquals(198, sample.candidates.map { it.id }.toSet().size)
        show()
        compose.onNodeWithTag("ln-candidates-list").assertExists()
        compose.onNodeWithTag("ln-candidates-section:en").assertExists()
        compose.onNodeWithTag("ln-candidates-list")
            .performScrollToNode(hasTestTag("ln-candidates-section:id"))
        compose.onNodeWithTag("ln-candidates-section:id").assertIsDisplayed()
        compose.onNodeWithTag("ln-candidates-list")
            .performScrollToNode(hasTestTag("ln-candidate:id11"))
        compose.onNodeWithTag("ln-candidate:id11").assertIsDisplayed()
        compose.onNodeWithTag("ln-candidates-list")
            .performScrollToNode(hasTestTag("ln-candidates-search"))
        compose.onNodeWithTag("ln-candidates-search").performTextInput("  indonesia novel 11 ")
        compose.onNodeWithTag("ln-candidate:id11").assertIsDisplayed()
        compose.onNodeWithTag("ln-candidate:en1").assertDoesNotExist()
        compose.onNodeWithTag("ln-candidates-section:en").assertDoesNotExist()
        compose.onNodeWithTag("ln-candidates-section:id").assertExists()
        compose.onNodeWithTag("ln-candidates-search").performTextReplacement("no_such_ln_source_987654")
        compose.onNodeWithTag("ln-candidates-empty").assertIsDisplayed()
        compose.onNodeWithTag("ln-candidates-section:id").assertDoesNotExist()
    }

    @Test
    fun loadingAndUnpublishedRegistryDoNotPretendToHaveCandidates() {
        show(snapshot = null, loading = true)
        compose.onNodeWithTag("ln-candidates-loading").assertIsDisplayed()
        compose.onNodeWithTag("ln-candidates-refresh").assertHasNoClickAction()
        compose.onNodeWithTag("ln-candidates-search").assertDoesNotExist()
    }

    @Test
    fun registryUnavailableHasExplicitRetryAndNoCandidateRows() {
        var refreshCalls = 0
        show(snapshot = null, error = "Registry not yet published") { refreshCalls++ }
        compose.onNodeWithTag("ln-candidates-error").assertIsDisplayed()
        compose.onNodeWithTag("ln-candidates-search").assertDoesNotExist()
        compose.onNodeWithTag("ln-candidates-refresh").performClick()
        assertEquals(1, refreshCalls)
    }
}
