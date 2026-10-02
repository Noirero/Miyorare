package org.koitharu.kotatsu.sync.library

import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.koitharu.kotatsu.SampleData
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.db.entity.toEntity
import org.koitharu.kotatsu.core.db.migrations.Migration49To50

@RunWith(AndroidJUnit4::class)
class LibrarySyncPersistenceTest {
	private val context = InstrumentationRegistry.getInstrumentation().targetContext

	@Test
	fun mappingAndInboxSurviveDatabaseReopen() = runBlocking {
		val name = "library-sync-test.db"
		context.deleteDatabase(name)
		fun open() = Room.databaseBuilder(context, MangaDatabase::class.java, name).build()
		try {
			val first = open()
			try {
				val db = first
				val manga = SampleData.manga.toEntity()
				db.getMangaDao().upsert(manga)
				db.getLibrarySyncDao().put(LibrarySyncMappingEntity("ANILIST", "7", manga.id, 1000))
				db.getLibrarySyncDao()
					.put(
						listOf(
							LibrarySyncEntryEntity(
								"ANILIST",
								"8",
								"Pending title",
								3,
								"CURRENT",
								2000,
								"17",
							)
						)
					)
			} finally {
				first.close()
			}
			val reopened = open()
			try {
				val db = reopened
				assertEquals("7", db.getLibrarySyncDao().mappings("ANILIST").single().externalId)
				assertNull(
					db.getLibrarySyncDao().entries("ANILIST").single().toEntry().localMangaId
				)
				assertTrue(db.getFavouritesDao().findAll().isEmpty())
				assertTrue(db.getLibrarySyncDao().mappings("KITSU").isEmpty())
			} finally {
				reopened.close()
			}
		} finally {
			context.deleteDatabase(name)
		}
	}

	@Test
	fun migrationKeepsMangaAndCreatesServiceScopedIndexes() {
		val name = "library-sync-migration-test.db"
		context.deleteDatabase(name)
		val helper =
			FrameworkSQLiteOpenHelperFactory()
				.create(
					SupportSQLiteOpenHelper.Configuration.builder(context)
						.name(name)
						.callback(
							object : SupportSQLiteOpenHelper.Callback(49) {
								override fun onCreate(db: SupportSQLiteDatabase) {
									db.execSQL(
										"CREATE TABLE manga (manga_id INTEGER NOT NULL PRIMARY KEY)"
									)
									db.execSQL("INSERT INTO manga VALUES (1)")
								}

								override fun onUpgrade(
									db: SupportSQLiteDatabase,
									oldVersion: Int,
									newVersion: Int,
								) = Unit
							}
						)
						.build()
				)
		try {
			val db = helper.writableDatabase
			Migration49To50().migrate(db)
			db.execSQL("INSERT INTO library_sync_mappings VALUES ('ANILIST', '7', 1, 1000)")
			db.execSQL("INSERT INTO library_sync_mappings VALUES ('KITSU', '8', 1, 1000)")
			db.query("SELECT count(*) FROM manga").use {
				assertTrue(it.moveToFirst())
				assertEquals(1, it.getInt(0))
			}
			db.query("SELECT count(*) FROM library_sync_mappings").use {
				assertTrue(it.moveToFirst())
				assertEquals(2, it.getInt(0))
			}
			var rejected = false
			try {
				db.execSQL("INSERT INTO library_sync_mappings VALUES ('ANILIST', '9', 1, 2000)")
			} catch (_: android.database.sqlite.SQLiteConstraintException) {
				rejected = true
			}
			assertTrue(rejected)
		} finally {
			helper.close()
			context.deleteDatabase(name)
		}
	}

	@Test
	fun secretsAreAuthenticatedCiphertextExcludedFromBackups() {
		val store = LibrarySyncSecretStore(context)
		val dummy = "test-token-not-a-real-credential"
		try {
			store.put(LibrarySyncServiceId.ANILIST, "test", dummy)
			assertEquals(
				dummy,
				LibrarySyncSecretStore(context).get(LibrarySyncServiceId.ANILIST, "test"),
			)
			val file = File(context.noBackupFilesDir, "library-sync-secrets/ANILIST-test")
			assertFalse(file.readBytes().toString(Charsets.UTF_8).contains(dummy))
			val corrupted =
				file.readBytes().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
			file.writeBytes(corrupted)
			assertNull(store.get(LibrarySyncServiceId.ANILIST, "test"))
		} finally {
			store.put(LibrarySyncServiceId.ANILIST, "test", null)
		}
	}
}
