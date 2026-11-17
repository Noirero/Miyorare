package org.koitharu.kotatsu.download.ui.list

import android.os.SystemClock
import androidx.lifecycle.SavedStateHandle
import androidx.work.Data
import androidx.work.WorkInfo
import org.koitharu.kotatsu.BuildConfig
import org.koitharu.kotatsu.download.domain.DownloadState
import java.util.IdentityHashMap
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/** One-shot, in-process instrumentation hook. No saved state or production cache. */
internal class DownloadScaleProbe(
	val snapshot: Map<UUID, Input>?,
	private val active: UUID,
	private val report: (String) -> Unit,
) {
	data class Input(val input: Data, val fallback: Data)

	class Reads {
		val count = AtomicLong()
		val queueNs = AtomicLong()
		val daoNs = AtomicLong()
		val record: (Long, Long) -> Unit = { queue, dao ->
			count.incrementAndGet()
			queueNs.addAndGet(queue)
			daoNs.addAndGet(dao)
		}
	}

	fun received(works: List<WorkInfo>) {
		val now = SystemClock.elapsedRealtimeNanos()
		val work = works.firstOrNull { it.id == active } ?: return
		report("stage=vm_work tick=${DownloadState.getProgress(work.progress)} at_ns=$now")
	}

	fun converted(works: List<WorkInfo>, started: Long, reads: Reads) {
		val ended = SystemClock.elapsedRealtimeNanos()
		val work = works.firstOrNull { it.id == active } ?: return
		report("stage=convert tick=${DownloadState.getProgress(work.progress)} start_ns=$started end_ns=$ended reads=${reads.count.get()} queue_sum_ns=${reads.queueNs.get()} dao_sum_ns=${reads.daoNs.get()}")
	}

	fun modelStage(stage: String, rows: List<DownloadItemModel>, started: Long) {
		val ended = SystemClock.elapsedRealtimeNanos()
		val row = rows.firstOrNull { active in it.workIds } ?: return
		report("stage=$stage tick=${row.progress} start_ns=$started end_ns=$ended")
	}

	companion object {
		private val pending = IdentityHashMap<SavedStateHandle, DownloadScaleProbe>()

		fun install(handle: SavedStateHandle, probe: DownloadScaleProbe) {
			check(BuildConfig.DEBUG)
			synchronized(pending) { check(pending.put(handle, probe) == null) }
		}

		fun take(handle: SavedStateHandle): DownloadScaleProbe? =
			if (BuildConfig.DEBUG) synchronized(pending) { pending.remove(handle) } else null
	}
}
