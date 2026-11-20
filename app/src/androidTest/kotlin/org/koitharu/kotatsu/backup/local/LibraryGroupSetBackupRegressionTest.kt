package org.koitharu.kotatsu.backup.local

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koitharu.kotatsu.SampleData
import org.koitharu.kotatsu.backup.local.data.LibraryGroupBackupCodec
import org.koitharu.kotatsu.backup.local.data.LocalBackupRepository
import org.koitharu.kotatsu.backup.local.data.model.MangaBackup
import org.koitharu.kotatsu.backup.local.data.model.LibraryGroupBackup
import org.koitharu.kotatsu.backup.local.data.model.LibraryGroupTimelineBackup
import org.koitharu.kotatsu.backup.local.domain.BackupSection
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.db.entity.toEntity
import org.koitharu.kotatsu.history.data.HistoryEntity
import org.koitharu.kotatsu.favourites.data.*
import org.koitharu.kotatsu.favourites.groups.data.*
import org.koitharu.kotatsu.favourites.vault.PrivateFavouritesSecurityStore
import org.koitharu.kotatsu.sync.data.SyncSettings
import org.koitharu.kotatsu.sync.data.model.SyncContent
import org.koitharu.kotatsu.sync.data.model.SyncFavourite
import org.koitharu.kotatsu.sync.data.model.SyncHistory
import org.koitharu.kotatsu.sync.data.model.SyncSnapshot
import org.koitharu.kotatsu.sync.domain.GoogleDriveSyncRepository
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
    @Inject lateinit var driveSync: GoogleDriveSyncRepository
    @Inject lateinit var syncSettings: SyncSettings

    private var previousDeletionSyncDisabled = true

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val memberIds = listOf(831001L, 831002L)

    @Before fun setUp() {
        hiltRule.inject()
        previousDeletionSyncDisabled = syncSettings.isDeletionSyncDisabled
        resetFixtureDatabase()
        privateSecurity.includePrivateInBackup = false
    }

    @After fun tearDown() {
        syncSettings.isDeletionSyncDisabled = previousDeletionSyncDisabled
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


    @Test fun remoteSetTombstoneWithOwnedGroupFailsClosedAcrossRetries() = runTest {
        val ownerKey = "sync-tombstone:owned"
        val fixture = seedGroup(FavouriteSpace.NORMAL, ownerKey, "Still locally owned")
        val remote = deletedRemoteSet(ownerKey)
        val baseline = syncOwnerSnapshot(fixture.groupId)

        // A remote Set tombstone must not partially move categories or delete
        // the Set when a local Group still owns it. Repeated apply is a retry.
        assertTombstoneRejected(remote, fixture.groupId, baseline)
        assertTombstoneRejected(remote, fixture.groupId, baseline)
        assertEquals("Unresolved owner conflict must not be silently discarded",
            fixture.setId, database.getLibraryGroupsDao().findGroup(fixture.groupId)?.setId)
    }

    @Test fun remoteSetTombstoneConvergesAfterExplicitGroupRehome() = runTest {
        val ownerKey = "sync-tombstone:resolve"
        val fixture = seedGroup(FavouriteSpace.NORMAL, ownerKey, "Group to preserve")
        val remote = deletedRemoteSet(ownerKey)
        val before = syncOwnerSnapshot(fixture.groupId)
        assertTombstoneRejected(remote, fixture.groupId, before)

        val destination = database.getFavouriteCategorySetsDao().getSets(0)
            .single { it.setId != fixture.setId }
        val children = groupState(fixture.groupId)
        // Explicit user-equivalent resolution, not an automatic production policy.
        // Existing SQL owner/membership triggers remain enabled.
        database.openHelper.writableDatabase.execSQL(
            "UPDATE library_groups SET set_id = ? WHERE group_id = ?",
            arrayOf(destination.setId, fixture.groupId),
        )
        driveSync.applyToDatabase(remote, false, setOf(SyncContent.FAVOURITES))

        val owner = checkNotNull(database.getFavouriteCategorySetsDao().findSet(fixture.setId))
        assertTrue("Remote deleted Set was not tombstoned", owner.deletedAt > 0)
        assertEquals(destination.setId, database.getLibraryGroupsDao().findGroup(fixture.groupId)?.setId)
        assertEquals(destination.setId,
            database.getFavouriteCategorySetsDao().getAssignment(fixture.categoryId)?.setId)
        val after = groupState(fixture.groupId)
        assertEquals(children.members, after.members)
        assertEquals(children.categories, after.categories)
        assertEquals(children.timeline, after.timeline)
        assertEquals(children.tracking, after.tracking)
        assertEquals(before.favourites, database.getFavouritesDao().findAllForSync())
        assertEquals(1, database.getLibraryGroupsDao().findAllGroups(0).size)
        // Applying the same cloud snapshot again must be idempotent.
        val converged = syncOwnerSnapshot(fixture.groupId)
        driveSync.applyToDatabase(remote, false, setOf(SyncContent.FAVOURITES))
        assertEquals("Converged retry must not mutate Group/Set/Category/Favourite rows",
            converged, syncOwnerSnapshot(fixture.groupId))
    }


    @Test fun remoteSetDeletionToggleFailsClosedWithoutOwnerMutation() = runTest {
        val fixture = seedGroup(FavouriteSpace.NORMAL, "sync-toggle:remote-set", "Owner survives")
        val original = SyncSnapshot(
            categorySets = driveSync.localCategorySets(),
            categories = driveSync.localCategories(),
        )
        val deleted = original.copy(categorySets = original.categorySets.map { set ->
            if (set.syncKey == "sync-toggle:remote-set")
                set.copy(updatedAt = 900L, deletedAt = 900L) else set
        })
        val before = syncOwnerSnapshot(fixture.groupId)
        syncSettings.isDeletionSyncDisabled = true
        repeat(2) {
            driveSync.applyToDatabase(deleted, false, setOf(SyncContent.FAVOURITES))
            assertEquals("Deletion protection unexpectedly mutated owner or Group",
                before, syncOwnerSnapshot(fixture.groupId))
        }
        syncSettings.isDeletionSyncDisabled = false
        assertTombstoneRejected(deleted, fixture.groupId, before)
        assertTombstoneRejected(deleted, fixture.groupId, before)
        syncSettings.isDeletionSyncDisabled = true
        driveSync.applyToDatabase(deleted, false, setOf(SyncContent.FAVOURITES))
        assertEquals("Reenabled protection must retain Group and active Set",
            before, syncOwnerSnapshot(fixture.groupId))
    }

    @Test fun localCategoryTombstoneSurvivesStaleRemoteAndToggle() = runTest {
        val fixture = seedGroup(FavouriteSpace.NORMAL, "sync-toggle:local-category", "Local delete")
        val staleLive = SyncSnapshot(
            categorySets = driveSync.localCategorySets(),
            categories = driveSync.localCategories(),
        )
        val categoriesDao = database.getFavouriteCategoriesDao()
        val local = categoriesDao.findAllForSync().single { it.categoryId.toLong() == fixture.categoryId }
        categoriesDao.upsert(local.copy(deletedAt = 950L))
        val before = syncOwnerSnapshot(fixture.groupId)
        val children = groupState(fixture.groupId)
        for (disabled in listOf(true, false, true, true)) {
            syncSettings.isDeletionSyncDisabled = disabled
            driveSync.applyToDatabase(staleLive, false, setOf(SyncContent.FAVOURITES))
            val current = categoriesDao.findAllForSync().single { it.categoryId.toLong() == fixture.categoryId }
            assertEquals("Stale Category resurrected; protection disabled=$disabled", 950L, current.deletedAt)
            assertEquals(fixture.setId, database.getLibraryGroupsDao().findGroup(fixture.groupId)?.setId)
            assertEquals(children, groupState(fixture.groupId))
            assertEquals(before.favourites, database.getFavouritesDao().findAllForSync())
            assertEquals(1, database.getLibraryGroupsDao().findAllGroups(0).size)
            assertEquals(1, database.getFavouriteCategorySetsDao().getAssignments(0).count {
                it.categoryId == fixture.categoryId
            })
        }
    }

    @Test fun localSetTombstoneSurvivesStaleOfflineSnapshotAfterRehome() = runTest {
        val fixture = seedGroup(FavouriteSpace.NORMAL, "sync-toggle:local-set", "Rehomed")
        val staleLive = SyncSnapshot(
            categorySets = driveSync.localCategorySets(),
            categories = driveSync.localCategories(),
        )
        val setsDao = database.getFavouriteCategorySetsDao()
        val fallback = setsDao.getSets(0).single { it.setId != fixture.setId }
        val groupBefore = groupState(fixture.groupId)
        // Local Set deletion requires explicit safe ownership/category resolution.
        database.openHelper.writableDatabase.execSQL(
            "UPDATE library_groups SET set_id = ? WHERE group_id = ?",
            arrayOf(fallback.setId, fixture.groupId),
        )
        setsDao.assign(checkNotNull(setsDao.getAssignment(fixture.categoryId)).copy(
            setId = fallback.setId, updatedAt = 850L,
        ))
        val owner = checkNotNull(setsDao.findSet(fixture.setId))
        setsDao.upsert(owner.copy(deletedAt = 950L, updatedAt = 950L))
        val resolvedChildren = groupState(fixture.groupId)
        assertEquals(groupBefore.members, resolvedChildren.members)
        assertEquals(groupBefore.timeline, resolvedChildren.timeline)
        assertEquals(groupBefore.tracking, resolvedChildren.tracking)
        val counts = syncOwnerSnapshot(fixture.groupId)
        for (disabled in listOf(true, false, true, true)) {
            syncSettings.isDeletionSyncDisabled = disabled
            driveSync.applyToDatabase(staleLive, false, setOf(SyncContent.FAVOURITES))
            assertEquals("Stale Set resurrected; protection disabled=$disabled", 950L,
                checkNotNull(setsDao.findSet(fixture.setId)).deletedAt)
            assertEquals(fallback.setId, database.getLibraryGroupsDao().findGroup(fixture.groupId)?.setId)
            assertEquals(fallback.setId, setsDao.getAssignment(fixture.categoryId)?.setId)
            assertEquals(resolvedChildren, groupState(fixture.groupId))
            assertEquals(counts.sets.size, setsDao.getSetsForSync(0).size)
            assertEquals(counts.categories.size, database.getFavouriteCategoriesDao().findAllForSync().size)
            assertEquals(counts.favourites, database.getFavouritesDao().findAllForSync())
        }
    }

    @Test fun remoteCategoryDeletionRequiresOptInAndResistsStaleReplay() = runTest {
        val fixture = seedGroup(FavouriteSpace.NORMAL, "sync-toggle:remote-category", "Remote delete")
        val staleLive = SyncSnapshot(
            categorySets = driveSync.localCategorySets(),
            categories = driveSync.localCategories(),
        )
        val tombstone = staleLive.copy(categories = staleLive.categories.map { category ->
            if (category.categoryId.toLong() == fixture.categoryId)
                category.copy(deletedAt = 975L, updatedAt = 975L) else category
        })
        val before = syncOwnerSnapshot(fixture.groupId)
        syncSettings.isDeletionSyncDisabled = true
        driveSync.applyToDatabase(tombstone, false, setOf(SyncContent.FAVOURITES))
        assertEquals("Protected remote Category deletion should be ignored",
            before, syncOwnerSnapshot(fixture.groupId))
        syncSettings.isDeletionSyncDisabled = false
        repeat(2) {
            driveSync.applyToDatabase(tombstone, false, setOf(SyncContent.FAVOURITES))
            val current = database.getFavouriteCategoriesDao().findAllForSync()
                .single { it.categoryId.toLong() == fixture.categoryId }
            assertEquals(975L, current.deletedAt)
            assertEquals(before.groupState, groupState(fixture.groupId))
        }
        syncSettings.isDeletionSyncDisabled = true
        repeat(2) {
            driveSync.applyToDatabase(staleLive, false, setOf(SyncContent.FAVOURITES))
            assertEquals("Stale Category unexpectedly resurrected", 975L,
                database.getFavouriteCategoriesDao().findAllForSync()
                    .single { it.categoryId.toLong() == fixture.categoryId }.deletedAt)
            assertEquals(before.groupState, groupState(fixture.groupId))
            assertEquals(fixture.setId, database.getLibraryGroupsDao().findGroup(fixture.groupId)?.setId)
        }
    }


    /**
     * Deterministic TTL boundary probe for the production pruning function.
     * No wall-clock or CI runner scheduling assumptions are involved.
     */
    @Test fun tombstoneTtlBoundaryKeepsSetCategoryButExpiresFavouriteHistory() = runTest {
        val fixture = seedGroup(FavouriteSpace.NORMAL, "sync-ttl:boundary", "TTL identity")
        val deletedAt = 1_000L
        val owner = driveSync.localCategorySets()
        val categories = driveSync.localCategories()
        val manga = ttlMangaBackup()
        val favourite = database.getFavouritesDao().findAllForSync()
            .single { it.mangaId == memberIds[0] }
        val history = HistoryEntity(
            mangaId = memberIds[0], createdAt = 100L, updatedAt = 100L,
            chapterId = 777L, page = 1, scroll = 0f, percent = 0.1f,
            deletedAt = deletedAt, chaptersCount = 1,
        )
        val original = SyncSnapshot(
            categorySets = owner.map { if (it.syncKey == "sync-ttl:boundary")
                it.copy(deletedAt = deletedAt, updatedAt = deletedAt) else it },
            categories = categories.map { if (it.categoryId.toLong() == fixture.categoryId)
                it.copy(deletedAt = deletedAt, updatedAt = deletedAt) else it },
            favourites = listOf(SyncFavourite(favourite.copy(deletedAt = deletedAt), manga)),
            history = listOf(SyncHistory(history, manga)),
        )
        val ttl = GoogleDriveSyncRepository.TOMBSTONE_TTL_MS
        val exactBoundary = pruneAtSyntheticTime(original, deletedAt + ttl)
        assertEquals(1, exactBoundary.favourites.size)
        assertEquals(1, exactBoundary.history.size)
        val beyondTtl = pruneAtSyntheticTime(original, deletedAt + ttl + 1L)
        assertTrue("Expired Favourite deletion should be pruned by current policy",
            beyondTtl.favourites.isEmpty())
        assertTrue("Expired History deletion should be pruned by current policy",
            beyondTtl.history.isEmpty())
        assertEquals("Durable Set identity tombstone must remain", original.categorySets,
            beyondTtl.categorySets)
        assertEquals("Durable Category identity tombstone must remain", original.categories,
            beyondTtl.categories)
    }

    /**
     * Diagnostic reproduction of the explicitly documented 60-day expiry trade-off.
     * A stale device coming online after BOTH cloud pruning and local DAO GC can
     * reintroduce an old live Favourite/History. This test records the behaviour,
     * not approval of the data-retention policy or proof of two-device E2E sync.
     */
    @Test fun expiredFavouriteHistoryTombstonesPermitStaleOfflineResurrection() = runTest {
        val fixture = seedGroup(FavouriteSpace.NORMAL, "sync-ttl:replay", "TTL replay")
        val manga = ttlMangaBackup()
        val favouritesDao = database.getFavouritesDao()
        val historyDao = database.getHistoryDao()
        val liveFavourite = favouritesDao.findAllForSync().single { it.mangaId == memberIds[0] }
        val liveHistory = HistoryEntity(
            mangaId = memberIds[0], createdAt = 100L, updatedAt = 100L,
            chapterId = 777L, page = 1, scroll = 0f, percent = 0.1f,
            deletedAt = 0L, chaptersCount = 1,
        )
        historyDao.upsertForSync(liveHistory)
        val staleOffline = SyncSnapshot(
            categorySets = driveSync.localCategorySets(),
            categories = driveSync.localCategories(),
            favourites = listOf(SyncFavourite(liveFavourite, manga)),
            history = listOf(SyncHistory(liveHistory, manga)),
        )
        val deletedAt = 1_000L
        val tombstoneFavourite = liveFavourite.copy(deletedAt = deletedAt)
        favouritesDao.upsert(tombstoneFavourite)
        database.openHelper.writableDatabase.execSQL(
            "UPDATE history SET deleted_at = ? WHERE manga_id = ?",
            arrayOf(deletedAt, memberIds[0]),
        )
        val deletedHistory = historyDao.findAllForSync()
            .single { it.mangaId == memberIds[0] }
        assertEquals(deletedAt, deletedHistory.deletedAt)
        val localDeleted = staleOffline.copy(
            favourites = listOf(SyncFavourite(tombstoneFavourite, manga)),
            history = listOf(SyncHistory(deletedHistory, manga)),
        )
        val ttl = GoogleDriveSyncRepository.TOMBSTONE_TTL_MS
        val syntheticNow = deletedAt + ttl + 1L
        val pruned = pruneAtSyntheticTime(localDeleted, syntheticNow)
        assertTrue(pruned.favourites.isEmpty())
        assertTrue(pruned.history.isEmpty())

        // Invoke precisely the two DAO GC operations used in gcOldTombstones;
        // unlike the production helper, the cutoff is supplied by this test.
        syncSettings.isDeletionSyncDisabled = false
        val cutoff = syntheticNow - ttl
        favouritesDao.gc(cutoff)
        historyDao.gc(cutoff)
        assertFalse(favouritesDao.findAllForSync().any { it.mangaId == memberIds[0] })
        assertFalse(historyDao.findAllForSync().any { it.mangaId == memberIds[0] })
        val groupBefore = groupState(fixture.groupId)
        repeat(2) {
            driveSync.applyToDatabase(staleOffline, false,
                setOf(SyncContent.FAVOURITES, SyncContent.HISTORY))
            assertEquals("Documented stale Favourite resurrection after TTL", 0L,
                favouritesDao.findAllForSync().single { it.mangaId == memberIds[0] }.deletedAt)
            assertEquals("Documented stale History resurrection after TTL", 0L,
                historyDao.findAllForSync().single { it.mangaId == memberIds[0] }.deletedAt)
            assertEquals("Group children must survive stale replay",
                groupBefore, groupState(fixture.groupId))
        }
    }

    private fun ttlMangaBackup() = MangaBackup(
        id = memberIds[0], title = "TTL fixture", url = "/backup-repro-${memberIds[0]}",
        publicUrl = "https://fixture.invalid/backup-repro-${memberIds[0]}",
        coverUrl = "", source = SampleData.mangaDetails.source.name,
    )

    private fun pruneAtSyntheticTime(snapshot: SyncSnapshot, now: Long): SyncSnapshot {
        val method = driveSync.javaClass.getDeclaredMethod(
            "pruneTombstones", SyncSnapshot::class.java, Long::class.javaPrimitiveType,
        )
        method.isAccessible = true
        return method.invoke(driveSync, snapshot, now) as SyncSnapshot
    }

    private suspend fun deletedRemoteSet(ownerKey: String): SyncSnapshot {
        // The user-facing deletion-sync protection is enabled by default; this
        // fixture explicitly tests opt-in propagation of a remote tombstone.
        syncSettings.isDeletionSyncDisabled = false
        assertFalse(syncSettings.isDeletionSyncDisabled)
        val sets = driveSync.localCategorySets()
        assertEquals(1, sets.count { it.syncKey == ownerKey })
        return SyncSnapshot(
            categorySets = sets.map { set ->
                if (set.syncKey == ownerKey) set.copy(updatedAt = 500L, deletedAt = 500L)
                else set
            },
            categories = driveSync.localCategories(),
        )
    }

    private suspend fun assertTombstoneRejected(
        remote: SyncSnapshot, groupId: Long, baseline: SyncOwnerSnapshot,
    ) {
        val failure = try {
            driveSync.applyToDatabase(remote, false, setOf(SyncContent.FAVOURITES))
            null
        } catch (error: Exception) {
            error
        }
        assertNotNull("Expected SQL owner trigger to reject remote Set tombstone", failure)
        val messages = generateSequence<Throwable>(failure) { it.cause }
            .mapNotNull { it.message }.joinToString(" | ")
        assertTrue("Expected Set ownership trigger, actual: $messages",
            messages.contains("Set still owns library groups"))
        assertEquals("Failed sync must roll back every Group/Set/Category/Favourite change",
            baseline, syncOwnerSnapshot(groupId))
    }

    private suspend fun syncOwnerSnapshot(groupId: Long): SyncOwnerSnapshot =
        SyncOwnerSnapshot(
            database.getFavouriteCategorySetsDao().getSetsForSync(0),
            database.getFavouriteCategoriesDao().findAllForSync(),
            database.getFavouriteCategorySetsDao().getAssignments(0),
            database.getFavouritesDao().findAllForSync(),
            database.getLibraryGroupsDao().findAllGroups(0),
            groupState(groupId),
        )

    private data class SyncOwnerSnapshot(
        val sets: List<FavouriteCategorySetEntity>,
        val categories: List<FavouriteCategoryEntity>,
        val assignments: List<FavouriteCategorySetAssignmentEntity>,
        val favourites: List<FavouriteEntity>,
        val groups: List<LibraryGroupEntity>,
        val groupState: GroupState,
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
