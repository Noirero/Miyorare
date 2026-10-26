package org.koitharu.kotatsu.settings.sources.migration

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
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
	private fun manga(id: Long, source: String, coverUrl: String? = null) = Manga(
		id = id, title = "Shared Story", altTitles = emptySet(), url = "/$id", publicUrl = "https://example.org/$id",
		rating = 0f, contentRating = null, coverUrl = coverUrl, tags = emptySet(), state = null,
		authors = setOf("Writer"), source = MangaSource(source), chapters = null, description = null,
	)
	private fun state(review: Boolean = false, incomplete: Boolean = false): MigrationReviewState {
		val next = if (review) candidate.copy(authors = emptySet()) else candidate
		return MigrationReviewState(
			loading = false,
			hasSearched = true,
			currentSourceChecked = true,
			discovery = MigrationDiscovery(
				seed,
				MigrationCandidateMatcher().assess(seed, listOf(MigrationCandidateObservation(next))),
				incomplete = incomplete,
			),
		)
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

	@Test fun readyShowsSourcesPreservationAndRequiresExplicitAction() {
		var executed = false
		compose.setContent { MaterialTheme { MigrationReviewScreen(state(), imageLoader, {}, { _, reviewed -> assertFalse(reviewed); executed = true }, {}, {}) } }
		compose.onNodeWithText(context.getString(R.string.migration_current_source, "OLD")).assertExists()
		compose.onNodeWithText(context.getString(R.string.migration_replacement_source, "NEW")).assertExists()
		assertFalse(executed)
		compose.onNodeWithTag("migration-execute").performScrollTo().performClick()
		assertTrue(executed)
	}

	@Test fun unavailableStopsForScopeChoiceBeforeSearch() {
		val availability = MigrationSearchAvailability(
			counts = mapOf(
				MigrationSearchScope.ALL to 5,
				MigrationSearchScope.STORE to 2,
				MigrationSearchScope.UMA to 1,
				MigrationSearchScope.GEKKOUSHI to 0,
				MigrationSearchScope.MIYORARE to 2,
			),
		)
		var chosen: MigrationSearchScope? = null
		var searches = 0
		compose.setContent { MaterialTheme { MigrationReviewScreen(
			state = MigrationReviewState(
				loading = false,
				currentSourceChecked = true,
				discovery = MigrationDiscovery(seed),
				availability = availability,
			),
			imageLoader = imageLoader,
			onRetry = {},
			onMigrate = { _, _ -> fail("Search must happen before migration") },
			onOpenResult = {},
			onOpenCandidate = {},
			onScopeChange = { chosen = it },
			onSearch = { searches++ },
		) } }
		compose.onNodeWithText(context.getString(R.string.migration_source_unavailable)).assertExists()
		compose.onNodeWithTag("migration-scope-all").assertIsEnabled()
		compose.onNodeWithTag("migration-scope-gekkoushi").assertIsNotEnabled()
		compose.onNodeWithTag("migration-execute").assertDoesNotExist()
		compose.onNodeWithTag("migration-scope-uma").performClick()
		assertEquals(MigrationSearchScope.UMA, chosen)
		compose.onNodeWithTag("migration-search").performClick()
		assertEquals(1, searches)
	}

	@Test fun directSearchPathDoesNotPretendTheCurrentSourceWasChecked() {
		val availability = MigrationSearchAvailability(counts = mapOf(MigrationSearchScope.ALL to 3))
		compose.setContent { MaterialTheme { MigrationReviewScreen(
			state = MigrationReviewState(
				loading = false,
				discovery = MigrationDiscovery(seed),
				availability = availability,
				manualSearchEnabled = true,
			),
			imageLoader = imageLoader,
			onRetry = {},
			onMigrate = { _, _ -> fail() },
			onOpenResult = {},
			onOpenCandidate = {},
		) } }
		compose.onNodeWithText(context.getString(R.string.migration_direct_search_active)).assertExists()
		compose.onNodeWithText(context.getString(R.string.migration_source_unavailable)).assertDoesNotExist()
		compose.onNodeWithTag("migration-search").assertIsEnabled()
	}

	@Test fun progressiveDiscoveryShowsCandidatesBeforeFinalDecisionWithoutExecution() {
		val progress = state(review = true).copy(loading = true, searching = true)
		compose.setContent { MaterialTheme { MigrationReviewScreen(progress, imageLoader, {}, { _, _ -> fail("Provisional candidate must not execute") }, {}, {}) } }
		compose.onNodeWithTag("migration-loading").assertExists()
		compose.onNodeWithTag("migration-candidate:NEW:2").performScrollTo().assertExists()
		compose.onNodeWithText(context.getString(R.string.migration_replacement_source, "NEW")).assertExists()
		compose.onNodeWithTag("migration-execute").assertDoesNotExist()
	}

	@Test fun reviewSelectionSurvivesSavedStateRecreationButNeverExecutesItself() {
		val restore = StateRestorationTester(compose)
		var executions = 0
		restore.setContent { MaterialTheme { MigrationReviewScreen(state(review = true), imageLoader, {}, { _, reviewed -> assertTrue(reviewed); executions++ }, {}, {}) } }
		compose.onNodeWithTag("migration-execute").performScrollTo().assertIsNotEnabled()
		compose.onNodeWithTag("migration-candidate:NEW:2").performScrollTo().performClick()
		restore.emulateSavedInstanceStateRestore()
		assertEquals(0, executions)
		compose.onNodeWithTag("migration-execute").performScrollTo().assertIsEnabled().performClick()
		assertEquals(1, executions)
	}

	@Test fun candidateCoverAndDetailsKeepSelectionSeparate() {
		val next = candidate.copy(authors = emptySet(), coverUrl = "file:///migration-candidate-cover.jpg")
		val reviewState = MigrationReviewState(
			loading = false,
			hasSearched = true,
			currentSourceChecked = true,
			discovery = MigrationDiscovery(seed, MigrationCandidateMatcher().assess(seed, listOf(MigrationCandidateObservation(next)))),
		)
		var opened: Manga? = null
		compose.setContent { MaterialTheme { MigrationReviewScreen(
			state = reviewState,
			imageLoader = imageLoader,
			onRetry = {},
			onMigrate = { _, _ -> fail("Details must not execute migration") },
			onOpenResult = { fail("Candidate details must not use the persisted-id route") },
			onOpenCandidate = { opened = it },
		) } }
		compose.onNodeWithTag("migration-candidate-cover:NEW:2", useUnmergedTree = true).performScrollTo().assertExists()
		compose.onNodeWithTag("migration-execute").performScrollTo().assertIsNotEnabled()
		compose.onNodeWithTag("migration-candidate-details:NEW:2").performScrollTo().performClick()
		assertEquals(next, opened)
		compose.onNodeWithTag("migration-execute").performScrollTo().assertIsNotEnabled()
	}

	@Test fun incompleteAndNoReplacementAreDifferentStates() {
		compose.setContent { MaterialTheme { MigrationReviewScreen(state(incomplete = true), imageLoader, {}, { _, _ -> fail("Must review first") }, {}, {}) } }
		compose.onNodeWithText(context.getString(R.string.migration_incomplete)).assertExists()
		compose.onNodeWithText(context.getString(R.string.migration_no_replacement)).assertDoesNotExist()
		compose.onNodeWithTag("migration-execute").performScrollTo().assertIsNotEnabled()
	}

	@Test fun busyStateDoesNotOfferDuplicateExecution() {
		compose.setContent { MaterialTheme { MigrationReviewScreen(MigrationReviewState(loading = true), imageLoader, {}, { _, _ -> fail() }, {}, {}) } }
		compose.onNodeWithTag("migration-loading").assertExists()
		compose.onNodeWithTag("migration-execute").assertDoesNotExist()
		compose.onNodeWithTag("migration-check-current").assertDoesNotExist()
	}

	@Test fun unresolvedHasNoMigrationAction() {
		val unresolved = MigrationReviewState(
			loading = false,
			hasSearched = true,
			currentSourceChecked = true,
			discovery = MigrationDiscovery(seed, MigrationCandidateMatcher().assess(seed, emptyList())),
		)
		compose.setContent { MaterialTheme { MigrationReviewScreen(unresolved, imageLoader, {}, { _, _ -> fail() }, {}, {}) } }
		compose.onNodeWithText(context.getString(R.string.migration_no_replacement)).assertExists()
		compose.onNodeWithTag("migration-execute").assertDoesNotExist()
	}

	@Test fun searchFailureIsNotReportedAsTransferConflict() {
		compose.setContent { MaterialTheme { MigrationReviewScreen(
			MigrationReviewState(
				loading = false,
				currentSourceChecked = true,
				discovery = MigrationDiscovery(seed),
				searchFailed = true,
			),
			imageLoader, {}, { _, _ -> fail() }, {}, {},
		) } }
		compose.onNodeWithText(context.getString(R.string.migration_search_failed)).assertExists()
		compose.onNodeWithText(context.getString(R.string.migration_blocked)).assertDoesNotExist()
		compose.onNodeWithTag("migration-retry").assertExists()
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
		compose.onNodeWithTag("migration-search").assertDoesNotExist()
		compose.onNodeWithTag("migration-manual-search").assertIsEnabled()
	}
}
