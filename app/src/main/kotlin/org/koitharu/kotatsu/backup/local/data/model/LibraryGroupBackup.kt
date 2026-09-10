package org.koitharu.kotatsu.backup.local.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.koitharu.kotatsu.favourites.groups.data.LibraryGroupEntity
import org.koitharu.kotatsu.favourites.groups.data.LibraryGroupMemberEntity
import org.koitharu.kotatsu.favourites.groups.data.LibraryGroupTrackingEntity

@Serializable
data class LibraryGroupMemberBackup(
	@SerialName("manga_id") val mangaId: Long,
	@SerialName("position") val position: Int,
) {
	constructor(entity: LibraryGroupMemberEntity) : this(
		mangaId = entity.mangaId,
		position = entity.position,
	)
}

@Serializable
data class LibraryGroupTrackingBackup(
	@SerialName("service") val service: Int,
	@SerialName("rate_id") val rateId: Long,
	@SerialName("target_id") val targetId: Long,
	@SerialName("target_title") val targetTitle: String,
	@SerialName("target_url") val targetUrl: String? = null,
	@SerialName("status") val status: String? = null,
	@SerialName("progress") val progress: Int = 0,
	@SerialName("rating") val rating: Float = 0f,
	@SerialName("comment") val comment: String? = null,
	@SerialName("last_sync_at") val lastSyncAt: Long = 0L,
) {
	constructor(entity: LibraryGroupTrackingEntity) : this(
		service = entity.service,
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

@Serializable
data class LibraryGroupBackup(
	@SerialName("title") val title: String,
	@SerialName("cover_url") val coverUrl: String? = null,
	@SerialName("cover_data") val coverData: String? = null,
	@SerialName("cover_file_extension") val coverFileExtension: String? = null,
	@SerialName("alternative_title") val alternativeTitle: String? = null,
	@SerialName("author") val author: String? = null,
	@SerialName("artist") val artist: String? = null,
	@SerialName("description") val description: String? = null,
	@SerialName("metadata_source") val metadataSource: Int? = null,
	@SerialName("metadata_target_id") val metadataTargetId: Long? = null,
	@SerialName("category_ids") val categoryIds: List<Long> = emptyList(),
	@SerialName("created_at") val createdAt: Long,
	@SerialName("members") val members: List<LibraryGroupMemberBackup>,
	@SerialName("tracking") val tracking: List<LibraryGroupTrackingBackup> = emptyList(),
) {
	constructor(
		entity: LibraryGroupEntity,
		members: List<LibraryGroupMemberEntity>,
		coverData: String?,
		coverFileExtension: String?,
		categoryIds: Collection<Long> = emptyList(),
		tracking: Collection<LibraryGroupTrackingEntity> = emptyList(),
	) : this(
		title = entity.title,
		coverUrl = entity.coverUrl,
		coverData = coverData,
		coverFileExtension = coverFileExtension,
		alternativeTitle = entity.alternativeTitle,
		author = entity.author,
		artist = entity.artist,
		description = entity.description,
		metadataSource = entity.metadataSource,
		metadataTargetId = entity.metadataTargetId,
		categoryIds = categoryIds.distinct(),
		createdAt = entity.createdAt,
		members = members.map(::LibraryGroupMemberBackup),
		tracking = tracking.map(::LibraryGroupTrackingBackup),
	)
}
