package org.koitharu.kotatsu.details.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.SemanticsNodeInteraction
import org.koitharu.kotatsu.core.model.MangaSource
import org.koitharu.kotatsu.core.prefs.MiyorareAppearance
import org.koitharu.kotatsu.core.prefs.MiyorareThemePreset
import org.koitharu.kotatsu.core.ui.LocalMiyorareVisualPalette
import org.koitharu.kotatsu.core.ui.miyorareThemeColors
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import androidx.test.platform.app.InstrumentationRegistry
import coil3.ImageLoader
import coil3.intercept.Interceptor
import coil3.request.ImageResult
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.koitharu.kotatsu.parsers.model.MangaState
import android.content.res.Configuration
import android.graphics.Canvas
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import android.graphics.Bitmap
import android.graphics.Color
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.SampleData
import org.koitharu.kotatsu.core.prefs.DetailsUiMode
import org.koitharu.kotatsu.core.prefs.VisualEffectLevel
import org.koitharu.kotatsu.details.data.ChapterPersonalMetadata
import org.koitharu.kotatsu.details.data.MangaDetails
import org.koitharu.kotatsu.details.ui.model.ChapterListItem
import org.koitharu.kotatsu.details.ui.model.HistoryInfo
import org.koitharu.kotatsu.details.ui.pager.filterChapterSearch
import org.koitharu.kotatsu.scrobbling.common.domain.model.*
import java.io.IOException

/** Real Compose semantics with a portrait loader that can never reach the network. */
class TrackerPeopleSectionTest {
	@get:Rule val compose = createComposeRule()
	@get:Rule val testName = TestName()
	private val context = InstrumentationRegistry.getInstrumentation().targetContext
	private val loader = ImageLoader.Builder(context).components {
		add(object : Interceptor {
			override suspend fun intercept(chain: Interceptor.Chain): ImageResult = throw IOException("Fixture-only portrait loader")
		})
	}.build()
	private val service = ScrobblerService.ANILIST

	@After fun closeLoader() { loader.shutdown() }

    // Selected by the existing Details Android 15 runtime suite; unlike a new standalone test
    // class these checks are guaranteed to execute under the current CI selector.
    @Test fun darkTrackerForegroundIgnoresIncorrectBlackAmbient() =
        DetailsTrackerForegroundContrastFixture.checkForeground(compose, context, loader, dark = true, amoled = false, custom = false)

    @Test fun lightTrackerForegroundIgnoresIncorrectWhiteAmbient() =
        DetailsTrackerForegroundContrastFixture.checkForeground(compose, context, loader, dark = false, amoled = false, custom = false)

    @Test fun amoledTrackerForegroundIgnoresIncorrectBlackAmbient() =
        DetailsTrackerForegroundContrastFixture.checkForeground(compose, context, loader, dark = true, amoled = true, custom = false)

    @Test fun customDarkTrackerForegroundIgnoresIncorrectBlackAmbient() =
        DetailsTrackerForegroundContrastFixture.checkForeground(compose, context, loader, dark = true, amoled = false, custom = true)

    @Test fun onDeviceLightAndDarkPaletteResourcesRenderWithDistinctSurfaces() {
        fun themed(mode: Int) = context.createConfigurationContext(
            Configuration(context.resources.configuration).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or mode
            }
        )
        val light = themed(Configuration.UI_MODE_NIGHT_NO)
        val dark = themed(Configuration.UI_MODE_NIGHT_YES)
        assertEquals(Color.rgb(247, 244, 239), ContextCompat.getColor(light, R.color.smart_local_ink))
        assertEquals(Color.rgb(16, 28, 43), ContextCompat.getColor(dark, R.color.smart_local_ink))
        assertEquals(Color.rgb(32, 50, 71), ContextCompat.getColor(light, R.color.smart_local_cream))
        assertEquals(Color.rgb(245, 233, 217), ContextCompat.getColor(dark, R.color.smart_local_cream))

