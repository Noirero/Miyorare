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
	val status: ScrobblingStatus? = null,
	val chapter: Int = 0,
	val rating: Float = 0f,
) {
	init {
		require(targetId > 0)
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
