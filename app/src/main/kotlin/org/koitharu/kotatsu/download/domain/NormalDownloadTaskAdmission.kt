package org.koitharu.kotatsu.download.domain

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.util.ext.isWriteable
import org.koitharu.kotatsu.download.ui.worker.DownloadTask
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
import org.koitharu.kotatsu.favourites.domain.NormalDownloadScope
import org.koitharu.kotatsu.parsers.model.Manga
import java.io.File
import javax.inject.Inject

/** Snapshot of the active policy for NEW requests, not a persisted task or read authorization. */
data class NormalDownloadAdmissionPlan(
	val eligibleIds: Set<Long>,
	val root: File?,
	val generation: Long,
)

/**
 * Common new-task admission. Never use this to retarget a serialized queued/pinned request.
 * Explicit Private stays authoritative, and defaulted Normal is not navigation provenance.
 * Dialog preview makes the active Normal destination explicit, including mixed selections.
 */
class NormalDownloadTaskAdmission @Inject constructor(
	private val scope: NormalDownloadScope,
	private val destinations: DownloadDestinationStore,
	@ApplicationContext private val context: Context,
) {
	internal suspend fun plan(items: Collection<Pair<Manga, FavouriteSpace?>>): NormalDownloadAdmissionPlan {
		val ids = LinkedHashSet<Long>()
		for ((manga, provenance) in items) {
			if (scope.appliesTo(manga, provenance)) ids += manga.id
		}
		if (ids.isEmpty()) return NormalDownloadAdmissionPlan(emptySet(), null, -1L)
		val generation = destinations.normalScannerRootChanges.value
		// Write admission may provision the existing app-owned default. A configured
		// unavailable path is retained by effectiveRoot and must not fall back.
		val root = withContext(Dispatchers.IO) {
			destinations.effectiveRoot(FavouriteSpace.NORMAL)
		}
		return destinations.withDestinationMutationLock {
			check(generation == destinations.normalScannerRootChanges.value) { changedMessage() }
			NormalDownloadAdmissionPlan(ids.toSet(), root, generation)
		}
	}

	internal suspend fun prepare(
		tasks: Collection<Pair<Manga, DownloadTask>>,
		explicitSpace: FavouriteSpace?,
		expected: NormalDownloadAdmissionPlan? = null,
	): Pair<NormalDownloadAdmissionPlan, List<Pair<Manga, DownloadTask>>> {
		val plan = plan(tasks.map { (manga, task) ->
			manga to if (task.favouriteSpace == FavouriteSpace.PRIVATE) FavouriteSpace.PRIVATE else explicitSpace
		})
		check(expected == null || expected == plan) { changedMessage() }
		if (plan.eligibleIds.isEmpty()) return plan to tasks.toList()
		val root = checkNotNull(plan.root) { context.getString(R.string.cannot_find_available_storage) }
		withContext(Dispatchers.IO) {
			if (!root.isDirectory || !root.isWriteable()) throw kotlin.io.AccessDeniedException(root)
		}
		val admitted = tasks.map { (manga, task) ->
			manga to if (manga.id in plan.eligibleIds && task.favouriteSpace != FavouriteSpace.PRIVATE)
				task.withDestination(root) else task
		}
		return plan to admitted
	}

	/** Short, non-suspending enqueue boundary. Do not hold the lock across Room or awaits. */
	internal fun <T> enqueueIfCurrent(plan: NormalDownloadAdmissionPlan, enqueue: () -> T): T =
		destinations.withDestinationMutationLock {
			check(plan.eligibleIds.isEmpty() || plan.generation == destinations.normalScannerRootChanges.value) {
				changedMessage()
			}
			enqueue()
		}

	private fun changedMessage() = context.getString(R.string.normal_download_destination_changed)
}
