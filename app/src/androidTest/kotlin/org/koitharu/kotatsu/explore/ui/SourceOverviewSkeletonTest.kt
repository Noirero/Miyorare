package org.koitharu.kotatsu.explore.ui

import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
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
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class SourceOverviewSkeletonTest {

    @get:Rule val compose = createComposeRule()

    private val manga = SampleData.manga.copy(title = "Skeleton fixture title", coverUrl = "")
    private val latest = SourceOverviewViewModel.Shelf(SortOrder.UPDATED)
    private val popular = SourceOverviewViewModel.Shelf(SortOrder.POPULARITY)

    private fun state(vararg shelves: SourceOverviewViewModel.Shelf) =
        SourceOverviewViewModel.State(manga.source, shelves.toList(), loading = false)

    private fun closeSearchAfterAssertion(view: View) {
        val ime = WindowInsetsCompat.Type.ime()
        // The fake navigation callback does not leave the Activity. Hide the IME while
        // the input is still served, then remove the focused editor before teardown.
        compose.runOnIdle { ViewCompat.getWindowInsetsController(view)?.hide(ime) }
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.runOnIdle { ViewCompat.getRootWindowInsets(view)?.isVisible(ime) == false }
        }
        val back = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.back)
        compose.onNodeWithContentDescription(back).performClick()
        compose.onNode(hasSetTextAction()).assertDoesNotExist()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.runOnIdle { ViewCompat.getRootWindowInsets(view)?.isVisible(ime) == false }
        }
    }

    @Test
    fun keyboardSearchSubmitsTrimmedQueryOnce() {
        val submitted = mutableListOf<String>()
        lateinit var view: View
        compose.setContent {
            val hostView = LocalView.current
            SideEffect { view = hostView }
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
        closeSearchAfterAssertion(view)
        compose.runOnIdle { assertEquals(listOf("girl"), submitted) }
    }

    @Test
    fun iconSearchStillSubmitsTrimmedQueryOnce() {
        val submitted = mutableListOf<String>()
        lateinit var view: View
        compose.setContent {
            val hostView = LocalView.current
            SideEffect { view = hostView }
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
        closeSearchAfterAssertion(view)
        compose.runOnIdle { assertEquals(listOf("girl"), submitted) }
    }

    @Test
    fun whitespaceOnlyQueryCannotBeSubmittedByIconOrKeyboard() {
        val submitted = mutableListOf<String>()
        lateinit var view: View
        compose.setContent {
            val hostView = LocalView.current
            SideEffect { view = hostView }
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
        closeSearchAfterAssertion(view)
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

    private data class PhaseRead(val sequence: Long, val phase: Float, val nanos: Long)

    private fun logShimmerPng(id: String, name: String, width: Int, height: Int, pixels: IntArray) {
        val bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        try {
            val png = ByteArrayOutputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
            }
            val chunks = png.chunked(1800)
            chunks.forEachIndexed { index, chunk ->
                Log.i("OverviewShimmerDiag", "PNG id=$id name=$name part=${index + 1}/${chunks.size} $chunk")
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun logShimmerComparison(id: String, label: String, baseline: PixelMap, actual: PixelMap) {
        if (baseline.width != actual.width || baseline.height != actual.height) {
            Log.i("OverviewShimmerDiag", "$id $label dimensions baseline=${baseline.width}x${baseline.height} actual=${actual.width}x${actual.height}")
            return
        }
        val width = baseline.width
        val height = baseline.height
        val basePixels = IntArray(width * height)
        val actualPixels = IntArray(width * height)
        val mask = IntArray(width * height) { android.graphics.Color.BLACK }
        val transitions = mutableMapOf<Pair<Int, Int>, Int>()
        var left = width
        var right = -1
        var top = height
        var bottom = -1
        var mismatches = 0
        for (row in 0 until height) for (col in 0 until width) {
            val i = row * width + col
            val before = baseline[col, row]
            val after = actual[col, row]
            basePixels[i] = before.toArgb()
            actualPixels[i] = after.toArgb()
            if (before != after) {
                mismatches++
                left = minOf(left, col)
                right = maxOf(right, col)
                top = minOf(top, row)
                bottom = maxOf(bottom, row)
                mask[i] = android.graphics.Color.MAGENTA
                val key = basePixels[i] to actualPixels[i]
                transitions[key] = (transitions[key] ?: 0) + 1
            }
        }
        Log.i("OverviewShimmerDiag", "$id $label size=${width}x$height mismatches=$mismatches bounds=[$left,$top..$right,$bottom]")
        val colors = transitions.entries.sortedByDescending { it.value }.take(8).joinToString { (pair, count) ->
            "${Integer.toHexString(pair.first)}->${Integer.toHexString(pair.second)}:$count"
        }
        Log.i("OverviewShimmerDiag", "$id $label topArgbTransitions=$colors")
        if (label == "first") logShimmerPng(id, "baseline", width, height, basePixels)
        logShimmerPng(id, label, width, height, actualPixels)
        logShimmerPng(id, "${label}_diff", width, height, mask)
    }

    @Test
    fun shimmerVariantsDrawOnlyTheirOwnLayerAndEndpointsStayStatic() {
        val phase = mutableStateOf(0f)
        val effect = mutableStateOf(OverviewSkeletonEffect.EDGE_AND_SOFT)
        val light = mutableStateOf(false)
        val lastRead = AtomicReference<PhaseRead?>(null)
        // Observe the actual draw-time read, not a second Compose state or a simulated renderer.
        val tracedPhase = object : State<Float> {
            override val value: Float
                get() = phase.value.also { current ->
                    lastRead.set(PhaseRead((lastRead.get()?.sequence ?: 0L) + 1L, current, SystemClock.elapsedRealtimeNanos()))
                }
        }
        lateinit var hostView: View
        compose.setContent {
            val view = LocalView.current
            SideEffect { hostView = view }
            val scheme = if (light.value) lightColorScheme() else darkColorScheme()
            MaterialTheme(colorScheme = scheme) {
                // The window capture previously compared clipped content against an implicit
                // Activity backing surface. Keep the contrast and all antialiased corners
                // inside a single controlled raster target, with a guard band around them.
                Box(
                    Modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                        .background(if (light.value) Color.Black else Color.White)
                        .testTag("shimmer_fixture")
                        .padding(8.dp),
                ) {
                    Box(
                        Modifier.size(104.dp, 156.dp).clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .overviewSkeletonShimmer(tracedPhase, effect.value, MaterialTheme.colorScheme.onSurface),
                    )
                }
            }
        }
        val node = compose.onNodeWithTag("shimmer_fixture")
        for (isLight in listOf(false, true)) {
            compose.runOnIdle { light.value = isLight; phase.value = 0f }
            val baseline = node.captureToImage().toPixelMap()
            val baselineRead = lastRead.get()
            val x = baseline.width / 2
            val y = baseline.height / 2
            val baseCenter = baseline[x, y]
            val backing = if (isLight) Color.Black else Color.White
            assertEquals("Fixture has explicit contrast backing (light=$isLight)", backing, baseline[0, 0])
            assertTrue("Fixture center differs from backing (light=$isLight)", baseCenter != backing)
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
            val beforeRequest = lastRead.get()
            var requestedNanos = 0L
            compose.runOnIdle {
                requestedNanos = SystemClock.elapsedRealtimeNanos()
                phase.value = 1f
            }
            val beforeCapture = lastRead.get()
            val stopped = node.captureToImage().toPixelMap()
            val afterCapture = lastRead.get()
            val captureNanos = SystemClock.elapsedRealtimeNanos()
            val id = "light-$isLight-$requestedNanos"
            Log.i("OverviewShimmerDiag", "$id baselineRead=$baselineRead beforeRequest=$beforeRequest requestNanos=$requestedNanos")
            Log.i("OverviewShimmerDiag", "$id beforeCapture=$beforeCapture afterCapture=$afterCapture captureNanos=$captureNanos")
            assertEquals("Stopped shimmer width (light=$isLight)", baseline.width, stopped.width)
            assertEquals("Stopped shimmer height (light=$isLight)", baseline.height, stopped.height)
            val mismatch = (0 until stopped.width).sumOf { col ->
                (0 until stopped.height).count { row -> stopped[col, row] != baseline[col, row] }
            }
            Log.i("OverviewShimmerDiag", "$id firstMismatch=$mismatch")
            if (mismatch != 0) {
                // Retain the original first capture as the gate. A later frame is diagnostic only.
                runCatching {
                    logShimmerComparison(id, "first", baseline, stopped)
                }.onFailure { Log.w("OverviewShimmerDiag", "$id first evidence failed", it) }
                runCatching {
                    val committed = CountDownLatch(1)
                    val commitNanos = AtomicReference<Long?>(null)
                    var registered = false
                    compose.runOnIdle {
                        if (Build.VERSION.SDK_INT >= 29 && hostView.isHardwareAccelerated &&
                            hostView.viewTreeObserver.isAlive) {
                            hostView.viewTreeObserver.registerFrameCommitCallback {
                                commitNanos.set(SystemClock.elapsedRealtimeNanos())
                                committed.countDown()
                            }
                            registered = true
                            hostView.postInvalidateOnAnimation()
                        }
                    }
                    val signaled = registered && committed.await(2, TimeUnit.SECONDS)
                    val later = node.captureToImage().toPixelMap()
                    Log.i("OverviewShimmerDiag", "$id frameCommit registered=$registered signaled=$signaled commitNanos=${commitNanos.get()} laterRead=${lastRead.get()} laterCaptureNanos=${SystemClock.elapsedRealtimeNanos()}")
                    logShimmerComparison(id, "later", baseline, later)
                    logShimmerComparison(id, "hybrid", baseline, hybrid)
                }.onFailure { Log.w("OverviewShimmerDiag", "$id later evidence failed", it) }
                // A second phase-0 sample distinguishes reference drift from endpoint-specific
                // differences. It is evidence only; the original first phase-1 capture still gates.
                runCatching {
                    compose.runOnIdle { phase.value = 0f }
                    val zeroAgain = node.captureToImage().toPixelMap()
                    Log.i("OverviewShimmerDiag", "$id zeroAgainRead=${lastRead.get()} captureNanos=${SystemClock.elapsedRealtimeNanos()}")
                    logShimmerComparison(id, "zero_again", baseline, zeroAgain)
                    logShimmerComparison(id, "zero_again_vs_stopped", stopped, zeroAgain)
                }.onFailure { Log.w("OverviewShimmerDiag", "$id phase-zero control failed", it) }
            }
            assertEquals("Stopped shimmer equals the static skeleton (light=$isLight, " +
                "center baseline=$baseCenter stopped=${stopped[x, y]})", 0, mismatch)
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
