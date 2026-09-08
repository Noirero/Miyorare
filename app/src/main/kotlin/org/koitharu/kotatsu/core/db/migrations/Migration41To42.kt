package org.koitharu.kotatsu.core.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Gives Advanced Library Groups the same FavouriteSpace boundary as the rest of Favourites.
 * Existing groups predate Private groups, so they are safely classified as Normal.
 */
class Migration41To42 : Migration(41, 42) {

	override fun migrate(db: SupportSQLiteDatabase) {
		db.execSQL("ALTER TABLE `library_groups` ADD COLUMN `space` INTEGER NOT NULL DEFAULT 0")
		db.execSQL(
			"CREATE INDEX IF NOT EXISTS `index_library_groups_space` ON `library_groups` (`space`)",
		)
	}
}
