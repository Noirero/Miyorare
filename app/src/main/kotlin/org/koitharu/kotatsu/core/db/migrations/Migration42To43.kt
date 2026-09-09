package org.koitharu.kotatsu.core.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Adds optional notes to bookmarks/EPUB highlights without rewriting existing rows. */
class Migration42To43 : Migration(42, 43) {

	override fun migrate(db: SupportSQLiteDatabase) {
		db.execSQL("ALTER TABLE `bookmarks` ADD COLUMN `note` TEXT")
	}
}
