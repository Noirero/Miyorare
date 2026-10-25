package org.koitharu.kotatsu.favourites.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** The only category → Set mapping. Category sort_key remains the only category order. */
@Entity(
	tableName = "favourite_category_set_assignments",
	foreignKeys = [
		ForeignKey(entity = FavouriteCategoryEntity::class, parentColumns = ["category_id"], childColumns = ["category_id"], onDelete = ForeignKey.CASCADE),
		ForeignKey(entity = FavouriteCategorySetEntity::class, parentColumns = ["set_id"], childColumns = ["set_id"], onDelete = ForeignKey.RESTRICT),
	],
	indices = [Index("set_id"), Index(value = ["category_sync_key"], unique = true)],
)
data class FavouriteCategorySetAssignmentEntity(
	@PrimaryKey @ColumnInfo(name = "category_id") val categoryId: Long,
	@ColumnInfo(name = "set_id") val setId: Long,
	@ColumnInfo(name = "category_sync_key") val categorySyncKey: String,
	@ColumnInfo(name = "updated_at") val updatedAt: Long,
)
