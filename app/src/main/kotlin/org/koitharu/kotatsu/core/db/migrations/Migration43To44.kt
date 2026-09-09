package org.koitharu.kotatsu.core.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Gives Advanced Library Groups first-class metadata/tracking and removes the historical global
 * manga-member uniqueness rule. Group membership is already validated per FavouriteSpace by the
 * repository, so the same manga can safely belong to one Normal group and one Private group.
 */
class Migration43To44 : Migration(43, 44) {

	override fun migrate(db: SupportSQLiteDatabase) {
		db.execSQL("ALTER TABLE `library_groups` ADD COLUMN `alternative_title` TEXT")
		db.execSQL("ALTER TABLE `library_groups` ADD COLUMN `author` TEXT")
		db.execSQL("ALTER TABLE `library_groups` ADD COLUMN `artist` TEXT")
		db.execSQL("ALTER TABLE `library_groups` ADD COLUMN `description` TEXT")
		db.execSQL("ALTER TABLE `library_groups` ADD COLUMN `metadata_source` INTEGER")
		db.execSQL("ALTER TABLE `library_groups` ADD COLUMN `metadata_target_id` INTEGER")

		// The old UNIQUE manga index prevented the same title from being grouped independently in
		// Normal and Private. Per-space membership checks now live in LibraryGroupsRepository.
		db.execSQL("DROP INDEX IF EXISTS `index_library_group_members_manga_id`")
		db.execSQL(
			"CREATE INDEX IF NOT EXISTS `index_library_group_members_manga_id` " +
				"ON `library_group_members` (`manga_id`)",
		)

		db.execSQL(
			"CREATE TABLE IF NOT EXISTS `library_group_tracking` (" +
				"`group_id` INTEGER NOT NULL, " +
				"`service` INTEGER NOT NULL, " +
				"`rate_id` INTEGER NOT NULL, " +
				"`target_id` INTEGER NOT NULL, " +
				"`target_title` TEXT NOT NULL, " +
				"`target_url` TEXT, " +
				"`status` TEXT, " +
				"`progress` INTEGER NOT NULL, " +
				"`rating` REAL NOT NULL, " +
				"`comment` TEXT, " +
				"`last_sync_at` INTEGER NOT NULL, " +
				"PRIMARY KEY(`group_id`, `service`), " +
				"FOREIGN KEY(`group_id`) REFERENCES `library_groups`(`group_id`) " +
				"ON UPDATE NO ACTION ON DELETE CASCADE" +
				")",
		)
		db.execSQL(
			"CREATE INDEX IF NOT EXISTS `index_library_group_tracking_group_id` " +
				"ON `library_group_tracking` (`group_id`)",
		)
		db.execSQL(
			"CREATE INDEX IF NOT EXISTS `index_library_group_tracking_service` " +
				"ON `library_group_tracking` (`service`)",
		)
	}
}
