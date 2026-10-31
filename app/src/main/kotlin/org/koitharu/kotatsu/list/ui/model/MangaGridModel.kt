package org.koitharu.kotatsu.list.ui.model

import org.koitharu.kotatsu.core.ui.model.MangaOverride
import org.koitharu.kotatsu.list.domain.ReadingProgress
import org.koitharu.kotatsu.list.ui.ListModelDiffCallback.Companion.PAYLOAD_ANYTHING_CHANGED
import org.koitharu.kotatsu.list.ui.ListModelDiffCallback.Companion.PAYLOAD_PROGRESS_CHANGED
import org.koitharu.kotatsu.parsers.model.Manga

/** Download badge authority for space-scoped Favourites; null means legacy hosts. */
enum class DownloadVerification { UNRESOLVED, VERIFIED_DOWNLOADED, VERIFIED_NOT_DOWNLOADED }

data class MangaGridModel(
	override val manga: Manga,
	override val override: MangaOverride?,
	override val counter: Int,
	val progress: ReadingProgress?,
	val isFavorite: Boolean,
	val isSaved: Boolean,
	val downloadVerification: DownloadVerification? = null,
	val isTitleHidden: Boolean = false,
	val isTitleOverCover: Boolean = true,
	val isGridSpacingIncreased: Boolean = false,
	val isPinned: Boolean = false,
	val isLocalSource: Boolean = false,
	val languageLabel: String? = null,
	val showContinueReading: Boolean = false,
	/** Cached chapter/history authority only; null means unread was not projected by this host. */
	val unreadCount: Int? = null,
) : MangaListModel() {

	override fun getChangePayload(previousState: ListModel): Any? = when {
		previousState !is MangaGridModel || previousState.manga != manga -> null

		previousState.progress != progress -> PAYLOAD_PROGRESS_CHANGED
		previousState.unreadCount != unreadCount -> PAYLOAD_ANYTHING_CHANGED
		previousState.isFavorite != isFavorite ||
			previousState.isSaved != isSaved ||
			previousState.downloadVerification != downloadVerification ||
			previousState.isTitleHidden != isTitleHidden ||
			previousState.isTitleOverCover != isTitleOverCover ||
			previousState.isGridSpacingIncreased != isGridSpacingIncreased ||
			previousState.isPinned != isPinned ||
			previousState.isLocalSource != isLocalSource ||
			previousState.languageLabel != languageLabel ||
			previousState.showContinueReading != showContinueReading -> PAYLOAD_ANYTHING_CHANGED

		else -> super.getChangePayload(previousState)
	}
}
