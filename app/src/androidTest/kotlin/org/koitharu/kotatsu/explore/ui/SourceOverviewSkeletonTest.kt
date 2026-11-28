package org.koitharu.kotatsu.explore.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun keyboardSearchSubmitsTrimmedQueryOnce() {
        val submitted = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                SourceOverview("Fixture", state(), {}, { submitted.add(it) }, {}, {}, {}, {}, {})
            }
        }
        val search = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.search)
        compose.onNodeWithContentDescription(search).performClick()
        val input = compose.onNode(hasSetTextAction())
        input.assert(hasImeAction(ImeAction.Search))
        input.performClick().performTextInput("  girl  ")
        compose.runOnIdle { assertTrue(submitted.isEmpty()) }
        input.performImeAction()
        compose.runOnIdle { assertEquals(listOf("girl"), submitted) }
    }

    @Test
    fun iconSearchStillSubmitsTrimmedQueryOnce() {
        val submitted = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                SourceOverview("Fixture", state(), {}, { submitted.add(it) }, {}, {}, {}, {}, {})
            }
        }
        val search = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.search)
        val icon = compose.onNodeWithContentDescription(search)
        icon.performClick()
        compose.onNode(hasSetTextAction()).performTextInput("  girl  ")
        compose.runOnIdle { assertTrue(submitted.isEmpty()) }
        icon.performClick()
        compose.runOnIdle { assertEquals(listOf("girl"), submitted) }
    }

    @Test
    fun whitespaceOnlyQueryCannotBeSubmittedByIconOrKeyboard() {
        val submitted = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                SourceOverview("Fixture", state(), {}, { submitted.add(it) }, {}, {}, {}, {}, {})
            }
        }
        val search = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.search)
        val icon = compose.onNodeWithContentDescription(search)
        icon.performClick()
        val input = compose.onNode(hasSetTextAction())
        input.performClick().performTextInput("   ")
        icon.assertIsNotEnabled()
        input.performImeAction()
        compose.runOnIdle { assertTrue(submitted.isEmpty()) }
    }

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

    @Test
    fun shimmerVariantsDrawOnlyTheirOwnLayerAndEndpointsStayStatic() {
        val phase = mutableStateOf(0f)
        val effect = mutableStateOf(OverviewSkeletonEffect.EDGE_AND_SOFT)
        val light = mutableStateOf(false)
        compose.setContent {
            val scheme = if (light.value) lightColorScheme() else darkColorScheme()
            MaterialTheme(colorScheme = scheme) {
                Box(
                    Modifier.size(104.dp, 156.dp).clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .overviewSkeletonShimmer(phase, effect.value, MaterialTheme.colorScheme.onSurface)
                        .testTag("shimmer_fixture"),
                )
            }
        }
        val node = compose.onNodeWithTag("shimmer_fixture")
        for (isLight in listOf(false, true)) {
            compose.runOnIdle { light.value = isLight; phase.value = 0f }
            val baseline = node.captureToImage().toPixelMap()
            val x = baseline.width / 2
            val y = baseline.height / 2
            val baseCenter = baseline[x, y]
            compose.runOnIdle { phase.value = 0.5f; effect.value = OverviewSkeletonEffect.SOFT }
            val soft = node.captureToImage().toPixelMap()
            assertTrue("Soft shimmer changes the face", soft[x, y] != baseCenter)

            compose.runOnIdle { effect.value = OverviewSkeletonEffect.EDGE }
            val edge = node.captureToImage().toPixelMap()
            assertEquals("Edge shimmer leaves the face unchanged", baseCenter, edge[x, y])
            assertTrue("Edge shimmer draws a visible stroke", (0 until edge.width).any { col ->
                (0 until edge.height).any { row -> edge[col, row] != baseline[col, row] }
            })

            compose.runOnIdle { effect.value = OverviewSkeletonEffect.EDGE_AND_SOFT }
            val hybrid = node.captureToImage().toPixelMap()
            assertEquals("Hybrid retains the soft face", soft[x, y], hybrid[x, y])
            assertTrue("Hybrid includes the edge", (0 until hybrid.width).any { col ->
                (0 until hybrid.height).any { row -> hybrid[col, row] != soft[col, row] }
            })

            // The target endpoint is also the Compose zero-duration-scale fallback.
            compose.runOnIdle { phase.value = 1f }
            val stopped = node.captureToImage().toPixelMap()
            assertTrue("Stopped shimmer equals the static skeleton", (0 until stopped.width).all { col ->
                (0 until stopped.height).all { row -> stopped[col, row] == baseline[col, row] }
            })
        }
    }

    @Test
    fun sharedShimmerPhaseExistsOnlyForResumedPendingShelves() {
        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry.createUnsafe(this)
            override val lifecycle: Lifecycle get() = registry
        }
        val pending = mutableStateOf(true)
        var observed: State<Float>? = null
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                val phase = rememberOverviewSkeletonPhase(pending.value)
                SideEffect { observed = phase }
            }
        }
        compose.runOnIdle { assertNotNull(observed); owner.registry.currentState = Lifecycle.State.STARTED }
        compose.runOnIdle { assertNull(observed); owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.runOnIdle { assertNotNull(observed); pending.value = false }
        compose.runOnIdle { assertNull(observed); pending.value = true }
        compose.runOnIdle { assertNotNull(observed) }
    }
}
