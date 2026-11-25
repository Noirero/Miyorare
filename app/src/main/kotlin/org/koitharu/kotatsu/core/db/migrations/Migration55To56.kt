package org.koitharu.kotatsu.core.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Persist ExHentai presence independently from download-owned records. */
class Migration55To56 : Migration(55, 56) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS exhentai_presence (" +
            "space INTEGER NOT NULL, mangaId INTEGER NOT NULL, root TEXT NOT NULL, " +
            "path TEXT NOT NULL, PRIMARY KEY(space, mangaId, path))")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_exhentai_presence_space_root ON exhentai_presence (space, root)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_exhentai_presence_mangaId ON exhentai_presence (mangaId)")
    }
}
