package org.koitharu.kotatsu.favourites.vault

import android.content.ContextWrapper
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.koitharu.kotatsu.SampleData
import org.koitharu.kotatsu.core.db.DatabasePrePopulateCallback
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.db.entity.toEntity
import org.koitharu.kotatsu.favourites.data.FavouriteCategoryEntity
import org.koitharu.kotatsu.favourites.groups.data.LibraryGroupCategoryEntity
import org.koitharu.kotatsu.favourites.groups.data.LibraryGroupEntity
import org.koitharu.kotatsu.favourites.groups.data.LibraryGroupMemberEntity
import org.koitharu.kotatsu.favourites.groups.data.LibraryGroupTimelineItemEntity
import org.koitharu.kotatsu.favourites.groups.data.LibraryGroupTrackingEntity
import java.io.File

/** Regression reproducers only: production MOVE_TO_NORMAL and independently isolated SQL guards. */
@RunWith(AndroidJUnit4::class)
class PrivateToNormalOwnershipRegressionTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun moveToNormalWithAssignedCategoryFailsClosedAndPreservesData() = runBlocking {
        val name = "private-normal-category-guard"
        val db = create(name)
        val securityDir = File(context.cacheDir, "private-normal-security-fixture")
        securityDir.deleteRecursively()
        try {
            seed(db, withNormalCollision = false)
            val sql = db.openHelper.writableDatabase
            val before = snapshot(db)
            val securityContext = object : ContextWrapper(context) {
                override fun getNoBackupFilesDir(): File = securityDir.apply { mkdirs() }
            }
            val security = PrivateFavouritesSecurityStore(securityContext)
            val originalProtection = security.protection
            lateinit var session: PrivateFavouritesSession
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                session = PrivateFavouritesSession(security)
            }
            try {
                val useCase = DisablePrivateFavouritesUseCase(db, security, session)
                expectSqlFailure("Category space is immutable") {
                    useCase(DisablePrivateFavouritesDestination.MOVE_TO_NORMAL)
                }
                assertEquals("A failing move must not disable Private security", originalProtection, security.protection)
                assertEquals("A failing move must roll back copied favourites and every child row", before, snapshot(db))
                assertHealthy(sql)
            } finally {
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    ProcessLifecycleOwner.get().lifecycle.removeObserver(session)
                }
            }
        } finally {
            db.close()
            context.deleteDatabase(name)
            securityDir.deleteRecursively()
        }
    }

    @Test fun groupSpaceChangeWithoutOwnerReassignmentIsRejectedAndRolledBack() = runBlocking {
        val name = "private-normal-owner-guard"
        val db = create(name)
        try {
            val fixture = seed(db, withNormalCollision = false)
            val before = snapshot(db)
            expectSqlFailure("Invalid library group Set owner") {
                db.withTransaction {
                    db.openHelper.writableDatabase.execSQL(
                        "UPDATE library_groups SET space = 0 WHERE group_id = ?",
                        arrayOf<Any>(fixture.privateGroupId),
                    )
                }
            }
            assertEquals(before, snapshot(db))
            assertHealthy(db.openHelper.writableDatabase)
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun destinationSetCollisionFailsClosedWithoutDroppingGroupChildren() = runBlocking {
        val name = "private-normal-collision-guard"
        val db = create(name)
        try {
            val fixture = seed(db, withNormalCollision = true)
            val before = snapshot(db)
            expectSqlFailure("Library group Set membership conflict") {
                db.withTransaction {
                    db.openHelper.writableDatabase.execSQL(
                        "UPDATE library_groups SET space = 0, set_id = ? WHERE group_id = ?",
                        arrayOf<Any>(fixture.normalSetId, fixture.privateGroupId),
                    )
                }
            }
            assertEquals(before, snapshot(db))
            assertHealthy(db.openHelper.writableDatabase)
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    private fun create(name: String): MangaDatabase {
        context.deleteDatabase(name)
        return Room.databaseBuilder(context, MangaDatabase::class.java, name)
            .addCallback(DatabasePrePopulateCallback(context.resources))
            .build()
    }

    private suspend fun seed(db: MangaDatabase, withNormalCollision: Boolean): Fixture {
        val sets = db.getFavouriteCategorySetsDao()
        val normalSetId = sets.getSets(0).single().setId
        val privateSetId = sets.getSets(1).single().setId
        val categoryDao = db.getFavouriteCategoriesDao()
        val privateCategoryId = 56201
        categoryDao.insert(FavouriteCategoryEntity(
            privateCategoryId, 101, 1, "Private category", "NEWEST", true, false, true, 0, 1,
        ))
        assertEquals(privateSetId, sets.getAssignment(privateCategoryId.toLong())?.setId)

        val manga1 = SampleData.mangaDetails.copy(
            id = 562001L, url = "/private-normal-1",
            publicUrl = "https://fixture.invalid/private-normal-1",
        ).toEntity()
        val manga2 = SampleData.mangaDetails.copy(
            id = 562002L, url = "/private-normal-2",
            publicUrl = "https://fixture.invalid/private-normal-2",
        ).toEntity()
        db.getMangaDao().upsert(manga1, emptyList())
        db.getMangaDao().upsert(manga2, emptyList())
        val sql = db.openHelper.writableDatabase
        for ((position, mangaId) in listOf(manga1.id, manga2.id).withIndex()) {
            sql.execSQL(
                "INSERT INTO private_favourites(manga_id,category_id,sort_key,pinned,created_at,deleted_at) VALUES(?,?,?,?,?,0)",
                arrayOf<Any>(mangaId, privateCategoryId, position, 0, 101),
            )
        }
        val groups = db.getLibraryGroupsDao()
        val privateGroupId = groups.insertGroup(LibraryGroupEntity(
            title = "Private group", coverUrl = null, createdAt = 101,
            space = 1, setId = privateSetId, description = "preserve me",
        ))
        groups.insertMembers(listOf(
            LibraryGroupMemberEntity(privateGroupId, manga1.id, 0),
            LibraryGroupMemberEntity(privateGroupId, manga2.id, 1),
        ))
        groups.insertCategories(listOf(LibraryGroupCategoryEntity(privateGroupId, privateCategoryId.toLong())))
        groups.insertTimeline(listOf(LibraryGroupTimelineItemEntity(privateGroupId, manga1.id, 777, 0)))
        db.getLibraryGroupTrackingDao().upsertAll(listOf(
            LibraryGroupTrackingEntity(privateGroupId, 1, 1, 1, "target", null, null, 0, 0f, null, 0),
        ))
        if (withNormalCollision) {
            val normalCategoryId = 56202
            categoryDao.insert(FavouriteCategoryEntity(
                normalCategoryId, 102, 2, "Normal category", "NEWEST", true, false, true, 0, 0,
            ))
            for ((position, mangaId) in listOf(manga1.id, manga2.id).withIndex()) {
                sql.execSQL(
                    "INSERT INTO favourites(manga_id,category_id,sort_key,pinned,created_at,deleted_at) VALUES(?,?,?,?,?,0)",
                    arrayOf<Any>(mangaId, normalCategoryId, position, 0, 102),
                )
            }
            val normalGroupId = groups.insertGroup(LibraryGroupEntity(
                title = "Normal group", coverUrl = null, createdAt = 102, space = 0, setId = normalSetId,
            ))
            groups.insertMembers(listOf(
                LibraryGroupMemberEntity(normalGroupId, manga1.id, 0),
                LibraryGroupMemberEntity(normalGroupId, manga2.id, 1),
            ))
            groups.insertCategories(listOf(LibraryGroupCategoryEntity(normalGroupId, normalCategoryId.toLong())))
        }
        assertHealthy(sql)
        return Fixture(privateGroupId, normalSetId)
    }

    private suspend fun expectSqlFailure(messagePart: String, operation: suspend () -> Unit) {
        val failure = runCatching { operation() }.exceptionOrNull()
        assertNotNull("Expected failure containing $messagePart", failure)
        val detail = generateSequence(failure) { it.cause }.mapNotNull { it.message }.joinToString(" | ")
        assertTrue("Expected '$messagePart'; actual: $detail", detail.contains(messagePart))
    }

    private fun snapshot(db: MangaDatabase): Map<String, List<List<String?>>> {
        val sql = db.openHelper.writableDatabase
        val tables = listOf(
            "favourite_category_sets" to "set_id",
            "favourite_categories" to "category_id",
            "favourite_category_set_assignments" to "category_id",
            "private_favourites" to "manga_id,category_id",
            "favourites" to "manga_id,category_id",
            "library_groups" to "group_id",
            "library_group_members" to "group_id,manga_id",
            "library_group_categories" to "group_id,category_id",
            "library_group_timeline" to "group_id,manga_id,chapter_id",
            "library_group_tracking" to "group_id,service",
        )
        return tables.associate { (table, order) ->
            table to sql.query("SELECT * FROM $table ORDER BY $order").use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add((0 until cursor.columnCount).map { index ->
                            if (cursor.isNull(index)) null else cursor.getString(index)
                        })
                    }
                }
            }
        }
    }

    private fun assertHealthy(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.query("PRAGMA foreign_key_check").use { assertFalse("Broken FK", it.moveToFirst()) }
        db.query("PRAGMA integrity_check").use {
            assertTrue(it.moveToFirst())
            assertEquals("ok", it.getString(0))
        }
    }

    private data class Fixture(val privateGroupId: Long, val normalSetId: Long)
}
