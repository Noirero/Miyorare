package org.koitharu.kotatsu.settings.sources

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.koitharu.kotatsu.R
import coil3.ImageLoader
import org.koitharu.kotatsu.tsuki.model.TsukiSourceDescriptor
import org.koitharu.kotatsu.kotatsu.model.KotatsuMangaSource

class KotatsuSourceManagementScreenTest {

	@get:Rule val compose = createComposeRule()

	@Test
	fun disabledSourceCanBrowseWithoutEnablingAndRowActionsAreIndependent() {
		val context = InstrumentationRegistry.getInstrumentation().targetContext
		val source = KotatsuMangaSource("FIXTURE", "Fixture Shelf", "en", "MANGA")
		val actions = mutableListOf<String>()
		compose.setContent {
			MaterialTheme {
				KotatsuSourcesScreen(
					sources = listOf(source),
					enabledSourceKeys = emptySet(),
					nsfwDisabled = false,
					onSourceEnabled = { key, enabled -> actions += "switch:$key:$enabled" },
					onSourceOpen = { actions += "open:${it.sourceKey}" },
					onSourceSettings = { actions += "settings:${it.sourceKey}" },
					loadWebsiteIcon = { null },
				)
			}
		}

		val toggle = compose.onNodeWithContentDescription(context.getString(R.string.kotatsu_source_enable_named, source.displayName))
		compose.onNodeWithText(source.displayName).performClick()
		toggle.assertIsOff()
		compose.onNodeWithContentDescription(context.getString(R.string.kotatsu_source_settings_named, source.displayName))
			.performClick()
		toggle.performClick()
		compose.runOnIdle {
			assertEquals(listOf("open:FIXTURE", "settings:FIXTURE", "switch:FIXTURE:true"), actions)
		}
	}

	@Test
	fun umaRowBrowseSettingsAndSwitchAreIndependent() {
		val context = InstrumentationRegistry.getInstrumentation().targetContext
		val title = "UMA Fixture"
		val actions = mutableListOf<String>()
		compose.setContent {
			MaterialTheme {
				UmaSourceRow(
					title = title,
					subtitle = "EN · MANGA",
					checked = false,
					switchEnabled = true,
					onBrowse = { actions += "browse" },
					onSettings = { actions += "settings" },
					onCheckedChange = { actions += "switch:$it" },
					leading = {},
				)
			}
		}
		val toggle = compose.onNodeWithContentDescription(context.getString(R.string.kotatsu_source_enable_named, title))
		compose.onNodeWithText(title).performClick()
		toggle.assertIsOff()
		compose.onNodeWithContentDescription(context.getString(R.string.kotatsu_source_settings_named, title)).performClick()
		toggle.performClick()
		compose.runOnIdle { assertEquals(listOf("browse", "settings", "switch:true"), actions) }
	}

	@Test
	fun packRowBrowseSettingsAndSwitchAreIndependent() {
		val context = InstrumentationRegistry.getInstrumentation().targetContext
		val title = "Pack Fixture"
		val source = TsukiSourceDescriptor("PACK_FIXTURE", title, "en", "MANGA")
		val actions = mutableListOf<String>()
		val imageLoader = ImageLoader.Builder(context).build()
		try {
			compose.setContent {
				MaterialTheme {
					MiyorareSourceSwitchItem(
						title = title, subtitle = "EN · MANGA", checked = false, enabled = true,
						onCheckedChange = { actions += "switch:$it" },
						onRowClick = { actions += "browse" },
						onSettings = { actions += "settings" },
						source = source, imageLoader = imageLoader,
					)
				}
			}
			val toggle = compose.onNodeWithContentDescription(context.getString(R.string.kotatsu_source_enable_named, title))
			compose.onNodeWithText(title).performClick()
			toggle.assertIsOff()
			compose.onNodeWithContentDescription(context.getString(R.string.kotatsu_source_settings_named, title)).performClick()
			toggle.performClick()
			compose.runOnIdle { assertEquals(listOf("browse", "settings", "switch:true"), actions) }
		} finally {
			imageLoader.shutdown()
		}
	}
	@Test
	fun brokenPackRowKeepsDiagnosticActionAndDisablesSettingsAndSwitch() {
		val context = InstrumentationRegistry.getInstrumentation().targetContext
		val title = "Broken Pack Fixture"
		val source = TsukiSourceDescriptor("BROKEN_PACK", title, "en", "MANGA", isBroken = true)
		val actions = mutableListOf<String>()
		val imageLoader = ImageLoader.Builder(context).build()
		try {
			compose.setContent {
				MaterialTheme {
					MiyorareSourceSwitchItem(
						title = title, subtitle = "Unavailable", checked = false, enabled = false,
						onCheckedChange = { actions += "switch:$it" },
						onRowClick = { actions += "diagnostic" },
						onSettings = null, source = source, imageLoader = imageLoader,
					)
				}
			}
			val toggle = compose.onNodeWithContentDescription(context.getString(R.string.kotatsu_source_enable_named, title))
			val settings = compose.onNodeWithContentDescription(context.getString(R.string.kotatsu_source_settings_named, title))
			toggle.assertIsNotEnabled().assertIsOff()
			settings.assertIsNotEnabled()
			// Touch input must not invoke either disabled control or the diagnostic row.
			toggle.performTouchInput { click() }
			settings.performTouchInput { click() }
			compose.runOnIdle { assertEquals(emptyList<String>(), actions) }
			compose.onNodeWithText(title).performClick()
			compose.runOnIdle { assertEquals(listOf("diagnostic"), actions) }
		} finally {
			imageLoader.shutdown()
		}
	}
}
