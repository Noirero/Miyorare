package org.koitharu.kotatsu.settings.sources.migration

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.test.platform.app.InstrumentationRegistry
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
	private val seed = manga(1, "OLD")
	private val candidate = manga(2, "NEW")
	private fun manga(id: Long, source: String) = Manga(
		id = id, title = "Shared Story", altTitles = emptySet(), url = "/$id", publicUrl = "https://example.org/$id",
		rating = 0f, contentRating = null, coverUrl = null, tags = emptySet(), state = null,
		authors = setOf("Writer"), source = MangaSource(source), chapters = null, description = null,
	)
	private fun state(review: Boolean = false, incomplete: Boolean = false): MigrationReviewState {
		val next = if (review) candidate.copy(authors = emptySet()) else candidate
		return MigrationReviewState(loading = false, discovery = MigrationDiscovery(seed,
			MigrationCandidateMatcher().assess(seed, listOf(MigrationCandidateObservation(next))), incomplete = incomplete))
	}
	@Test fun readyShowsSourcesPreservationAndRequiresExplicitAction() {
		var executed = false
		compose.setContent { MaterialTheme { MigrationReviewScreen(state(), {}, { _, reviewed -> assertFalse(reviewed); executed = true }, {}) } }
		compose.onNodeWithText(context.getString(R.string.migration_current_source, "OLD")).assertExists()
		compose.onNodeWithText(context.getString(R.string.migration_replacement_source, "NEW")).assertExists()
		assertFalse(executed)
		compose.onNodeWithTag("migration-execute").performScrollTo().performClick()
		assertTrue(executed)
	}
	@Test fun reviewSelectionSurvivesSavedStateRecreationButNeverExecutesItself() {
		val restore = StateRestorationTester(compose)
		var executions = 0
		restore.setContent { MaterialTheme { MigrationReviewScreen(state(review = true), {}, { _, reviewed -> assertTrue(reviewed); executions++ }, {}) } }
		compose.onNodeWithTag("migration-execute").performScrollTo().assertIsNotEnabled()
		compose.onNodeWithTag("migration-candidate:NEW:2").performScrollTo().performClick()
		restore.emulateSavedInstanceStateRestore()
		assertEquals(0, executions)
		compose.onNodeWithTag("migration-execute").performScrollTo().assertIsEnabled().performClick()
		assertEquals(1, executions)
	}
	@Test fun incompleteAndNoReplacementAreDifferentRecoverableStates() {
		compose.setContent { MaterialTheme { MigrationReviewScreen(state(incomplete = true), {}, { _, _ -> fail("Must review first") }, {}) } }
		compose.onNodeWithText(context.getString(R.string.migration_incomplete)).assertExists()
		compose.onNodeWithText(context.getString(R.string.migration_no_replacement)).assertDoesNotExist()
		compose.onNodeWithTag("migration-execute").performScrollTo().assertIsNotEnabled()
		compose.onNodeWithTag("migration-retry").performScrollTo().assertIsEnabled()
	}
	@Test fun busyAndCompletedStatesDoNotOfferDuplicateExecution() {
		compose.setContent { MaterialTheme { MigrationReviewScreen(MigrationReviewState(loading = true), {}, { _, _ -> fail() }, {}) } }
		compose.onNodeWithTag("migration-loading").assertExists()
		compose.onNodeWithTag("migration-execute").assertDoesNotExist()
	}
	@Test fun unresolvedKeepsRetryWithoutMigrationAction() {
		var retries = 0
		val unresolved = MigrationReviewState(loading = false, discovery = MigrationDiscovery(seed,
			MigrationCandidateMatcher().assess(seed, emptyList())))
		compose.setContent { MaterialTheme { MigrationReviewScreen(unresolved, { retries++ }, { _, _ -> fail() }, {}) } }
		compose.onNodeWithText(context.getString(R.string.migration_no_replacement)).assertExists()
		compose.onNodeWithTag("migration-execute").assertDoesNotExist()
		compose.onNodeWithTag("migration-retry").performScrollTo().performClick()
		assertEquals(1, retries)
	}
}
