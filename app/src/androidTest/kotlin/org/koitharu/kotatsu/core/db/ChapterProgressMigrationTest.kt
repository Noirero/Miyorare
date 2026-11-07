package org.koitharu.kotatsu.core.db

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.koitharu.kotatsu.core.db.migrations.Migration53To54

/** Verifies additive schema migration preserves legacy History rows with no backfill. */
@RunWith(AndroidJUnit4::class)
class ChapterProgressMigrationTest {
    @Test fun migrationAddsEmptyProgressTableWithoutTouchingExistingHistory() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "progress-schema-53-to-54-test.db"
        context.deleteDatabase(name)
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(53) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE manga (manga_id INTEGER NOT NULL PRIMARY KEY)")
                        db.execSQL("CREATE TABLE history (manga_id INTEGER NOT NULL, chapter_id INTEGER NOT NULL, page INTEGER NOT NULL)")
                        db.execSQL("INSERT INTO manga(manga_id) VALUES (5)")
                        db.execSQL("INSERT INTO history(manga_id, chapter_id, page) VALUES (5, 218, 13)")
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                }).build(),
        )
        try {
            val db = helper.writableDatabase
            Migration53To54().migrate(db)
            db.query("SELECT chapter_id, page FROM history WHERE manga_id = 5").use { cursor ->
                assertEquals(true, cursor.moveToFirst())
                assertEquals(218L, cursor.getLong(0))
                assertEquals(13, cursor.getInt(1))
            }
            db.query("SELECT COUNT(*) FROM chapter_progress").use { cursor ->
                assertEquals(true, cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
            db.execSQL("INSERT INTO chapter_progress (manga_id, source, url, space, page, scroll, total_pages) VALUES (5, 'TEST', '/218', 0, 13, 0, 38)")
            db.query("SELECT page FROM chapter_progress WHERE manga_id = 5").use { cursor ->
                assertEquals(true, cursor.moveToFirst())
                assertEquals(13, cursor.getInt(0))
            }
        } finally {
            helper.close()
            context.deleteDatabase(name)
        }
    }
}
