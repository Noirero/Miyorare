package org.koitharu.kotatsu.explore.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.SampleData
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.SortOrder

class SourceOverviewSkeletonTest {

    @get:Rule val compose = createComposeRule()

    private val manga = SampleData.manga.copy(title = "Skeleton fixture title", coverUrl = "")
    private val latest = SourceOverviewViewModel.Shelf(SortOrder.UPDATED)
    private val popular = SourceOverviewViewModel.Shelf(SortOrder.POPULARITY)

    private fun state(vararg shelves: SourceOverviewViewModel.Shelf) =
        SourceOverviewViewModel.State(manga.source, shelves.toList(), loading = false)

    @Test
    fun metadataReplacesOnlyItsOwnSkeletonAndRestoresRealClickAction() {
        val current = mutableStateOf(state(latest, popular))
        var clicked: Manga? = null
        compose.setContent {
            MaterialTheme {
                SourceOverview("Fixture", current.value, {}, {}, {}, {}, {}, { clicked = it }, {})
            }
        }
        val list = compose.onNodeWithTag("source_overview_shelves")
        list.performScrollToIndex(0)
        compose.onAllNodesWithTag("source_overview_skeleton_UPDATED").onFirst().assertHasNoClickAction()
        compose.onNodeWithText(manga.title).assertDoesNotExist()
        list.performScrollToIndex(1)
        compose.onAllNodesWithTag("source_overview_skeleton_POPULARITY").onFirst().assertHasNoClickAction()

        compose.runOnIdle { current.value = state(latest.copy(entries = listOf(manga)), popular) }
        list.performScrollToIndex(0)
        compose.onAllNodesWithTag("source_overview_skeleton_UPDATED").assertCountEquals(0)
        compose.onNodeWithText(manga.title).assertExists().performClick()
        compose.runOnIdle { assertEquals(manga, clicked) }
        list.performScrollToIndex(1)
        compose.onAllNodesWithTag("source_overview_skeleton_POPULARITY").onFirst().assertExists()

        compose.runOnIdle {
            current.value = state(latest.copy(entries = listOf(manga)), popular.copy(entries = listOf(manga.copy(id = manga.id + 1, title = "Popular fixture"))))
        }
        list.performScrollToIndex(1)
        compose.onAllNodesWithTag("source_overview_skeleton_POPULARITY").assertCountEquals(0)
        compose.onNodeWithText("Popular fixture").assertExists()
    }

    @Test
    fun skeletonAndRealShelfHaveEqualHeightAtNormalAndLargeFontScale() {
        val current = mutableStateOf(state(latest))
        val fontScale = mutableStateOf(1f)
        val dark = mutableStateOf(false)
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale.value)) {
                MaterialTheme(colorScheme = if (dark.value) darkColorScheme() else lightColorScheme()) {
                    SourceOverview("Fixture", current.value, {}, {}, {}, {}, {}, {}, {})
                }
            }
        }
        val row = compose.onNodeWithTag("source_overview_shelf_UPDATED")
        for (scale in listOf(1f, 2f)) {
            compose.runOnIdle {
                fontScale.value = scale
                dark.value = scale == 2f
                current.value = state(latest)
            }
            val placeholderHeight = row.fetchSemanticsNode().boundsInRoot.height
            compose.onAllNodesWithTag("source_overview_skeleton_UPDATED").onFirst().assertExists()
            compose.onNodeWithTag("source_overview_shelf_POPULARITY").assertDoesNotExist()
            compose.runOnIdle { current.value = state(latest.copy(entries = listOf(manga.copy(title = "A long manga title spanning several words")))) }
            val contentHeight = row.fetchSemanticsNode().boundsInRoot.height
            assertEquals("Shelf height at font scale $scale", placeholderHeight, contentHeight, 1f)
        }
    }

    @Test
    fun emptyAndFailedShelvesDoNotKeepLoadingPlaceholders() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val current = mutableStateOf(state(latest))
        var retries = 0
        compose.setContent {
            MaterialTheme {
                SourceOverview("Fixture", current.value, {}, {}, {}, {}, {}, {}, { retries++ })
            }
        }
        compose.onAllNodesWithTag("source_overview_skeleton_UPDATED").onFirst().assertExists()
        compose.runOnIdle { current.value = state(latest.copy(entries = emptyList())) }
        compose.onAllNodesWithTag("source_overview_skeleton_UPDATED").assertCountEquals(0)
        compose.onNodeWithText(context.getString(R.string.nothing_found)).assertExists()
        compose.runOnIdle { current.value = state(latest.copy(failed = true)) }
        compose.onAllNodesWithTag("source_overview_skeleton_UPDATED").assertCountEquals(0)
        compose.onNodeWithText(context.getString(R.string.source_overview_load_failed)).assertExists()
        compose.onNodeWithText(context.getString(R.string.retry)).performClick()
        compose.runOnIdle { assertEquals(1, retries) }
        compose.runOnIdle { current.value = state(latest) }
        compose.onAllNodesWithTag("source_overview_skeleton_UPDATED").onFirst().assertExists()
    }
}
