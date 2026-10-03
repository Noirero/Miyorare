package org.koitharu.kotatsu.core.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

class Migration49To50 : Migration(49, 50) {
	override fun migrate(db: SupportSQLiteDatabase) {
		db.execSQL(
			"CREATE TABLE IF NOT EXISTS `library_sync_mappings` (`service` TEXT NOT NULL, `external_id` TEXT NOT NULL, `local_manga_id` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`service`, `external_id`), FOREIGN KEY(`local_manga_id`) REFERENCES `manga`(`manga_id`) ON UPDATE NO ACTION ON DELETE CASCADE)"
		)
		db.execSQL(
			"CREATE UNIQUE INDEX IF NOT EXISTS `index_library_sync_mappings_service_local_manga_id` ON `library_sync_mappings` (`service`, `local_manga_id`)"
		)
		db.execSQL(
			"CREATE INDEX IF NOT EXISTS `index_library_sync_mappings_local_manga_id` ON `library_sync_mappings` (`local_manga_id`)"
		)
		db.execSQL(
			"CREATE TABLE IF NOT EXISTS `library_sync_entries` (`service` TEXT NOT NULL, `external_id` TEXT NOT NULL, `title` TEXT NOT NULL, `progress` INTEGER NOT NULL, `status` TEXT, `updated_at` INTEGER NOT NULL, `remote_entry_id` TEXT, PRIMARY KEY(`service`, `external_id`))"
		)
	}
}
