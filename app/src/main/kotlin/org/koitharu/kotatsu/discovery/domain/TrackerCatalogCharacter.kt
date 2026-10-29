package org.koitharu.kotatsu.discovery.domain

import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService

data class TrackerCatalogCharacterWork(
	val remoteId: String,
	val title: String,
	val imageUrl: String? = null,
	val providerUrl: String? = null,
	val role: String? = null,
	val medium: String,
) {
	init {
		require(remoteId.toLongOrNull()?.let { it > 0L && it.toString() == remoteId } == true)
		require(title.isNotBlank())
		require(medium.isNotBlank())
	}
}

data class TrackerCatalogCharacterProfile(
	val id: String,
	val name: String,
	val nativeName: String? = null,
	val nicknames: List<String> = emptyList(),
	val imageUrl: String? = null,
	val about: String? = null,
	val favorites: Long? = null,
	val providerUrl: String? = null,
	val works: List<TrackerCatalogCharacterWork> = emptyList(),
	val voiceActors: List<TrackerCatalogPerson> = emptyList(),
) {
	init {
		require(id.toLongOrNull()?.let { it > 0L && it.toString() == id } == true)
		require(name.isNotBlank())
		require(favorites == null || favorites >= 0L)
	}
}

internal fun TrackerCatalogCharacterProfile.belongsTo(service: ScrobblerService): Boolean = service == ScrobblerService.MAL
