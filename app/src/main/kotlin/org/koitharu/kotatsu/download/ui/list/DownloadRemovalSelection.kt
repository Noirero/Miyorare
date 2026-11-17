package org.koitharu.kotatsu.download.ui.list

internal data class DownloadRemovalFeedback(
	val removed: Int,
	val active: Int,
	val unavailable: Int,
	val deleteFiles: Boolean,
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
