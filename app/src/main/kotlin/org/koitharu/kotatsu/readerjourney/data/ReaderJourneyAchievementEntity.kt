package org.koitharu.kotatsu.readerjourney.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "reader_journey_achievements")
data class ReaderJourneyAchievementEntity(
	@PrimaryKey
	@ColumnInfo(name = "achievement_id")
	val achievementId: String,
	@ColumnInfo(name = "unlocked_at")
	val unlockedAt: Long,
)
