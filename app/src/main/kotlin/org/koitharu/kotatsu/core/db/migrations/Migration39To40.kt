package org.koitharu.kotatsu.core.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Adds Private Favourites without moving or duplicating any existing manga/chapter data. */
class Migration39To40 : Migration(39, 40) {

	override fun migrate(db: SupportSQLiteDatabase) {
		// Existing categories are normal by definition. The NOT NULL DEFAULT keeps every upgraded
		// installation backwards-compatible without a destructive rewrite.
		db.execSQL("ALTER TABLE `favourite_categories` ADD COLUMN `space` INTEGER NOT NULL DEFAULT 0")
		db.execSQL(
			"CREATE INDEX IF NOT EXISTS `index_favourite_categories_space` " +
				"ON `favourite_categories` (`space`)",
		)
		db.execSQL(
			"CREATE TABLE IF NOT EXISTS `private_favourites` (" +
				"`manga_id` INTEGER NOT NULL, " +
				"`category_id` INTEGER NOT NULL, " +
				"`sort_key` INTEGER NOT NULL, " +
				"`pinned` INTEGER NOT NULL, " +
				"`created_at` INTEGER NOT NULL, " +
				"`deleted_at` INTEGER NOT NULL, " +
				"PRIMARY KEY(`manga_id`, `category_id`), " +
				"FOREIGN KEY(`manga_id`) REFERENCES `manga`(`manga_id`) ON UPDATE NO ACTION ON DELETE CASCADE, " +
				"FOREIGN KEY(`category_id`) REFERENCES `favourite_categories`(`category_id`) ON UPDATE NO ACTION ON DELETE CASCADE" +
				")",
		)
		db.execSQL(
			"CREATE INDEX IF NOT EXISTS `index_private_favourites_manga_id` " +
				"ON `private_favourites` (`manga_id`)",
		)
		db.execSQL(
			"CREATE INDEX IF NOT EXISTS `index_private_favourites_category_id` " +
				"ON `private_favourites` (`category_id`)",
		)
	}
}
