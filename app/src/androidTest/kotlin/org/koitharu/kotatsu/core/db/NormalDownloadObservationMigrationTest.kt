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
import org.koitharu.kotatsu.core.db.migrations.Migration56To57

/** Actual SQLite v56 -> v57 and reopen, with existing ownership and Ex presence retained. */
@RunWith(AndroidJUnit4::class)
class NormalDownloadObservationMigrationTest {
	@Test fun v56To57PreservesExistingRecordsAndKeepsOldSessionRowsSeparate() {
		val context = InstrumentationRegistry.getInstrumentation().targetContext
		val name = "normal-observation-migration-test.db"
		context.deleteDatabase(name)
		try {
			context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { old ->
				old.execSQL("CREATE TABLE manga (manga_id INTEGER NOT NULL PRIMARY KEY)")
				old.execSQL("INSERT INTO manga VALUES (42)")
				old.execSQL("CREATE TABLE favourite_download_index (manga_id INTEGER NOT NULL, space INTEGER NOT NULL, path TEXT NOT NULL, PRIMARY KEY(manga_id, space))")
				old.execSQL("INSERT INTO favourite_download_index VALUES (42, 0, '/old/file.cbz')")
				old.execSQL("CREATE TABLE exhentai_presence (space INTEGER NOT NULL, mangaId INTEGER NOT NULL, root TEXT NOT NULL, path TEXT NOT NULL, PRIMARY KEY(space, mangaId, path))")
				old.execSQL("INSERT INTO exhentai_presence VALUES (0, 42, '/old', '/old/ex.cbz')")
				old.version = 56
			}
			fun helper() = FrameworkSQLiteOpenHelperFactory().create(
				SupportSQLiteOpenHelper.Configuration.builder(context).name(name).callback(
					object : SupportSQLiteOpenHelper.Callback(57) {
						override fun onCreate(db: SupportSQLiteDatabase) = Unit
						override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
							assertEquals(56, oldVersion)
							assertEquals(57, newVersion)
							Migration56To57().migrate(db)
						}
					},
				).build(),
			)
			helper().use { h ->
				val db = h.writableDatabase
				db.query("SELECT path FROM favourite_download_index WHERE manga_id=42").use {
					assertTrue(it.moveToFirst()); assertEquals("/old/file.cbz", it.getString(0))
				}
				db.query("SELECT path FROM exhentai_presence WHERE mangaId=42").use {
					assertTrue(it.moveToFirst()); assertEquals("/old/ex.cbz", it.getString(0))
				}
				db.execSQL("INSERT INTO normal_download_observation VALUES (42, 'old-session', 1, 1, '/old', 'PRESENT', '/old/file.cbz')")
			}
			helper().use { h ->
				val db = h.writableDatabase
				db.query("SELECT path FROM normal_download_observation WHERE session='old-session' AND rootEpoch=1").use {
					assertTrue(it.moveToFirst()); assertEquals("/old/file.cbz", it.getString(0))
				}
				db.query("SELECT COUNT(*) FROM normal_download_observation WHERE session='new-session' AND rootEpoch=1").use {
					assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0))
				}
			}
		} finally { context.deleteDatabase(name) }
	}
}
