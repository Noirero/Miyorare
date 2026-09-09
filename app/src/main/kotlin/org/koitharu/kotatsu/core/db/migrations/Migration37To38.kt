package org.koitharu.kotatsu.core.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

class Migration37To38 : Migration(37, 38) {

	override fun migrate(db: SupportSQLiteDatabase) {
		db.execSQL(
			"CREATE TABLE IF NOT EXISTS `library_groups` (" +
				"`group_id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
				"`title` TEXT NOT NULL, " +
				"`cover_url` TEXT, " +
				"`created_at` INTEGER NOT NULL" +
				")",
		)
		db.execSQL(
			"CREATE TABLE IF NOT EXISTS `library_group_members` (" +
				"`group_id` INTEGER NOT NULL, " +
				"`manga_id` INTEGER NOT NULL, " +
				"`position` INTEGER NOT NULL, " +
				"PRIMARY KEY(`group_id`, `manga_id`), " +
				"FOREIGN KEY(`group_id`) REFERENCES `library_groups`(`group_id`) ON UPDATE NO ACTION ON DELETE CASCADE, " +
				"FOREIGN KEY(`manga_id`) REFERENCES `manga`(`manga_id`) ON UPDATE NO ACTION ON DELETE CASCADE" +
				")",
		)
		db.execSQL("CREATE INDEX IF NOT EXISTS `index_library_group_members_group_id` ON `library_group_members` (`group_id`)")
		db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_library_group_members_manga_id` ON `library_group_members` (`manga_id`)")
	}
}
