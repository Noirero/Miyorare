package org.koitharu.kotatsu.settings.sources.migration

import android.view.ContextThemeWrapper
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.platform.LocalContext
import androidx.test.platform.app.InstrumentationRegistry
import coil3.ImageLoader
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.MangaSource
import org.koitharu.kotatsu.parsers.model.Manga

class MigrationLandingScreenTest {
	@get:Rule val compose = createComposeRule()
	private val context = InstrumentationRegistry.getInstrumentation().targetContext
	private val loader = ImageLoader.Builder(context).build()
	private val themedContext = ContextThemeWrapper(context, R.style.Theme_Kotatsu)

	@After fun close() { loader.shutdown() }

	private fun manga(id: Long, sourceName: String) = Manga(
		id = id, title = "Story $id", altTitles = emptySet(), url = "/$id",
		publicUrl = "https://fixture.invalid/$id", rating = 0f,
		contentRating = null, coverUrl = null, tags = emptySet(),
		state = null, authors = emptySet(), source = MangaSource(sourceName),
	)

	private fun state() = BrokenSourcesMigrationState(
		isLoading = false,
		sources = listOf(
			LibrarySourceOption(
				key = "family:example", canonicalKey = "family:example",
				sourceKeys = setOf("SOURCE_EN", "SOURCE_JA"),
				title = "E-Hentai (EN)", mangaCount = 2, isUnavailable = true,
				iconSourceKey = "SOURCE_EN", iconUrl = null,
				variants = listOf(
					LibrarySourceVariant("SOURCE_EN", "E-Hentai (EN)", 1),
					LibrarySourceVariant("SOURCE_JA", "E-Hentai (JA)", 1),
				),
			),
		),
		manga = listOf(manga(1L, "SOURCE_EN"), manga(2L, "SOURCE_JA")),
	)

	@Test
	fun sourceFirstNavigationOpensASelectedManga() {
		var opened = emptyList<Long>()
		compose.setContent {
			CompositionLocalProvider(LocalContext provides themedContext) {
				MaterialTheme {
					BrokenSourcesMigrationScreen(
						state = state(), imageLoader = loader,
						onRetry = {}, onOpenManga = { opened = it.toList() },
					)
				}
			}
		}
		compose.onNodeWithText("Pilih source").assertExists()
		compose.onNodeWithText("E-Hentai (EN)").performClick()
		compose.onNodeWithText("Story 1").performClick()
		assertEquals(listOf(1L), opened)
	}

	@Test
	fun sourceAllAndSearchUseOnlyTheExactSourceIdentity() {
		var opened = emptyList<Long>()
		compose.setContent {
			CompositionLocalProvider(LocalContext provides themedContext) {
				MaterialTheme {
					BrokenSourcesMigrationScreen(
						state = state(), imageLoader = loader,
						onRetry = {}, onOpenManga = { opened = it.toList() },
					)
				}
			}
		}
		compose.onNodeWithText("E-Hentai (JA)").performClick()
		compose.onNodeWithText("Story 2").assertExists()
		compose.onNodeWithText("Story 1").assertDoesNotExist()
		compose.onNodeWithTag("migration-landing-search").performTextInput("Nothing")
		compose.onNodeWithText("Story 2").assertDoesNotExist()
		compose.onNodeWithText("Migrasikan semua (1)").performClick()
		assertEquals(listOf(2L), opened)
	}

}
