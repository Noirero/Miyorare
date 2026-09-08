package org.koitharu.kotatsu.core.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

class Migration39To40 : Migration(39, 40) {

	override fun migrate(db: SupportSQLiteDatabase) {
		db.execSQL(
			"CREATE TABLE IF NOT EXISTS `library_group_categories` (" +
				"`group_id` INTEGER NOT NULL, " +
				"`category_id` INTEGER NOT NULL, " +
				"PRIMARY KEY(`group_id`, `category_id`), " +
				"FOREIGN KEY(`group_id`) REFERENCES `library_groups`(`group_id`) " +
				"ON UPDATE NO ACTION ON DELETE CASCADE" +
				")",
		)
		db.execSQL(
			"CREATE INDEX IF NOT EXISTS `index_library_group_categories_group_id` " +
				"ON `library_group_categories` (`group_id`)",
		)
		db.execSQL(
			"CREATE INDEX IF NOT EXISTS `index_library_group_categories_category_id` " +
				"ON `library_group_categories` (`category_id`)",
		)
	}
}
