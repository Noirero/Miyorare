package org.koitharu.kotatsu.core.db

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.koitharu.kotatsu.core.db.migrations.Migration55To56

/**
 * Opens an actual file written by historical v55 Room code at immutable SHA
 * b4e66ec6130bac5e05d77af2ca6cc11c99ad1190, not a downgraded v56 database.
 * Only the diagnostic CI lane produces this file; never silently build a substitute.
 */
@RunWith(AndroidJUnit4::class)
class HistoricalV55CompatibilityTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun historicalRoomV55DatabaseUpgradesWithoutLosingGroupChildren() = runBlocking {
        val name = "historical-v55-library-groups"
        val path = context.getDatabasePath(name)
        assertTrue("Missing database created by historical v55 Room instrumentation", path.isFile)
        var scopedId = -1L
        var defaultId = -1L
        var otherSetId = -1L
        var defaultSetId = -1L
        val historical = SQLiteDatabase.openDatabase(
            path.absolutePath, null, SQLiteDatabase.OPEN_READONLY,
        )
        try {
            assertEquals("Input is not a historical v55 database", 55, historical.version)
            historical.rawQuery("SELECT identity_hash FROM room_master_table WHERE id = 42", null).use {
                assertTrue("Historical Room identity hash missing", it.moveToFirst())
                assertTrue(it.getString(0).isNotBlank())
            }
            historical.rawQuery("PRAGMA table_info(library_groups)", null).use { cursor ->
                val column = cursor.getColumnIndexOrThrow("name")
                while (cursor.moveToNext()) {
                    assertNotEquals("Historical v55 unexpectedly has set_id", "set_id", cursor.getString(column))
                }
            }
            scopedId = queryLong(historical, "SELECT group_id FROM library_groups WHERE title = 'Historical scoped'")
            defaultId = queryLong(historical, "SELECT group_id FROM library_groups WHERE title = 'Historical default'")
            otherSetId = queryLong(historical, "SELECT set_id FROM favourite_category_sets WHERE sync_key = 'historical:v55:other'")
            defaultSetId = queryLong(historical, "SELECT set_id FROM favourite_category_sets WHERE sync_key = 'miyorare:category-set:default:0'")
            assertEquals(2L, queryLong(historical, "SELECT COUNT(*) FROM library_group_members WHERE group_id = $scopedId"))
            assertEquals(1L, queryLong(historical, "SELECT COUNT(*) FROM library_group_timeline WHERE group_id = $scopedId"))
            assertEquals(1L, queryLong(historical, "SELECT COUNT(*) FROM library_group_categories WHERE group_id = $scopedId"))
            assertEquals(1L, queryLong(historical, "SELECT COUNT(*) FROM library_group_tracking WHERE group_id = $scopedId"))
            assertEquals(0L, countForeignKeyErrors(historical))
        } finally {
            historical.close()
        }

        val migrated = Room.databaseBuilder(context, MangaDatabase::class.java, name)
            .addMigrations(Migration55To56())
            .build()
        try {
            val sql = migrated.openHelper.writableDatabase
            assertEquals(56, sql.version) // Full Room v56 schema validation occurs on open.
            assertEquals(otherSetId, migrated.getLibraryGroupsDao().findGroup(scopedId)?.setId)
            assertEquals(defaultSetId, migrated.getLibraryGroupsDao().findGroup(defaultId)?.setId)
            assertEquals("preserve metadata", migrated.getLibraryGroupsDao().findGroup(scopedId)?.description)
            assertEquals(listOf(0, 1), migrated.getLibraryGroupsDao().findMembers(scopedId).map { it.position })
            assertEquals(1, migrated.getLibraryGroupsDao().findTimeline(scopedId).size)
            assertEquals(1, migrated.getLibraryGroupsDao().findCategories(scopedId).size)
            assertEquals(1, migrated.getLibraryGroupTrackingDao().findAll(scopedId).size)
            assertEquals(0, countRows(sql, "PRAGMA foreign_key_check"))
            assertEquals("ok", scalarText(sql, "PRAGMA integrity_check"))
        } finally {
            migrated.close()
        }

        val reopened = Room.databaseBuilder(context, MangaDatabase::class.java, name).build()
        try {
            assertEquals(56, reopened.openHelper.writableDatabase.version)
            assertEquals(otherSetId, reopened.getLibraryGroupsDao().findGroup(scopedId)?.setId)
            assertEquals(2, reopened.getLibraryGroupsDao().findMembers(scopedId).size)
            assertEquals(1, reopened.getLibraryGroupsDao().findTimeline(scopedId).size)
        } finally {
            reopened.close()
        }
    }

    private fun queryLong(db: SQLiteDatabase, sql: String): Long =
        db.rawQuery(sql, null).use {
            assertTrue("Missing historical fixture row: $sql", it.moveToFirst())
            it.getLong(0)
        }

    private fun countForeignKeyErrors(db: SQLiteDatabase): Long =
        db.rawQuery("PRAGMA foreign_key_check", null).use {
            var count = 0L
            while (it.moveToNext()) count++
            count
        }

    private fun countRows(db: androidx.sqlite.db.SupportSQLiteDatabase, sql: String): Int =
        db.query(sql).use {
            var count = 0
            while (it.moveToNext()) count++
            count
        }

    private fun scalarText(db: androidx.sqlite.db.SupportSQLiteDatabase, sql: String): String =
        db.query(sql).use {
            assertTrue(it.moveToFirst())
            it.getString(0)
        }
}
