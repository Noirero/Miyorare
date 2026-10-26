package org.koitharu.kotatsu.discovery.domain

import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerUser
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblingStatus

data class CatalogAccountState(
	val service: ScrobblerService,
	val authorized: Boolean = false,
	val user: ScrobblerUser? = null,
	val profileUrl: String? = null,
	val loading: Boolean = false,
	val failed: Boolean = false,
)

data class CatalogTrackingEntry(
	val service: ScrobblerService,
	val targetId: Long,
	val entryId: Long? = null,
	val status: ScrobblingStatus? = null,
	val chapter: Int = 0,
	val rating: Float = 0f,
) {
	init {
		require(targetId > 0)
		require(entryId == null || entryId > 0)
		require(chapter >= 0)
		require(rating in 0f..1f)
	}
}

data class CatalogTrackingState(
	val loading: Boolean = false,
	val entry: CatalogTrackingEntry? = null,
	val adding: Boolean = false,
	val failed: Boolean = false,
)

data class CatalogTrackingDraft(
	val status: ScrobblingStatus = ScrobblingStatus.PLANNED,
	val chapter: Int = 0,
	val rating: Float = 0f,
) {
	init {
		require(chapter >= 0)
		require(rating in 0f..1f)
	}
}

enum class CatalogTrackingMutationFailure {
	AUTH_EXPIRED,
	PROVIDER,
}

data class CatalogTrackingEditorState(
	val visible: Boolean = false,
	val existing: CatalogTrackingEntry? = null,
	val saving: Boolean = false,
	val removing: Boolean = false,
	val failure: CatalogTrackingMutationFailure? = null,
)

enum class CatalogTrackingFeedbackAction {
	ADDED,
	UPDATED,
	REMOVED,
}

data class CatalogTrackingFeedback(
	val service: ScrobblerService,
	val action: CatalogTrackingFeedbackAction,
	val status: ScrobblingStatus? = null,
)

fun catalogSupportedTrackingStatuses(service: ScrobblerService): List<ScrobblingStatus> = when (service) {
	ScrobblerService.ANILIST,
	ScrobblerService.MANGABAKA,
	-> listOf(
		ScrobblingStatus.PLANNED,
		ScrobblingStatus.READING,
		ScrobblingStatus.RE_READING,
		ScrobblingStatus.COMPLETED,
		ScrobblingStatus.ON_HOLD,
		ScrobblingStatus.DROPPED,
	)
	ScrobblerService.MAL,
	ScrobblerService.KITSU,
	-> listOf(
		ScrobblingStatus.PLANNED,
		ScrobblingStatus.READING,
		ScrobblingStatus.COMPLETED,
		ScrobblingStatus.ON_HOLD,
		ScrobblingStatus.DROPPED,
	)
	else -> emptyList()
}
