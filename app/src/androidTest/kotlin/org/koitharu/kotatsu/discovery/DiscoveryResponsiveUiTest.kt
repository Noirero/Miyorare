package org.koitharu.kotatsu.discovery

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import coil3.ImageLoader
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.koitharu.kotatsu.discovery.domain.CatalogFilter
import org.koitharu.kotatsu.discovery.domain.CatalogHomeRequest
import org.koitharu.kotatsu.discovery.domain.CatalogMediaKind
import org.koitharu.kotatsu.discovery.domain.CatalogSectionKey
import org.koitharu.kotatsu.discovery.domain.CatalogSectionType
import org.koitharu.kotatsu.discovery.domain.DiscoveryHubState
import org.koitharu.kotatsu.discovery.domain.TrackerCatalogHome
import org.koitharu.kotatsu.discovery.domain.TrackerCatalogItem
import org.koitharu.kotatsu.discovery.domain.TrackerCatalogKey
import org.koitharu.kotatsu.discovery.domain.TrackerCatalogSection
import org.koitharu.kotatsu.discovery.ui.DiscoveryHubScreen
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService

class DiscoveryResponsiveUiTest {
	@get:Rule val compose = createComposeRule()
	private val context = InstrumentationRegistry.getInstrumentation().targetContext
	private val loader = ImageLoader.Builder(context).build()

	@After fun close() { loader.shutdown() }

	@Test fun narrowPhoneKeepsPrimaryHomeControlsAtLargeFontScales() {
		val fontScale = mutableFloatStateOf(1.3f)
		val service = ScrobblerService.MAL
		val section = CatalogSectionKey(service, CatalogMediaKind.MANGA, CatalogSectionType.TOP_RANKED)
		val items = (1..5).map { id ->
			TrackerCatalogItem(
				key = TrackerCatalogKey(service, CatalogMediaKind.MANGA, id.toString()),
				title = "A deliberately long catalogue title that must remain safely bounded $id",
				score = 8.2,
				adult = false,
			)
		}
		val state = DiscoveryHubState(
			request = CatalogHomeRequest(service, CatalogFilter.ALL, allowAdult = false),
			home = TrackerCatalogHome(listOf(TrackerCatalogSection(section, items))),
			loading = false,
		)

		compose.setContent {
			MaterialTheme {
				val density = LocalDensity.current
				CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale.floatValue)) {
					Box(Modifier.width(320.dp).height(900.dp)) {
						DiscoveryHubScreen(
							state = state,
							services = listOf(ScrobblerService.ANILIST, ScrobblerService.MAL, ScrobblerService.KITSU),
							imageLoader = loader,
							onProvider = {},
							onFilter = {},
							onRefresh = {},
							onSearch = {},
							onItem = {},
							onSection = {},
							onAttribution = {},
							scrollRequests = MutableSharedFlow(),
							showLocalSearch = false,
						)
					}
				}
			}
		}

		assertResponsiveHome()
		compose.runOnIdle { fontScale.floatValue = 1.5f }
		compose.waitForIdle()
		assertResponsiveHome()
	}

	private fun assertResponsiveHome() {
		compose.onNodeWithTag("catalog_provider_selector").assertExists().assertHasClickAction()
		compose.onNodeWithTag("catalog_refresh").assertExists().assertHasClickAction()
		compose.onNodeWithTag("catalog_filter_ALL").assertExists().assertIsSelected().assertHasClickAction()
		compose.onNodeWithTag("catalog_spotlight").assertExists().assertHasClickAction()
		compose.onNodeWithTag("catalog_card_MAL_MANGA_2").assertExists().assertHasClickAction()
		compose.onNodeWithTag("catalog_search_action").assertDoesNotExist()
	}
}
