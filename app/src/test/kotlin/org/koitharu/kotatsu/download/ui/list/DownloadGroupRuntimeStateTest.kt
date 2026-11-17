package org.koitharu.kotatsu.download.ui.list

import androidx.work.WorkInfo
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.download.domain.DownloadPhase
import java.time.Instant
import java.util.UUID

class DownloadGroupRuntimeStateTest {

	@Test
	fun `finished sibling cannot impose finalizing retry or stuck flags on active work`() {
		val active = item(1)
		val historical = item(2).copy(
			workState = WorkInfo.State.CANCELLED, isFinalizing = true, isIndeterminate = true,
			isStuck = true, retryAttempt = 9, eta = 999L,
		)
		val group = active.copy(max = 100, progress = 75, workIds = setOf(active.id, historical.id))
			.withGroupRuntimeState(listOf(active), listOf(active, historical))

		assertFalse(group.isFinalizing)
		assertFalse(group.isIndeterminate)
		assertFalse(group.isStuck)
		assertEquals(0, group.retryAttempt)
		assertEquals(active.eta, group.eta)
		assertEquals(100, group.max)
		assertEquals(75, group.progress)
		assertEquals(setOf(active.id, historical.id), group.workIds)
	}

	@Test
	fun `unmeasurable resolving work stays indeterminate beside completed measurable history`() {
		val resolving = item(1).copy(
			max = 0, progress = 0, phase = DownloadPhase.RESOLVING, isIndeterminate = true, eta = -1L,
		)
		val completed = item(2).copy(workState = WorkInfo.State.SUCCEEDED, progress = 10)
		val group = resolving.withGroupRuntimeState(listOf(resolving), listOf(resolving, completed))

		assertTrue(group.isIndeterminate)
		assertEquals(DownloadPhase.RESOLVING, group.phase)
		assertEquals(-1L, group.eta)
	}

	@Test
	fun `mixed paused and running group offers both actions without changing per work capabilities`() {
		val running = item(1)
		val paused = item(2).copy(isPaused = true)
		val members = listOf(running, paused)
		val group = running.withGroupRuntimeState(members, members)

		assertTrue(group.canPause)
		assertTrue(group.canResume)
		assertFalse(running.canResume)
		assertFalse(paused.canPause)
		assertTrue(paused.canResume)
	}

	@Test
	fun `terminal group has no pause or resume action despite retained paused flags`() {
		val completed = item(1).copy(workState = WorkInfo.State.SUCCEEDED)
		val failed = item(2).copy(workState = WorkInfo.State.FAILED, isPaused = true)
		val group = completed.withGroupRuntimeState(listOf(completed), listOf(completed, failed))

		assertFalse(group.canPause)
		assertFalse(group.canResume)
	}

	@Test
	fun `active group with completed siblings is entirely skipped`() {
		val active = item(1).copy(workIds = setOf(UUID(1L, 0L), UUID(2L, 0L)))
		val plan = planDownloadRemoval(setOf(active.selectionId), listOf(active))
		assertTrue(plan.eligible.isEmpty())
		assertEquals(1, plan.active)
		assertEquals(0, plan.unavailable)
	}

	@Test
	fun `mixed selection counts visible groups without removing pending or unselected work`() {
		val active = item(1)
		val done = item(2).copy(workState = WorkInfo.State.SUCCEEDED)
		val pending = item(3).copy(workState = WorkInfo.State.CANCELLED, uiAction = DownloadUiAction.CANCELLING)
		val unrelated = item(4).copy(workState = WorkInfo.State.SUCCEEDED)
		val plan = planDownloadRemoval(setOf(active.selectionId, done.selectionId, pending.selectionId, 99L),
			listOf(active, done, pending, unrelated))
		assertEquals(listOf(done), plan.eligible)
		assertEquals(1, plan.active)
		assertEquals(2, plan.unavailable)
	}

	private fun item(id: Long) = DownloadItemModel(
		id = UUID(id, 0L), workState = WorkInfo.State.RUNNING,
		isIndeterminate = false, isPaused = false, isFinalizing = false,
		phase = DownloadPhase.DOWNLOADING, phaseChapter = 1, requestedChapters = 1, retryAttempt = 0,
		manga = null, error = null, max = 10, progress = 5, eta = 123L, isStuck = false,
		timestamp = Instant.EPOCH, chaptersDownloaded = 0, downloadSizeBytes = 0L, isExpanded = false,
		chapters = MutableStateFlow(null),
	)
}
