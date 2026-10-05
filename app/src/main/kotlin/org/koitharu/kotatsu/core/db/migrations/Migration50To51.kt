package org.koitharu.kotatsu.core.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Old updated_at mixes Reader and administrative writes; preserve it only as a frozen fallback. */
class Migration50To51 : Migration(50, 51) {
	override fun migrate(db: SupportSQLiteDatabase) {
		db.execSQL("ALTER TABLE history ADD COLUMN last_reader_activity_at INTEGER NOT NULL DEFAULT 0")
		db.execSQL("ALTER TABLE history ADD COLUMN legacy_resume_updated_at INTEGER NOT NULL DEFAULT 0")
		db.execSQL("UPDATE history SET legacy_resume_updated_at = updated_at")
		db.execSQL("CREATE INDEX index_history_last_reader_activity_at_legacy_resume_updated_at ON history(last_reader_activity_at, legacy_resume_updated_at)")
		db.execSQL("CREATE INDEX index_history_legacy_resume_updated_at ON history(legacy_resume_updated_at)")
	}
}
