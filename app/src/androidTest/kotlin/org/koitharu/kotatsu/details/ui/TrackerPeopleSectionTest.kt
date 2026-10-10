package org.koitharu.kotatsu.details.ui

import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodes
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
		val chapter = SampleData.chapter.copy(id = 531L, title = "Ported chapter", number = 1f, uploadDate = 0L)
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
					details = MangaDetails(SampleData.mangaDetails.copy(chapters = listOf(chapter)), null, null, "Fixture description", true),
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
