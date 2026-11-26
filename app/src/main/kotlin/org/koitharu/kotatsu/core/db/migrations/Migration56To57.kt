package org.koitharu.kotatsu.core.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Separate scoped observations from all existing ownership and personal records. */
class Migration56To57 : Migration(56, 57) {
	override fun migrate(db: SupportSQLiteDatabase) {
		db.execSQL("CREATE TABLE IF NOT EXISTS normal_download_observation (" +
			"mangaId INTEGER NOT NULL PRIMARY KEY, session TEXT NOT NULL, rootEpoch INTEGER NOT NULL, " +
			"revision INTEGER NOT NULL, rootPath TEXT, status TEXT NOT NULL, path TEXT, " +
			"FOREIGN KEY(mangaId) REFERENCES manga(manga_id) ON UPDATE NO ACTION ON DELETE CASCADE)")
		db.execSQL("CREATE INDEX IF NOT EXISTS index_normal_download_observation_session_rootEpoch_status " +
			"ON normal_download_observation (session, rootEpoch, status)")
	}
}
