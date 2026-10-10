package org.koitharu.kotatsu.core.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

class Migration48To49 : Migration(48, 49) {
	override fun migrate(db: SupportSQLiteDatabase) {
		db.execSQL("""
			CREATE TABLE IF NOT EXISTS chapter_personal (
				manga_id INTEGER NOT NULL,
				source TEXT NOT NULL,
				url TEXT NOT NULL,
				rating INTEGER,
				note TEXT,
				PRIMARY KEY(manga_id, source, url),
				FOREIGN KEY(manga_id) REFERENCES manga(manga_id) ON UPDATE NO ACTION ON DELETE CASCADE
			)
		""".trimIndent())
	}
}
