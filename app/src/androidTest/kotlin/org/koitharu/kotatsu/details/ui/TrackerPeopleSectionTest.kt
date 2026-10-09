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
import androidx.test.platform.app.InstrumentationRegistry
import coil3.ImageLoader
import coil3.intercept.Interceptor
import coil3.request.ImageResult
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.koitharu.kotatsu.R
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

	private fun setContent(state: DetailsPeopleUiState) {
		compose.setContent { MaterialTheme { TrackerPeopleSection(state, loader, {}, {}) } }
	}
	private fun provider(characters: TrackerResult<TrackerPerson>, staff: TrackerResult<TrackerPerson>) = AssociatedTrackerDetails(
		service, TrackerTarget(service, "42"), TrackerDetailsReadState.LOADED, characters, staff,
	)
}
