package org.koitharu.kotatsu.backup.local

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koitharu.kotatsu.SampleData
import org.koitharu.kotatsu.backup.local.data.LibraryGroupBackupCodec
import org.koitharu.kotatsu.backup.local.data.LocalBackupRepository
import org.koitharu.kotatsu.backup.local.data.model.LibraryGroupBackup
import org.koitharu.kotatsu.backup.local.domain.BackupSection
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.db.entity.toEntity
import org.koitharu.kotatsu.favourites.data.*
import org.koitharu.kotatsu.favourites.groups.data.*
import org.koitharu.kotatsu.favourites.vault.PrivateFavouritesSecurityStore
import java.io.File
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.inject.Inject

/**
 * Targeted negative reproductions against current production backup/restore.
 * The owner/collision tests are expected to be RED until a separate approved production fix.
 * No SQL guards are bypassed and no synthetic restore path is used for the round trips.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class LibraryGroupSetBackupRegressionTest {
    @get:Rule var hiltRule = HiltAndroidRule(this)

    @Inject lateinit var database: MangaDatabase
    @Inject lateinit var repository: LocalBackupRepository
    @Inject lateinit var codec: LibraryGroupBackupCodec
    @Inject lateinit var privateSecurity: PrivateFavouritesSecurityStore

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val memberIds = listOf(831001L, 831002L)

    @Before fun setUp() {
        hiltRule.inject()
        resetFixtureDatabase()
        privateSecurity.includePrivateInBackup = false
    }

    @Test fun normalGroupBackupPreservesSetOwnershipAcrossRemappedIds() = runTest {
        val ownerKey = "backup-repro:normal:owner"
        val original = seedGroup(FavouriteSpace.NORMAL, ownerKey, "Source normal group")
        val file = backupFile("normal")
        try {
            writeBackup(file)
            resetFixtureDatabase()
            val otherKey = "backup-repro:normal:unrelated"
            database.ensureCategorySets(FavouriteSpace.NORMAL)
            insertSet(FavouriteSpace.NORMAL, otherKey)

            val result = restore(file, setOf(
                BackupSection.CATEGORIES, BackupSection.FAVOURITES, BackupSection.LIBRARY_GROUPS,
            ))
            assertTrue("Normal round-trip restore failed: ${result.failures}", result.isAllSuccess)
            val restored = database.getLibraryGroupsDao().findAllGroups(0).single()
            val restoredSet = checkNotNull(database.getFavouriteCategorySetsDao().findSet(restored.setId))
            assertNotEquals("Fixture did not remap local Set ids", original.setId, restored.setId)
            assertEquals("Normal Group Set owner changed during restore", ownerKey, restoredSet.syncKey)
            assertEquals(memberIds, database.getLibraryGroupsDao().findMembers(restored.groupId).map { it.mangaId })
            assertEquals(1, database.getLibraryGroupsDao().findTimeline(restored.groupId).size)
            assertEquals(1, database.getLibraryGroupTrackingDao().findAll(restored.groupId).size)
        } finally {
            file.delete()
        }
    }

    @Test fun privateGroupBackupPreservesSetOwnershipWithOptIn() = runTest {
        val ownerKey = "backup-repro:private:owner"
        val original = seedGroup(FavouriteSpace.PRIVATE, ownerKey, "Source private group")
        val off = backupFile("private-off")
        val on = backupFile("private-on")
        try {
            privateSecurity.includePrivateInBackup = false
            writeBackup(off)
            assertFalse(
                "Private Group data must be absent without opt-in",
                zipEntries(off).contains(LocalBackupRepository.PRIVATE_FAVOURITES_ENTRY),
            )

            privateSecurity.includePrivateInBackup = true
            writeBackup(on)
            assertTrue(
                "Private backup opt-in did not export a private payload",
                zipEntries(on).contains(LocalBackupRepository.PRIVATE_FAVOURITES_ENTRY),
            )
            resetFixtureDatabase()
            database.ensureCategorySets(FavouriteSpace.PRIVATE)
            insertSet(FavouriteSpace.PRIVATE, "backup-repro:private:unrelated")

            val result = restore(on, emptySet(), restorePrivate = true)
            assertTrue("Private round-trip restore failed: ${result.failures}", result.isAllSuccess)
            val restored = database.getLibraryGroupsDao().findAllGroups(1).single()
            val restoredSet = checkNotNull(database.getFavouriteCategorySetsDao().findSet(restored.setId))
            assertNotEquals("Fixture did not remap Private Set ids", original.setId, restored.setId)
            assertEquals("Private Group Set owner changed during restore", ownerKey, restoredSet.syncKey)
            assertEquals(memberIds, database.getLibraryGroupsDao().findMembers(restored.groupId).map { it.mangaId })
            assertEquals(1, database.getLibraryGroupsDao().findTimeline(restored.groupId).size)
        } finally {
            privateSecurity.includePrivateInBackup = false
            off.delete()
            on.delete()
        }
    }

    @Test fun existingGroupOwnerCollisionFailsClosedWithoutMutation() = runTest {
        val source = seedGroup(FavouriteSpace.NORMAL, "backup-repro:collision:source", "Incoming group")
        val payload = codec.dump(FavouriteSpace.NORMAL).toList().single()
        assertEquals("Incoming group", payload.title)

        resetFixtureDatabase()
        val existing = seedGroup(FavouriteSpace.NORMAL, "backup-repro:collision:target", "Unrelated destination group")
        val dao = database.getLibraryGroupsDao()
        val before = groupState(existing.groupId)
        val attempted = codec.restore(
            items = sequenceOf(payload),
            space = FavouriteSpace.NORMAL,
            categoryIdMap = mapOf(source.categoryId to existing.categoryId),
        )
        val after = groupState(existing.groupId)
        val rejected = !attempted.isAllSuccess
        val preserved = before == after
        assertTrue(
            "Expected owner collision rejection and full rollback; rejected=$rejected, preserved=$preserved, failures=${attempted.failures}",
            rejected && preserved,
        )
        assertEquals(existing.setId, checkNotNull(dao.findGroup(existing.groupId)).setId)
        assertEquals(1, dao.findTimeline(existing.groupId).size)
    }

    @Test fun legacyGroupBackupRestoresWithoutSetIdentity() = runTest {
        val created = seedGroup(FavouriteSpace.NORMAL, "backup-repro:legacy:holder", "Seed member library")
        database.getLibraryGroupsDao().deleteGroup(created.groupId)
        val legacyJson = """{"title":"Legacy imported group","created_at":42,"members":[{"manga_id":831001,"position":0},{"manga_id":831002,"position":1}]}"""
        val legacy = Json.decodeFromString<LibraryGroupBackup>(legacyJson)
        val result = codec.restore(sequenceOf(legacy), FavouriteSpace.NORMAL)
        assertTrue("Legacy Group backup must remain restorable: ${result.failures}", result.isAllSuccess)

        val group = database.getLibraryGroupsDao().findAllGroups(0).single()
        val owner = checkNotNull(database.getFavouriteCategorySetsDao().findSet(group.setId))
        assertEquals(
            "Legacy Group without a portable Set identity should use the default Set",
            org.koitharu.kotatsu.favourites.domain.CategorySetIdentity.defaultSet(0),
            owner.syncKey,
        )
        assertEquals(memberIds, database.getLibraryGroupsDao().findMembers(group.groupId).map { it.mangaId })
    }

    private suspend fun seedGroup(space: FavouriteSpace, ownerKey: String, title: String): Seed {
        database.ensureCategorySets(space)
        val setId = insertSet(space, ownerKey)
        val categories = database.getFavouriteCategoriesDao()
        val categoryId = categories.insert(FavouriteCategoryEntity(
            categoryId = 0, createdAt = 42L, sortKey = 1, title = "Fixture ${space.name} $ownerKey",
            order = "NEWEST", track = true, downloadNewChapters = false,
            isVisibleInLibrary = true, deletedAt = 0, space = space.dbValue,
        ))
        val sets = database.getFavouriteCategorySetsDao()
        val assignment = checkNotNull(sets.getAssignment(categoryId))
        sets.assign(assignment.copy(setId = setId, updatedAt = 43))
        assertEquals(setId, sets.getAssignment(categoryId)?.setId)

        memberIds.forEachIndexed { index, id ->
            val manga = SampleData.mangaDetails.copy(
                id = id, url = "/backup-repro-$id", publicUrl = "https://fixture.invalid/backup-repro-$id",
            ).toEntity()
            database.getMangaDao().upsert(manga, emptyList())
            if (space == FavouriteSpace.NORMAL) {
                database.getFavouritesDao().upsert(FavouriteEntity(
                    mangaId = id, categoryId = categoryId, sortKey = index,
                    isPinned = false, createdAt = 100L, deletedAt = 0L,
                ))
            } else {
                database.getPrivateFavouritesDao().upsert(PrivateFavouriteEntity(
                    mangaId = id, categoryId = categoryId, sortKey = index,
                    isPinned = false, createdAt = 100L, deletedAt = 0L,
                ))
            }
        }
        val dao = database.getLibraryGroupsDao()
        val groupId = dao.insertGroup(LibraryGroupEntity(
            title = title, coverUrl = null, createdAt = 101L,
            space = space.dbValue, setId = setId, description = "preserve description",
        ))
        dao.insertMembers(memberIds.mapIndexed { pos, mangaId ->
            LibraryGroupMemberEntity(groupId, mangaId, pos)
        })
        dao.insertCategories(listOf(LibraryGroupCategoryEntity(groupId, categoryId)))
        dao.insertTimeline(listOf(LibraryGroupTimelineItemEntity(groupId, memberIds[0], 777L, 0)))
        database.getLibraryGroupTrackingDao().upsertAll(listOf(
            LibraryGroupTrackingEntity(groupId, 1, 1, 1, "tracking", null, null, 0, 0f, null, 0),
        ))
        return Seed(categoryId, setId, groupId)
    }

    private suspend fun insertSet(space: FavouriteSpace, syncKey: String): Long =
        database.getFavouriteCategorySetsDao().insert(FavouriteCategorySetEntity(
            syncKey = syncKey, title = syncKey, sortKey = 1, createdAt = 10L,
            updatedAt = 10L, space = space.dbValue,
        ))

    private suspend fun groupState(id: Long): GroupState = GroupState(
        database.getLibraryGroupsDao().findGroup(id),
        database.getLibraryGroupsDao().findMembers(id),
        database.getLibraryGroupsDao().findCategories(id),
        database.getLibraryGroupsDao().findTimeline(id),
        database.getLibraryGroupTrackingDao().findAll(id),
    )

    /**
     * Keep the v56 owner trigger intact. Room's clearAllTables() deletes Set
     * rows before it can remove their Groups, so explicitly delete fixture
     * Groups first (including cascade children), then clear the remaining rows.
     */
    private fun resetFixtureDatabase() {
        database.openHelper.writableDatabase.execSQL("DELETE FROM library_groups")
        database.clearAllTables()
    }

    private fun backupFile(suffix: String): File =
        File.createTempFile("backup-group-set-$suffix-", ".zip", context.cacheDir)

    private suspend fun writeBackup(file: File) {
        ZipOutputStream(file.outputStream()).use { repository.createBackup(it, progress = null) }
    }

    private suspend fun restore(
        file: File, sections: Set<BackupSection>, restorePrivate: Boolean = false,
    ) = ZipInputStream(file.inputStream()).use { input ->
        repository.restoreBackup(input, sections, progress = null, restorePrivateFavourites = restorePrivate)
    }

    private fun zipEntries(file: File): Set<String> =
        ZipInputStream(file.inputStream()).use { input ->
            buildSet {
                var entry = input.nextEntry
                while (entry != null) {
                    add(entry.name)
                    input.closeEntry()
                    entry = input.nextEntry
                }
            }
        }

    private data class Seed(val categoryId: Long, val setId: Long, val groupId: Long)
    private data class GroupState(
        val group: LibraryGroupEntity?,
        val members: List<LibraryGroupMemberEntity>,
        val categories: List<LibraryGroupCategoryEntity>,
        val timeline: List<LibraryGroupTimelineItemEntity>,
        val tracking: List<LibraryGroupTrackingEntity>,
    )
}
