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
	fun explicitSelectionAndSearchControlTheBottomAction() {
		var current by mutableStateOf(state())
		var continued = 0
		compose.setContent {
			CompositionLocalProvider(LocalContext provides themedContext) {
				MaterialTheme {
					BrokenSourcesMigrationScreen(
					state = current, imageLoader = loader,
					onToggleManga = { id ->
						current = current.copy(selectedMangaIds = MigrationSelectionPolicy.toggle(
							current.selectedMangaIds, current.manga.mapTo(hashSetOf()) { it.id }, id))
					},
					onSelectManga = { ids -> current = current.copy(selectedMangaIds = ids.toSet()) },
					onRetry = {}, onContinue = { continued++ },
					)
				}
			}
		}
		compose.onNodeWithText("Lanjut ke pencarian").assertIsNotEnabled()
		compose.onNodeWithText("Story 1").performClick()
		compose.onNodeWithText("Lanjut ke pencarian").assertIsEnabled().performClick()
		assertEquals(1, continued)
		compose.onNodeWithText("Filter source (1)").performClick()
		compose.onNodeWithText("E-Hentai (2)").assertExists()
		compose.onNodeWithText("E-Hentai (2)").performClick()
		compose.onNodeWithText("E-Hentai (JA)").assertExists()
	}

	@Test
	fun searchFiltersMangaWithoutChangingSelectedIds() {
		var current by mutableStateOf(state().copy(selectedMangaIds = setOf(1L)))
		compose.setContent {
			CompositionLocalProvider(LocalContext provides themedContext) {
				MaterialTheme {
					BrokenSourcesMigrationScreen(
					state = current, imageLoader = loader,
					onToggleManga = {},
					onSelectManga = { ids -> current = current.copy(selectedMangaIds = ids.toSet()) },
					onRetry = {}, onContinue = {},
					)
				}
			}
		}
		compose.onNodeWithTag("migration-landing-search").performTextInput("Story 2")
		compose.onNodeWithText("Story 1").assertDoesNotExist()
		compose.onNode(hasText("Story 2") and hasText("Belum diperiksa dalam sesi ini")).assertExists()
		compose.onNodeWithText("1 manga dipilih").assertExists()
	}
}
