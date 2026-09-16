package org.koitharu.kotatsu.favourites.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import org.koitharu.kotatsu.core.db.entity.MangaEntity

/**
 * Space-aware ownership index used only by ordinary Normal/Private favourites screens.
 * The global local_index remains unchanged and continues to back the virtual Downloaded shelf.
 */
@Entity(
	tableName = "favourite_download_index",
	primaryKeys = ["manga_id", "space"],
	foreignKeys = [
		ForeignKey(
			entity = MangaEntity::class,
			parentColumns = ["manga_id"],
			childColumns = ["manga_id"],
			onDelete = ForeignKey.CASCADE,
		),
	],
	indices = [
		Index(value = ["manga_id"]),
		Index(value = ["path"]),
	],
)
data class FavouriteDownloadIndexEntity(
	@ColumnInfo(name = "manga_id") val mangaId: Long,
	@ColumnInfo(name = "space") val space: Int,
	@ColumnInfo(name = "path") val path: String,
)
