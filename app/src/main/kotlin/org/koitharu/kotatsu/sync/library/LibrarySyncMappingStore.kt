package org.koitharu.kotatsu.sync.library

import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import org.koitharu.kotatsu.core.db.MangaDatabase

@Singleton
class LibrarySyncMappingStore @Inject constructor(private val db: MangaDatabase) {
	suspend fun put(
		service: LibrarySyncServiceId,
		externalId: String,
		localMangaId: Long,
		updatedAt: Instant = Instant.now(),
	) {
		db.getLibrarySyncDao()
			.put(
				LibrarySyncMappingEntity(
					service.name,
					externalId,
					localMangaId,
					updatedAt.toEpochMilli(),
				)
			)
	}

	suspend fun localId(service: LibrarySyncServiceId, externalId: String): Long? =
		db.getLibrarySyncDao()
			.mappings(service.name)
			.firstOrNull { it.externalId == externalId }
			?.localMangaId

	suspend fun externalId(service: LibrarySyncServiceId, localMangaId: Long): String? =
		db.getLibrarySyncDao()
			.mappings(service.name)
			.firstOrNull { it.localMangaId == localMangaId }
			?.externalId
}
