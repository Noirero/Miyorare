package org.koitharu.kotatsu.sync.domain

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.koitharu.kotatsu.favourites.domain.CategorySetIdentity
import org.koitharu.kotatsu.sync.data.model.*

class CategorySetsSyncTest {
	private val main = SyncCategorySet(CategorySetIdentity.defaultSet(0), "Utama", 0, 0, 0)
	private val second = SyncCategorySet("set-second", "Second", 1, 10, 10)
	private fun category(id: Int = 1, key: String = "category-a", set: String = main.syncKey, title: String = "Favorite", time: Long = 10) =
		SyncCategory(id, 1, 0, title, "NEWEST", true, true, true, 0, key, set, time, "MANGA")
	private fun snapshot(cats: List<SyncCategory> = emptyList(), sets: List<SyncCategorySet> = listOf(main, second)) = SyncSnapshot(categorySets = sets, categories = cats)
	private fun merged(a: SyncSnapshot, b: SyncSnapshot, deletions: Boolean = true) = SyncMerger.mergeFavouriteState(a, b, deletions)

	@Test fun oldSchemaAndIndependentUpgradeUseTheSameDefaultAndCategoryIdentity() {
		val oldCategory = category(id = 99).copy(syncKey = "")
		val old = SyncSnapshot(schemaVersion = 4, categories = listOf(oldCategory))
		val migrated = category(id = 3, key = CategorySetIdentity.legacyCategory(0, "Favorite"))
		val state = merged(snapshot(listOf(migrated), listOf(main)), old)
		assertEquals(1, state.sets.size)
		assertEquals(main.syncKey, state.sets.single().syncKey)
		assertEquals(1, state.categories.size)
		assertEquals(3, state.categories.single().categoryId)
	}

	@Test fun legacyDuplicateRowsAreKeptWithDifferentIdentities() {
		val old = SyncSnapshot(schemaVersion = 4, categories = listOf(category(4).copy(syncKey = ""), category(8).copy(syncKey = "", title = " favorite ")))
		val upgraded = SyncMerger.upgradeCategorySets(old)
		assertEquals(2, upgraded.categories.map { it.syncKey }.toSet().size)
		assertEquals(setOf(4, 8), upgraded.categories.map { it.categoryId }.toSet())
	}

	@Test fun emptySetsAndMoreThanTwoSetsSurviveWireRoundTrip() {
		val third = second.copy(syncKey = "third", title = "Archive", sortKey = 2)
		val source = snapshot(sets = listOf(main, second, third))
		val json = Json { encodeDefaults = true }
		val decoded = json.decodeFromString(SyncSnapshot.serializer(), json.encodeToString(SyncSnapshot.serializer(), source))
		assertEquals(source.categorySets, decoded.categorySets)
		assertEquals(3, merged(source, decoded).sets.size)
	}

	@Test fun setRenameAndReorderUpdateOneIdentityAndConvergeInEitherDirection() {
		val changed = second.copy(title = "Renamed", sortKey = -1, updatedAt = 20)
		val a = snapshot()
		val b = snapshot(sets = listOf(main, changed))
		assertEquals(merged(a, b).sets, merged(b, a).sets)
		assertEquals(changed, merged(a, b).sets.first())
		assertEquals(2, merged(a, b).sets.size)
	}

	@Test fun categoryRenameAndMoveKeepStableIdentityAndLocalNumericId() {
		val a = category(id = 3)
		val b = a.copy(categoryId = 91, title = "Renamed", setSyncKey = second.syncKey, sortKey = 9, updatedAt = 30)
		val state = merged(snapshot(listOf(a)), snapshot(listOf(b)))
		assertEquals(listOf(b.copy(categoryId = 3)), state.categories)
	}

	@Test fun sameTitleInDifferentSetsNeverCollides() {
		val a = category(1, "first-key")
		val b = category(1, "second-key", second.syncKey)
		val state = merged(snapshot(listOf(a)), snapshot(listOf(b)))
		assertEquals(2, state.categories.size)
		assertEquals(setOf("first-key", "second-key"), state.categories.map { it.syncKey }.toSet())
		assertEquals(2, state.categories.map { it.categoryId }.toSet().size)
	}

	@Test fun incomingNumericIdReservesPrivateAndOtherLocalRows() {
		val state = SyncMerger.mergeFavouriteState(snapshot(), snapshot(listOf(category(1))), reservedIds = setOf(1, 2, 3))
		assertEquals(4, state.categories.single().categoryId)
	}

	@Test fun safeDeleteMovesCategoryAndOldSnapshotCannotResurrectSet() {
		val old = category(set = second.syncKey)
		val moved = old.copy(setSyncKey = main.syncKey, updatedAt = 30)
		val deleted = second.copy(deletedAt = 30, updatedAt = 30)
		val state = merged(snapshot(listOf(moved), listOf(main, deleted)), snapshot(listOf(old)))
		assertEquals(moved, state.categories.single())
		assertEquals(30L, state.sets.first { it.syncKey == second.syncKey }.deletedAt)
	}

