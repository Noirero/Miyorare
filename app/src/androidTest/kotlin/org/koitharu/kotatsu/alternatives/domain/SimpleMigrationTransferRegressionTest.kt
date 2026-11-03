package org.koitharu.kotatsu.alternatives.domain

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.koitharu.kotatsu.SampleData
import org.koitharu.kotatsu.bookmarks.data.BookmarkEntity
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.db.entity.toEntities
import org.koitharu.kotatsu.core.db.entity.toEntity
import org.koitharu.kotatsu.core.model.MissingMangaSource
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.ChapterReadOverridesMigrationStore
import org.koitharu.kotatsu.details.data.MangaNotesRepository
import org.koitharu.kotatsu.favourites.data.FavouriteCategoryEntity
import org.koitharu.kotatsu.favourites.data.FavouriteEntity
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
import org.koitharu.kotatsu.favourites.data.PrivateFavouriteEntity
import org.koitharu.kotatsu.favourites.groups.data.LibraryGroupEntity
import org.koitharu.kotatsu.favourites.groups.data.LibraryGroupMemberEntity
import org.koitharu.kotatsu.favourites.groups.data.LibraryGroupTimelineItemEntity
import org.koitharu.kotatsu.history.data.HistoryEntity
import org.koitharu.kotatsu.stats.data.StatsEntity
import org.koitharu.kotatsu.tracker.data.TrackLogEntity
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.reader.ui.config.MangaReaderProfileStore

