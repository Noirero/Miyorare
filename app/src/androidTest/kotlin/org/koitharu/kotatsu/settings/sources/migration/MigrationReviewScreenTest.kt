package org.koitharu.kotatsu.settings.sources.migration

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import coil3.ImageLoader
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.alternatives.domain.*
import org.koitharu.kotatsu.core.model.MangaSource
import org.koitharu.kotatsu.parsers.model.Manga

class MigrationReviewScreenTest {
	@get:Rule val compose = createComposeRule()
	private val context = InstrumentationRegistry.getInstrumentation().targetContext
	private val imageLoader by lazy { ImageLoader.Builder(context).build() }
	private val seed = manga(1, "OLD")
	private val candidate = manga(2, "NEW")
	private val sourceA = MangaSource("SOURCE_A")
	private val sourceB = MangaSource("SOURCE_B")

	private fun manga(id: Long, source: String, coverUrl: String? = null) = Manga(
		id = id, title = "Shared Story", altTitles = emptySet(), url = "/$id", publicUrl = "https://example.org/$id",
		rating = 0f, contentRating = null, coverUrl = coverUrl, tags = emptySet(), state = null,
		authors = setOf("Writer"), source = MangaSource(source), chapters = null, description = null,
	)

	private fun assessment(review: Boolean = true): MigrationMatchAssessment {
		val next = if (review) candidate.copy(authors = emptySet()) else candidate
		val assessed = MigrationCandidateMatcher().assess(seed, listOf(MigrationCandidateObservation(next, setOf("Shared Story"))))
		return if (review) assessed.copy(state = MigrationMatchState.NEEDS_REVIEW) else assessed
	}

	@Test fun initialScreenWaitsForExplicitUserChoice() {
		var checks = 0
		var direct = 0
		compose.setContent { MaterialTheme { MigrationReviewScreen(
			MigrationReviewState(loading = false, discovery = MigrationDiscovery(seed)),
			imageLoader,
			onRetry = { checks++ },
			onMigrate = { _, _ -> fail() },
			onOpenResult = {},
			onOpenCandidate = {},
			onManualSearch = { direct++ },
		) } }
		compose.onNodeWithTag("migration-loading").assertDoesNotExist()
		compose.onNodeWithTag("migration-check-current").assertIsEnabled().performClick()
		compose.onNodeWithTag("migration-direct-search").assertIsEnabled().performClick()
		assertEquals(1, checks)
		assertEquals(1, direct)
	}

	@Test fun directSourcePickerRequiresExplicitSourceSelectionBeforeSearch() {
		var searches = 0
		compose.setContent { MaterialTheme { MigrationReviewScreen(
			state = MigrationReviewState(
				loading = false,
				discovery = MigrationDiscovery(seed),
				manualSearchEnabled = true,
				availableSources = listOf(sourceA, sourceB),
			),
			imageLoader = imageLoader,
			onRetry = {},
			onMigrate = { _, _ -> fail() },
			onOpenResult = {},
			onOpenCandidate = {},
			onSearch = { searches++ },
		) } }
		compose.onNodeWithText(context.getString(R.string.migration_direct_choose_sources)).assertExists()
		compose.onNodeWithTag("migration-source-list").assertExists()
		compose.onNodeWithTag("migration-search").assertIsNotEnabled()
		compose.onNodeWithTag("migration-loading").assertDoesNotExist()
		assertEquals(0, searches)
	}

	@Test fun selectedSourcesEnableSearchAndSelectAllUsesVisibleSourceSet() {
		var searched = 0
		var selectedNames: Set<String>? = null
		var selectedValue: Boolean? = null
		compose.setContent { MaterialTheme { MigrationReviewScreen(
			state = MigrationReviewState(
				loading = false,
				discovery = MigrationDiscovery(seed),
				manualSearchEnabled = true,
				availableSources = listOf(sourceA, sourceB),
				selectedSourceNames = setOf(sourceA.name),
			),
			imageLoader = imageLoader,
			onRetry = {},
			onMigrate = { _, _ -> fail() },
			onOpenResult = {},
			onOpenCandidate = {},
			onSearch = { searched++ },
			onSetSourcesSelected = { names, selected -> selectedNames = names; selectedValue = selected },
		) } }
		compose.onNodeWithTag("migration-search").assertIsEnabled().performClick()
		assertEquals(1, searched)
		compose.onNodeWithTag("migration-select-all-visible").performClick()
		assertEquals(setOf(sourceA.name, sourceB.name), selectedNames)
		assertEquals(true, selectedValue)
	}

