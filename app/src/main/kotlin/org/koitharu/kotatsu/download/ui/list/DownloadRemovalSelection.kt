package org.koitharu.kotatsu.download.ui.list

import kotlinx.coroutines.CancellationException
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.local.domain.UnverifiedDownloadRemovalException

internal data class DownloadRemovalFeedback(
	val removed: Int,
	val active: Int,
	val unavailable: Int,
	val deleteFiles: Boolean,
	val ambiguous: Int = 0,
	val missing: Int = 0,
	val failed: Int = 0,
	val notAttempted: Int = 0,
)

internal data class DownloadRemovalSelection(
	val eligible: List<DownloadItemModel>,
	val active: Int,
	val unavailable: Int,
)

/** Classify visible groups at confirmation, not raw work IDs or another tab/space. */
internal fun planDownloadRemoval(ids: Set<Long>, rows: List<DownloadItemModel>): DownloadRemovalSelection {
	val selected = rows.filter { it.selectionId in ids }
	val eligible = selected.filter { it.workState.isFinished && it.uiAction == null }
	val active = selected.count { !it.workState.isFinished }
	return DownloadRemovalSelection(eligible, active, ids.size - eligible.size - active)
}

internal enum class DownloadRemovalOutcome { REMOVED, ACTIVE, AMBIGUOUS, MISSING, FAILED }

internal fun <T : Any> verifiedRemovalValue(value: T?): T =
	value ?: throw UnverifiedDownloadRemovalException()

/** Continue past pre-mutation rejections; stop after a failure with unknown mutation state. */
internal suspend fun <T> processDownloadRemoval(
	groups: List<T>,
	initial: DownloadRemovalFeedback,
	remove: suspend (T) -> DownloadRemovalOutcome,
): DownloadRemovalFeedback {
	var result = initial
	for ((index, group) in groups.withIndex()) {
		val outcome = try {
			remove(group)
		} catch (e: CancellationException) {
			throw e
		} catch (e: UnverifiedDownloadRemovalException) {
			if (e.missing) DownloadRemovalOutcome.MISSING else DownloadRemovalOutcome.AMBIGUOUS
		} catch (e: Exception) {
			e.printStackTraceDebug()
			DownloadRemovalOutcome.FAILED
		}
		result = when (outcome) {
			DownloadRemovalOutcome.REMOVED -> result.copy(removed = result.removed + 1)
			DownloadRemovalOutcome.ACTIVE -> result.copy(active = result.active + 1)
			DownloadRemovalOutcome.AMBIGUOUS -> result.copy(ambiguous = result.ambiguous + 1)
			DownloadRemovalOutcome.MISSING -> result.copy(missing = result.missing + 1)
			DownloadRemovalOutcome.FAILED -> return result.copy(
				failed = result.failed + 1,
				notAttempted = result.notAttempted + groups.size - index - 1,
			)
		}
	}
	return result
}
