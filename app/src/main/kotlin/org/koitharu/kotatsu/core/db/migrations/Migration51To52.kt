package org.koitharu.kotatsu.core.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Preserve the original manga/chapter snapshot for recoverable partial migrations. */
class Migration51To52 : Migration(51, 52) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE manga ADD COLUMN preserved_to_manga_id INTEGER DEFAULT NULL")
        db.execSQL("ALTER TABLE manga ADD COLUMN simple_migration_preserved INTEGER NOT NULL DEFAULT 0")
    }
}