	@Test fun advancedOptionsStayCollapsedUntilRequested() {
		compose.setContent { MaterialTheme { MigrationReviewScreen(
			state = MigrationReviewState(
				loading = false,
				discovery = MigrationDiscovery(seed),
				manualSearchEnabled = true,
				availableSources = listOf(sourceA),
			),
			imageLoader = imageLoader,
			onRetry = {},
			onMigrate = { _, _ -> fail() },
			onOpenResult = {},
			onOpenCandidate = {},
		) } }
		compose.onNodeWithText(context.getString(R.string.migration_title_cleaned)).assertDoesNotExist()
		compose.onNodeWithTag("migration-advanced-toggle").performClick()
		compose.onNodeWithText(context.getString(R.string.migration_title_cleaned)).assertExists()
		compose.onNodeWithText(context.getString(R.string.migration_preserve_all_fixed)).assertExists()
	}

	@Test fun checkedUnavailableAlsoUsesExplicitSourcePicker() {
		compose.setContent { MaterialTheme { MigrationReviewScreen(
			state = MigrationReviewState(
				loading = false,
				currentSourceChecked = true,
				discovery = MigrationDiscovery(seed, sourceHealthy = false),
				availableSources = listOf(sourceA),
			),
			imageLoader = imageLoader,
			onRetry = {},
			onMigrate = { _, _ -> fail() },
			onOpenResult = {},
			onOpenCandidate = {},
		) } }
		compose.onNodeWithText(context.getString(R.string.migration_source_unavailable)).assertExists()
		compose.onNodeWithTag("migration-source-list").assertExists()
		compose.onNodeWithTag("migration-search").assertIsNotEnabled()
		compose.onNodeWithTag("migration-advanced-toggle").assertDoesNotExist()
	}

	@Test fun directStreamingShowsResultsBeforeSearchFinishesWithoutExecution() {
		val streaming = MigrationReviewState(
			loading = true,
			searching = true,
			hasSearched = true,
			manualSearchEnabled = true,
			selectedSourceNames = setOf("NEW"),
			discovery = MigrationDiscovery(
				seed = seed,
				assessment = assessment(),
				progress = MigrationSearchProgress(
					phase = MigrationSearchPhase.SEARCHING_SOURCES,
					query = "Shared Story",
					completedSources = 1,
					totalSources = 5,
				),
			),
		)
		compose.setContent { MaterialTheme { MigrationReviewScreen(streaming, imageLoader, {}, { _, _ -> fail() }, {}, {}) } }
		compose.onNodeWithTag("migration-loading").assertExists()
		compose.onNodeWithTag("migration-candidate:NEW:2").performScrollTo().assertExists()
		compose.onNodeWithTag("migration-execute").assertDoesNotExist()
	}

	@Test fun directResultMustBeSelectedBeforeCurrentToReplacementConfirmationCanMigrate() {
		var migrated: Manga? = null
		var reviewed: Boolean? = null
		val direct = MigrationReviewState(
			loading = false,
			hasSearched = true,
			manualSearchEnabled = true,
			discovery = MigrationDiscovery(seed = seed, assessment = assessment()),
		)
		compose.setContent { MaterialTheme { MigrationReviewScreen(
			direct,
			imageLoader,
			onRetry = {},
			onMigrate = { manga, isReviewed -> migrated = manga; reviewed = isReviewed },
			onOpenResult = {},
			onOpenCandidate = {},
		) } }
		compose.onNodeWithTag("migration-execute").assertDoesNotExist()
		compose.onNodeWithTag("migration-candidate:NEW:2").performScrollTo().performClick()
		compose.onNodeWithTag("migration-selection-summary").performScrollTo().assertExists()
		compose.onNodeWithText(context.getString(R.string.migration_current_selection)).assertExists()
		compose.onNodeWithText(context.getString(R.string.migration_new_selection)).assertExists()
		compose.onNodeWithTag("migration-execute").performScrollTo().performClick()
		assertEquals(candidate.id, migrated?.id)
		assertEquals(true, reviewed)
	}

