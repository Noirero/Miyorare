package org.koitharu.kotatsu.sync.library

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LibrarySyncRegistry
@Inject
constructor(
	secrets: LibrarySyncSecretStore,
	state: LibrarySyncStateStore,
) {
	private val services: Map<LibrarySyncServiceId, LibrarySyncService> =
		listOf(
				LibrarySyncBlockedService(
					LibrarySyncServiceId.MANGADEX,
					"MangaDex public OAuth clients are not available. The documented personal-client flow requires a client secret and is unsuitable for a distributed mobile application.",
				),
				LibrarySyncBlockedService(
					LibrarySyncServiceId.MANGAUPDATES,
					"The official OpenAPI describes PUT /account/login and bearer JWT, but its response context has no documented token fields. Safe login cannot be implemented without guessing the contract.",
				),
				AniListLibrarySyncService(secrets, state),
				KitsuLibrarySyncService(secrets, state),
				LibrarySyncBlockedService(
					LibrarySyncServiceId.NOVELUPDATES,
					"No official API for Reading List authentication, pull or push was found. Website scraping and private endpoints are not used.",
				),
				LibrarySyncBlockedService(
					LibrarySyncServiceId.RANOBEDB,
					"The official API v0 is read-only. User lists are explicitly not available.",
				),
			)
			.associateBy { it.id }

	operator fun get(id: LibrarySyncServiceId): LibrarySyncService = services.getValue(id)

	fun all(): Collection<LibrarySyncService> = services.values
}

/** API lookup validates user-supplied IDs; no title-based automatic identity guessing. */
interface LibrarySyncCatalog {
	suspend fun lookup(externalId: String): SyncEntry
}
