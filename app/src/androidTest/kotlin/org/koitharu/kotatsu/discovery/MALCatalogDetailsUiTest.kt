package org.koitharu.kotatsu.discovery

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.platform.app.InstrumentationRegistry
import coil3.ImageLoader
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.discovery.domain.CatalogMediaKind
import org.koitharu.kotatsu.discovery.domain.CatalogRelatedMediaKind
import org.koitharu.kotatsu.discovery.domain.TrackerCatalogCharacterDetails
import org.koitharu.kotatsu.discovery.domain.TrackerCatalogCharacterWork
import org.koitharu.kotatsu.discovery.domain.TrackerCatalogDetails
import org.koitharu.kotatsu.discovery.domain.TrackerCatalogItem
import org.koitharu.kotatsu.discovery.domain.TrackerCatalogKey
import org.koitharu.kotatsu.discovery.domain.TrackerCatalogPerson
import org.koitharu.kotatsu.discovery.domain.TrackerCatalogScoreDistribution
import org.koitharu.kotatsu.discovery.domain.TrackerCatalogStatistics
import org.koitharu.kotatsu.discovery.ui.CatalogSupplementalDetails
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService

class MALCatalogDetailsUiTest {
	@get:Rule val compose = createComposeRule()
	private val context = InstrumentationRegistry.getInstrumentation().targetContext
	private val loader = ImageLoader.Builder(context).build()

	@After
	fun close() {
		loader.shutdown()
	}

	@Test
	fun statisticsAreCollapsedByDefaultAndExpandOnDemand() {
		compose.setContent {
			MaterialTheme { CatalogSupplementalDetails(details(), loader) {} }
		}

		compose.onNodeWithTag("catalog_statistics").assertExists()
		compose.onNodeWithTag("catalog_statistics_content").assertDoesNotExist()
		compose.onNodeWithTag("catalog_statistics_toggle").performClick()
		compose.onNodeWithTag("catalog_statistics_content").assertExists()
		compose.onNodeWithText(context.getString(R.string.hub_reading)).assertExists()
		compose.onNodeWithText(context.getString(R.string.hub_score_distribution)).assertExists()
		compose.onNodeWithTag("catalog_statistics_toggle").performClick()
		compose.onNodeWithTag("catalog_statistics_content").assertDoesNotExist()
	}

	@Test
	fun MALCharactersPreviewTenAndViewAllExposesTheFullReturnedList() {
		compose.setContent {
			MaterialTheme { CatalogSupplementalDetails(details(), loader) {} }
		}

		compose.onNodeWithText("Character 11").assertDoesNotExist()
		compose.onNodeWithTag("catalog_characters_view_all").performClick()
		compose.onNodeWithText(context.getString(R.string.hub_show_less)).assertExists()
		compose.onNodeWithTag("catalog_characters_list").performScrollToNode(hasText("Character 12"))
		compose.onNodeWithText("Character 12").assertExists()
	}

	@Test
	fun MALCharacterCardOpensInternalProfileWithLoadedDetails() {
		compose.setContent {
			MaterialTheme {
				CatalogSupplementalDetails(
					details = details(),
					imageLoader = loader,
					onItem = {},
					loadCharacterDetails = { person ->
						TrackerCatalogCharacterDetails(
							person = person,
							nativeName = "Native Character Name",
							nicknames = listOf("Nickname"),
							about = "Character biography",
							favorites = 42,
							voices = listOf(TrackerCatalogPerson(id = "99", name = "Voice Actor", roles = listOf("Japanese"))),
							works = listOf(
								TrackerCatalogCharacterWork(
									service = ScrobblerService.MAL,
									remoteId = "13",
									title = "Work",
									mediaKind = CatalogRelatedMediaKind.MANGA,
									role = "Main",
								),
							),
						)
					},
				)
			}
		}

		compose.onNodeWithTag("catalog_characters_person_1").performClick()
		compose.onNodeWithTag("catalog_character_details").assertExists()
		compose.onNodeWithText("Native Character Name").assertExists()
		compose.onNodeWithText("Character biography").assertExists()
		compose.onNodeWithText("Voice Actor").assertExists()
		compose.onNodeWithTag("catalog_character_works").assertExists()
	}

	private fun details(): TrackerCatalogDetails {
		val key = TrackerCatalogKey(ScrobblerService.MAL, CatalogMediaKind.MANGA, "13")
		return TrackerCatalogDetails(
			item = TrackerCatalogItem(key, "Work"),
			characters = (1..12).map { index ->
				TrackerCatalogPerson(
					id = index.toString(),
					name = "Character $index",
					imageUrl = "https://cdn.myanimelist.net/images/characters/$index.jpg",
					roles = listOf(if (index == 1) "Main" else "Supporting"),
				)
			},
			statistics = TrackerCatalogStatistics(
				reading = 120,
				completed = 340,
				onHold = 12,
				dropped = 9,
				planToRead = 800,
				total = 1281,
				scores = listOf(TrackerCatalogScoreDistribution(10, 200, 40.5)),
			),
		)
	}
}
