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
import org.koitharu.kotatsu.backup.local.data.model.LibraryGroupTimelineBackup
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
 * Targeted acceptance regressions for portable Set ownership, timeline, collision
 * rollback and legacy native backups. SQL guards remain active for every test.
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
            assertEquals(
                listOf(LibraryGroupTimelineItemEntity(restored.groupId, memberIds[0], 777L, 0)),
                database.getLibraryGroupsDao().findTimeline(restored.groupId),
            )
            assertEquals(
                "tracking",
                database.getLibraryGroupTrackingDao().findAll(restored.groupId).single().targetTitle,
            )
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
            assertEquals(
                listOf(LibraryGroupTimelineItemEntity(restored.groupId, memberIds[0], 777L, 0)),
                database.getLibraryGroupsDao().findTimeline(restored.groupId),
            )
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
        // Resolve the incoming owner's portable key, so this actually exercises
        // cross-Set ownership collision rather than the missing-owner guard.
        insertSet(FavouriteSpace.NORMAL, "backup-repro:collision:source")
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
        assertTrue("Expected real Set collision, got: ${attempted.failures}",
            attempted.failures.any { it.message?.contains("Set owner collision") == true })
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


    @Test fun missingSetOwnerFailsClosedWithoutMutation() = runTest {
        val seeded = seedGroup(FavouriteSpace.NORMAL, "backup-edge:owner", "Original owner")
        val original = codec.dump(FavouriteSpace.NORMAL).toList().single()
        assertRejectedWithoutMutation(
            seeded.groupId,
            original.copy(setSyncKey = "backup-edge:missing", title = "SHOULD NOT APPLY"),
            "Set owner is missing",
            mapOf(seeded.categoryId to seeded.categoryId),
        )
    }

    @Test fun tombstonedSetOwnerFailsClosedWithoutMutation() = runTest {
        val seeded = seedGroup(FavouriteSpace.NORMAL, "backup-edge:active", "Active owner")
        val original = codec.dump(FavouriteSpace.NORMAL).toList().single()
        val tombstoneKey = "backup-edge:deleted"
        database.getFavouriteCategorySetsDao().insert(FavouriteCategorySetEntity(
            syncKey = tombstoneKey, title = "Deleted", sortKey = 2,
            createdAt = 10L, updatedAt = 20L, deletedAt = 20L, space = 0,
        ))
        assertRejectedWithoutMutation(
            seeded.groupId,
            original.copy(setSyncKey = tombstoneKey, title = "SHOULD NOT APPLY"),
            "Set owner is deleted",
            mapOf(seeded.categoryId to seeded.categoryId),
        )
    }

    @Test fun timelineWithUnknownMemberFailsClosedWithoutMutation() = runTest {
        val seeded = seedGroup(FavouriteSpace.NORMAL, "backup-edge:timeline-unknown", "Original timeline")
        val original = codec.dump(FavouriteSpace.NORMAL).toList().single()
        assertRejectedWithoutMutation(
            seeded.groupId,
            original.copy(title = "SHOULD NOT APPLY", timeline = listOf(
                LibraryGroupTimelineBackup(999999L, 777L, 0),
            )),
            "timeline references an unknown member",
            mapOf(seeded.categoryId to seeded.categoryId),
        )
    }

    @Test fun duplicateTimelineChapterFailsClosedWithoutMutation() = runTest {
        val seeded = seedGroup(FavouriteSpace.NORMAL, "backup-edge:timeline-duplicate", "Original timeline")
        val original = codec.dump(FavouriteSpace.NORMAL).toList().single()
        val chapter = checkNotNull(original.timeline).single()
        assertRejectedWithoutMutation(
            seeded.groupId,
            original.copy(title = "SHOULD NOT APPLY", timeline = listOf(chapter, chapter)),
            "duplicate chapter",
            mapOf(seeded.categoryId to seeded.categoryId),
        )
    }

    @Test fun negativeTimelinePositionFailsClosedWithoutMutation() = runTest {
        val seeded = seedGroup(FavouriteSpace.NORMAL, "backup-edge:timeline-position", "Original timeline")
        val original = codec.dump(FavouriteSpace.NORMAL).toList().single()
        val chapter = checkNotNull(original.timeline).single()
        assertRejectedWithoutMutation(
            seeded.groupId,
            original.copy(title = "SHOULD NOT APPLY", timeline = listOf(chapter.copy(position = -1))),
            "timeline position",
            mapOf(seeded.categoryId to seeded.categoryId),
        )
    }

    @Test fun duplicateGroupMembershipFailsClosedWithoutMutation() = runTest {
        val seeded = seedGroup(FavouriteSpace.NORMAL, "backup-edge:duplicate-members", "Original members")
        val original = codec.dump(FavouriteSpace.NORMAL).toList().single()
        assertRejectedWithoutMutation(
            seeded.groupId,
            original.copy(title = "SHOULD NOT APPLY", members = original.members + original.members.first()),
            "Invalid library group membership",
            mapOf(seeded.categoryId to seeded.categoryId),
        )
    }

    @Test fun overlappingGroupsAcrossSetsRejectAmbiguousRestoreWithoutMutation() = runTest {
        val seeded = seedGroup(FavouriteSpace.NORMAL, "backup-edge:set-a", "Set A")
        val incoming = codec.dump(FavouriteSpace.NORMAL).toList().single().copy(title = "SHOULD NOT APPLY")
        val dao = database.getLibraryGroupsDao()
        val otherSet = insertSet(FavouriteSpace.NORMAL, "backup-edge:set-b")
        val otherId = dao.insertGroup(LibraryGroupEntity(
            title = "Set B", coverUrl = null, createdAt = 200L,
            space = FavouriteSpace.NORMAL.dbValue, setId = otherSet,
        ))
        dao.insertMembers(memberIds.mapIndexed { index, mangaId ->
            LibraryGroupMemberEntity(otherId, mangaId, index)
        })
        val beforeA = groupState(seeded.groupId)
        val beforeB = groupState(otherId)
        val result = codec.restore(
            sequenceOf(incoming), FavouriteSpace.NORMAL,
            mapOf(seeded.categoryId to seeded.categoryId),
        )
        assertFalse("Ambiguous cross-Set Group restore succeeded", result.isAllSuccess)
        assertTrue("Wrong failure: ${result.failures}",
            result.failures.any { it.message?.contains("different groups") == true })
        assertEquals(beforeA, groupState(seeded.groupId))
        assertEquals(beforeB, groupState(otherId))
        assertEquals(2, dao.findAllGroups(0).size)
    }

    @Test fun lateCategoryMappingFailureRollsBackEveryGroupChild() = runTest {
        val seeded = seedGroup(FavouriteSpace.NORMAL, "backup-edge:late-rollback", "Original metadata")
        val original = codec.dump(FavouriteSpace.NORMAL).toList().single()
        val reordered = original.members.reversed().mapIndexed { index, member ->
            member.copy(position = index)
        }
        // A missing category mapping fails after member positions and metadata
        // have been updated inside the transaction; neither may survive rollback.
        assertRejectedWithoutMutation(
            seeded.groupId,
            original.copy(
                title = "SHOULD NOT APPLY",
                description = "SHOULD NOT APPLY",
                members = reordered,
                timeline = emptyList(),
                tracking = emptyList(),
            ),
            "unmapped category",
            emptyMap(),
        )
    }


    @Test fun normalNativeGroupRestoreTwiceSurvivesDatabaseReopen() = runTest {
        assertNativeIdempotency(FavouriteSpace.NORMAL)
    }

    @Test fun privateNativeGroupRestoreTwiceSurvivesDatabaseReopen() = runTest {
        assertNativeIdempotency(FavouriteSpace.PRIVATE)
    }

    private suspend fun assertNativeIdempotency(space: FavouriteSpace) {
        val ownerKey = "backup-idempotent:${space.name.lowercase()}"
        val source = seedGroup(space, ownerKey, "Durable Group")
        val file = backupFile("twice-${space.name.lowercase()}")
        try {
            privateSecurity.includePrivateInBackup = space == FavouriteSpace.PRIVATE
            writeBackup(file)
            resetFixtureDatabase()
            database.ensureCategorySets(space)
            insertSet(space, "$ownerKey:unrelated")
            val sections = if (space == FavouriteSpace.NORMAL) {
                setOf(BackupSection.CATEGORIES, BackupSection.FAVOURITES, BackupSection.LIBRARY_GROUPS)
            } else {
                emptySet()
            }
            val restorePrivate = space == FavouriteSpace.PRIVATE
            val firstResult = restore(file, sections, restorePrivate)
            assertTrue("First $space restore failed: ${firstResult.failures}", firstResult.isAllSuccess)
            val first = persistenceSnapshot(database, space, ownerKey)
            assertNotEquals("Fixture did not remap Set ids", source.setId, first.group.group?.setId)
            assertEquals(memberIds, first.group.members.map { it.mangaId })
            assertEquals(listOf(0, 1), first.group.members.map { it.position })
            assertEquals(listOf(777L), first.group.timeline.map { it.chapterId })
            assertEquals(listOf("tracking"), first.group.tracking.map { it.targetTitle })
            assertEquals(1, first.group.categories.size)
            assertEquals(1, first.groupCount)
            assertEquals(listOf(1, 1), first.favouriteCounts)
            val secondResult = restore(file, sections, restorePrivate)
            assertTrue("Second $space restore failed: ${secondResult.failures}", secondResult.isAllSuccess)
            assertEquals("Repeated $space restore changed or duplicated state",
                first, persistenceSnapshot(database, space, ownerKey))

            // A category-mapping error after metadata/member writes must roll back.
            val original = codec.dump(space).toList().single()
            val invalid = original.copy(
                title = "MUST ROLL BACK", description = "MUST ROLL BACK",
                members = original.members.reversed().mapIndexed { index, item ->
                    item.copy(position = index)
                },
                timeline = emptyList(), tracking = emptyList(),
            )
            val rejected = codec.restore(sequenceOf(invalid), space, emptyMap())
            assertFalse("Invalid $space restore accepted", rejected.isAllSuccess)
            assertTrue("Unexpected rejection: ${rejected.failures}",
                rejected.failures.any { it.message?.contains("unmapped category") == true })
            assertEquals("Rollback lost Group state",
                first, persistenceSnapshot(database, space, ownerKey))

            // Independent file-backed Room handle: verify persisted SQLite rows,
            // not only the Hilt-injected Room instance's in-memory state.
            val firstHandle = org.koitharu.kotatsu.core.db.MangaDatabase(context)
            try {
                assertEquals("Fresh Room handle differs after $space restore",
                    first, persistenceSnapshot(firstHandle, space, ownerKey))
            } finally {
                firstHandle.close()
            }
            val reopened = org.koitharu.kotatsu.core.db.MangaDatabase(context)
            try {
                assertEquals("Database close/reopen lost $space state",
                    first, persistenceSnapshot(reopened, space, ownerKey))
            } finally {
                reopened.close()
            }
        } finally {
            privateSecurity.includePrivateInBackup = false
            file.delete()
        }
    }

    private suspend fun persistenceSnapshot(
        db: MangaDatabase, space: FavouriteSpace, ownerKey: String,
    ): GroupPersistenceSnapshot {
        val dao = db.getLibraryGroupsDao()
        val groups = dao.findAllGroups(space.dbValue)
        val group = groups.single()
        val setsDao = db.getFavouriteCategorySetsDao()
        val set = checkNotNull(setsDao.findSet(group.setId))
        assertEquals("Wrong Set owner", ownerKey, set.syncKey)
        assertEquals(space.dbValue, set.space)
        assertEquals(0L, set.deletedAt)
        val categories = db.getFavouriteCategoriesDao().findAllInSpace(space.dbValue)
        val assignments = setsDao.getAssignments(space.dbValue)
        val state = GroupState(
            group, dao.findMembers(group.groupId), dao.findCategories(group.groupId),
            dao.findTimeline(group.groupId), db.getLibraryGroupTrackingDao().findAll(group.groupId),
        )
        assertTrue("Group categories invalid", state.categories.all { entry ->
            categories.any { it.categoryId.toLong() == entry.categoryId } &&
                assignments.any { it.categoryId == entry.categoryId && it.setId == group.setId }
        })
        val counts = memberIds.map { memberId ->
            if (space == FavouriteSpace.NORMAL) db.getFavouritesDao().findCategoriesCount(memberId)
            else db.getPrivateFavouritesDao().findCategoriesCount(memberId)
        }
        return GroupPersistenceSnapshot(
            state, groups.size, setsDao.getSets(space.dbValue).size,
            categories.size, assignments.size, counts,
        )
    }

    private data class GroupPersistenceSnapshot(
        val group: GroupState,
        val groupCount: Int,
        val setCount: Int,
        val categoryCount: Int,
        val assignmentCount: Int,
        val favouriteCounts: List<Int>,
    )

    private suspend fun assertRejectedWithoutMutation(
        groupId: Long,
        backup: LibraryGroupBackup,
        message: String,
        categoryIdMap: Map<Long, Long>,
    ) {
        val dao = database.getLibraryGroupsDao()
        val before = groupState(groupId)
        val groupsBefore = dao.findAllGroups(FavouriteSpace.NORMAL.dbValue)
        val result = codec.restore(sequenceOf(backup), FavouriteSpace.NORMAL, categoryIdMap)
        assertFalse("Expected safe rejection: ${result.failures}", result.isAllSuccess)
        assertTrue("Wrong rejection reason (expected $message): ${result.failures}",
            result.failures.any { it.message?.contains(message) == true })
        assertEquals("Group state changed despite rejected restore", before, groupState(groupId))
        assertEquals("Group count/metadata changed despite rejected restore",
            groupsBefore, dao.findAllGroups(FavouriteSpace.NORMAL.dbValue))
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
