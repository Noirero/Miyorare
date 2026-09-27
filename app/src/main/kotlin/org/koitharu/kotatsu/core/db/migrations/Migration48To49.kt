package org.koitharu.kotatsu.core.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

class Migration48To49 : Migration(48, 49) {

	override fun migrate(db: SupportSQLiteDatabase) {
		db.execSQL(
			"ALTER TABLE `reader_journey_profile` ADD COLUMN `xp_floor` INTEGER NOT NULL DEFAULT 0",
		)
		db.execSQL(
			"""
			UPDATE `reader_journey_profile`
			SET `xp_floor` = MAX(
				0,
				`total_xp` - (SELECT IFNULL(SUM(`awarded_xp`), 0) FROM `reader_journey_chapters`)
			)
			""".trimIndent(),
		)
		db.execSQL(
			"""
			CREATE TABLE IF NOT EXISTS `reader_journey_xp_events` (
				`event_key` TEXT NOT NULL,
				`source` TEXT NOT NULL,
				`xp` INTEGER NOT NULL,
				`occurred_at` INTEGER NOT NULL,
				`manga_id` INTEGER,
				`chapter_id` INTEGER,
				`context` TEXT,
				`profile_delta` INTEGER NOT NULL,
				PRIMARY KEY(`event_key`)
			)
			""".trimIndent(),
		)
		db.execSQL(
			"CREATE INDEX IF NOT EXISTS `index_reader_journey_xp_events_occurred_at` ON `reader_journey_xp_events` (`occurred_at`)",
		)
		db.execSQL(
			"CREATE INDEX IF NOT EXISTS `index_reader_journey_xp_events_source_occurred_at` ON `reader_journey_xp_events` (`source`, `occurred_at`)",
		)
		db.execSQL(
			"""
			CREATE TABLE IF NOT EXISTS `reader_journey_weekly_state` (
				`week_key` TEXT NOT NULL,
				`task_ids` TEXT NOT NULL,
				`rerolls_used` INTEGER NOT NULL,
				`updated_at` INTEGER NOT NULL,
				PRIMARY KEY(`week_key`)
			)
			""".trimIndent(),
		)
	}
}