	@Test fun concurrentEditIntoDeletedSetIsRehomedWithoutRecreatingCategory() {
		val category = category(set = second.syncKey, time = 80)
		val state = merged(snapshot(listOf(category)), snapshot(sets = listOf(main, second.copy(deletedAt = 30, updatedAt = 30))))
		assertEquals(main.syncKey, state.categories.single().setSyncKey)
		assertEquals(category.syncKey, state.categories.single().syncKey)
		assertTrue(state.categories.single().updatedAt > category.updatedAt)
	}

	@Test fun categoryTombstoneWinsAgainstStaleOrOfflineRename() {
		val deletion = category().copy(deletedAt = 30, updatedAt = 30)
		val renamedOffline = category(title = "Offline rename", time = 80)
		val a = merged(snapshot(listOf(deletion)), snapshot(listOf(renamedOffline)))
		val b = merged(snapshot(listOf(renamedOffline)), snapshot(listOf(deletion)))
		assertEquals(a.categories, b.categories)
		assertEquals(deletion, a.categories.single())
	}

	@Test fun deletionSyncDisabledKeepsLiveCloudState() {
		val live = category()
		val deleted = live.copy(deletedAt = 30, updatedAt = 30)
		val state = merged(snapshot(listOf(deleted), listOf(main, second.copy(deletedAt = 30))), snapshot(listOf(live)), false)
		assertEquals(0L, state.categories.single().deletedAt)
		assertTrue(state.sets.all { it.deletedAt == 0L })
	}

	@Test fun concurrentDeletionOfDifferentLastSetsRecoversOneLiveSetDeterministically() {
		val a = snapshot(sets = listOf(main.copy(deletedAt = 30, updatedAt = 30), second))
		val b = snapshot(sets = listOf(main, second.copy(deletedAt = 40, updatedAt = 40)))
		assertEquals(merged(a, b).sets.toSet(), merged(b, a).sets.toSet())
		assertEquals(1, merged(a, b).sets.count { it.deletedAt == 0L })
		assertTrue(merged(a, b).sets.filter { it.deletedAt == 0L }.none { it.syncKey == main.syncKey || it.syncKey == second.syncKey })
	}

	@Test fun exactTimestampConflictHasTheSameWinnerOnBothDevices() {
		val a = category(title = "One")
		val b = a.copy(title = "Two")
		assertEquals(merged(snapshot(listOf(a)), snapshot(listOf(b))).categories, merged(snapshot(listOf(b)), snapshot(listOf(a))).categories)
	}

	@Test fun duplicateDriveFilesDoNotMergeUnrelatedNumericIds() {
		val a = snapshot(listOf(category(1, "a")))
		val b = snapshot(listOf(category(1, "b", second.syncKey)))
		val result = checkNotNull(SyncMerger.combine(listOf(a, b)))
		assertEquals(2, result.categories.size)
		assertEquals(2, result.categories.map { it.categoryId }.toSet().size)
	}

	@Test fun concurrentSameNamedSetsKeepBothIdentitiesWithUnambiguousTitles() {
		val a = second.copy(syncKey = "identity-one", title = "Shared")
		val b = second.copy(syncKey = "identity-two", title = " shared ")
		val first = merged(snapshot(sets = listOf(main, a)), snapshot(sets = listOf(main, b)))
		val reverse = merged(snapshot(sets = listOf(main, b)), snapshot(sets = listOf(main, a)))
		assertEquals(first.sets, reverse.sets)
		assertEquals(3, first.sets.size)
		assertEquals(3, first.sets.map { CategorySetIdentity.normalizeTitle(it.title) }.toSet().size)
	}

	@Test(expected = IllegalArgumentException::class) fun unsupportedNewerSchemaFailsClosed() {
		SyncMerger.upgradeCategorySets(SyncSnapshot(schemaVersion = SyncSnapshot.SCHEMA_VERSION + 1))
	}
	@Test(expected = IllegalArgumentException::class) fun privateSetMetadataCannotEnterNormalSync() {
		merged(snapshot(), snapshot(sets = listOf(main, second.copy(space = 1))))
	}
	@Test(expected = IllegalArgumentException::class) fun missingStableIdentityInNewSchemaFailsClosed() {
		SyncMerger.upgradeCategorySets(snapshot(listOf(category().copy(syncKey = ""))))
	}
	@Test(expected = IllegalArgumentException::class) fun unknownSetReferenceFailsClosed() {
		merged(snapshot(), snapshot(listOf(category(set = "missing-set"))))
	}
}
