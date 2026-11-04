package org.koitharu.kotatsu.details.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
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
import androidx.test.platform.app.InstrumentationRegistry
import coil3.ImageLoader
import coil3.intercept.Interceptor
import coil3.request.ImageResult
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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


    @Test fun compactHeroEyeShowsSourceAndCompressesStatus() =
        verifySourceStatusInteraction(centered = false, compactExpanded = false, width = 220)

    @Test fun centeredHeroEyeShowsSourceAndCompressesStatus() =
        verifySourceStatusInteraction(centered = true, compactExpanded = false, width = 320)

    @Test fun expandedCompactHeroEyeShowsSourceAndCompressesStatus() =
        verifySourceStatusInteraction(centered = false, compactExpanded = true, width = 220)

    private fun verifySourceStatusInteraction(centered: Boolean, compactExpanded: Boolean, width: Int) {
        val sourceName = "Komik manga indonesia"
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
                    Box(androidx.compose.ui.Modifier.width(width.dp)) {
                        HeroSourceStatusRow(
                            manga = mangaState.value,
                            sourceTitle = sourceName,
                            imageLoader = loader,
                            status = renderedStatus,
                            showActiveRelease = true,
                            accent = colors.colorScheme.primary,
                            centered = centered,
                            compactExpanded = compactExpanded,
                            onSourceClick = { sourceNavigations++ },
                        )
                    }
                }
            }
        }

        val sourceText = compose.onNodeWithTag("hero-source-title", useUnmergedTree = true)
        val sourceContent = compose.onNodeWithTag("hero-source-content")
        val statusCard = compose.onNodeWithTag("hero-status-card")
        val toggle = compose.onNodeWithTag("hero-source-toggle")
        val showLabel = context.getString(R.string.details_show_source)
        val hideLabel = context.getString(R.string.details_hide_source)
        val releaseText = context.getString(R.string.details_release_active)

        fun assertHiddenWithNormalStatus() {
            compose.onNodeWithContentDescription(showLabel).assertIsDisplayed()
            compose.onNodeWithTag("hero-source-eye-off", useUnmergedTree = true).assertExists()
            compose.onNodeWithTag("hero-source-eye-open", useUnmergedTree = true).assertDoesNotExist()
            compose.onNodeWithText(context.getString(R.string.details_source), useUnmergedTree = true).assertIsDisplayed()
            sourceText.assertTextEquals("...")
            compose.onNodeWithTag("hero-source-favicon", useUnmergedTree = true).assertDoesNotExist()
            compose.onNodeWithText(sourceName, useUnmergedTree = true).assertDoesNotExist()
            statusCard.assertIsDisplayed()
            compose.onNodeWithText(context.getString(R.string.status), useUnmergedTree = true).assertIsDisplayed()
            compose.onNodeWithText(renderedStatus, useUnmergedTree = true).assertIsDisplayed()
            compose.onNodeWithText(releaseText, useUnmergedTree = true).assertIsDisplayed()
            compose.onNodeWithTag("hero-status-icon", useUnmergedTree = true).assertIsDisplayed()
        }

        assertHiddenWithNormalStatus()
        val normalSourceWidth = sourceContent.fetchSemanticsNode().boundsInRoot.width
        val normalStatusWidth = statusCard.fetchSemanticsNode().boundsInRoot.width

        toggle.performClick()
        compose.onNodeWithContentDescription(hideLabel).assertIsDisplayed()
        compose.onNodeWithTag("hero-source-eye-open", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("hero-source-eye-off", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("hero-source-favicon", useUnmergedTree = true).assertIsDisplayed()
        sourceText.assertTextEquals(sourceName)
        assertTextOverflow(sourceText, expected = false)
        statusCard.assertIsDisplayed()
        compose.onNodeWithTag("hero-status-icon", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.status), useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText(renderedStatus, useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText(releaseText, useUnmergedTree = true).assertDoesNotExist()
        assertTrue("Opened Source must get more width",
            sourceContent.fetchSemanticsNode().boundsInRoot.width > normalSourceWidth)
        assertTrue("Status must be narrower, not removed",
            statusCard.fetchSemanticsNode().boundsInRoot.width < normalStatusWidth)
        val statusAccessibility = statusCard.fetchSemanticsNode()
            .config[SemanticsProperties.ContentDescription].joinToString(" ")
        assertTrue("Icon-only Status must announce its value", statusAccessibility.contains(renderedStatus))
        compose.runOnIdle { assertEquals("Eye click must not navigate", 0, sourceNavigations) }

        sourceContent.performClick()
        compose.runOnIdle { assertEquals("Source content navigates once", 1, sourceNavigations) }

        toggle.performClick()
        assertHiddenWithNormalStatus()
        assertEquals("Closing eye restores main Source sizing", normalSourceWidth,
            sourceContent.fetchSemanticsNode().boundsInRoot.width, 1f)
        assertEquals("Closing eye restores full Status sizing", normalStatusWidth,
            statusCard.fetchSemanticsNode().boundsInRoot.width, 1f)
        compose.runOnIdle { assertEquals(1, sourceNavigations) }

        toggle.performClick()
        compose.runOnIdle { mangaState.value = manga.copy(source = MangaSource("MIHON_424243")) }
        assertHiddenWithNormalStatus()
        toggle.performClick()
        compose.runOnIdle { mangaState.value = mangaState.value.copy(id = manga.id + 1) }
        assertHiddenWithNormalStatus()
    }

    private fun assertTextOverflow(node: SemanticsNodeInteraction, expected: Boolean) {
        val results = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        assertEquals("Unexpected hero text clipping/overflow", expected, results.single().hasVisualOverflow)
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
