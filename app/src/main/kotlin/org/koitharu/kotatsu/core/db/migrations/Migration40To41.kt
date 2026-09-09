package org.koitharu.kotatsu.core.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Reconciles the two historical v40 schemas:
 * - Beta v40 had Library Group category placement but no Private Favourites tables/space column.
 * - PF5 v40 had Private Favourites but no Library Group category placement table.
 *
 * Every operation is idempotent so either v40 shape safely converges to the same v41 schema.
 */
class Migration40To41 : Migration(40, 41) {

	override fun migrate(db: SupportSQLiteDatabase) {
		if (!db.hasColumn("favourite_categories", "space")) {
			db.execSQL("ALTER TABLE `favourite_categories` ADD COLUMN `space` INTEGER NOT NULL DEFAULT 0")
		}
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

	private fun SupportSQLiteDatabase.hasColumn(table: String, column: String): Boolean {
		query("PRAGMA table_info(`$table`)").use { cursor ->
			val nameIndex = cursor.getColumnIndex("name")
			while (cursor.moveToNext()) {
				if (nameIndex >= 0 && cursor.getString(nameIndex) == column) return true
			}
		}
		return false
	}
}