        fun drawableLuminance(ctx: android.content.Context, id: Int): Double {
            val image = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
            try {
                val d = ContextCompat.getDrawable(ctx, id) ?: error("Missing local background: $id")
                d.setBounds(0, 0, image.width, image.height)
                d.draw(Canvas(image))
                return ColorUtils.calculateLuminance(image.getPixel(128, 128))
            } finally {
                image.recycle()
            }
        }
        for (id in listOf(R.drawable.bg_smart_local_archive_hero,
                R.drawable.bg_smart_local_archive_stat, R.drawable.bg_smart_local_archive_resume)) {
            assertTrue("Local day background must be bright", drawableLuminance(light, id) >= 0.55)
            assertTrue("Local night background must stay cinematic", drawableLuminance(dark, id) < 0.25)
        }
    }

    @Test fun onDeviceGalleryRevisitReusesDecodedThumbnailAndInvalidatesOnRevision() {
        val chapterItems = (0 until 48).map { index ->
            ChapterListItem(SampleData.chapter.copy(
                id = 70_000L + index, url = "local://gallery-probe/$index",
                title = "Decode probe $index", number = index.toFloat(), uploadDate = 0L,
            ), ChapterListItem.FLAG_UNREAD)
        }
        val firstId = chapterItems.first().chapter.id
        val encoded = ByteArrayOutputStream().also { stream ->
            Bitmap.createBitmap(48, 72, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.BLUE)
                compress(Bitmap.CompressFormat.PNG, 100, stream)
                recycle()
            }
        }.toByteArray()
        val revision = androidx.compose.runtime.mutableLongStateOf(1L)
        val decodes = ConcurrentHashMap<Long, AtomicInteger>()
        val fetches = ConcurrentHashMap<Long, AtomicInteger>()
        val memoryHits = ConcurrentHashMap<Long, AtomicInteger>()
        compose.setContent {
            MaterialTheme {
                LocalChapterGallery(
                    chapters = chapterItems, mangaTitle = "Decode probe",
                    bottomPadding = 0.dp, topPadding = 0.dp,
                    imageLoader = loader,
                    galleryRevision = revision.longValue,
                    thumbnailMemoryKey = { chapter -> "fixture-gallery:${chapter.id}:revision-${revision.longValue}" },
                    coverForChapter = { chapter ->
                        fetches.computeIfAbsent(chapter.id) { AtomicInteger() }.incrementAndGet()
                        encoded
                    },
                    onChapterClick = {},
                    onThumbnailDecoded = { chapter ->
                        decodes.computeIfAbsent(chapter.id) { AtomicInteger() }.incrementAndGet()
                    },
                    onThumbnailMemoryHit = { chapter ->
                        memoryHits.computeIfAbsent(chapter.id) { AtomicInteger() }.incrementAndGet()
                    },
                )
            }
        }
        compose.waitUntil(15_000) { (decodes[firstId]?.get() ?: 0) == 1 }
        val initialDecodes = decodes[firstId]!!.get()
        val initialFetches = fetches[firstId]!!.get()
        compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("Decode probe 40"))
        compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("Decode probe 0"))
        compose.waitUntil(15_000) { (memoryHits[firstId]?.get() ?: 0) > 0 }
        val afterDecodes = decodes[firstId]!!.get()
        val afterFetches = fetches[firstId]!!.get()
        assertEquals("Revisit must skip decode while bounded Coil memory retains thumbnail", initialDecodes, afterDecodes)
        assertEquals("Revisit must skip derived disk fetch on memory hit", initialFetches, afterFetches)
        assertTrue("Bounded Coil memory should serve a revisit", (loader.memoryCache?.size ?: 0L) > 0L)
        android.util.Log.i("LocalGalleryDecodeProbe",
            "SYNTHETIC firstChapter initialDecodes=$initialDecodes afterDecodes=$afterDecodes " +
                "initialFetches=$initialFetches afterFetches=$afterFetches " +
                "memoryHits=${memoryHits[firstId]?.get()}")

        compose.runOnIdle { revision.longValue = 2L }
        compose.waitUntil(15_000) { (decodes[firstId]?.get() ?: 0) == 2 }
        assertEquals("New thumbnail identity must fetch fresh bytes", 2, fetches[firstId]!!.get())
    }


    @Test fun compactHeroSourceToggleKeepsRoutingAndUnclipsStatus() =
        verifySourceStatusInteraction(centered = false, compactExpanded = false, width = 220)

    @Test fun centeredHeroSourceToggleKeepsRoutingAndUnclipsStatus() =
        verifySourceStatusInteraction(centered = true, compactExpanded = false, width = 320)

    @Test fun expandedCompactHeroSourceToggleKeepsRoutingAndUnclipsStatus() =
        verifySourceStatusInteraction(centered = false, compactExpanded = true, width = 220)

    private fun verifySourceStatusInteraction(centered: Boolean, compactExpanded: Boolean, width: Int) {
        // A realistic extension name that must become fully visible within a cover-height card.
        val fullSourceName = "Komik manga indonesia"
        val renderedStatus = "Masih lanjut"
        val manga = SampleData.mangaDetails.copy(
            source = MangaSource("MIHON_424242"),
            url = "https://example.invalid/manga/hero-source-status",
            publicUrl = "https://example.invalid/manga/hero-source-status",
        )
        val colors = miyorareThemeColors(
            preset = MiyorareThemePreset.MIYORARE,
            customAccent = MiyorareAppearance.DEFAULT_CUSTOM_ACCENT,
            darkTheme = true,
            amoled = false,
            effectLevel = VisualEffectLevel.LIGHT,
        )
        val mangaState = mutableStateOf(manga)
        var sourceNavigations = 0
        compose.setContent {
            MaterialTheme(colorScheme = colors.colorScheme) {
                CompositionLocalProvider(LocalMiyorareVisualPalette provides colors.visualPalette) {
                    Box(Modifier.width(width.dp)) {
                        HeroSourceStatusRow(
                            manga = mangaState.value, sourceTitle = fullSourceName,
                            imageLoader = loader, status = renderedStatus,
                            showActiveRelease = true, accent = colors.colorScheme.primary,
                            centered = centered, compactExpanded = compactExpanded,
                            onSourceClick = { sourceNavigations++ },
                        )
                    }
                }
            }
        }

        val row = compose.onNodeWithTag("hero-source-status-row")
        val sourceText = compose.onNodeWithTag("hero-source-title", useUnmergedTree = true)
        val sourceContent = compose.onNodeWithTag("hero-source-content")
        val statusCard = compose.onNodeWithTag("hero-status-card")
        val toggle = compose.onNodeWithTag("hero-source-toggle")
        val expandLabel = context.getString(R.string.details_expand_source_details)
        val collapseLabel = context.getString(R.string.details_collapse_source_details)
        val releaseText = context.getString(R.string.details_release_active)

        fun assertCollapsedTextAndStatus() {
            sourceContent.assertIsDisplayed()
            statusCard.assertIsDisplayed()
            sourceText.assertTextEquals("...")
            assertReadableStatusText(compose.onNodeWithText(renderedStatus, useUnmergedTree = true), maxLines = 2)
            assertReadableStatusText(compose.onNodeWithText(releaseText, useUnmergedTree = true), maxLines = 2)
            if (!centered) compose.onNodeWithTag("hero-status-icon").assertIsDisplayed()
        }

        compose.onNodeWithContentDescription(expandLabel).assertIsDisplayed()
        assertCollapsedTextAndStatus()
        compose.onNodeWithTag("hero-source-expanded-detail").assertDoesNotExist()
        compose.onNodeWithTag("hero-source-expanded-text").assertDoesNotExist()

        val collapsedSource = sourceContent.fetchSemanticsNode().boundsInRoot
        val collapsedStatus = statusCard.fetchSemanticsNode().boundsInRoot
        val collapsedRow = row.fetchSemanticsNode().boundsInRoot

        toggle.performClick()
        compose.onNodeWithContentDescription(collapseLabel).assertIsDisplayed()
        sourceContent.assertIsDisplayed()
        sourceText.assertTextEquals(fullSourceName)
        assertTextOverflow(sourceText, expected = false, renderedStatus = renderedStatus)
        compose.onNodeWithTag("hero-source-expanded-detail").assertDoesNotExist()
        compose.onNodeWithTag("hero-source-expanded-text").assertDoesNotExist()

        val expandedSource = sourceContent.fetchSemanticsNode().boundsInRoot
        val expandedStatus = statusCard.fetchSemanticsNode().boundsInRoot
        val expandedRow = row.fetchSemanticsNode().boundsInRoot
        assertTrue("Expanded Source must grow rightward", expandedSource.width > collapsedSource.width)
        assertTrue("Source must stop before visible Status", expandedSource.right < expandedStatus.left)
        assertTrue("Status must narrow but remain visible", expandedStatus.width > 0f &&
            expandedStatus.width < collapsedStatus.width)
        assertEquals("Status right edge stays fixed", collapsedStatus.right, expandedStatus.right, 1f)
        assertEquals("Source left unchanged", collapsedSource.left, expandedSource.left, 1f)
        assertEquals("Source top unchanged", collapsedSource.top, expandedSource.top, 1f)
        assertEquals("Source bottom unchanged", collapsedSource.bottom, expandedSource.bottom, 1f)
        assertTrue("Status must shift right to make room for Source", expandedStatus.left > collapsedStatus.left)
        assertEquals("Status top unchanged", collapsedStatus.top, expandedStatus.top, 1f)
        assertEquals("Row height must not change", collapsedRow.height, expandedRow.height, 1f)
        assertEquals("Row top must not shift", collapsedRow.top, expandedRow.top, 1f)
        compose.runOnIdle { assertEquals("Chevron must not navigate", 0, sourceNavigations) }

        sourceContent.performClick()
        compose.runOnIdle { assertEquals("Source content navigates exactly once", 1, sourceNavigations) }
        toggle.performClick()
        compose.onNodeWithContentDescription(expandLabel).assertIsDisplayed()
        assertCollapsedTextAndStatus()
        assertEquals("Collapse restores Source width", collapsedSource.width,
            sourceContent.fetchSemanticsNode().boundsInRoot.width, 1f)
        assertEquals("Collapse restores Status width", collapsedStatus.width,
            statusCard.fetchSemanticsNode().boundsInRoot.width, 1f)
        compose.runOnIdle { assertEquals(1, sourceNavigations) }

        // Source identity and manga identity each reset transient expansion independently.
        toggle.performClick()
        assertTrue(sourceContent.fetchSemanticsNode().boundsInRoot.width > collapsedSource.width)
        compose.runOnIdle { mangaState.value = manga.copy(source = MangaSource("MIHON_424243")) }
        compose.onNodeWithContentDescription(expandLabel).assertIsDisplayed()
        assertCollapsedTextAndStatus()
        assertEquals(collapsedSource.width, sourceContent.fetchSemanticsNode().boundsInRoot.width, 1f)

        toggle.performClick()
        assertTrue(sourceContent.fetchSemanticsNode().boundsInRoot.width > collapsedSource.width)
        compose.runOnIdle { mangaState.value = mangaState.value.copy(id = manga.id + 1) }
        compose.onNodeWithContentDescription(expandLabel).assertIsDisplayed()
        assertCollapsedTextAndStatus()
        assertEquals(collapsedSource.width, sourceContent.fetchSemanticsNode().boundsInRoot.width, 1f)
    }


    @Test fun compactHeroLongTitleAndCreatorDoNotOverlapPinnedMetadata() =
        verifyLongTitleCreatorClearance(compactExpanded = false, creator = "Royal Studio")

    @Test fun expandedCompactHeroLongTitleAndCreatorDoNotOverlapPinnedMetadata() =
        verifyLongTitleCreatorClearance(compactExpanded = true, creator = "Royal Studio")

    @Test fun expandedCompactHeroLongCreatorDoesNotOverlapPinnedMetadata() =
        verifyLongTitleCreatorClearance(
            compactExpanded = true,
            creator = "Royal Studio Creator And Illustration Department With An Extended Name",
        )

    private fun verifyLongTitleCreatorClearance(compactExpanded: Boolean, creator: String) {
        val longTitle = "The Regressed Son Of A Duke Is An Assassin"
        val sourceName = "Komik manga indonesia"
        val manga = SampleData.mangaDetails.copy(
            title = longTitle,
            authors = setOf(creator),
            altTitles = emptySet(),
            state = MangaState.ONGOING,
            source = MangaSource("MIHON_424242"),
            url = "https://example.invalid/manga/long-title-cover-geometry",
            publicUrl = "https://example.invalid/manga/long-title-cover-geometry",
        )
        val colors = miyorareThemeColors(
            preset = MiyorareThemePreset.MIYORARE,
            customAccent = MiyorareAppearance.DEFAULT_CUSTOM_ACCENT,
            darkTheme = true,
            amoled = false,
            effectLevel = VisualEffectLevel.LIGHT,
        )
        compose.setContent {
            MaterialTheme(colorScheme = colors.colorScheme) {
                CompositionLocalProvider(LocalMiyorareVisualPalette provides colors.visualPalette) {
                    Column(Modifier.width(360.dp)) {
                        ModernDetailsHero(
                            centered = false, manga = manga, details = null,
                            sourceTitle = sourceName, accent = colors.colorScheme.primary,
                            imageLoader = loader, coverUrl = null, actions = heroNoopActions(),
                            compactExpanded = compactExpanded,
                        )
                        Box(Modifier.width(360.dp).height(1.dp).testTag("hero-following-actions-anchor"))
                    }
                }
            }
        }

        val cover = compose.onNodeWithTag("details-hero-cover")
        val row = compose.onNodeWithTag("hero-source-status-row")
        val source = compose.onNodeWithTag("hero-source-content")
        val status = compose.onNodeWithTag("hero-status-card")
        val followingActions = compose.onNodeWithTag("hero-following-actions-anchor")
        val title = compose.onNodeWithText(longTitle, useUnmergedTree = true)
        val creatorText = compose.onNodeWithText(creator, useUnmergedTree = true)
        val density = context.resources.displayMetrics.density
        val clearancePx = 3f * density

        fun assertLongHeroGeometry() {
            val coverBounds = cover.fetchSemanticsNode().boundsInRoot
            val rowBounds = row.fetchSemanticsNode().boundsInRoot
            val sourceBounds = source.fetchSemanticsNode().boundsInRoot
            val statusBounds = status.fetchSemanticsNode().boundsInRoot
            val titleBounds = title.fetchSemanticsNode().boundsInRoot
            val creatorBounds = creatorText.fetchSemanticsNode().boundsInRoot

            assertTrue("Title must have visible area: $titleBounds", titleBounds.height > 0f)
            assertTrue("Creator must have visible area: $creatorBounds", creatorBounds.height > 0f)
            assertTrue("Title must precede creator: $titleBounds / $creatorBounds",
                titleBounds.bottom <= creatorBounds.top + 1f)
            assertTrue(
                "Creator must clear Source/Status: creator=$creatorBounds, row=$rowBounds",
                creatorBounds.bottom + clearancePx <= rowBounds.top + 1f,
            )
            assertTrue("Title must not intersect Source/Status",
                titleBounds.bottom + clearancePx <= rowBounds.top + 1f)
            assertTrue("Source must stay beside cover", sourceBounds.left >= coverBounds.right)
            if (!compactExpanded) {
                assertTrue("Collapsed Source stays within cover bottom",
                    sourceBounds.bottom <= coverBounds.bottom + 1f)
                assertTrue("Collapsed Status stays within cover bottom",
                    statusBounds.bottom <= coverBounds.bottom + 1f)
                assertTrue("Collapsed title at most two lines", textLayout(title).lineCount <= 2)
                assertEquals("Collapsed creator exactly one line", 1, textLayout(creatorText).lineCount)
            } else {
                assertTextFullyRendered(title)
                assertTextFullyRendered(creatorText)
                assertTrue("Expanded title must render 3+ lines for this fixture",
                    textLayout(title).lineCount >= 3)
            }
            assertEquals("Source/Status row remains 72dp", 72f * density, rowBounds.height, 1.5f)
            compose.onNodeWithTag("hero-source-expanded-detail").assertDoesNotExist()
            compose.onNodeWithTag("hero-source-expanded-text").assertDoesNotExist()
        }

        assertLongHeroGeometry()
        compose.onNodeWithTag("hero-source-title", useUnmergedTree = true).assertTextEquals("...")
        val initialSource = source.fetchSemanticsNode().boundsInRoot
        val initialStatus = status.fetchSemanticsNode().boundsInRoot
        val initialActions = followingActions.fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("hero-source-toggle").performClick()
        val expandedSource = source.fetchSemanticsNode().boundsInRoot
        assertTrue("Source must expand horizontally", expandedSource.width > initialSource.width)
        val expandedStatus = status.fetchSemanticsNode().boundsInRoot
        assertTrue("Expanded Source must stop before Status", expandedSource.right < expandedStatus.left)
        assertTrue("Status must shrink but retain visible width",
            expandedStatus.width > 0f && expandedStatus.width < initialStatus.width)
        compose.onNodeWithTag("hero-source-title", useUnmergedTree = true).assertTextEquals(sourceName)
        assertLongHeroGeometry()
        assertEquals("Source expansion must not shift following actions", initialActions.top,
            followingActions.fetchSemanticsNode().boundsInRoot.top, 1f)
        compose.onNodeWithTag("hero-source-toggle").performClick()
        assertLongHeroGeometry()
        compose.onNodeWithTag("hero-source-title", useUnmergedTree = true).assertTextEquals("...")
        assertEquals("Collapsed Source width must restore", initialSource.width,
            source.fetchSemanticsNode().boundsInRoot.width, 1f)
        assertEquals("Collapsed Status width must restore", initialStatus.width,
            status.fetchSemanticsNode().boundsInRoot.width, 1f)
    }

    @Test fun compactHeroSourceStatusRespectsCoverBottom() = verifyCoverBoundary(compactExpanded = false)

    @Test fun compactExpandedHeroSourceStatusRespectsCoverBottom() = verifyCoverBoundary(compactExpanded = true)

    private fun verifyCoverBoundary(compactExpanded: Boolean) {
        val renderedStatus = context.getString(R.string.state_ongoing)
        val manga = SampleData.mangaDetails.copy(
            title = "Compact hero geometry",
            authors = emptySet(),
            altTitles = emptySet(),
            state = MangaState.ONGOING,
            source = MangaSource("MIHON_424242"),
            url = "https://example.invalid/manga/hero-cover-geometry",
            publicUrl = "https://example.invalid/manga/hero-cover-geometry",
        )
        val colors = miyorareThemeColors(
            preset = MiyorareThemePreset.MIYORARE,
            customAccent = MiyorareAppearance.DEFAULT_CUSTOM_ACCENT,
            darkTheme = true, amoled = false, effectLevel = VisualEffectLevel.LIGHT,
        )
        compose.setContent {
            MaterialTheme(colorScheme = colors.colorScheme) {
                CompositionLocalProvider(LocalMiyorareVisualPalette provides colors.visualPalette) {
                    Box(Modifier.width(360.dp)) {
                        ModernDetailsHero(
                            centered = false, manga = manga, details = null,
                            sourceTitle = "Komik manga indonesia",
                            accent = colors.colorScheme.primary, imageLoader = loader,
                            coverUrl = null, actions = heroNoopActions(),
                            compactExpanded = compactExpanded,
                        )
                    }
                }
            }
        }
        val cover = compose.onNodeWithTag("details-hero-cover")
        val source = compose.onNodeWithTag("hero-source-content")
        val status = compose.onNodeWithTag("hero-status-card")
        val row = compose.onNodeWithTag("hero-source-status-row")
        val coverBottom = cover.fetchSemanticsNode().boundsInRoot.bottom
        val rowBefore = row.fetchSemanticsNode().boundsInRoot
        val sourceBefore = source.fetchSemanticsNode().boundsInRoot
        val statusBefore = status.fetchSemanticsNode().boundsInRoot
        if (!compactExpanded) {
            assertTrue("Collapsed Source must not exceed actual cover bottom", sourceBefore.bottom <= coverBottom + 1f)
            assertTrue("Collapsed Status must not exceed actual cover bottom", statusBefore.bottom <= coverBottom + 1f)
        }
        assertTrue("Source must stay beside cover", sourceBefore.left >= cover.fetchSemanticsNode().boundsInRoot.right)
        status.assertIsDisplayed()
        val active = compose.onNodeWithText(context.getString(R.string.details_release_active), useUnmergedTree = true)
        if (!compactExpanded) assertReadableStatusText(active, maxLines = 2)
        val value = compose.onNodeWithText(renderedStatus, useUnmergedTree = true)
        if (!compactExpanded) assertReadableStatusText(value, maxLines = 2)

        compose.onNodeWithTag("hero-source-toggle").performClick()
        val sourceAfter = source.fetchSemanticsNode().boundsInRoot
        val rowAfter = row.fetchSemanticsNode().boundsInRoot
        val statusAfter = status.fetchSemanticsNode().boundsInRoot
        assertTrue("Expanded Source leaves Status visible", sourceAfter.right < statusAfter.left)
        assertTrue("Status becomes narrower", statusAfter.width > 0f &&
            statusAfter.width < statusBefore.width)
        assertEquals("Expansion does not shift Source vertically", sourceBefore.top, sourceAfter.top, 1f)
        assertEquals("Expansion does not extend hero row", rowBefore.height, rowAfter.height, 1f)
        if (!compactExpanded) {
            assertTrue("Collapsed-hero expanded Source stays within cover bottom",
                sourceAfter.bottom <= coverBottom + 1f)
        }
        compose.onNodeWithTag("hero-source-toggle").performClick()
        status.assertIsDisplayed()
        assertEquals("Collapse restores Source width", sourceBefore.width,
            source.fetchSemanticsNode().boundsInRoot.width, 1f)
        assertEquals("Collapse restores Status width", statusBefore.width,
            status.fetchSemanticsNode().boundsInRoot.width, 1f)
    }

    @Test fun expandingHeroPreservesSourceChevronState() {
        val sourceName = "Komik manga indonesia"
        val title = "The Regressed Son Of A Duke Is An Assassin"
        val manga = SampleData.mangaDetails.copy(
            title = title,
            authors = setOf("Royal Studio Creator And Illustration Department With An Extended Name"),
            altTitles = emptySet(), state = MangaState.ONGOING,
            source = MangaSource("MIHON_424242"),
            url = "https://example.invalid/manga/hero-independent-states",
            publicUrl = "https://example.invalid/manga/hero-independent-states",
        )
        val heroExpanded = mutableStateOf(false)
        val mangaState = mutableStateOf(manga)
        val colors = miyorareThemeColors(
            preset = MiyorareThemePreset.MIYORARE,
            customAccent = MiyorareAppearance.DEFAULT_CUSTOM_ACCENT,
            darkTheme = true, amoled = false, effectLevel = VisualEffectLevel.LIGHT,
        )
        compose.setContent {
            MaterialTheme(colorScheme = colors.colorScheme) {
                CompositionLocalProvider(LocalMiyorareVisualPalette provides colors.visualPalette) {
                    Column(Modifier.width(360.dp)) {
                        ModernDetailsHero(
                            centered = false, manga = mangaState.value, details = null,
                            sourceTitle = sourceName, accent = colors.colorScheme.primary,
                            imageLoader = loader, coverUrl = null, actions = heroNoopActions(),
                            compactExpanded = heroExpanded.value,
                        )
                        Box(Modifier.width(360.dp).height(1.dp).testTag("hero-following-actions-anchor"))
                    }
                }
            }
        }
        val source = compose.onNodeWithTag("hero-source-content")
        val status = compose.onNodeWithTag("hero-status-card")
        val sourceText = compose.onNodeWithTag("hero-source-title", useUnmergedTree = true)
        val toggle = compose.onNodeWithTag("hero-source-toggle")
        val next = compose.onNodeWithTag("hero-following-actions-anchor")
        sourceText.assertTextEquals("...")
        val collapsedStatusWidth = status.fetchSemanticsNode().boundsInRoot.width
        toggle.performClick()
        sourceText.assertTextEquals(sourceName)
        val sourceOpenWidth = source.fetchSemanticsNode().boundsInRoot.width
        val statusNarrowWidth = status.fetchSemanticsNode().boundsInRoot.width
        assertTrue(statusNarrowWidth > 0f && statusNarrowWidth < collapsedStatusWidth)

        compose.runOnIdle { heroExpanded.value = true }
        sourceText.assertTextEquals(sourceName)
        assertEquals("Hero expansion must preserve Source width", sourceOpenWidth,
            source.fetchSemanticsNode().boundsInRoot.width, 1f)
        assertEquals("Hero expansion must preserve narrow Status width", statusNarrowWidth,
            status.fetchSemanticsNode().boundsInRoot.width, 1f)
        val titleNode = compose.onNodeWithText(title, useUnmergedTree = true)
        val creatorNode = compose.onNodeWithText(manga.authors.single(), useUnmergedTree = true)
        assertTextFullyRendered(titleNode)
        assertTextFullyRendered(creatorNode)
        val coverBounds = compose.onNodeWithTag("details-hero-cover").fetchSemanticsNode().boundsInRoot
        val titleBounds = titleNode.fetchSemanticsNode().boundsInRoot
        val creatorBounds = creatorNode.fetchSemanticsNode().boundsInRoot
        val rowBounds = compose.onNodeWithTag("hero-source-status-row")
            .fetchSemanticsNode().boundsInRoot
        val statusBounds = status.fetchSemanticsNode().boundsInRoot
        val sourceBounds = source.fetchSemanticsNode().boundsInRoot
        val nextBounds = next.fetchSemanticsNode().boundsInRoot
        val density = context.resources.displayMetrics.density
        val tolerancePx = 1f
        val expectedExpandedSpacingPx = 8f * density // Modern LIGHT palette uses 8dp.
        assertTrue("Expanded title must precede creator: $titleBounds / $creatorBounds",
            titleBounds.bottom <= creatorBounds.top + tolerancePx)
        assertTrue(
            "Expanded creator must precede Source/Status with spacing: " +
                "creator=$creatorBounds row=$rowBounds",
            creatorBounds.bottom + expectedExpandedSpacingPx - tolerancePx <= rowBounds.top,
        )
        assertTrue("Expanded Status must stay inside Source/Status row",
            statusBounds.top >= rowBounds.top - tolerancePx &&
                statusBounds.bottom <= rowBounds.bottom + tolerancePx)
        assertTrue("Expanded Source must stay inside Source/Status row",
            sourceBounds.top >= rowBounds.top - tolerancePx &&
                sourceBounds.bottom <= rowBounds.bottom + tolerancePx)
        assertTrue("Following content must come after actual hero bottom: " +
            "following=$nextBounds, cover=$coverBounds, row=$rowBounds",
            nextBounds.top + tolerancePx >= maxOf(coverBounds.bottom, rowBounds.bottom))

        compose.runOnIdle { heroExpanded.value = false }
        sourceText.assertTextEquals(sourceName)
        toggle.performClick()
        sourceText.assertTextEquals("...")
        assertEquals("Source collapse restores Status width", collapsedStatusWidth,
            status.fetchSemanticsNode().boundsInRoot.width, 1f)

        toggle.performClick()
        compose.runOnIdle { mangaState.value = manga.copy(source = MangaSource("MIHON_424243")) }
        sourceText.assertTextEquals("...")
        toggle.performClick()
        compose.runOnIdle { mangaState.value = mangaState.value.copy(id = manga.id + 1) }
        sourceText.assertTextEquals("...")
    }

    private fun heroNoopActions() = DetailsExpressiveActions(
        onTagClick = {}, onCoverClick = {}, onScrobblingMore = {}, onScrobblingCardClick = {},
        onFavoriteClick = {}, onFavoriteLongClick = {}, onTitleClick = {}, onAuthorClick = {},
        onRelatedMore = {}, onRelatedClick = {}, onRelatedMangaClick = {}, onRelatedKeywordMore = { _, _ -> },
        onRelatedDiscoveryRequested = {}, onSourceClick = {}, onLocalClick = {},
        onGenreRecommendationsVisibilityChanged = {}, onReadClick = {}, onIncognitoClick = {},
        onForgetHistoryClick = {}, onChaptersClick = {}, onChapterOptionsClick = {},
        onChapterOptionsSetDefaultClick = {}, onChapterOptionsResetClick = {},
        onChapterClick = {}, onChapterDownloadClick = {}, onChapterPersonalClick = {},
        onTrackerPeopleRequested = {}, onTrackerPeopleRetry = {}, onTrackerPeopleRefresh = {},
        onTrackerRecommendationsRequested = {}, onTrackerRecommendationsRetry = {},
        onTrackerRecommendationsRefresh = {}, onTrackerRecommendationClick = {},
        onTrackerRecommendationProvider = {}, onMangaUpdatesProgressRetry = {},
    )

    private fun textLayout(node: SemanticsNodeInteraction): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        return results.single()
    }

    private fun assertTextFullyRendered(node: SemanticsNodeInteraction) {
        node.assertIsDisplayed()
        val layout = textLayout(node)
        assertFalse("Full hero text must not overflow vertically", layout.didOverflowHeight)
        for (line in 0 until layout.lineCount) {
            assertFalse("Expanded hero must not ellipsize line $line", layout.isLineEllipsized(line))
        }
    }

    private fun assertReadableStatusText(node: SemanticsNodeInteraction, maxLines: Int) {
        node.assertIsDisplayed()
        val layout = textLayout(node)
        val textBounds = node.fetchSemanticsNode().boundsInRoot
        val cardBounds = compose.onNodeWithTag("hero-status-card").fetchSemanticsNode().boundsInRoot
        val tolerancePx = 1f
        assertTrue("Status text must have 1..$maxLines readable lines",
            layout.lineCount in 1..maxLines)
        val lastLineBottomPx = layout.getLineBottom(layout.lineCount - 1)
        val paragraphHeightPx = layout.multiParagraph.height
        assertTrue(
            "Status line exceeds measured height beyond 1px rounding: " +
                "lineBottom=$lastLineBottomPx, paragraphHeight=$paragraphHeightPx, " +
                "measuredHeight=${layout.size.height}, didOverflowHeight=${layout.didOverflowHeight}",
            lastLineBottomPx <= layout.size.height + tolerancePx &&
                paragraphHeightPx <= layout.size.height + tolerancePx,
        )
        for (line in 0 until layout.lineCount) {
            val width = layout.getLineRight(line) - layout.getLineLeft(line)
            assertTrue("Status glyphs exceed allocated width: line=$width measured=${layout.size.width}",
                width <= layout.size.width + tolerancePx)
        }
        assertTrue("Status text must stay within card horizontally: $textBounds / $cardBounds",
            textBounds.left >= cardBounds.left - tolerancePx &&
                textBounds.right <= cardBounds.right + tolerancePx)
        assertTrue("Status text must stay within card vertically: $textBounds / $cardBounds",
            textBounds.top >= cardBounds.top - tolerancePx &&
                textBounds.bottom <= cardBounds.bottom + tolerancePx)
    }

    private fun assertTextOverflow(
        node: SemanticsNodeInteraction,
        expected: Boolean,
        renderedStatus: String? = null,
    ) {
        val results = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        val layout = results.single()
        // Diagnostics must never replace/mask the actual overflow assertion.
        val evidence = if (!expected && layout.hasVisualOverflow) {
            runCatching { compactStatusBudgetEvidence(renderedStatus) }
                .getOrElse { "collectorError=${it::class.java.simpleName}: ${it.message}" }
        } else ""
        val textIdentity = runCatching {
            node.fetchSemanticsNode().config[SemanticsProperties.Text]
        }.getOrNull()?.joinToString(separator = " | ") { item -> item.text }
            ?: "<no text semantics>"
        assertEquals(
            "test=${testName.methodName}, targetText='$textIdentity', " +
                "lines=${layout.lineCount}, size=${layout.size}, " +
                "overflowWidth=${layout.didOverflowWidth}, overflowHeight=${layout.didOverflowHeight}, " +
                "intrinsicWidth=${layout.multiParagraph.maxIntrinsicWidth}; $evidence",
            expected, layout.hasVisualOverflow,
        )
    }

    // Fixture-aware evidence: each missing/ambiguous node is reported independently,
    // without replacing the primary assertion with a diagnostic lookup exception.
    private fun compactStatusBudgetEvidence(renderedStatus: String?): String {
        val density = context.resources.displayMetrics.density
        val card = compose.onNodeWithTag("hero-status-card").fetchSemanticsNode().boundsInRoot
        val row = compose.onNodeWithTag("hero-source-status-row").fetchSemanticsNode().boundsInRoot
        val labels = listOf(
            "header" to context.getString(R.string.status),
            "value" to renderedStatus,
            "release" to context.getString(R.string.details_release_active),
        )
        val children = labels.joinToString("; ") { (label, content) ->
            if (content == null) {
                "$label=missing(fixture value not provided)"
            } else {
                runCatching {
                    val candidate = compose.onNodeWithText(content, useUnmergedTree = true)
                    val bounds = candidate.fetchSemanticsNode().boundsInRoot
                    val measured = mutableListOf<TextLayoutResult>()
                    candidate.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(measured) }
                    val result = measured.single()
                    "$label.text='$content', bounds=$bounds, size=${result.size}, " +
                        "lines=${result.lineCount}, overflowWidth=${result.didOverflowWidth}, " +
                        "overflowHeight=${result.didOverflowHeight}, " +
                        "intrinsicWidth=${result.multiParagraph.maxIntrinsicWidth}"
                }.getOrElse { exception ->
                    "$label=missing-or-ambiguous(text='$content', cause=${exception::class.java.simpleName})"
                }
            }
        }
        return "density=$density, card=$card, cardHeightDp=${card.height / density}, " +
            "row=$row, rowHeightDp=${row.height / density}; $children"
    }

	@Test fun characterPortraitNameAndRoleAndStaffAreRenderedWithProviderAttribution() {
		setContent(DetailsPeopleUiState(providers = listOf(provider(
			TrackerResult.Success(listOf(TrackerPerson("1", "Character One", "https://portrait.invalid/1.png", listOf("MAIN")))),
			TrackerResult.Success(listOf(TrackerPerson("2", "Staff One", null, listOf("Story")))),
		))))
		compose.onNodeWithTag("tracker-people:ANILIST:characters").assertExists()
		compose.onNodeWithTag("tracker-people:ANILIST:staff").assertExists()
		compose.onNodeWithText("Character One").assertExists()
		compose.onNodeWithText("MAIN").assertExists()
		compose.onNodeWithText("Staff One").assertExists()
		compose.onNodeWithText("Story").assertExists()
		compose.onNodeWithTag("tracker-person-image").assertExists()
		compose.onNodeWithTag("tracker-person-no-image").assertExists()
	}

	@Test fun missingImagesAndRolesDoNotInventProviderData() {
		setContent(DetailsPeopleUiState(providers = listOf(provider(
			TrackerResult.Unsupported,
			TrackerResult.Success(listOf(TrackerPerson("2", "Author Without Image", null, emptyList()))),
		))))
		compose.onNodeWithTag("tracker-people:ANILIST:characters").assertDoesNotExist()
		compose.onNodeWithText("Author Without Image").assertExists()
		compose.onNodeWithTag("tracker-person-no-image").assertExists()
		compose.onNodeWithTag("tracker-person-image").assertDoesNotExist()
	}

	@Test fun emptyUnsupportedAndUnmappedStatesHaveNoMisleadingSections() {
		setContent(DetailsPeopleUiState(providers = listOf(
			provider(TrackerResult.Empty(), TrackerResult.Unsupported),
			AssociatedTrackerDetails(ScrobblerService.KITSU, null, TrackerDetailsReadState.NO_MAPPING),
		)))
		compose.onNodeWithTag("tracker-people:ANILIST:characters").assertDoesNotExist()
		compose.onNodeWithTag("tracker-people:ANILIST:staff").assertDoesNotExist()
		compose.onNodeWithTag("tracker-people:KITSU:characters").assertDoesNotExist()
		compose.onNodeWithTag("tracker-people-loading").assertDoesNotExist()
	}

	@Test fun partialContentSurvivesIndependentFailureAndRetryNamesOnlyFailedProvider() {
		var retried: ScrobblerService? = null
		compose.setContent {
			MaterialTheme {
				TrackerPeopleSection(
					DetailsPeopleUiState(providers = listOf(provider(
						TrackerResult.Partial(listOf(TrackerPerson("1", "Available Character", null, listOf("MAIN"))), setOf(TrackerPartialReason.PROVIDER_ERROR)),
						TrackerResult.Error(IOException("raw internal secret-shaped error must not be visible")),
					))), loader, { retried = it }, {},
				)
			}
		}
		compose.onNodeWithText("Available Character").assertExists()
		compose.onNodeWithText(context.getString(R.string.tracker_people_partial)).assertExists()
		compose.onNodeWithText("raw internal secret-shaped error must not be visible").assertDoesNotExist()
		compose.onNodeWithText(context.getString(R.string.tracker_people_retry)).performClick()
		assertEquals(service, retried)
	}

	@Test fun loadingAndFailureNeverReplaceCoreSiblingContentAndPrivacyRemovalClearsPeople() {
		val state = mutableStateOf(DetailsPeopleUiState(isLoading = true))
		compose.setContent {
			MaterialTheme {
				Column {
					Text("Core chapters remain usable")
					TrackerPeopleSection(state.value, loader, {}, {})
				}
			}
		}
		compose.onNodeWithText("Core chapters remain usable").assertExists()
		compose.onNodeWithTag("tracker-people-loading").assertExists()
		compose.runOnIdle { state.value = DetailsPeopleUiState(providers = listOf(provider(
			TrackerResult.Success(listOf(TrackerPerson("1", "Visible Person", null, emptyList()))), TrackerResult.Empty(),
		))) }
		compose.onNodeWithText("Visible Person").assertExists()
		compose.runOnIdle { state.value = DetailsPeopleUiState() }
		compose.onNodeWithText("Visible Person").assertDoesNotExist()
		compose.onNodeWithText("Core chapters remain usable").assertExists()
	}

	@Test fun equalNamesAcrossProvidersRemainSeparateLanes() {
		val people = TrackerResult.Success(listOf(TrackerPerson("1", "Same Name", null, emptyList())))
		setContent(DetailsPeopleUiState(providers = listOf(
			provider(people, TrackerResult.Empty()),
			provider(people, TrackerResult.Empty()).copy(service = ScrobblerService.KITSU, target = TrackerTarget(ScrobblerService.KITSU, "42")),
		)))
		compose.onNodeWithTag("tracker-people:ANILIST:characters").assertExists()
		compose.onNodeWithTag("tracker-people:KITSU:characters").assertExists()
		compose.onAllNodesWithTag("tracker-person-no-image").assertCountEquals(2)
	}

	@Test fun fullDetailsCollapseAndNoteSearchPreserveChaptersPeopleAndRecommendationNavigation() {
		val remoteSource = org.koitharu.kotatsu.core.model.MangaSource("MIHON_424242")
		val chapter = SampleData.chapter.copy(id = 531L, title = "Ported chapter", number = 1f, uploadDate = 0L, source = remoteSource)
		val row = ChapterListItem(chapter, ChapterListItem.FLAG_UNREAD, personalMetadata = ChapterPersonalMetadata(5, "Peak encounter"))
		val rows = listOf(row)
		val query = mutableStateOf<String?>(null)
		var opened: TrackerRecommendation? = null
		var personal: ChapterListItem? = null
		val recommendation = TrackerRecommendation(TrackerTarget(service, "531"), "Preserved recommendation", null, TrackerRecommendationKind.RECOMMENDATION)
		val actions = DetailsExpressiveActions(
			onTagClick = {}, onCoverClick = {}, onScrobblingMore = {}, onScrobblingCardClick = {},
			onFavoriteClick = {}, onFavoriteLongClick = {}, onTitleClick = {}, onAuthorClick = {},
			onRelatedMore = {}, onRelatedClick = {}, onRelatedMangaClick = {}, onRelatedKeywordMore = { _, _ -> },
			onRelatedDiscoveryRequested = {}, onSourceClick = {}, onLocalClick = {},
			onGenreRecommendationsVisibilityChanged = {}, onReadClick = {}, onIncognitoClick = {}, onForgetHistoryClick = {},
			onChaptersClick = {}, onChapterOptionsClick = {}, onChapterOptionsSetDefaultClick = {}, onChapterOptionsResetClick = {},
			onChapterClick = {}, onChapterDownloadClick = {}, onChapterPersonalClick = { personal = it },
			onChapterNotesSearchClick = { query.value = "" }, onChapterNotesQueryChange = { query.value = it },
			onChapterNotesSearchExit = { query.value = null },
			onTrackerPeopleRequested = {}, onTrackerPeopleRetry = {}, onTrackerPeopleRefresh = {},
			onTrackerRecommendationsRequested = {}, onTrackerRecommendationsRetry = {}, onTrackerRecommendationsRefresh = {},
			onTrackerRecommendationClick = { opened = it }, onTrackerRecommendationProvider = {}, onMangaUpdatesProgressRetry = {},
		)
		compose.setContent {
			MaterialTheme {
				DetailsExpressiveScreen(
					details = MangaDetails(SampleData.mangaDetails.copy(source = remoteSource, chapters = listOf(chapter)), null, null, "Fixture description", true),
					note = null, tags = emptyList(), historyInfo = HistoryInfo(1, -2, null, false, false, true, null),
					chapters = rows.filterChapterSearch("", query.value), chapterReleasePrediction = null, chapterNotesQuery = query.value,
					isChapterFilterActive = false, isLoading = false, favouriteCount = 0, favouriteLabel = null, scrobblings = emptyList(),
					trackerPeople = DetailsPeopleUiState(providers = listOf(provider(
						TrackerResult.Success(listOf(TrackerPerson("531", "Preserved character", null, listOf("MAIN")))),
						TrackerResult.Success(listOf(TrackerPerson("532", "Preserved staff", null, listOf("Story")))),
					))),
					trackerRecommendations = DetailsPeopleUiState(providers = listOf(provider(TrackerResult.Empty(), TrackerResult.Empty()).copy(
						recommendations = TrackerResult.Success(listOf(recommendation)),
					))),
					mangaUpdatesProgressFailed = false, genreRecommendations = emptyList(), expandedRelated = DetailsRelatedUiState(),
					relatedDiscoveryEnabled = false, localSize = 0L, indexedLocalBook = null, sourceTitle = "Fixture source", imageLoader = loader,
					coverUrl = null, backdropUrl = null, isBackdropEnabled = false, backdropBlurAmount = 0,
					visualEffectLevel = VisualEffectLevel.LIGHT, style = DetailsUiMode.COMPACT, topInset = 0.dp, bottomContentPadding = 0.dp,
					onScroll = {}, actions = actions,
				)
			}
		}
		fun scrollTo(matcher: androidx.compose.ui.test.SemanticsMatcher) {
			compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(matcher)
		}
		val collapse = context.getString(R.string.collapse)
		val expand = context.getString(R.string.expand)
		scrollTo(hasContentDescription(collapse))
		compose.onNodeWithContentDescription(collapse).performClick()
		compose.onNodeWithText("Ported chapter").assertDoesNotExist()
		scrollTo(hasTestTag("tracker-people:ANILIST:characters"))
		compose.onNodeWithText("Preserved character").assertExists()
		scrollTo(hasTestTag("tracker-people:ANILIST:staff"))
		compose.onNodeWithText("Preserved staff").assertExists()
		scrollTo(hasTestTag("tracker-recommendation:ANILIST:531"))
		compose.onNodeWithTag("tracker-recommendation:ANILIST:531").performClick()
		assertEquals(recommendation, opened)
		scrollTo(hasContentDescription(expand))
		compose.onNodeWithContentDescription(expand).performClick()
		compose.onNodeWithText("Ported chapter").assertExists()
		compose.onNodeWithContentDescription(context.getString(R.string.chapter_personal_edit)).performClick()
		assertEquals(row, personal)
		compose.runOnIdle { query.value = "" }
		compose.onNodeWithText(context.getString(R.string.chapter_search_notes)).performTextInput("missing note")
		compose.onNodeWithText(context.getString(R.string.chapter_search_notes_empty)).assertExists()
		compose.onNodeWithText("Ported chapter").assertDoesNotExist()
		compose.onNodeWithText(context.getString(R.string.chapter_search_notes)).performTextClearance()
		compose.onNodeWithText("Ported chapter").assertExists()
		compose.onNodeWithContentDescription(collapse).performClick()
		assertEquals("", query.value)
		compose.onNodeWithContentDescription(expand).performClick()
		compose.onNodeWithContentDescription(context.getString(R.string.chapter_search_notes_exit)).performClick()
		assertEquals(null, query.value)
		compose.onNodeWithText("Ported chapter").assertExists()
		assertEquals(ChapterPersonalMetadata(5, "Peak encounter"), row.personalMetadata)
	}

	private fun setContent(state: DetailsPeopleUiState) {
		compose.setContent { MaterialTheme { TrackerPeopleSection(state, loader, {}, {}) } }
	}
	private fun provider(characters: TrackerResult<TrackerPerson>, staff: TrackerResult<TrackerPerson>) = AssociatedTrackerDetails(
		service, TrackerTarget(service, "42"), TrackerDetailsReadState.LOADED, characters, staff,
	)
}
