package org.koitharu.kotatsu.core.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Introduces Reader Journey without backfilling old history.
 *
 * Existing reading history remains available to Stats, but XP intentionally starts from verified
 * completions observed after this migration so importing/migrating old history cannot create an XP
 * windfall.
 */
class Migration46To47 : Migration(46, 47) {

	override fun migrate(db: SupportSQLiteDatabase) {
		db.execSQL(
			"""
			CREATE TABLE IF NOT EXISTS `reader_journey_chapters` (
				`manga_id` INTEGER NOT NULL,
				`chapter_id` INTEGER NOT NULL,
				`is_novel` INTEGER NOT NULL,
				`reading_units` INTEGER NOT NULL,
				`completion_count` INTEGER NOT NULL,
				`awarded_xp` INTEGER NOT NULL,
				`first_completed_at` INTEGER NOT NULL,
				`last_completed_at` INTEGER NOT NULL,
				PRIMARY KEY(`manga_id`, `chapter_id`)
			)
			""".trimIndent(),
		)
		db.execSQL(
			"""
			CREATE TABLE IF NOT EXISTS `reader_journey_profile` (
				`id` INTEGER NOT NULL,
				`total_xp` INTEGER NOT NULL,
				`completed_chapters` INTEGER NOT NULL,
				`manga_chapters` INTEGER NOT NULL,
				`novel_chapters` INTEGER NOT NULL,
				`updated_at` INTEGER NOT NULL,
				PRIMARY KEY(`id`)
			)
			""".trimIndent(),
		)
	}
}
