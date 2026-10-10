package org.koitharu.kotatsu.details.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import coil3.ImageLoader
import coil3.intercept.Interceptor
import coil3.request.ImageResult
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.scrobbling.common.domain.model.*
import java.io.IOException

class TrackerRecommendationSectionTest {
	@get:Rule val compose = createComposeRule()
	private val context = InstrumentationRegistry.getInstrumentation().targetContext
	private val loader = ImageLoader.Builder(context).components {
		add(object : Interceptor { override suspend fun intercept(chain: Interceptor.Chain): ImageResult = throw IOException("Fixture-only cover loader") })
	}.build()
	private val item = TrackerRecommendation(TrackerTarget(ScrobblerService.ANILIST, "7", "https://anilist.co/manga/7"), "Real recommendation title", "https://image.invalid/cover", TrackerRecommendationKind.RECOMMENDATION)
	private fun provider(service: ScrobblerService, result: TrackerResult<TrackerRecommendation>) = AssociatedTrackerDetails(service, TrackerTarget(service, "42"), TrackerDetailsReadState.LOADED, recommendations = result)
	@After fun close() { loader.shutdown() }

	@Test fun realTitleAndProviderIdentityReachNavigationOnlyAfterExplicitClick() {
		var clicked: TrackerRecommendation? = null
		var external: TrackerRecommendation? = null
		compose.setContent { MaterialTheme { TrackerRecommendationSection(DetailsPeopleUiState(providers = listOf(provider(item.target.service, TrackerResult.Success(listOf(item))))), loader, { clicked = it }, { external = it }, {}, {}) } }
		compose.onNodeWithText(item.title).assertExists()
		assertNull(clicked); assertNull(external)
		compose.onNodeWithTag("tracker-recommendation:ANILIST:7").performClick()
		assertEquals(item, clicked)
		assertNull(external)
		compose.onNodeWithText(context.getString(R.string.tracker_provider_page)).performClick()
		assertEquals(item, external)
	}

	@Test fun equalTitlesRemainInSeparateProviderSectionsAndSimilarDataIsIdentified() {
		val similar = item.copy(target = TrackerTarget(ScrobblerService.SHIKIMORI, "7"), kind = TrackerRecommendationKind.SIMILAR_MANGA)
		compose.setContent { MaterialTheme { TrackerRecommendationSection(DetailsPeopleUiState(providers = listOf(provider(item.target.service, TrackerResult.Success(listOf(item))), provider(similar.target.service, TrackerResult.Success(listOf(similar))))), loader, {}, {}, {}, {}) } }
		compose.onNodeWithTag("tracker-recommendations:ANILIST:RECOMMENDATION").assertExists()
		compose.onNodeWithTag("tracker-recommendations:SHIKIMORI:SIMILAR_MANGA").assertExists()
	}

	@Test fun unsupportedAndEmptyProvidersHaveNoSection() {
		compose.setContent { MaterialTheme { TrackerRecommendationSection(DetailsPeopleUiState(providers = listOf(provider(ScrobblerService.KITSU, TrackerResult.Unsupported), provider(ScrobblerService.ANILIST, TrackerResult.Empty()))), loader, {}, {}, {}, {}) } }
		compose.onNodeWithTag("tracker-recommendations:ANILIST:RECOMMENDATION").assertDoesNotExist()
		compose.onNodeWithTag("tracker-recommendations:KITSU:RECOMMENDATION").assertDoesNotExist()
	}

	@Test fun failureRetriesOnlyThatProviderAndDoesNotReplaceAvailableContent() {
		var retry: ScrobblerService? = null
		compose.setContent { MaterialTheme { TrackerRecommendationSection(DetailsPeopleUiState(providers = listOf(provider(item.target.service, TrackerResult.Success(listOf(item))), provider(ScrobblerService.MAL, TrackerResult.Error(IOException("private fixture error"))))), loader, {}, {}, { retry = it }, {}) } }
		compose.onNodeWithText(item.title).assertExists()
		compose.onNodeWithText("private fixture error").assertDoesNotExist()
		compose.onNodeWithText(context.getString(R.string.tracker_people_retry)).performClick()
		assertEquals(ScrobblerService.MAL, retry)
	}

	@Test fun loadingAndPrivacyRemovalKeepCoreChaptersUsable() {
		val state = mutableStateOf(DetailsPeopleUiState(isLoading = true))
		compose.setContent { MaterialTheme { Column { Text("Core chapters"); TrackerRecommendationSection(state.value, loader, {}, {}, {}, {}) } } }
		compose.onNodeWithTag("tracker-recommendations-loading").assertExists()
		compose.onNodeWithText("Core chapters").assertExists()
		compose.runOnIdle { state.value = DetailsPeopleUiState(providers = listOf(provider(item.target.service, TrackerResult.Success(listOf(item))))) }
		compose.onNodeWithText(item.title).assertExists()
		compose.runOnIdle { state.value = DetailsPeopleUiState() }
		compose.onNodeWithText(item.title).assertDoesNotExist()
		compose.onNodeWithText("Core chapters").assertExists()
	}
}
