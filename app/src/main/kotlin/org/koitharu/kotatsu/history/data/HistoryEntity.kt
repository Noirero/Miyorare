package org.koitharu.kotatsu.history.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import org.koitharu.kotatsu.core.db.TABLE_HISTORY
import org.koitharu.kotatsu.core.db.entity.MangaEntity

@Entity(
	tableName = TABLE_HISTORY,
	indices = [
		Index(value = ["last_reader_activity_at", "legacy_resume_updated_at"]),
		Index(value = ["legacy_resume_updated_at"]),
	],
	foreignKeys = [
		ForeignKey(
			entity = MangaEntity::class,
			parentColumns = ["manga_id"],
			childColumns = ["manga_id"],
			onDelete = ForeignKey.CASCADE,
		),
	],
)
data class HistoryEntity(
	@PrimaryKey(autoGenerate = false)
	@ColumnInfo(name = "manga_id") val mangaId: Long,
	@ColumnInfo(name = "created_at") val createdAt: Long,
	@ColumnInfo(name = "updated_at") val updatedAt: Long,
	@ColumnInfo(name = "chapter_id") val chapterId: Long,
	@ColumnInfo(name = "page") val page: Int,
	@ColumnInfo(name = "scroll") val scroll: Float,
	@ColumnInfo(name = "percent") val percent: Float,
	@ColumnInfo(name = "deleted_at") val deletedAt: Long,
	@ColumnInfo(name = "chapters") val chaptersCount: Int,
	/** Device-local evidence of a saved Reader state. Progress imports never set this. */
	@ColumnInfo(name = "last_reader_activity_at", defaultValue = "0") val lastReaderActivityAt: Long = 0L,
	/** Frozen pre-upgrade ordering, not evidence of reading. Retired on the first Reader save. */
	@ColumnInfo(name = "legacy_resume_updated_at", defaultValue = "0") val legacyResumeUpdatedAt: Long = 0L,
)
