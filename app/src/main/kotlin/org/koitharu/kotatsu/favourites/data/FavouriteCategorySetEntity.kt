package org.koitharu.kotatsu.favourites.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Sets own categories, never manga. Tombstones keep a deleted identity from being resurrected. */
@Entity(tableName = "favourite_category_sets", indices = [Index(value = ["sync_key"], unique = true), Index("space")])
data class FavouriteCategorySetEntity(
	@PrimaryKey(autoGenerate = true) @ColumnInfo(name = "set_id") val setId: Long = 0,
	@ColumnInfo(name = "sync_key") val syncKey: String,
	@ColumnInfo(name = "title") val title: String,
	@ColumnInfo(name = "sort_key") val sortKey: Int,
	@ColumnInfo(name = "created_at") val createdAt: Long,
	@ColumnInfo(name = "updated_at") val updatedAt: Long,
	@ColumnInfo(name = "deleted_at") val deletedAt: Long = 0,
	@ColumnInfo(name = "space") val space: Int,
)

const val EXTRA_FAVOURITE_SET_ID = "favourite_set_id"
const val EXTRA_FAVOURITE_CONTENT_TYPE = "favourite_content_type"
