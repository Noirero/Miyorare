package org.koitharu.kotatsu.core.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Persists whether the chapter snapshot has been initialized, including a valid empty result.
 *
 * Backfill is deliberately conservative: only rows that already have chapter rows are marked
 * initialized. Ambiguous old zero-chapter rows are refreshed once rather than risking that a
 * previously-GCed cache is mistaken for an authoritative empty source result.
 */
class Migration45To46 : Migration(45, 46) {

	override fun migrate(db: SupportSQLiteDatabase) {
		db.execSQL(
			"ALTER TABLE `manga` ADD COLUMN `chapters_initialized` INTEGER NOT NULL DEFAULT 0",
		)
		db.execSQL(
			"""
			UPDATE manga
			SET chapters_initialized = 1
			WHERE EXISTS (
				SELECT 1 FROM chapters
				WHERE chapters.manga_id = manga.manga_id
			)
			""".trimIndent(),
		)
	}
}
