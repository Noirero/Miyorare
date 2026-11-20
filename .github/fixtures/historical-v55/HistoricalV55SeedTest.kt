package org.koitharu.kotatsu.core.db

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.koitharu.kotatsu.SampleData
import org.koitharu.kotatsu.core.db.entity.toEntity
import org.koitharu.kotatsu.favourites.data.FavouriteCategoryEntity
import org.koitharu.kotatsu.favourites.data.FavouriteCategorySetEntity
import org.koitharu.kotatsu.favourites.groups.data.LibraryGroupCategoryEntity
import org.koitharu.kotatsu.favourites.groups.data.LibraryGroupEntity
import org.koitharu.kotatsu.favourites.groups.data.LibraryGroupMemberEntity
import org.koitharu.kotatsu.favourites.groups.data.LibraryGroupTimelineItemEntity
import org.koitharu.kotatsu.favourites.groups.data.LibraryGroupTrackingEntity

/**
 * Compiled and executed ONLY against immutable historical Room v55 source.
 * The diagnostic workflow copies this file into that source's androidTest tree.
 */
@RunWith(AndroidJUnit4::class)
class HistoricalV55SeedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun createAuthenticRoomV55DatabaseWithGroups() = runBlocking {
        val name = "historical-v55-library-groups"
        context.deleteDatabase(name)
        val db = Room.databaseBuilder(context, MangaDatabase::class.java, name)
            .addCallback(DatabasePrePopulateCallback(context.resources))
            .build()
        try {
            assertEquals(55, db.openHelper.writableDatabase.version)
            val sets = db.getFavouriteCategorySetsDao()
            val defaultSet = sets.getSets(0).single()
            val otherSetId = sets.insert(
                FavouriteCategorySetEntity(
                    syncKey = "historical:v55:other", title = "Other", sortKey = 1,
                    createdAt = 2, updatedAt = 2, space = 0,
                ),
            )
            val category = FavouriteCategoryEntity(
                55101, 123, 2, "Scoped", "NEWEST", true, false, true, 0, 0,
            )
            db.getFavouriteCategoriesDao().insert(category)
            val assignment = checkNotNull(sets.getAssignment(category.categoryId.toLong()))
            sets.assign(assignment.copy(setId = otherSetId))
            val manga1 = SampleData.mangaDetails.copy(
                id = 551001L, url = "/historical-v55-1",
                publicUrl = "https://fixture.invalid/historical-v55-1",
            ).toEntity()
            val manga2 = SampleData.mangaDetails.copy(
                id = 551002L, url = "/historical-v55-2",
                publicUrl = "https://fixture.invalid/historical-v55-2",
            ).toEntity()
            db.getMangaDao().upsert(manga1, emptyList())
            db.getMangaDao().upsert(manga2, emptyList())
            val groups = db.getLibraryGroupsDao()
            val scoped = groups.insertGroup(
                LibraryGroupEntity(
                    title = "Historical scoped", coverUrl = null, createdAt = 1,
                    space = 0, description = "preserve metadata",
                ),
            )
            val unplaced = groups.insertGroup(
                LibraryGroupEntity(title = "Historical default", coverUrl = null, createdAt = 2, space = 0),
            )
            groups.insertMembers(listOf(
                LibraryGroupMemberEntity(scoped, manga1.id, 0),
                LibraryGroupMemberEntity(scoped, manga2.id, 1),
            ))
            groups.insertCategories(listOf(LibraryGroupCategoryEntity(scoped, category.categoryId.toLong())))
            groups.insertTimeline(listOf(LibraryGroupTimelineItemEntity(scoped, manga1.id, 101, 0)))
            db.getLibraryGroupTrackingDao().upsertAll(
                listOf(LibraryGroupTrackingEntity(scoped, 1, 1, 1, "target", null, null, 0, 0f, null, 0)),
            )
            assertTrue(defaultSet.setId > 0)
            assertTrue(otherSetId > 0)
            assertTrue(unplaced > scoped)
            assertEquals(2, groups.findMembers(scoped).size)
            assertEquals(1, groups.findTimeline(scoped).size)
            assertEquals(1, groups.findCategories(scoped).size)
            assertEquals(1, db.getLibraryGroupTrackingDao().findAll(scoped).size)
            db.openHelper.writableDatabase.query(
                "SELECT identity_hash FROM room_master_table WHERE id = 42",
            ).use {
                assertTrue("Missing historical Room identity hash", it.moveToFirst())
                assertTrue(it.getString(0).isNotBlank())
            }
        } finally {
            db.close()
        }
    }
}
