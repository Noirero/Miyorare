package org.koitharu.kotatsu.favourites

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.koitharu.kotatsu.SampleData
import org.koitharu.kotatsu.backup.local.data.LocalBackupRepository
import org.koitharu.kotatsu.backup.local.data.model.CategoryBackup
import org.koitharu.kotatsu.backup.local.data.model.CategorySetBackup
import org.koitharu.kotatsu.backup.local.domain.BackupSection
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.favourites.data.*
import org.koitharu.kotatsu.favourites.domain.*
import org.koitharu.kotatsu.favourites.vault.PrivateFavouritesSecurityStore
import org.koitharu.kotatsu.list.domain.ListSortOrder
import org.koitharu.kotatsu.sync.data.SyncSettings
import org.koitharu.kotatsu.sync.data.model.*
import org.koitharu.kotatsu.sync.domain.GoogleDriveSyncRepository
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.inject.Inject

/** Real Room/repository/ZIP/sync-apply checks. No Drive account or live network is required. */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class CategorySetsRuntimeTest {
	@get:Rule val hilt = HiltAndroidRule(this)
	@Inject lateinit var database: MangaDatabase
	@Inject lateinit var repository: FavouritesRepository
	@Inject lateinit var backup: LocalBackupRepository
	@Inject lateinit var types: FavouriteContentTypeStore
	@Inject lateinit var selection: FavouriteSetSelectionStore
	@Inject lateinit var security: PrivateFavouritesSecurityStore
	@Inject lateinit var sync: GoogleDriveSyncRepository
	@Inject lateinit var syncSettings: SyncSettings
	@Inject lateinit var transfer: TransferFavouritesToPrivateUseCase
	private val normal = FavouriteSpace.NORMAL
	private val privateSpace = FavouriteSpace.PRIVATE
	private val manga get() = SampleData.manga

	@Before fun prepare() = runBlocking {
		hilt.inject()
		database.clearAllTables()
		types.removeCategories(types.novelCategoryIds.value)
		types.setSelectedType(FavouriteContentType.MANGA, normal)
		types.setSelectedType(FavouriteContentType.MANGA, privateSpace)
		repository.getSets(normal)
		repository.getSets(privateSpace)
		security.includePrivateInBackup = false
		syncSettings.isDeletionSyncDisabled = false
	}
	@After fun cleanup() { security.includePrivateInBackup = false; syncSettings.isDeletionSyncDisabled = false }
	private suspend fun main(space: FavouriteSpace = normal) = repository.getSets(space).first()
	private suspend fun category(title: String, set: FavouriteCategorySetEntity, novel: Boolean = false): Long {
		val id = repository.createCategory(title, ListSortOrder.NEWEST, true, true, true, FavouriteSpace.fromDb(set.space), set.setId).id
		types.setCategoryType(id, if (novel) FavouriteContentType.NOVEL else FavouriteContentType.MANGA)
		return id
	}
	private suspend fun members(vararg ids: Long, space: FavouriteSpace = normal) = repository.updateCategoryMemberships(listOf(manga), ids.associateWith { true }, space)
	private suspend fun ids(set: FavouriteCategorySetEntity) = repository.observeCategoriesInSet(set.setId, FavouriteSpace.fromDb(set.space)).first().map { it.id }

	@Test fun managementCategoryCountsIncludeBothTypesAndHiddenCategoriesWithinOneSpace() = runBlocking {
		val first = main()
		val second = repository.createSet("Second", normal)
		val privateSet = main(privateSpace)
		val mangaCategory = category("Manga", first)
		category("Novel", first, novel = true)
		val hidden = category("Hidden", first)
		repository.setCategoriesVisibleInSet(listOf(hidden), false, first.setId, normal)
		category("Private", privateSet)
		assertEquals(mapOf(first.setId to 3, second.setId to 0), repository.observeSetCategoryCounts(normal).first())
		assertEquals(mapOf(privateSet.setId to 1), repository.observeSetCategoryCounts(privateSpace).first())
		repository.moveCategoriesToSet(listOf(mangaCategory), second.setId, normal)
		assertEquals(mapOf(first.setId to 2, second.setId to 1), repository.observeSetCategoryCounts(normal).first())
		repository.removeCategoriesInSet(listOf(hidden), first.setId, normal)
		assertEquals(mapOf(first.setId to 1, second.setId to 1), repository.observeSetCategoryCounts(normal).first())
	}

	@Test fun defaultsUnlimitedSetsRenameReorderAndDuplicateValidation() = runBlocking {
		val first = main()
		assertEquals("Utama", first.title)
		assertNotEquals(first.syncKey, main(privateSpace).syncKey)
		val second = repository.createSet("Second", normal)
		val third = repository.createSet("Third", normal)
		assertEquals(3, repository.getSets(normal).size)
		assertTrue(runCatching { repository.createSet(" second ", normal) }.exceptionOrNull() is DuplicateCategoryTitleException)
		repository.renameSet(first.setId, "Renamed default", normal)
		assertEquals(first.syncKey, repository.getSets(normal).first().syncKey)
		repository.reorderSets(listOf(third.setId, second.setId, first.setId), normal)
		assertEquals(listOf(third.setId, second.setId, first.setId), repository.getSets(normal).map { it.setId })
		assertEquals(1, repository.getSets(privateSpace).size)
	}

	@Test fun moveKeepsEveryCategoryFieldIdentityAndMembership() = runBlocking {
		val first = main()
		val second = repository.createSet("Second", normal)
		val id = category("A", first, novel = true)
		members(id)
		val before = database.getFavouriteCategoriesDao().find(id.toInt())
		val identity = database.getFavouriteCategorySetsDao().getAssignment(id)!!.categorySyncKey
		val memberships = database.getFavouritesDao().findAllForSync()
		repository.moveCategoriesToSet(listOf(id), second.setId, normal)
		val after = database.getFavouriteCategoriesDao().find(id.toInt())
		assertEquals(before.copy(sortKey = after.sortKey), after)
		assertEquals(memberships, database.getFavouritesDao().findAllForSync())
		assertEquals(identity, database.getFavouriteCategorySetsDao().getAssignment(id)!!.categorySyncKey)
		assertEquals(second.setId, repository.getCategorySet(id, normal).setId)
		assertTrue(types.isCategoryForType(id, FavouriteContentType.NOVEL))
	}

	@Test fun scopedAndGlobalApisDistinctAllAndTypeFiltering() = runBlocking {
		val first = main()
		val second = repository.createSet("Second", normal)
		val a = category("Favorite", first)
		val b = category("Another", first)
		val c = category("Favorite", second, novel = true)
		members(a, b, c)
		assertEquals(setOf(a, b, c), repository.observeCategories(normal).first().map { it.id }.toSet())
		assertEquals(setOf(a, b), ids(first).toSet())
		assertEquals(listOf(c), ids(second))
		assertEquals(1, repository.getDistinctMangaCount(ids(first), normal))
		assertEquals(1, repository.observeInCategories(ids(first), ListSortOrder.NEWEST, emptySet(), 100, emptyList(), normal).first().size)
		assertEquals(listOf(c), repository.observeCategoriesInSet(second.setId, normal).first().filter { types.isCategoryForType(it.id, FavouriteContentType.NOVEL) }.map { it.id })
		assertTrue(runCatching { category(" favorite ", first) }.exceptionOrNull() is DuplicateCategoryTitleException)
		assertTrue(repository.observeCategories(privateSpace).first().isEmpty())
	}

	@Test fun categoryReorderDoesNotTouchAnotherSetOrHiddenContentType() = runBlocking {
		val first = main()
		val second = repository.createSet("Second", normal)
		val a = category("A", first)
		val hidden = category("Novel", first, true)
		val b = category("B", first)
		val other = category("C", second)
		val hiddenBefore = database.getFavouriteCategoriesDao().find(hidden.toInt())
		val otherBefore = database.getFavouriteCategoriesDao().find(other.toInt())
		repository.reorderCategories(listOf(b, a), first.setId, normal)
		assertEquals(listOf(b, hidden, a), ids(first))
		assertEquals(hiddenBefore, database.getFavouriteCategoriesDao().find(hidden.toInt()))
		assertEquals(otherBefore, database.getFavouriteCategoriesDao().find(other.toInt()))
	}

	@Test fun staleManagerSelectionCannotModifyCategoriesInHiddenSets() = runBlocking {
		val first = main()
		val second = repository.createSet("Second", normal)
		val a = category("A", first)
		val b = category("B", second, true)
		repository.setCategoriesVisibleInSet(listOf(a, b), false, first.setId, normal)
		assertTrue(database.getFavouriteCategoriesDao().find(b.toInt()).isVisibleInLibrary)
		val removed = repository.removeCategoriesInSet(listOf(b), first.setId, normal)
		assertTrue(removed.isEmpty())
		repository.moveCategoriesToSet(listOf(b), first.setId, normal, first.setId)
		assertEquals(second.setId, repository.getCategorySet(b, normal).setId)
	}

	@Test fun pendingDeletedCategoryDoesNotPoisonAtomicSaveInOtherSets() = runBlocking {
		val first = main()
		val second = repository.createSet("Second", normal)
		val a = category("A", first)
		val deleted = category("Deleted while picker is open", second)
		repository.deleteSet(second.setId, normal, destructive = true)
		repository.updateCategoryMemberships(listOf(manga), mapOf(a to true, deleted to true))
		assertEquals(setOf(a), repository.getCategoriesIds(manga.id))
	}

	@Test fun safeAndDestructiveDeletionAndLastSetProtection() = runBlocking {
		val first = main()
		val second = repository.createSet("Second", normal)
		val a = category("A", second)
		members(a)
		repository.deleteSet(second.setId, normal, first.setId)
		assertEquals(listOf(a), ids(first))
		assertEquals(setOf(a), repository.getCategoriesIds(manga.id))
		assertTrue(database.getFavouriteCategorySetsDao().findSet(second.setId)!!.deletedAt > 0)
		assertTrue(runCatching { repository.deleteSet(first.setId, normal, destructive = true) }.exceptionOrNull() is IllegalArgumentException)
		val third = repository.createSet("Third", normal)
		repository.moveCategoriesToSet(listOf(a), third.setId, normal)
		repository.deleteSet(third.setId, normal, destructive = true)
		assertTrue(repository.getCategoriesIds(manga.id).isEmpty())
		assertTrue(database.getFavouriteCategoriesDao().findAllForSync().first { it.categoryId.toLong() == a }.deletedAt > 0)
		assertNotNull(database.getFavouriteCategorySetsDao().getAssignment(a)) // durable identity tombstone
	}

	@Test fun lastActiveSetIsIndependentAndRepairsDeletedSelection() = runBlocking {
		val first = main()
		val second = repository.createSet("Second", normal)
		val secret = repository.createSet("Secret", privateSpace)
		selection.select(second, normal, FavouriteContentType.MANGA)
		selection.select(first, normal, FavouriteContentType.NOVEL)
		selection.select(secret, privateSpace, FavouriteContentType.MANGA)
		fun type(type: FavouriteContentType) = flowOf(type)
		assertEquals(second.setId, selection.observe(normal, type(FavouriteContentType.MANGA)).first().setId)
		assertEquals(first.setId, selection.observe(normal, type(FavouriteContentType.NOVEL)).first().setId)
		assertEquals(secret.setId, selection.observe(privateSpace, type(FavouriteContentType.MANGA)).first().setId)
		repository.deleteSet(second.setId, normal, first.setId)
		assertEquals(first.setId, selection.observe(normal, type(FavouriteContentType.MANGA)).first().setId)
		assertEquals(first.syncKey, InstrumentationRegistry.getInstrumentation().targetContext.getSharedPreferences("favourite_set_navigation", 0).getString("0:MANGA", null))
	}

	@Test fun crossSpaceAssignmentsAndMembershipsFailClosed() = runBlocking {
		val first = main()
		val secret = main(privateSpace)
		val id = category("Private", secret)
		assertTrue(runCatching { repository.moveCategoriesToSet(listOf(id), first.setId, normal) }.exceptionOrNull() is IllegalArgumentException)
		assertTrue(runCatching { members(id) }.isFailure)
		assertTrue(runCatching { database.getFavouriteCategorySetsDao().assign(database.getFavouriteCategorySetsDao().getAssignment(id)!!.copy(setId = first.setId)) }.isFailure)
		assertEquals(secret.setId, repository.getCategorySet(id, privateSpace).setId)
		assertTrue(repository.observeCategory(id, normal).first() == null)
	}

	private suspend fun zip(): ByteArray = ByteArrayOutputStream().also { buffer -> ZipOutputStream(buffer).use { backup.createBackup(it, null) } }.toByteArray()
	private fun entries(bytes: ByteArray): Map<String, String> = ZipInputStream(bytes.inputStream()).use { input ->
		buildMap { var entry = input.nextEntry; while (entry != null) { put(entry.name, input.readBytes().decodeToString()); entry = input.nextEntry } }
	}
	private suspend fun restore(bytes: ByteArray, privateEnabled: Boolean = false) {
		val result = ZipInputStream(bytes.inputStream()).use { backup.restoreBackup(it, setOf(BackupSection.CATEGORIES, BackupSection.FAVOURITES), null, privateEnabled) }
		assertTrue("Restore errors: ${result.failures}", result.isAllSuccess)
	}

	@Test fun nativeBackupRoundTripPreservesEmptySetsIdentityOrderAndNumericRemap() = runBlocking {
		val first = main()
		val second = repository.createSet("Second", normal)
		val empty = repository.createSet("Empty", normal)
		repository.renameSet(first.setId, "Renamed", normal)
		repository.reorderSets(listOf(empty.setId, second.setId, first.setId), normal)
		val a = category("Favorite", first)
		val b = category("Favorite", second, true)
		members(a, b)
		val assignments = database.getFavouriteCategorySetsDao().getAssignments(0).associateBy { it.categoryId }
		val data = zip()
		database.clearAllTables()
		val conflict = FavouriteCategoryEntity(a.toInt(), 5, 99, "Unrelated", "NEWEST", false, false, true, 0)
		database.getFavouriteCategoriesDao().insert(conflict)
		restore(data)
		val restored = repository.getSets(normal)
		assertEquals(listOf(empty.syncKey, second.syncKey, first.syncKey), restored.map { it.syncKey })
		assertTrue(ids(restored.first()).isEmpty())
		val newA = database.getFavouriteCategorySetsDao().findCategoryByKey(assignments[a]!!.categorySyncKey, 0)!!
		val newB = database.getFavouriteCategorySetsDao().findCategoryByKey(assignments[b]!!.categorySyncKey, 0)!!
		assertNotEquals(a, newA.categoryId.toLong())
		assertEquals(conflict, database.getFavouriteCategoriesDao().find(a.toInt()))
		assertEquals(setOf(newA.categoryId.toLong(), newB.categoryId.toLong()), repository.getCategoriesIds(manga.id))
		assertEquals(second.syncKey, repository.getCategorySet(newB.categoryId.toLong(), normal).syncKey)
		assertTrue(types.isCategoryForType(newB.categoryId.toLong(), FavouriteContentType.NOVEL))
	}

	@Test fun backupMetadataSnapshotDoesNotEmitOrphanMembershipsFromLaterCategoryCreates() = runBlocking {
		val first = main()
		val original = category("Original", first)
		members(original)
		val buffer = ByteArrayOutputStream()
		val output = object : ZipOutputStream(buffer) {
			var entryName = ""
			var inserted = false
			override fun putNextEntry(entry: ZipEntry) { entryName = entry.name; super.putNextEntry(entry) }
			override fun closeEntry() {
				super.closeEntry()
				if (entryName == "category_sets" && !inserted) {
					inserted = true
					runBlocking { val late = category("Created after metadata snapshot", first); members(late) }
				}
			}
		}
		output.use { backup.createBackup(it, null) }
		database.clearAllTables()
		restore(buffer.toByteArray())
		assertEquals(listOf("Original"), repository.observeCategories(normal).first().map { it.title })
		assertEquals(1, repository.getCategoriesIds(manga.id).size)
	}

	@Test fun legacyBackupWithoutSetMetadataRestoresIntoDefault() = runBlocking {
		val old = CategoryBackup(45, 123, 9, "Legacy", contentType = "NOVEL")
		val bytes = ByteArrayOutputStream().also { buffer -> ZipOutputStream(buffer).use { output ->
			output.putNextEntry(ZipEntry("categories")); output.write(Json.encodeToString(listOf(old)).toByteArray()); output.closeEntry()
		} }.toByteArray()
		val result = ZipInputStream(bytes.inputStream()).use { backup.restoreBackup(it, setOf(BackupSection.CATEGORIES), null) }
		assertTrue(result.failures.toString(), result.isAllSuccess)
		val category = repository.observeCategories(normal).first().single()
		assertEquals(main().syncKey, repository.getCategorySet(category.id, normal).syncKey)
		assertTrue(types.isCategoryForType(category.id, FavouriteContentType.NOVEL))
	}

	@Test fun legacyBackupDuplicateRowsRemainDistinctAndRepeatedRestoreKeepsIdentity() = runBlocking {
		val old = listOf(CategoryBackup(45, 123, 1, "Same"), CategoryBackup(46, 123, 2, "Same"))
		val bytes = ByteArrayOutputStream().also { buffer -> ZipOutputStream(buffer).use { output ->
			output.putNextEntry(ZipEntry("categories")); output.write(Json.encodeToString(old).toByteArray()); output.closeEntry()
		} }.toByteArray()
		repeat(2) {
			val result = ZipInputStream(bytes.inputStream()).use { backup.restoreBackup(it, setOf(BackupSection.CATEGORIES), null) }
			assertTrue(result.failures.toString(), result.isAllSuccess)
			assertEquals(2, repository.observeCategories(normal).first().size)
			assertNotNull(database.getFavouriteCategorySetsDao().findCategoryByKey(CategorySetIdentity.legacyCategory(0, "Same"), 0))
			assertNotNull(database.getFavouriteCategorySetsDao().findCategoryByKey(CategorySetIdentity.legacyCategory(0, "Same", 1), 0))
		}
	}

	@Test fun legacyRestoreDoesNotOverwriteUnrelatedMigratedCategoryWithSameTitle() = runBlocking {
		val id = category("Same", main())
		val original = database.getFavouriteCategoriesDao().find(id.toInt()).copy(createdAt = 5)
		database.getFavouriteCategoriesDao().upsert(original)
		val assignment = database.getFavouriteCategorySetsDao().getAssignment(id)!!
		database.getFavouriteCategorySetsDao().assign(assignment.copy(categorySyncKey = CategorySetIdentity.legacyCategory(0, "Same")))
		val old = listOf(CategoryBackup(45, 123, 1, "Same"))
		val bytes = ByteArrayOutputStream().also { buffer -> ZipOutputStream(buffer).use { output ->
			output.putNextEntry(ZipEntry("categories")); output.write(Json.encodeToString(old).toByteArray()); output.closeEntry()
		} }.toByteArray()
		repeat(2) {
			val result = ZipInputStream(bytes.inputStream()).use { backup.restoreBackup(it, setOf(BackupSection.CATEGORIES), null) }
			assertTrue(result.failures.toString(), result.isAllSuccess)
			assertEquals(2, repository.observeCategories(normal).first().size)
			assertEquals(original, database.getFavouriteCategoriesDao().find(id.toInt()))
		}
	}

	@Test fun privateBackupMetadataRequiresExportAndRestoreOptIn() = runBlocking {
		val secret = repository.createSet("SECRET_SET_NAME", privateSpace)
		val empty = repository.createSet("SECRET_EMPTY_SET", privateSpace)
		val id = category("SECRET_CATEGORY_NAME", secret)
		members(id, space = privateSpace)
		val routine = entries(zip())
		assertFalse(routine.containsKey("private_favourites"))
		assertTrue(routine.values.none { it.contains("SECRET_") })
		security.includePrivateInBackup = true
		val data = zip()
		assertTrue(entries(data).getValue("private_favourites").contains("SECRET_EMPTY_SET"))
		database.clearAllTables(); repository.getSets(normal); repository.getSets(privateSpace)
		restore(data, false)
		assertTrue(repository.getSets(privateSpace).none { it.syncKey == secret.syncKey })
		restore(data, true)
		assertTrue(repository.getSets(normal).none { it.syncKey == secret.syncKey })
		assertTrue(ids(repository.getSets(privateSpace).first { it.syncKey == empty.syncKey }).isEmpty())
		val restoredCategory = repository.observeCategories(privateSpace).first().single()
		assertEquals(secret.syncKey, repository.getCategorySet(restoredCategory.id, privateSpace).syncKey)
		assertEquals(setOf(restoredCategory.id), repository.getCategoriesIds(manga.id, privateSpace))
		assertTrue(repository.getCategoriesIds(manga.id, normal).isEmpty())
	}

	@Test fun preserveCategoryTransferKeepsSameNamedCategoriesInSeparateSetsAndSpaces() = runBlocking {
		val first = main()
		val second = repository.createSet("Second", normal)
		val a = category("Favorite", first)
		val b = category("Favorite", second)
		members(a, b)
		val copied = transfer.transfer(setOf(manga.id), PrivateTransferDestination.PreserveCategories)
		assertTrue(copied.isComplete)
		val privateIds = repository.getCategoriesIds(manga.id, privateSpace)
		assertEquals(2, privateIds.size)
		val privateSets = privateIds.map { repository.getCategorySet(it, privateSpace) }
		assertEquals(setOf("Utama", "Second"), privateSets.map { it.title }.toSet())
		assertEquals(2, privateSets.map { it.syncKey }.toSet().size)
		assertTrue(privateSets.none { target -> repository.getSets(normal).any { it.syncKey == target.syncKey } })
		assertEquals(setOf(a, b), repository.getCategoriesIds(manga.id, normal))
		assertEquals(0, transfer.transfer(setOf(manga.id), PrivateTransferDestination.PreserveCategories).createdCategoryCount)
		assertEquals(privateIds, repository.getCategoriesIds(manga.id, privateSpace))
		assertTrue(transfer.transferToNormal(setOf(manga.id), NormalTransferDestination.PreserveCategories).isComplete)
		assertEquals(setOf(a, b), repository.getCategoriesIds(manga.id, normal))
	}

	@Test fun deletionSyncDisabledDoesNotApplyNewMembershipsToLocallyDeletedCategory() = runBlocking {
		val id = category("Locally deleted", main())
		val live = sync.localCategories().single()
		repository.removeCategories(listOf(id))
		syncSettings.isDeletionSyncDisabled = true
		val remote = SyncSnapshot(categorySets = sync.localCategorySets(), categories = listOf(live), favourites = listOf(
			SyncFavourite(manga.id, id, 0, false, 100, 0, org.koitharu.kotatsu.backup.local.data.model.MangaBackup(
				id = manga.id, title = manga.title, url = manga.url, publicUrl = manga.publicUrl, coverUrl = manga.coverUrl.orEmpty(), source = manga.source.name)),
		))
		sync.applyToDatabase(remote, false, setOf(SyncContent.FAVOURITES))
		assertTrue(database.getFavouriteCategoriesDao().findAllForSync().single().deletedAt > 0)
		assertTrue(database.getFavouritesDao().findAllForSync().isEmpty())
	}

	@Test fun normalSyncExportExcludesPrivateAndApplyPreservesStableIdentity() = runBlocking {
		val first = main()
		val second = repository.createSet("Second", normal)
		val id = category("Normal", first)
		val privateId = category("SECRET_CATEGORY", main(privateSpace))
		repository.createSet("SECRET_SET", privateSpace)
		val exported = sync.localCategories().single()
		assertEquals(id.toInt(), exported.categoryId)
		assertTrue(sync.localCategorySets().none { it.title.startsWith("SECRET") || it.space != 0 })
		val changed = exported.copy(categoryId = privateId.toInt(), title = "Renamed", setSyncKey = second.syncKey, updatedAt = exported.updatedAt + 100)
		val remote = SyncSnapshot(categorySets = sync.localCategorySets().map { if (it.syncKey == second.syncKey) it.copy(title = "Cloud Set", updatedAt = it.updatedAt + 100) else it }, categories = listOf(changed))
		sync.applyToDatabase(remote, false, setOf(SyncContent.FAVOURITES))
		assertEquals("Renamed", database.getFavouriteCategoriesDao().find(id.toInt()).title)
		assertEquals(second.syncKey, repository.getCategorySet(id, normal).syncKey)
		assertEquals("SECRET_CATEGORY", database.getFavouriteCategoriesDao().find(privateId.toInt()).title)
		assertEquals(exported.syncKey, sync.localCategories().single().syncKey)
	}
}
