package org.koitharu.kotatsu.readerjourney.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
	tableName = "reader_journey_xp_events",
	indices = [
		Index(value = ["occurred_at"]),
		Index(value = ["source", "occurred_at"]),
	],
)
data class ReaderJourneyXpEventEntity(
	@PrimaryKey
	@ColumnInfo(name = "event_key")
	val eventKey: String,
	@ColumnInfo(name = "source")
	val source: String,
	@ColumnInfo(name = "xp")
	val xp: Int,
	@ColumnInfo(name = "occurred_at")
	val occurredAt: Long,
	@ColumnInfo(name = "manga_id")
	val mangaId: Long? = null,
	@ColumnInfo(name = "chapter_id")
	val chapterId: Long? = null,
	@ColumnInfo(name = "context")
	val context: String? = null,
	@ColumnInfo(name = "profile_delta")
	val profileDelta: Boolean = true,
)

@Entity(tableName = "reader_journey_weekly_state")
data class ReaderJourneyWeeklyStateEntity(
	@PrimaryKey
	@ColumnInfo(name = "week_key")
	val weekKey: String,
	@ColumnInfo(name = "task_ids")
	val taskIds: String,
	@ColumnInfo(name = "rerolls_used")
	val rerollsUsed: Int = 0,
	@ColumnInfo(name = "updated_at")
	val updatedAt: Long = 0L,
)
