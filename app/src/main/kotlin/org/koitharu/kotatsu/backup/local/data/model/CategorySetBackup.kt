package org.koitharu.kotatsu.backup.local.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.koitharu.kotatsu.favourites.data.FavouriteCategorySetEntity

/** A separate payload is essential: a Set may have no categories at all. Local IDs are omitted. */
@Serializable
data class CategorySetBackup(
	@SerialName("sync_key") val syncKey: String,
	@SerialName("title") val title: String,
	@SerialName("sort_key") val sortKey: Int,
	@SerialName("created_at") val createdAt: Long,
	@SerialName("updated_at") val updatedAt: Long,
	@SerialName("space") val space: Int,
) {
	constructor(entity: FavouriteCategorySetEntity) : this(entity.syncKey, entity.title, entity.sortKey, entity.createdAt, entity.updatedAt, entity.space)
	fun toEntity(id: Long = 0) = FavouriteCategorySetEntity(id, syncKey, title, sortKey, createdAt, updatedAt, 0, space)
}
