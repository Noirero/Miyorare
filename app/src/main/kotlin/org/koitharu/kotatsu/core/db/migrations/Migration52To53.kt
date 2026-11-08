package org.koitharu.kotatsu.core.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Existing per-manga reader filters remain unchanged; custom tint is off by default. */
class Migration52To53 : Migration(52, 53) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE preferences ADD COLUMN cf_custom_enabled INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE preferences ADD COLUMN cf_custom_red INTEGER NOT NULL DEFAULT 70")
        db.execSQL("ALTER TABLE preferences ADD COLUMN cf_custom_green INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE preferences ADD COLUMN cf_custom_blue INTEGER NOT NULL DEFAULT 171")
        db.execSQL("ALTER TABLE preferences ADD COLUMN cf_custom_alpha INTEGER NOT NULL DEFAULT 115")
        db.execSQL("ALTER TABLE preferences ADD COLUMN cf_custom_mode TEXT NOT NULL DEFAULT 'OVERLAY'")
    }
}
