package org.koitharu.kotatsu.core.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Adds a lightweight Normal/Private ownership index for ordinary favourites download status. */
class Migration44To45 : Migration(44, 45) {

	override fun migrate(db: SupportSQLiteDatabase) {
		db.execSQL(
			"CREATE TABLE IF NOT EXISTS `favourite_download_index` (" +
				"`manga_id` INTEGER NOT NULL, " +
				"`space` INTEGER NOT NULL, " +
				"`path` TEXT NOT NULL, " +
				"PRIMARY KEY(`manga_id`, `space`), " +
				"FOREIGN KEY(`manga_id`) REFERENCES `manga`(`manga_id`) " +
				"ON UPDATE NO ACTION ON DELETE CASCADE" +
				")",
		)
		db.execSQL(
			"CREATE INDEX IF NOT EXISTS `index_favourite_download_index_manga_id` " +
				"ON `favourite_download_index` (`manga_id`)",
		)
		db.execSQL(
			"CREATE INDEX IF NOT EXISTS `index_favourite_download_index_path` " +
				"ON `favourite_download_index` (`path`)",
		)
	}
}
