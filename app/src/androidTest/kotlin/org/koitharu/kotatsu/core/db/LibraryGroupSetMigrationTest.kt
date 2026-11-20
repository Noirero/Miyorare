package org.koitharu.kotatsu.core.db

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.koitharu.kotatsu.SampleData
import org.koitharu.kotatsu.core.db.entity.toEntity
import org.koitharu.kotatsu.core.db.migrations.Migration55To56
import org.koitharu.kotatsu.favourites.data.FavouriteCategoryEntity
import org.koitharu.kotatsu.favourites.data.FavouriteCategorySetEntity
import org.koitharu.kotatsu.favourites.groups.data.LibraryGroupCategoryEntity
import org.koitharu.kotatsu.favourites.groups.data.LibraryGroupEntity
import org.koitharu.kotatsu.favourites.groups.data.LibraryGroupMemberEntity
import org.koitharu.kotatsu.favourites.groups.data.LibraryGroupTimelineItemEntity
import org.koitharu.kotatsu.favourites.groups.data.LibraryGroupTrackingEntity

/** A v55-shaped fixture derived from the v56 schema: must be complemented by an authentic v55 export. */
@RunWith(AndroidJUnit4::class)
class LibraryGroupSetMigrationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun upgradePreservesGroupChildrenAndResolvesOwners() = runBlocking {
        val name = "group-set-upgrade-fixture"
        context.deleteDatabase(name)
        try {
            val first = create(name)
            val ids = try {
                seed(first).also { downgradeTo55(first) }
            } finally {
                first.close()
            }
            val migrated = Room.databaseBuilder(context, MangaDatabase::class.java, name)
                .addMigrations(Migration55To56()).build()
            try {
                val sql = migrated.openHelper.writableDatabase // Room verifies the final schema.
                assertEquals(56, sql.version)
                val dao = migrated.getLibraryGroupsDao()
                assertEquals(ids.scopedSetId, dao.findGroup(ids.scopedGroupId)?.setId)
                assertEquals(ids.defaultSetId, dao.findGroup(ids.unplacedGroupId)?.setId)
                assertEquals(2, dao.findMembers(ids.scopedGroupId).size)
                assertEquals(1, dao.findTimeline(ids.scopedGroupId).size)
                assertEquals(1, dao.findCategories(ids.scopedGroupId).size)
                assertEquals(1, migrated.getLibraryGroupTrackingDao().findAll(ids.scopedGroupId).size)
                assertEquals("preserve metadata", dao.findGroup(ids.scopedGroupId)?.description)
                assertEquals(0, count(sql, "PRAGMA foreign_key_check"))
                assertEquals("ok", scalarText(sql, "PRAGMA integrity_check"))
                assertTrue(sqlTriggers(sql).containsKey("library_group_member_insert"))
            } finally {
                migrated.close()
            }
            val restarted = Room.databaseBuilder(context, MangaDatabase::class.java, name).build()
            try {
                assertEquals(ids.scopedSetId, restarted.getLibraryGroupsDao().findGroup(ids.scopedGroupId)?.setId)
            } finally {
                restarted.close()
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    @Test fun failedBackfillRollsBackSchemaAndPreservesRows() = runBlocking {
        val name = "group-set-upgrade-rollback"
        context.deleteDatabase(name)
        try {
            val initial = create(name)
            val ids = try {
                seed(initial).also { downgradeTo55(initial) }
            } finally {
                initial.close()
            }
            // v55 allows this otherwise-invalid space; no active Set can own it.
            val raw = SQLiteDatabase.openDatabase(
                context.getDatabasePath(name).absolutePath, null, SQLiteDatabase.OPEN_READWRITE,
            )
            try {
                raw.execSQL("UPDATE library_groups SET space = 9 WHERE group_id = ?", arrayOf(ids.scopedGroupId))
            } finally {
                raw.close()
            }
            val failed = Room.databaseBuilder(context, MangaDatabase::class.java, name)
                .addMigrations(Migration55To56()).build()
            try {
                assertThrows(Throwable::class.java) { failed.openHelper.writableDatabase }
            } finally {
                failed.close()
            }
            val verify = SQLiteDatabase.openDatabase(
                context.getDatabasePath(name).absolutePath, null, SQLiteDatabase.OPEN_READONLY,
            )
            try {
                assertEquals(55, verify.version)
                verify.rawQuery("PRAGMA table_info(library_groups)", null).use { cursor ->
                    val nameIndex = cursor.getColumnIndexOrThrow("name")
                    val names = ArrayList<String>()
                    while (cursor.moveToNext()) names += cursor.getString(nameIndex)
                    assertFalse(names.contains("set_id"))
                }
                verify.rawQuery("SELECT COUNT(*) FROM library_groups", null).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(2, cursor.getInt(0))
                }
            } finally {
                verify.close()
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    @Test fun upgradedAndFreshDatabasesHaveIdenticalGroupTriggers() = runBlocking {
        val upgradedName = "group-set-trigger-upgrade"
        val freshName = "group-set-trigger-fresh"
        context.deleteDatabase(upgradedName)
        context.deleteDatabase(freshName)
        try {
            val first = create(upgradedName)
            try {
                seed(first)
                downgradeTo55(first)
            } finally {
                first.close()
            }
            val upgraded = Room.databaseBuilder(context, MangaDatabase::class.java, upgradedName)
                .addMigrations(Migration55To56()).build()
            val fresh = create(freshName)
            try {
                val upgradedSql = upgraded.openHelper.writableDatabase
                val freshSql = fresh.openHelper.writableDatabase
                assertEquals(sqlTriggers(freshSql), sqlTriggers(upgradedSql))
                assertTrue(sqlTriggers(upgradedSql).isNotEmpty())
                for (sql in listOf(upgradedSql, freshSql)) {
                    assertThrows(Throwable::class.java) {
                        sql.execSQL(
                            "INSERT INTO library_groups(title,cover_url,created_at,space,set_id) " +
                                "VALUES ('invalid',NULL,1,0,0)",
                        )
                    }
                }
            } finally {
                upgraded.close()
                fresh.close()
            }
        } finally {
            context.deleteDatabase(upgradedName)
            context.deleteDatabase(freshName)
        }
    }

    private fun create(name: String): MangaDatabase =
        Room.databaseBuilder(context, MangaDatabase::class.java, name)
            .addCallback(DatabasePrePopulateCallback(context.resources))
            .build()

    private suspend fun seed(db: MangaDatabase): Seed {
        val setsDao = db.getFavouriteCategorySetsDao()
        val defaultSetId = setsDao.getSets(0).single().setId
        val scopedSetId = setsDao.insert(
            FavouriteCategorySetEntity(
                syncKey = "fixture:set:other", title = "Other", sortKey = 1,
                createdAt = 2, updatedAt = 2, space = 0,
            ),
        )
        val category = FavouriteCategoryEntity(
            55101, 123, 2, "Scoped", "NEWEST", true, false, true, 0, 0,
        )
        db.getFavouriteCategoriesDao().insert(category)
        val assignment = checkNotNull(setsDao.getAssignment(category.categoryId.toLong()))
        setsDao.assign(assignment.copy(setId = scopedSetId))
        val firstManga = SampleData.mangaDetails.copy(
            id = 551001L, url = "/scoped-migration-1",
            publicUrl = "https://fixture.invalid/scoped-migration-1",
        ).toEntity()
        val secondManga = SampleData.mangaDetails.copy(
            id = 551002L, url = "/scoped-migration-2",
            publicUrl = "https://fixture.invalid/scoped-migration-2",
        ).toEntity()
        db.getMangaDao().upsert(firstManga, emptyList())
        db.getMangaDao().upsert(secondManga, emptyList())
        val groups = db.getLibraryGroupsDao()
        val scopedGroupId = groups.insertGroup(
            LibraryGroupEntity(
                title = "Scoped", coverUrl = null, createdAt = 1,
                space = 0, setId = scopedSetId, description = "preserve metadata",
            ),
        )
        val unplacedGroupId = groups.insertGroup(
            LibraryGroupEntity(title = "Unplaced", coverUrl = null, createdAt = 2, setId = defaultSetId),
        )
        groups.insertMembers(
            listOf(
                LibraryGroupMemberEntity(scopedGroupId, firstManga.id, 0),
                LibraryGroupMemberEntity(scopedGroupId, secondManga.id, 1),
            ),
        )
        groups.insertCategories(listOf(LibraryGroupCategoryEntity(scopedGroupId, category.categoryId.toLong())))
        groups.insertTimeline(listOf(LibraryGroupTimelineItemEntity(scopedGroupId, firstManga.id, 101, 0)))
        db.getLibraryGroupTrackingDao().upsertAll(
            listOf(LibraryGroupTrackingEntity(scopedGroupId, 1, 1, 1, "target", null, null, 0, 0f, null, 0)),
        )
        return Seed(defaultSetId, scopedSetId, scopedGroupId, unplacedGroupId)
    }

    /** Recreate the v55 table shape without altering the member/timeline/tracking child tables. */
    private fun downgradeTo55(db: MangaDatabase) {
        val sql = db.openHelper.writableDatabase
        for (name in sqlTriggers(sql).keys) sql.execSQL("DROP TRIGGER IF EXISTS \"$name\"")
        sql.execSQL("DROP INDEX IF EXISTS index_library_groups_space_set_id")
        sql.execSQL("ALTER TABLE library_groups DROP COLUMN set_id")
        sql.execSQL("DROP TABLE room_master_table")
        sql.version = 55
    }

    private fun sqlTriggers(db: androidx.sqlite.db.SupportSQLiteDatabase): Map<String, String> {
        val result = sortedMapOf<String, String>()
        db.query(
            "SELECT name,sql FROM sqlite_master WHERE type='trigger' AND name LIKE 'library_group_%' ORDER BY name",
        ).use { cursor ->
            while (cursor.moveToNext()) result[cursor.getString(0)] = cursor.getString(1)
        }
        return result
    }

    private fun count(db: androidx.sqlite.db.SupportSQLiteDatabase, query: String): Int =
        db.query(query).use { cursor ->
            var result = 0
            while (cursor.moveToNext()) result++
            result
        }

    private fun scalarText(db: androidx.sqlite.db.SupportSQLiteDatabase, query: String): String =
        db.query(query).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getString(0)
        }

    private data class Seed(
        val defaultSetId: Long, val scopedSetId: Long,
        val scopedGroupId: Long, val unplacedGroupId: Long,
    )
}
