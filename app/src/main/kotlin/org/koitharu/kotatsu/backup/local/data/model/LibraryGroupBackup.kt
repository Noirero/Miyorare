package org.koitharu.kotatsu.backup.local.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.koitharu.kotatsu.favourites.groups.data.LibraryGroupEntity
import org.koitharu.kotatsu.favourites.groups.data.LibraryGroupMemberEntity

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
data class LibraryGroupBackup(
	@SerialName("title") val title: String,
	@SerialName("cover_url") val coverUrl: String? = null,
	@SerialName("cover_data") val coverData: String? = null,
	@SerialName("cover_file_extension") val coverFileExtension: String? = null,
	@SerialName("category_ids") val categoryIds: List<Long> = emptyList(),
	@SerialName("created_at") val createdAt: Long,
	@SerialName("members") val members: List<LibraryGroupMemberBackup>,
) {
	constructor(
		entity: LibraryGroupEntity,
		members: List<LibraryGroupMemberEntity>,
		coverData: String?,
		coverFileExtension: String?,
		categoryIds: Collection<Long> = emptyList(),
	) : this(
		title = entity.title,
		coverUrl = entity.coverUrl,
		coverData = coverData,
		coverFileExtension = coverFileExtension,
		categoryIds = categoryIds.distinct(),
		createdAt = entity.createdAt,
		members = members.map(::LibraryGroupMemberBackup),
	)
}