@RunWith(AndroidJUnit4::class)
class SimpleMigrationTransferRegressionTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun fiftyToThirtyFiveMovesMandatoryAndPreservesUnmappedHistoryAndTimeline() = runTest {
        val db = Room.inMemoryDatabaseBuilder(context, MangaDatabase::class.java).build()
        val settings = AppSettings(context)
        val notes = MangaNotesRepository(context)
        val old = manga(997001L, "SIMPLE_OLD", 50)
        val target = manga(997002L, "SIMPLE_NEW", 35)
        val groupId = 0L
        val oldId = old.id
        val newId = target.id
        try {
            seed(db, old, target, private = false)
            notes.set(oldId, "Preserve my manga note")
            val sourceLast = old.chapters.orEmpty().first { it.number == 45f }
            val targetFirst = target.chapters.orEmpty().first { it.number == 1f }
            val sourceFirst = old.chapters.orEmpty().first { it.number == 1f }
            settings.setChapterReadOverrides(oldId, mapOf(sourceLast.id to true, sourceFirst.id to false))
            db.getHistoryDao().insert(HistoryEntity(oldId, 1L, 2L, sourceLast.id, 7, 0.3f, 0.7f, 0L, 50))
            db.getStatsDao().upsert(StatsEntity(oldId, startedAt = 40L, duration = 50L, pages = 6, chapters = 1))
            db.getTrackLogsDao().insert(TrackLogEntity(
                id = 997401L, mangaId = oldId, chapters = "Chapter 45",
                chapterIds = sourceLast.id.toString(), createdAt = 45L, isUnread = true,
            ))
            db.getBookmarksDao().insert(
                BookmarkEntity(oldId, 997100L, sourceFirst.id, 3, 0,
                    "https://fixture.invalid/p.jpg", 10L, 0.3f, "keep me"),
            )
            val groups = db.getLibraryGroupsDao()
            val id = groups.insertGroup(LibraryGroupEntity(
                title = "Personal Set", coverUrl = null, createdAt = 1L,
                space = FavouriteSpace.NORMAL.dbValue,
            ))
            groups.insertMembers(listOf(LibraryGroupMemberEntity(id, oldId, 3)))
            groups.insertTimeline(listOf(LibraryGroupTimelineItemEntity(id, oldId, sourceLast.id, 9)))
            val result = transfer(db, settings, notes)(old, target)
            assertTrue(result)
            assertTrue(db.getFavouritesDao().findAllRaw(oldId).isEmpty())
            assertEquals(1, db.getFavouritesDao().findAllRaw(newId).size)
            assertEquals("Preserve my manga note", notes.get(newId))
            assertNull(notes.get(oldId))
            assertNull(db.getHistoryDao().find(newId))
            assertEquals(sourceLast.id, db.getHistoryDao().find(oldId)?.chapterId)
            assertEquals(50L, db.getStatsDao().findAll(oldId).single().duration)
            assertEquals(sourceLast.id.toString(), db.getTrackLogsDao().findAllForManga(oldId).single().chapterIds)
            assertTrue(db.getStatsDao().findAll(newId).isEmpty())
            assertTrue(db.getBookmarksDao().findAll(newId).isEmpty())
            assertTrue(settings.getChapterReadOverrides(newId).isEmpty())
            assertEquals(mapOf(sourceLast.id to true, sourceFirst.id to false),
                settings.getChapterReadOverrides(oldId))
            assertEquals(1, groups.findMembersByMangaIds(listOf(newId), 0).size)
            // Source group member remains only to prevent timeline's FK cascade deletion.
            assertEquals(1, groups.findMembersByMangaIds(listOf(oldId), 0).size)
            assertEquals(newId, db.getMangaDao().preservedDestination(oldId))
            // The regular group repair must not cascade-delete the preserved timeline.
            groups.deleteMembersNotInLibrary(FavouriteSpace.NORMAL.dbValue)
            assertTrue(groups.findTimeline(id).any { it.mangaId == oldId && it.chapterId == sourceLast.id })
            assertFalse(db.getMangaDao().isMigrationEntryVisible(oldId))
            assertTrue(db.getMangaDao().isMigrationEntryVisible(newId))
        } finally {
            notes.set(oldId, null); notes.set(newId, null)
            settings.setChapterReadOverrides(oldId, emptyMap())
            settings.setChapterReadOverrides(newId, emptyMap())
            db.close()
        }
    }

    @Test fun preservedTimelineAndChapterSnapshotSurviveRoutineCleanup() = runTest {
        val db = Room.inMemoryDatabaseBuilder(context, MangaDatabase::class.java).build()
        val settings = AppSettings(context)
        val old = manga(997041L, "SIMPLE_OLD", 2)
        val target = manga(997042L, "SIMPLE_NEW", 1)
        try {
            seed(db, old, target, private = false)
            val missing = old.chapters.orEmpty().first { it.number == 2f }
            val groups = db.getLibraryGroupsDao()
            val groupId = groups.insertGroup(LibraryGroupEntity(
                title = "Unmappable timeline", coverUrl = null, createdAt = 1L, space = 0,
            ))
            groups.insertMembers(listOf(LibraryGroupMemberEntity(groupId, old.id, 1)))
            groups.insertTimeline(listOf(LibraryGroupTimelineItemEntity(groupId, old.id, missing.id, 4)))
            db.getChaptersDao().replaceAll(old.id, old.chapters.orEmpty().mapIndexed { index, chapter ->
                org.koitharu.kotatsu.core.db.entity.ChapterEntity(
                    chapterId = chapter.id, mangaId = old.id, title = chapter.title.orEmpty(),
                    number = chapter.number, volume = chapter.volume, url = chapter.url,
                    scanlator = null, uploadDate = 0L, branch = null,
                    source = chapter.source.name, index = index,
                )
            })
            assertTrue(transfer(db, settings, MangaNotesRepository(context))(old, target))
            groups.deleteMembersNotInLibrary(0)
            db.getChaptersDao().gc(listOf(old.id))
            db.getMangaDao().cleanup(setOf(target.id))
            assertEquals(target.id, db.getMangaDao().preservedDestination(old.id))
            assertTrue(db.getChaptersDao().hasAny(old.id))
            assertTrue(groups.findTimeline(groupId).any { it.mangaId == old.id && it.chapterId == missing.id })
        } finally { db.close() }
    }

    @Test fun thirtyFiveToSeventyMapsHistoryWithoutRequiringIdenticalCounts() = runTest {
        val db = Room.inMemoryDatabaseBuilder(context, MangaDatabase::class.java).build()
        val settings = AppSettings(context)
        val notes = MangaNotesRepository(context)
        val old = manga(997011L, "SIMPLE_OLD", 35)
        val target = manga(997012L, "SIMPLE_OLD", 70)
        try {
            seed(db, old, target, private = false)
            val source = old.chapters.orEmpty().first { it.number == 12f }
            val destination = target.chapters.orEmpty().first { it.number == 12f }
            db.getHistoryDao().insert(HistoryEntity(old.id, 1L, 2L, source.id, 5, 0.2f, 0.7f, 0L, 35))
            val result = transfer(db, settings, notes)(old, target)
            assertFalse(result)
            assertNull(db.getHistoryDao().find(old.id))
            assertEquals(destination.id, db.getHistoryDao().find(target.id)?.chapterId)
            assertTrue(db.getFavouritesDao().findAllRaw(old.id).isEmpty())
            assertEquals(1, db.getFavouritesDao().findAllRaw(target.id).size)
        } finally {
            settings.setChapterReadOverrides(old.id, emptyMap())
            settings.setChapterReadOverrides(target.id, emptyMap())
            db.close()
        }
    }

    @Test fun privateOnlySourceStaysPrivateWhenDestinationIsEmpty() = runTest {
        val db = Room.inMemoryDatabaseBuilder(context, MangaDatabase::class.java).build()
        val settings = AppSettings(context)
        val notes = MangaNotesRepository(context)
        val old = manga(997021L, "SIMPLE_OLD", 1)
        val target = manga(997022L, "SIMPLE_NEW", 3)
        try {
            seed(db, old, target, private = true)
            val groups = db.getLibraryGroupsDao()
            val groupId = groups.insertGroup(LibraryGroupEntity(
                title = "Private Group", coverUrl = null, createdAt = 1L,
                space = FavouriteSpace.PRIVATE.dbValue,
            ))
            groups.insertMembers(listOf(LibraryGroupMemberEntity(groupId, old.id, 1)))
            val failure = runCatching { transfer(db, settings, notes)(old, target) }.exceptionOrNull()
            assertTrue(failure is LosslessMigrationConflictException)
            // Direct simple-path invocation must never remove the Private protection boundary.
            assertEquals(1, groups.findMembersByMangaIds(listOf(old.id), 1).size)
            assertTrue(groups.findMembersByMangaIds(listOf(target.id), 1).isEmpty())
            assertTrue(db.getFavouritesDao().findAllRaw(target.id).isEmpty())
            assertEquals(1, db.getPrivateFavouritesDao().findAllRaw(old.id).size)
            assertTrue(db.getPrivateFavouritesDao().findAllRaw(target.id).isEmpty())
        } finally { db.close() }
    }

    @Test fun unmappableOverrideDoesNotPreventLaterSafeOverrides() = runTest {
        val db = Room.inMemoryDatabaseBuilder(context, MangaDatabase::class.java).build()
        val settings = AppSettings(context)
        val old = manga(997071L, "SIMPLE_SAME", 2)
        val target = manga(997072L, "SIMPLE_SAME", 1)
        try {
            seed(db, old, target, private = false)
            val unmappable = old.chapters.orEmpty().first { it.number == 2f }
            val safe = old.chapters.orEmpty().first { it.number == 1f }
            val destination = target.chapters.orEmpty().single()
            settings.setChapterReadOverrides(old.id, linkedMapOf(
                unmappable.id to true, safe.id to false,
            ))
            assertTrue(transfer(db, settings, MangaNotesRepository(context))(old, target))
            assertEquals(mapOf(destination.id to false), settings.getChapterReadOverrides(target.id))
            assertEquals(mapOf(unmappable.id to true, safe.id to false),
                settings.getChapterReadOverrides(old.id))
            assertEquals(target.id, db.getMangaDao().preservedDestination(old.id))
        } finally {
            settings.setChapterReadOverrides(old.id, emptyMap())
            settings.setChapterReadOverrides(target.id, emptyMap())
            db.close()
        }
    }

    @Test fun categoryCollisionDoesNotOverwriteDestinationOrConsumeSource() = runTest {
        val db = Room.inMemoryDatabaseBuilder(context, MangaDatabase::class.java).build()
        val settings = AppSettings(context)
        val notes = MangaNotesRepository(context)
        val old = manga(997051L, "SIMPLE_OLD", 1)
        val target = manga(997052L, "SIMPLE_NEW", 1)
        try {
            seed(db, old, target, private = false)
            val conflicting = FavouriteEntity(target.id, 997900L, 99, true, 9, 0)
            db.getFavouritesDao().upsert(conflicting)
            val error = runCatching { transfer(db, settings, notes)(old, target) }.exceptionOrNull()
            assertTrue(error is LosslessMigrationConflictException)
            assertEquals(MigrationConflictReason.LIBRARY_STATE,
                (error as LosslessMigrationConflictException).reason)
            assertEquals(1, db.getFavouritesDao().findAllRaw(old.id).size)
            assertEquals(conflicting, db.getFavouritesDao().findAllRaw(target.id).single())
            assertNull(db.getMangaDao().preservedDestination(old.id))
        } finally { db.close() }
    }

    @Test fun conflictingOptionalBookmarkIsNotOverwrittenOrDeleted() = runTest {
        val db = Room.inMemoryDatabaseBuilder(context, MangaDatabase::class.java).build()
        val settings = AppSettings(context)
        val notes = MangaNotesRepository(context)
        val old = manga(997061L, "SIMPLE_OLD", 1)
        val target = manga(997062L, "SIMPLE_OLD", 2)
        try {
            seed(db, old, target, private = false)
            val sourceChapter = old.chapters.orEmpty().first()
            val targetChapter = target.chapters.orEmpty().first()
            val oldBookmark = BookmarkEntity(old.id, 997701L, sourceChapter.id, 3, 0,
                "https://fixture.invalid/old.jpg", 5L, 0.2f, "source")
            val targetBookmark = BookmarkEntity(target.id, 997701L, targetChapter.id, 7, 0,
                "https://fixture.invalid/new.jpg", 6L, 0.4f, "target")
            db.getBookmarksDao().insert(oldBookmark)
            db.getBookmarksDao().insert(targetBookmark)
            assertTrue(transfer(db, settings, notes)(old, target))
            assertEquals(oldBookmark, db.getBookmarksDao().findAll(old.id).single())
            assertEquals(targetBookmark, db.getBookmarksDao().findAll(target.id).single())
            assertEquals(target.id, db.getMangaDao().preservedDestination(old.id))
            assertTrue(db.getFavouritesDao().findAllRaw(old.id).isEmpty())
        } finally { db.close() }
    }

    @Test fun groupPositionConflictRollsBackMandatoryMembership() = runTest {
        val db = Room.inMemoryDatabaseBuilder(context, MangaDatabase::class.java).build()
        val settings = AppSettings(context)
        val notes = MangaNotesRepository(context)
        val old = manga(997081L, "SIMPLE_OLD", 2)
        val target = manga(997082L, "SIMPLE_NEW", 1)
        try {
            seed(db, old, target, private = false)
            val groupDao = db.getLibraryGroupsDao()
            val groupId = groupDao.insertGroup(LibraryGroupEntity(
                title = "Same Group", coverUrl = null, createdAt = 1L, space = 0,
            ))
            groupDao.insertMembers(listOf(
                LibraryGroupMemberEntity(groupId, old.id, 1),
                LibraryGroupMemberEntity(groupId, target.id, 2),
            ))
            val failure = runCatching { transfer(db, settings, notes)(old, target) }.exceptionOrNull()
            assertTrue(failure is LosslessMigrationConflictException)
            assertEquals(MigrationConflictReason.LIBRARY_STATE,
                (failure as LosslessMigrationConflictException).reason)
            assertEquals(1, db.getFavouritesDao().findAllRaw(old.id).size)
            assertTrue(db.getFavouritesDao().findAllRaw(target.id).isEmpty())
            assertEquals(1, groupDao.findMembersByMangaIds(listOf(old.id), 0).size)
            assertEquals(1, groupDao.findMembersByMangaIds(listOf(target.id), 0).size)
            assertNull(db.getMangaDao().preservedDestination(old.id))
        } finally { db.close() }
    }

    @Test fun differentDestinationNoteBlocksBeforeLibraryOwnershipMutates() = runTest {
        val db = Room.inMemoryDatabaseBuilder(context, MangaDatabase::class.java).build()
        val settings = AppSettings(context)
        val notes = MangaNotesRepository(context)
        val old = manga(997031L, "SIMPLE_OLD", 1)
        val target = manga(997032L, "SIMPLE_NEW", 1)
        try {
            seed(db, old, target, private = false)
            notes.set(old.id, "source")
            notes.set(target.id, "destination")
            val error = runCatching { transfer(db, settings, notes)(old, target) }.exceptionOrNull()
            assertTrue(error is LosslessMigrationConflictException)
            assertEquals(1, db.getFavouritesDao().findAllRaw(old.id).size)
            assertTrue(db.getFavouritesDao().findAllRaw(target.id).isEmpty())
            assertEquals("source", notes.get(old.id))
            assertEquals("destination", notes.get(target.id))
        } finally {
            notes.set(old.id, null); notes.set(target.id, null); db.close()
        }
    }

    private fun transfer(db: MangaDatabase, settings: AppSettings, notes: MangaNotesRepository) =
        SimpleMigrationTransfer(db, settings, notes, MangaReaderProfileStore(context),
            ChapterReadOverridesMigrationStore(context))

    private suspend fun seed(db: MangaDatabase, old: Manga, target: Manga, private: Boolean) {
        for (manga in listOf(old, target)) {
            val tags = manga.tags.toEntities()
            db.getTagsDao().upsert(tags)
            db.getMangaDao().upsert(manga.toEntity(), tags)
        }
        val category = if (private) 997901L else 997900L
        db.getFavouriteCategoriesDao().upsert(FavouriteCategoryEntity(
            category.toInt(), 1, 0, "Fixture", "NAME", false, false, true, 0,
            if (private) 1 else 0,
        ))
        if (private) {
            db.getPrivateFavouritesDao().upsert(PrivateFavouriteEntity(old.id, category, 1, false, 1, 0))
        } else {
            db.getFavouritesDao().upsert(FavouriteEntity(old.id, category, 1, false, 1, 0))
        }
    }

    private fun manga(id: Long, sourceName: String, count: Int): Manga {
        val source = MissingMangaSource(sourceName)
        return SampleData.mangaDetails.copy(
            id = id, source = source, url = "/title" + id,
            publicUrl = "https://fixture.invalid/title/" + id,
            chapters = (1..count).map { index ->
                MangaChapter(
                    id = id * 1000 + index, title = "Chapter " + index, number = index.toFloat(),
                    volume = 0, url = "/chapter/" + index, scanlator = null,
                    uploadDate = 0L, branch = null, source = source,
                )
            },
        )
    }
}