package org.koitharu.kotatsu.settings.about

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.koitharu.kotatsu.R

class AboutProjectCommunityTest {
	@get:Rule val compose = createComposeRule()

	@Test fun iconActionsRemainReachableAndOpenDistinctDestinationsAtLargeFontScale() {
		val context = InstrumentationRegistry.getInstrumentation().targetContext
		val opened = mutableListOf<Pair<Int, Int>>()
		compose.setContent {
			MaterialTheme {
				val density = LocalDensity.current
				CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
					Column(Modifier.width(320.dp).verticalScroll(rememberScrollState())) {
						AboutProjectCommunity { url, project -> opened += url to project }
					}
				}
			}
		}
		fun click(project: Int, label: Int) {
			val description = context.getString(
				R.string.about_project_link_description,
				context.getString(project),
				context.getString(label),
			)
			compose.onNodeWithContentDescription(description)
				.performScrollTo()
				.assertIsDisplayed()
				.performClick()
		}
		click(R.string.about_dropsauce_name, R.string.about_github_label)
		click(R.string.about_dropsauce_name, R.string.discord)
		click(R.string.about_kotatsu_name, R.string.about_github_kotatsu_label)
		click(R.string.about_kotatsu_name, R.string.about_github_kotatsu_redo_label)
		click(R.string.about_kotatsu_name, R.string.about_discord_kotatsu_redo_label)
		click(R.string.about_tsuki_uma_gekkoushi_name, R.string.about_github_uma_label)
		click(R.string.about_tsuki_uma_gekkoushi_name, R.string.about_github_gekkoushi_label)
		compose.runOnIdle {
			assertEquals(
				listOf(
					R.string.about_url_dropsauce_github to R.string.about_dropsauce_name,
					R.string.about_url_dropsauce_discord to R.string.about_dropsauce_name,
					R.string.about_url_kotatsu_github to R.string.about_kotatsu_name,
					R.string.about_url_kotatsu_redo_github to R.string.about_kotatsu_name,
					R.string.about_url_kotatsu_redo_discord to R.string.about_kotatsu_name,
					R.string.about_url_uma_github to R.string.about_tsuki_uma_gekkoushi_name,
					R.string.about_url_gekkoushi_github to R.string.about_tsuki_uma_gekkoushi_name,
				),
				opened,
			)
		}
	}
}
