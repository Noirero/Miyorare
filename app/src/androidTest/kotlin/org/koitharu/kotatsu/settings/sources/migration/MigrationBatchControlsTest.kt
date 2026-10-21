package org.koitharu.kotatsu.settings.sources.migration

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.alternatives.domain.*
import org.koitharu.kotatsu.core.model.MangaSource
import org.koitharu.kotatsu.parsers.model.Manga

class MigrationBatchControlsTest {
	@get:Rule val compose = createComposeRule()
	private val context = InstrumentationRegistry.getInstrumentation().targetContext
	private fun manga(id: Long) = Manga(id = id, title = "Story $id", altTitles = emptySet(), url = "/$id", publicUrl = "https://fixture.invalid/$id",
		rating = 0f, contentRating = null, coverUrl = null, tags = emptySet(), state = null, authors = emptySet(), source = MangaSource("OLD"))
	private val selection = MigrationSelection("NEW", 101, "fingerprint")
	private fun state() = SourceMangaListState(isLoading = false, manga = listOf(manga(1), manga(2), manga(3)),
		batchItems = listOf(MigrationBatchStatus.READY, MigrationBatchStatus.NEEDS_REVIEW, MigrationBatchStatus.NO_REPLACEMENT)
			.mapIndexed { index, status -> MigrationBatchSnapshot(index + 1L, "OLD", 1, "batch", MigrationBatchOutcome(status, selection)) }.associateBy { it.mangaId })
	@Test fun summaryAndReadyOnlyActionReachTheUser() {
		var executions = 0
		compose.setContent { MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) { MigrationBatchControls(state(), {}, { executions++ }, {}, {}) } } }
		compose.onNodeWithText(context.getString(R.string.migration_batch_summary, 1, 1, 1)).assertExists()
		assertEquals(0, executions)
		compose.onNodeWithTag("migration-batch-execute").performScrollTo().performClick()
		assertEquals(1, executions)
	}
	@Test fun reviewAndUnresolvedAreFilterableWithoutMigration() {
		var filter: MigrationBatchStatus? = null
		compose.setContent { MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) { MigrationBatchControls(state(), {}, {}, {}, { filter = it }) } } }
		compose.onNodeWithTag("migration-batch-filter").performScrollTo().performClick()
		compose.onNodeWithText(context.getString(R.string.migration_batch_filter_count, context.getString(R.string.migration_batch_review), 1)).performClick()
		assertEquals(MigrationBatchStatus.NEEDS_REVIEW, filter)
		assertEquals(listOf(2L), state().copy(filter = filter).visibleManga.map { it.id })
	}
	@Test fun activeBatchCanCancelAndCannotScheduleAnotherExecution() {
		var cancellations = 0
		compose.setContent { MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
			MigrationBatchControls(state().copy(batchBusy = true, ownedBatchName = "batch", batchCompleted = 1, batchTotal = 3), {}, {}, { cancellations++ }, {})
		} } }
		compose.onNodeWithTag("migration-batch-check").performScrollTo().assertIsNotEnabled()
		compose.onNodeWithTag("migration-batch-execute").performScrollTo().assertIsNotEnabled()
		compose.onNodeWithTag("migration-batch-cancel").performScrollTo().performClick()
		assertEquals(1, cancellations)
	}
}
