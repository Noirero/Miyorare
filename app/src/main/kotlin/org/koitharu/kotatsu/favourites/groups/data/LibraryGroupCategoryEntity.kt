package org.koitharu.kotatsu.favourites.groups.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import org.koitharu.kotatsu.core.db.TABLE_LIBRARY_GROUP_CATEGORIES

@Entity(
	tableName = TABLE_LIBRARY_GROUP_CATEGORIES,
	primaryKeys = ["group_id", "category_id"],
	foreignKeys = [
		ForeignKey(
			entity = LibraryGroupEntity::class,
			parentColumns = ["group_id"],
			childColumns = ["group_id"],
			onDelete = ForeignKey.CASCADE,
		),
	],
	indices = [
		Index(value = ["group_id"]),
		Index(value = ["category_id"]),
	],
)
data class LibraryGroupCategoryEntity(
	@ColumnInfo(name = "group_id") val groupId: Long,
	@ColumnInfo(name = "category_id") val categoryId: Long,
)
