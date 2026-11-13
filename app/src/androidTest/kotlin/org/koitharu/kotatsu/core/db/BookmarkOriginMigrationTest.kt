package org.koitharu.kotatsu.core.db

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.koitharu.kotatsu.core.db.migrations.Migration54To55

/** Upgrade an actual v54 table and verify that no legacy Reader bookmark is re-owned. */
@RunWith(AndroidJUnit4::class)
class BookmarkOriginMigrationTest {

    @Test
    fun migrationPreservesLegacyRowsAsReaderOwned() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "bookmark-origin-migration-test.db"
        context.deleteDatabase(name)
        try {
            val old = context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null)
            try {
                old.execSQL(
                    """CREATE TABLE bookmarks (
                        manga_id INTEGER NOT NULL,
                        page_id INTEGER NOT NULL,
                        chapter_id INTEGER NOT NULL,
                        page INTEGER NOT NULL,
                        scroll INTEGER NOT NULL,
                        image TEXT NOT NULL,
                        created_at INTEGER NOT NULL,
                        percent REAL NOT NULL,
                        note TEXT,
                        PRIMARY KEY(manga_id, page_id)
                    )""".trimIndent(),
                )
                old.execSQL(
                    "INSERT INTO bookmarks (manga_id, page_id, chapter_id, page, scroll, image, created_at, percent, note) VALUES (1, 101, 5, 3, 0, 'page.png', 10, 0.1, 'keep')",
                )
                old.version = 54
            } finally {
                old.close()
            }
            val callback = object : SupportSQLiteOpenHelper.Callback(55) {
                override fun onCreate(db: SupportSQLiteDatabase) = Unit
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                    assertEquals(54, oldVersion)
                    assertEquals(55, newVersion)
                    Migration54To55().migrate(db)
                }
            }
            val config = SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name)
                .callback(callback)
                .build()
            val helper = FrameworkSQLiteOpenHelperFactory().create(config)
            try {
                helper.writableDatabase.query(
                    "SELECT page_id, origin, note FROM bookmarks WHERE manga_id = 1",
                ).use { cursor ->
                    org.junit.Assert.assertTrue(cursor.moveToFirst())
                    assertEquals(101L, cursor.getLong(0))
                    assertEquals("READER", cursor.getString(1))
                    assertEquals("keep", cursor.getString(2))
                    org.junit.Assert.assertFalse(cursor.moveToNext())
                }
            } finally {
                helper.close()
            }
        } finally {
            context.deleteDatabase(name)
        }
    }
}
