package org.koitharu.kotatsu.core.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

class Migration38To39 : Migration(38, 39) {

	override fun migrate(db: SupportSQLiteDatabase) {
		db.execSQL(
			"CREATE TABLE IF NOT EXISTS `library_group_timeline` (" +
				"`group_id` INTEGER NOT NULL, " +
				"`manga_id` INTEGER NOT NULL, " +
				"`chapter_id` INTEGER NOT NULL, " +
				"`position` INTEGER NOT NULL, " +
				"PRIMARY KEY(`group_id`, `manga_id`, `chapter_id`), " +
				"FOREIGN KEY(`group_id`, `manga_id`) REFERENCES `library_group_members`(`group_id`, `manga_id`) " +
				"ON UPDATE NO ACTION ON DELETE CASCADE" +
				")",
		)
		db.execSQL(
			"CREATE INDEX IF NOT EXISTS `index_library_group_timeline_group_id_manga_id` " +
				"ON `library_group_timeline` (`group_id`, `manga_id`)",
		)
		db.execSQL(
			"CREATE INDEX IF NOT EXISTS `index_library_group_timeline_group_id_position` " +
				"ON `library_group_timeline` (`group_id`, `position`)",
		)
	}
}
