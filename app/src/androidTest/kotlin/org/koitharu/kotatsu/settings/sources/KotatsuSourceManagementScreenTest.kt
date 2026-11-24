package org.koitharu.kotatsu.settings.sources

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.koitharu.kotatsu.R
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
}
