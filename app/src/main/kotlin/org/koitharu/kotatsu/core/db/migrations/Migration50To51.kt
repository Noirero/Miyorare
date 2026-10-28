package org.koitharu.kotatsu.core.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

class Migration50To51 : Migration(50, 51) {
	override fun migrate(db: SupportSQLiteDatabase) {
		if (!db.hasColumn("history", "space")) {
			db.execSQL("ALTER TABLE history ADD COLUMN space INTEGER NOT NULL DEFAULT 0")
		}
		db.execSQL("CREATE INDEX IF NOT EXISTS index_history_space ON history(space)")
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
