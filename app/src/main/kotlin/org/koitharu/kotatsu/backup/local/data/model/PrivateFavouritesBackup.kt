package org.koitharu.kotatsu.backup.local.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.koitharu.kotatsu.core.db.entity.MangaWithTags
import org.koitharu.kotatsu.favourites.data.FavouriteCategoryEntity
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
import org.koitharu.kotatsu.favourites.data.PrivateFavouriteEntity
import org.koitharu.kotatsu.favourites.data.PrivateFavouriteManga

/**
 * Optional, isolated payload for Private Favourites. It is written to its own ZIP entry only when
 * the user explicitly opts in, so legacy CATEGORIES/FAVOURITES sections remain Normal-only.
 */
@Serializable
data class PrivateFavouritesBackup(
	@SerialName("categories") val categories: List<PrivateCategoryBackup> = emptyList(),
	@SerialName("favourites") val favourites: List<PrivateFavouriteItemBackup> = emptyList(),
)

@Serializable
data class PrivateCategoryBackup(
	@SerialName("category_id") val categoryId: Int,
	@SerialName("created_at") val createdAt: Long,
	@SerialName("sort_key") val sortKey: Int,
	@SerialName("title") val title: String,
	@SerialName("order") val order: String = "NEWEST",
	@SerialName("track") val track: Boolean = false,
	@SerialName("download_new_chapters") val downloadNewChapters: Boolean = false,
	@SerialName("show_in_lib") val isVisibleInLibrary: Boolean = true,
	@SerialName("content_type") val contentType: String? = null,
) {
	constructor(entity: FavouriteCategoryEntity, contentType: String? = null) : this(
		categoryId = entity.categoryId,
		createdAt = entity.createdAt,
		sortKey = entity.sortKey,
		title = entity.title,
		order = entity.order,
		track = entity.track,
		downloadNewChapters = entity.downloadNewChapters,
		isVisibleInLibrary = entity.isVisibleInLibrary,
		contentType = contentType,
	)

	fun toEntity() = FavouriteCategoryEntity(
		categoryId = categoryId,
		createdAt = createdAt,
		sortKey = sortKey,
		title = title,
		order = order,
		track = track,
		downloadNewChapters = downloadNewChapters,
		isVisibleInLibrary = isVisibleInLibrary,
		deletedAt = 0L,
		space = FavouriteSpace.PRIVATE.dbValue,
	)
}

@Serializable
data class PrivateFavouriteItemBackup(
	@SerialName("manga_id") val mangaId: Long,
	@SerialName("category_id") val categoryId: Long,
	@SerialName("sort_key") val sortKey: Int = 0,
	@SerialName("pinned") val isPinned: Boolean = false,
	@SerialName("created_at") val createdAt: Long,
	@SerialName("manga") val manga: MangaBackup,
) {
	constructor(entity: PrivateFavouriteManga) : this(
		mangaId = entity.manga.id,
		categoryId = entity.favourite.categoryId,
		sortKey = entity.favourite.sortKey,
		isPinned = entity.favourite.isPinned,
		createdAt = entity.favourite.createdAt,
		manga = MangaBackup(MangaWithTags(entity.manga, entity.tags)),
	)

	fun toEntity() = PrivateFavouriteEntity(
		// The embedded manga snapshot is authoritative. A malformed/stale duplicated manga_id field
		// must never create a membership whose foreign key points at a different manga.
		mangaId = manga.id,
		categoryId = categoryId,
		sortKey = sortKey,
		isPinned = isPinned,
		createdAt = createdAt,
		deletedAt = 0L,
	)
}
