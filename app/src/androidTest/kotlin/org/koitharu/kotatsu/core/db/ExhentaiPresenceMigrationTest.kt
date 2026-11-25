package org.koitharu.kotatsu.core.db

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koitharu.kotatsu.core.db.migrations.Migration55To56

/** Upgrade a v55 database; presence must not replace or erase downloaded ownership. */
@RunWith(AndroidJUnit4::class)
class ExhentaiPresenceMigrationTest {

    @Test
    fun v55ToV56CreatesIndependentPresenceIndexWithoutDeletingOwnership() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "exhentai-presence-migration-test.db"
        context.deleteDatabase(name)
        try {
            val old = context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null)
            try {
                old.execSQL(
                    "CREATE TABLE favourite_download_index (" +
                        "manga_id INTEGER NOT NULL, space INTEGER NOT NULL, " +
                        "path TEXT NOT NULL, PRIMARY KEY(manga_id, space))",
                )
                old.execSQL(
                    "INSERT INTO favourite_download_index VALUES " +
                        "(42, 0, '/existing/downloads/ExHentai/Chapter.cbz')",
                )
                old.version = 55
            } finally {
                old.close()
            }

            val callback = object : SupportSQLiteOpenHelper.Callback(56) {
                override fun onCreate(db: SupportSQLiteDatabase) = Unit

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                    assertEquals(55, oldVersion)
                    assertEquals(56, newVersion)
                    Migration55To56().migrate(db)
                }
            }
            val helper = FrameworkSQLiteOpenHelperFactory().create(
                SupportSQLiteOpenHelper.Configuration.builder(context)
                    .name(name)
                    .callback(callback)
                    .build(),
            )
            try {
                val db = helper.writableDatabase
                db.query("SELECT path FROM favourite_download_index WHERE manga_id = 42").use {
                    assertTrue(it.moveToFirst())
                    assertEquals("/existing/downloads/ExHentai/Chapter.cbz", it.getString(0))
                }
                val columns = LinkedHashSet<String>()
                db.query("PRAGMA table_info(exhentai_presence)").use {
                    val nameColumn = it.getColumnIndexOrThrow("name")
                    while (it.moveToNext()) columns += it.getString(nameColumn)
                }
                assertEquals(setOf("space", "mangaId", "root", "path"), columns)
                db.execSQL(
                    "INSERT INTO exhentai_presence(space, mangaId, root, path) " +
                        "VALUES (0, 42, '/active', '/active/downloads/ExHentai/Chapter.cbz')",
                )
                db.query("SELECT COUNT(*) FROM exhentai_presence WHERE mangaId = 42").use {
                    assertTrue(it.moveToFirst())
                    assertEquals(1, it.getInt(0))
                }
                val indexNames = LinkedHashSet<String>()
                db.query("PRAGMA index_list(exhentai_presence)").use {
                    val nameColumn = it.getColumnIndexOrThrow("name")
                    while (it.moveToNext()) indexNames += it.getString(nameColumn)
                }
                assertTrue("Missing space/root lookup index", "index_exhentai_presence_space_root" in indexNames)
                assertTrue("Missing manga lookup index", "index_exhentai_presence_mangaId" in indexNames)
            } finally {
                helper.close()
            }
        } finally {
            context.deleteDatabase(name)
        }
    }
}
