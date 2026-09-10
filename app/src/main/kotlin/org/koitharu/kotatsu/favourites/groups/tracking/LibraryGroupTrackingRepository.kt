package org.koitharu.kotatsu.favourites.groups.tracking

import dagger.Reusable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
import org.koitharu.kotatsu.favourites.groups.data.LibraryGroupTrackingEntity
import org.koitharu.kotatsu.favourites.groups.domain.LibraryGroupsRepository
import org.koitharu.kotatsu.scrobbling.common.domain.ScrobblerRepositoryMap
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerManga
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerMangaInfo
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerMangaType
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService
import javax.inject.Inject

data class LibraryGroupTracking(
	val service: ScrobblerService,
	val rateId: Long,
	val targetId: Long,
	val targetTitle: String,
	val targetUrl: String?,
	val status: String?,
	val progress: Int,
	val rating: Float,
	val comment: String?,
	val lastSyncAt: Long,
)

@Reusable
class LibraryGroupTrackingRepository @Inject constructor(
	private val db: MangaDatabase,
	private val groupsRepository: LibraryGroupsRepository,
	private val repositories: ScrobblerRepositoryMap,
	private val remote: LibraryGroupRemoteTrackingGateway,
) {

	private val dao
		get() = db.getLibraryGroupTrackingDao()

	fun observe(groupId: Long): Flow<List<LibraryGroupTracking>> = dao.observe(groupId)
		.map { rows -> rows.mapNotNull(::toDomain) }
		.distinctUntilChanged()

	suspend fun get(groupId: Long): List<LibraryGroupTracking> =
		dao.findAll(groupId).mapNotNull(::toDomain)

	fun availableServices(): List<ScrobblerService> = ScrobblerService.values().filter { service ->
		repositories[service].isAuthorized
	}

	suspend fun search(service: ScrobblerService, query: String): List<ScrobblerManga> {
		require(repositories[service].isAuthorized) { "Sign in to this tracking service first" }
		val normalized = query.trim()
		require(normalized.isNotEmpty()) { "Tracking search cannot be empty" }
		return repositories[service].findManga(normalized, 0, ScrobblerMangaType.MANGA)
	}

	suspend fun getMetadata(service: ScrobblerService, targetId: Long): ScrobblerMangaInfo {
		require(repositories[service].isAuthorized) { "Sign in to this tracking service first" }
		return repositories[service].getMangaInfo(targetId)
	}

	suspend fun link(
		groupId: Long,
		space: FavouriteSpace,
		service: ScrobblerService,
		target: ScrobblerManga,
		groupProgress: Int,
	): LibraryGroupTracking {
		requireNotNull(groupsRepository.getGroup(groupId, space)) { "Library group is no longer available" }
		require(repositories[service].isAuthorized) { "Sign in to this tracking service first" }
		var remoteRate = remote.ensureLinked(service, target.id)
		if (groupProgress > remoteRate.progress) {
			remoteRate = remote.syncProgress(service, remoteRate, groupProgress)
		}
		val entity = remoteRate.toEntity(
			groupId = groupId,
			service = service,
			targetTitle = target.name,
			targetUrl = target.url,
		)
		dao.upsert(entity)
		return requireNotNull(toDomain(entity))
	}

	suspend fun refresh(
		groupId: Long,
		space: FavouriteSpace,
		service: ScrobblerService,
	): LibraryGroupTracking {
		requireNotNull(groupsRepository.getGroup(groupId, space)) { "Library group is no longer available" }
		val current = requireNotNull(dao.find(groupId, service.id)) { "Tracking link is no longer available" }
		val refreshed = remote.refresh(service, current.toRemote()).toEntity(
			groupId = groupId,
			service = service,
			targetTitle = current.targetTitle,
			targetUrl = current.targetUrl,
		)
		dao.upsert(refreshed)
		return requireNotNull(toDomain(refreshed))
	}

	suspend fun syncProgress(
		groupId: Long,
		space: FavouriteSpace,
		progress: Int,
	): List<LibraryGroupTracking> {
		requireNotNull(groupsRepository.getGroup(groupId, space)) { "Library group is no longer available" }
		val normalizedProgress = progress.coerceAtLeast(0)
		val result = ArrayList<LibraryGroupTracking>()
		for (current in dao.findAll(groupId)) {
			val service = ScrobblerService.values().firstOrNull { it.id == current.service } ?: continue
			if (!repositories[service].isAuthorized) {
				toDomain(current)?.let(result::add)
				continue
			}
			val synced = remote.syncProgress(service, current.toRemote(), normalizedProgress).toEntity(
				groupId = groupId,
				service = service,
				targetTitle = current.targetTitle,
				targetUrl = current.targetUrl,
			)
			dao.upsert(synced)
			toDomain(synced)?.let(result::add)
		}
		return result
	}

	suspend fun unlink(
		groupId: Long,
		space: FavouriteSpace,
		service: ScrobblerService,
	) {
		requireNotNull(groupsRepository.getGroup(groupId, space)) { "Library group is no longer available" }
		// Match normal manga tracking semantics: unlink locally without deleting the user's remote list entry.
		dao.delete(groupId, service.id)
	}

	private fun RemoteLibraryGroupRate.toEntity(
		groupId: Long,
		service: ScrobblerService,
		targetTitle: String,
		targetUrl: String?,
	) = LibraryGroupTrackingEntity(
		groupId = groupId,
		service = service.id,
		rateId = rateId,
		targetId = targetId,
		targetTitle = targetTitle.trim(),
		targetUrl = targetUrl?.trim()?.takeIf { it.isNotEmpty() },
		status = status?.trim()?.takeIf { it.isNotEmpty() },
		progress = progress.coerceAtLeast(0),
		rating = rating.coerceIn(0f, 1f),
		comment = comment?.trim()?.takeIf { it.isNotEmpty() },
		lastSyncAt = System.currentTimeMillis(),
	)

	private fun LibraryGroupTrackingEntity.toRemote() = RemoteLibraryGroupRate(
		rateId = rateId,
		targetId = targetId,
		status = status,
		progress = progress,
		rating = rating,
		comment = comment,
	)

	private fun toDomain(entity: LibraryGroupTrackingEntity): LibraryGroupTracking? {
		val service = ScrobblerService.values().firstOrNull { it.id == entity.service } ?: return null
		return LibraryGroupTracking(
			service = service,
			rateId = entity.rateId,
			targetId = entity.targetId,
			targetTitle = entity.targetTitle,
			targetUrl = entity.targetUrl,
			status = entity.status,
			progress = entity.progress,
			rating = entity.rating,
			comment = entity.comment,
			lastSyncAt = entity.lastSyncAt,
		)
	}
}
