package org.koitharu.kotatsu.core.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

class Migration50To51 : Migration(50, 51) {
	override fun migrate(db: SupportSQLiteDatabase) {
		db.execSQL("ALTER TABLE history ADD COLUMN space INTEGER NOT NULL DEFAULT 0")
		db.execSQL("CREATE INDEX IF NOT EXISTS index_history_space ON history(space)")
	}
}
