package org.koitharu.kotatsu.details.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import org.koitharu.kotatsu.core.db.entity.MangaEntity

/** User data lives independently of the replaceable/garbage-collected chapter cache. */
@Entity(
	tableName = "chapter_personal",
	primaryKeys = ["manga_id", "source", "url"],
	foreignKeys = [ForeignKey(
		entity = MangaEntity::class,
		parentColumns = ["manga_id"],
		childColumns = ["manga_id"],
		onDelete = ForeignKey.CASCADE,
	)],
)
data class ChapterPersonalEntity(
	@ColumnInfo(name = "manga_id") val mangaId: Long,
	@ColumnInfo(name = "source") val source: String,
	@ColumnInfo(name = "url") val url: String,
	@ColumnInfo(name = "rating") val rating: Int?,
	@ColumnInfo(name = "note") val note: String?,
) {
	fun key() = ChapterPersonalKey(source, url)
	fun toMetadata() = ChapterPersonalMetadata.normalized(rating, note)
}
