package org.koitharu.kotatsu.readerjourney.data

import androidx.room.ColumnInfo
import androidx.room.Entity

@Entity(
	tableName = "reader_journey_chapters",
	primaryKeys = ["manga_id", "chapter_id"],
)
data class ReaderJourneyChapterEntity(
	@ColumnInfo(name = "manga_id") val mangaId: Long,
	@ColumnInfo(name = "chapter_id") val chapterId: Long,
	@ColumnInfo(name = "is_novel") val isNovel: Boolean,
	@ColumnInfo(name = "reading_units") val readingUnits: Int,
	@ColumnInfo(name = "completion_count") val completionCount: Int,
	@ColumnInfo(name = "awarded_xp") val awardedXp: Long,
	@ColumnInfo(name = "first_completed_at") val firstCompletedAt: Long,
	@ColumnInfo(name = "last_completed_at") val lastCompletedAt: Long,
)

@Entity(tableName = "reader_journey_profile")
data class ReaderJourneyProfileEntity(
	@androidx.room.PrimaryKey val id: Int = PROFILE_ID,
	@ColumnInfo(name = "total_xp") val totalXp: Long = 0L,
	@ColumnInfo(name = "completed_chapters") val completedChapters: Long = 0L,
	@ColumnInfo(name = "manga_chapters") val mangaChapters: Long = 0L,
	@ColumnInfo(name = "novel_chapters") val novelChapters: Long = 0L,
	@ColumnInfo(name = "updated_at") val updatedAt: Long = 0L,
) {
	companion object {
		const val PROFILE_ID = 0
	}
}
