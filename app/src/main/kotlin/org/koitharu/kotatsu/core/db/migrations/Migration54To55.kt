package org.koitharu.kotatsu.core.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Preserve pre-existing bookmarks as Reader-owned; new Details swipe bookmarks are explicit. */
class Migration54To55 : Migration(54, 55) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `bookmarks` ADD COLUMN `origin` TEXT NOT NULL DEFAULT 'READER'")
    }
}
