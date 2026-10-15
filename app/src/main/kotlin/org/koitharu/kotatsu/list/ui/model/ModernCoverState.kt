package org.koitharu.kotatsu.list.ui.model

import org.koitharu.kotatsu.core.model.getLanguageCode
import org.koitharu.kotatsu.core.model.isLocal
import org.koitharu.kotatsu.core.prefs.MiyorareDesignStyle

/** Explicit host opt-in: utility/picker adapters keep their existing presentation. */
enum class ModernCoverContext {
	LEGACY, BROWSING, COLLECTION;

	fun usesOverlay(style: MiyorareDesignStyle) = this != LEGACY && style == MiyorareDesignStyle.MODERN
	fun usesCollectionBorder(style: MiyorareDesignStyle) = this == COLLECTION && usesOverlay(style)
}

/** Presentation projection only. A generic counter is never evidence of unread chapters. */
data class ModernCoverState(
	val title: String,
	val isTitleOverCover: Boolean,
	val isTitleHidden: Boolean,
	val isFavorite: Boolean,
	val isSaved: Boolean,
	val isPinned: Boolean,
	val isLocalSource: Boolean,
	val languageLabel: String?,
	val unreadCount: Int?,
	val showContinueReading: Boolean,
	/** Preserve the host's original counter (tracker updates, Local chapter total, etc.). */
	val legacyCounter: Int,
) {
	companion object {
		fun from(model: MangaGridModel, host: ModernCoverContext): ModernCoverState {
			require(host != ModernCoverContext.LEGACY)
			val collection = host == ModernCoverContext.COLLECTION
			return ModernCoverState(
				title = model.title,
				isTitleOverCover = model.isTitleOverCover,
				isTitleHidden = model.isTitleHidden,
				isFavorite = model.isFavorite,
				isSaved = model.isSaved,
				isPinned = collection && model.isPinned,
				isLocalSource = if (collection) model.isLocalSource else model.source.isLocal,
				languageLabel = if (collection) model.languageLabel else model.source.getLanguageCode(),
				unreadCount = model.unreadCount,
				showContinueReading = collection && model.showContinueReading,
				legacyCounter = if (collection) 0 else model.counter,
			)
		}
	}
}