	@Test fun changeResultReturnsToResultListWithoutMigration() {
		val direct = MigrationReviewState(
			loading = false,
			hasSearched = true,
			manualSearchEnabled = true,
			discovery = MigrationDiscovery(seed = seed, assessment = assessment()),
		)
		compose.setContent { MaterialTheme { MigrationReviewScreen(direct, imageLoader, {}, { _, _ -> fail() }, {}, {}) } }
		compose.onNodeWithTag("migration-candidate:NEW:2").performScrollTo().performClick()
		compose.onNodeWithTag("migration-change-result").performScrollTo().performClick()
		compose.onNodeWithTag("migration-selection-summary").assertDoesNotExist()
		compose.onNodeWithTag("migration-candidate:NEW:2").performScrollTo().assertExists()
	}

	@Test fun sourceLoadingIsSeparateFromReplacementSearch() {
		compose.setContent { MaterialTheme { MigrationReviewScreen(
			MigrationReviewState(
				loading = false,
				sourceSelectionLoading = true,
				manualSearchEnabled = true,
				discovery = MigrationDiscovery(seed),
			),
			imageLoader, {}, { _, _ -> fail() }, {}, {},
		) } }
		compose.onNodeWithTag("migration-source-list-loading").assertExists()
		compose.onNodeWithTag("migration-search").assertDoesNotExist()
	}

	@Test fun searchFailureIsNotReportedAsTransferConflictAndCanChangeSources() {
		var searches = 0
		var changes = 0
		compose.setContent { MaterialTheme { MigrationReviewScreen(
			MigrationReviewState(
				loading = false,
				manualSearchEnabled = true,
				hasSearched = true,
				discovery = MigrationDiscovery(seed),
				searchFailed = true,
			),
			imageLoader, {}, { _, _ -> fail() }, {}, {},
			onSearch = { searches++ },
			onBackToSources = { changes++ },
		) } }
		compose.onNodeWithText(context.getString(R.string.migration_search_failed)).assertExists()
		compose.onNodeWithText(context.getString(R.string.migration_blocked)).assertDoesNotExist()
		compose.onNodeWithTag("migration-retry").performClick()
		compose.onNodeWithTag("migration-change-sources").performClick()
		assertEquals(1, searches)
		assertEquals(1, changes)
	}

	@Test fun selectedResultChangeUsesSpecificRecoveryCopy() {
		compose.setContent { MaterialTheme { MigrationReviewScreen(
			MigrationReviewState(
				loading = false,
				manualSearchEnabled = true,
				hasSearched = true,
				changed = true,
				discovery = MigrationDiscovery(seed, assessment()),
			),
			imageLoader, {}, { _, _ -> fail() }, {}, {},
		) } }
		compose.onNodeWithText(context.getString(R.string.migration_selected_result_changed)).assertExists()
	}

	@Test fun completedStateOffersOnlyThePersistedDestination() {
		var opened: Long? = null
		compose.setContent { MaterialTheme { MigrationReviewScreen(MigrationReviewState(loading = false, completedId = 42L), imageLoader, {}, { _, _ -> fail() }, { opened = it }, {}) } }
		compose.onNodeWithTag("migration-execute").assertDoesNotExist()
		compose.onNodeWithText(context.getString(R.string.migration_open_result)).performClick()
		assertEquals(42L, opened)
	}

	@Test fun recoveredSourceDoesNotOfferMigrationUnlessUserChoosesDirectSearch() {
		val healthy = MigrationReviewState(
			loading = false,
			currentSourceChecked = true,
			discovery = MigrationDiscovery(seed, sourceHealthy = true),
		)
		compose.setContent { MaterialTheme { MigrationReviewScreen(healthy, imageLoader, {}, { _, _ -> fail() }, {}, {}) } }
		compose.onNodeWithText(context.getString(R.string.migration_source_healthy)).assertExists()
		compose.onNodeWithTag("migration-execute").assertDoesNotExist()
		compose.onNodeWithTag("migration-manual-search").assertIsEnabled()
	}
}
