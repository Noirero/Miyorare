package org.koitharu.kotatsu.core.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Additive user-owned chapter visit/position storage. Legacy History and overrides are untouched. */
class Migration53To54 : Migration(53, 54) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `chapter_progress` (
                `manga_id` INTEGER NOT NULL,
                `source` TEXT NOT NULL,
                `url` TEXT NOT NULL,
                `space` INTEGER NOT NULL DEFAULT 0,
                `page` INTEGER NOT NULL,
                `scroll` INTEGER NOT NULL,
                `total_pages` INTEGER,
                PRIMARY KEY(`manga_id`, `source`, `url`, `space`),
                FOREIGN KEY(`manga_id`) REFERENCES `manga`(`manga_id`)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
    }
}
