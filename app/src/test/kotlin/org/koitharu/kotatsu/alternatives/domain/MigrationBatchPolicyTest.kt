package org.koitharu.kotatsu.alternatives.domain

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.koitharu.kotatsu.core.model.MangaSource
import org.koitharu.kotatsu.parsers.model.Manga

class MigrationBatchPolicyTest {
	private val seed = manga(1)
	private val replacement = manga(101).copy(source = MangaSource("NEW"))
	private fun manga(id: Long) = Manga(id = id, title = "Batch Story", altTitles = emptySet(), url = "/$id",
		publicUrl = "https://fixture.invalid/$id", rating = 0f, contentRating = null, coverUrl = null,
		tags = emptySet(), state = null, authors = setOf("Writer"), source = MangaSource("OLD"))
	private fun ready() = MigrationDiscovery(seed, MigrationCandidateMatcher().assess(seed, listOf(MigrationCandidateObservation(replacement))))
	private fun snapshot(id: Long, generation: Long, status: MigrationBatchStatus, selection: MigrationSelection? = null) =
		MigrationBatchSnapshot(id, "OLD", generation, "batch-$generation", MigrationBatchOutcome(status, selection))

	@Test fun onlyReadyVisibleCurrentSourceResultsBecomeBatchRequests() {
		val states = listOf(MigrationBatchStatus.READY, MigrationBatchStatus.NEEDS_REVIEW, MigrationBatchStatus.NO_REPLACEMENT,
			MigrationBatchStatus.INCOMPLETE, MigrationBatchStatus.ERROR, MigrationBatchStatus.INTERRUPTED)
		val items = states.mapIndexed { index, status -> snapshot(index + 1L, 1, status, MigrationSelection.of(replacement)) }.associateBy { it.mangaId }
		val requests = MigrationBatchPolicy.readyRequests((1L..6L).map(::manga), items)
		assertEquals(listOf(1L), requests.map { it.mangaId })
		assertTrue(MigrationBatchPolicy.readyRequests(listOf(manga(1).copy(source = MangaSource("CHANGED"))), items).isEmpty())
		assertTrue(MigrationBatchPolicy.readyRequests(emptyList(), items).isEmpty())
	}
	@Test fun incompleteAndAmbiguousSearchesNeverBecomeReadyCounts() {
		assertEquals(MigrationBatchStatus.INCOMPLETE, MigrationBatchPolicy.assess(ready().copy(incomplete = true)).status)
		val ambiguous = ready().copy(assessment = ready().assessment!!.copy(state = MigrationMatchState.NEEDS_REVIEW))
		assertEquals(MigrationBatchStatus.NEEDS_REVIEW, MigrationBatchPolicy.assess(ambiguous).status)
		assertNull(MigrationBatchPolicy.assess(ambiguous).selection)
	}
	@Test fun durableGenerationsBeatArrivalOrderAndOldReadySnapshots() {
		val old = snapshot(1, 1, MigrationBatchStatus.READY, MigrationSelection.of(replacement))
		val fresh = snapshot(1, 2, MigrationBatchStatus.NO_REPLACEMENT)
		assertEquals(fresh, MigrationBatchPolicy.latest(listOf(old, fresh))[1L])
		assertEquals(fresh, MigrationBatchPolicy.latest(listOf(fresh, old))[1L])
		val conflict = old.copy(outcome = MigrationBatchOutcome(MigrationBatchStatus.ERROR))
		assertEquals(MigrationBatchStatus.INTERRUPTED, MigrationBatchPolicy.latest(listOf(old, conflict))[1L]?.outcome?.status)
	}
	@Test fun partialFailureDoesNotHideSuccessAndCanBeRetried() = runTest {
		val request = MigrationBatchRequest(1, "OLD", MigrationBatchAction.MIGRATE, MigrationSelection.of(replacement))
		val failed = MigrationBatchPolicy.process(request, { true }, { error("No scan here") }, { throw IllegalStateException("collision") })
		val successful = MigrationBatchPolicy.process(request.copy(mangaId = 2), { true }, { error("No scan here") }, { replacement })
		assertEquals(MigrationBatchStatus.ERROR, failed.status)
		assertEquals(MigrationBatchStatus.MIGRATED, successful.status)
		val retry = MigrationBatchPolicy.process(request, { true }, { error("No scan here") }, { replacement })
		assertEquals(MigrationBatchStatus.MIGRATED, retry.status)
	}
	@Test fun replayAfterCommitOrPrivacyRemovalDoesNotExecuteAgain() = runTest {
		var calls = 0
		val request = MigrationBatchRequest(1, "OLD", MigrationBatchAction.MIGRATE, MigrationSelection.of(replacement))
		val result = MigrationBatchPolicy.process(request, { false }, { calls++; ready() }, { calls++; replacement })
		assertEquals(0, calls)
		assertEquals(MigrationBatchStatus.INTERRUPTED, result.status)
		assertEquals(replacement.id, result.destinationId)
	}
	@Test fun candidateChangeAndSourceRecoveryDoNotReuseStaleReadyPermission() = runTest {
		val request = MigrationBatchRequest(1, "OLD", MigrationBatchAction.MIGRATE, MigrationSelection.of(replacement))
		val changed = MigrationBatchPolicy.process(request, { true }, { ready() }, { throw AutoFixUseCase.SelectionChangedException(ready()) })
		assertEquals(MigrationBatchStatus.NEEDS_REVIEW, changed.status)
		assertNull(changed.selection)
		val recovered = MigrationBatchPolicy.process(request, { true }, { ready() }, { null })
		assertEquals(MigrationBatchStatus.HEALTHY, recovered.status)
	}
	@Test fun cancellationPropagatesInsteadOfInventingAnOutcome() = runTest {
		try {
			MigrationBatchPolicy.process(MigrationBatchRequest(1, "OLD", MigrationBatchAction.CHECK), { true }, { throw CancellationException() }, { error("Never") })
			fail("Cancellation must propagate to WorkManager")
		} catch (_: CancellationException) { }
	}
}
