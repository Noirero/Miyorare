package org.koitharu.kotatsu.core.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Adds privacy-safe Reader Journey achievement persistence.
 *
 * Achievement ids are generic milestone ids only; no manga title, source, genre, tag or mature
 * identity is stored in this table.
 */
class Migration47To48 : Migration(47, 48) {

	override fun migrate(db: SupportSQLiteDatabase) {
		db.execSQL(
			"""
			CREATE TABLE IF NOT EXISTS `reader_journey_achievements` (
				`achievement_id` TEXT NOT NULL,
				`unlocked_at` INTEGER NOT NULL,
				PRIMARY KEY(`achievement_id`)
			)
			""".trimIndent(),
		)
	}
}
