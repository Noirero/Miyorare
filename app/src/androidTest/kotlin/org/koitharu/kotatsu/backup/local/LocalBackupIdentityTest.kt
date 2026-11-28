package org.koitharu.kotatsu.backup.local

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.preference.PreferenceManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.protobuf.ProtoBuf
import org.koitharu.kotatsu.backup.MihonBackupManager
import org.koitharu.kotatsu.backup.MihonRestoreTarget
import org.koitharu.kotatsu.backup.model.MihonBackup
import org.koitharu.kotatsu.backup.model.MihonBackupPreference
import org.koitharu.kotatsu.backup.model.MihonBackupManga
import org.koitharu.kotatsu.backup.model.MihonBackupCategory
import org.koitharu.kotatsu.backup.model.MihonBackupSourcePreferences
import org.koitharu.kotatsu.backup.model.MihonBooleanPreferenceValue
import org.koitharu.kotatsu.backup.model.MihonIntPreferenceValue
import org.koitharu.kotatsu.backup.model.MihonStringPreferenceValue
import org.koitharu.kotatsu.core.prefs.RestoredPreferencePolicy
import org.koitharu.kotatsu.core.prefs.SourceSettings
import org.koitharu.kotatsu.core.util.ext.putAll
import org.koitharu.kotatsu.mihon.model.mihonMangaId
import java.util.zip.GZIPOutputStream
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Provider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koitharu.kotatsu.backup.local.data.LocalBackupRepository
import org.koitharu.kotatsu.backup.local.data.MiyorareAutoBackupAgent
import org.koitharu.kotatsu.backup.local.domain.BackupSection
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.bookmarks.data.BookmarkEntity
import org.koitharu.kotatsu.core.db.entity.ChapterEntity
import org.koitharu.kotatsu.core.db.entity.MangaEntity
import org.koitharu.kotatsu.core.db.entity.MangaPrefsEntity
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.favourites.data.FavouriteCategoryEntity
import org.koitharu.kotatsu.favourites.data.FavouriteEntity
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
import org.koitharu.kotatsu.favourites.data.PrivateFavouriteEntity
import org.koitharu.kotatsu.favourites.vault.PrivateFavouritesSecurityStore
import org.koitharu.kotatsu.history.data.HistoryEntity
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.inject.Inject

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class LocalBackupIdentityTest {

	@get:Rule
	var hiltRule = HiltAndroidRule(this)

	@Inject
	lateinit var repository: LocalBackupRepository

	@Inject
	lateinit var mihonBackupManagerProvider: Provider<MihonBackupManager>

	@Inject
	lateinit var database: MangaDatabase

	@Inject
	lateinit var privateSecurity: PrivateFavouritesSecurityStore

	@Before
	fun setUp() {
		hiltRule.inject()
		database.clearAllTables()
		privateSecurity.includePrivateInBackup = false
	}

	@Test
	fun rawIntegerCollisionAndTargetedRepairUseRealSharedPreferences() {
		val instrumentation = InstrumentationRegistry.getInstrumentation()
		val prefs = instrumentation.targetContext.getSharedPreferences("restore_type_collision_fixture", Context.MODE_PRIVATE)
		val key = AppSettings.KEY_AUTO_UPDATE_EXTENSIONS
		val captured = AtomicReference<Throwable?>()
		val listener = SharedPreferences.OnSharedPreferenceChangeListener { store, changed ->
			if (changed == key) captured.set(runCatching { store.getBoolean(key, false) }.exceptionOrNull())
		}
		try {
			prefs.edit().remove(key).putString("unrelated", "keep").commit()
			prefs.registerOnSharedPreferenceChangeListener(listener)
			// Negative control: the old importer stored the incoming Integer unchanged.
			instrumentation.runOnMainSync { prefs.edit().putInt(key, 2).commit() }
			assertTrue(captured.get() is ClassCastException)
			prefs.unregisterOnSharedPreferenceChangeListener(listener)
			for (invalid in listOf(0, 1, 2, 1L, 1f, "true", setOf("true"))) {
				assertTrue(prefs.edit().apply { putAll(mapOf(key to invalid)) }.commit())
				assertTrue(RestoredPreferencePolicy.repairAutoUpdateExtensions(prefs))
				assertEquals(false, prefs.all[key])
				assertTrue(RestoredPreferencePolicy.repairAutoUpdateExtensions(prefs))
				assertEquals("keep", prefs.getString("unrelated", null))
			}
			for (value in listOf(true, false)) {
				prefs.edit().putBoolean(key, value).commit()
				assertTrue(RestoredPreferencePolicy.repairAutoUpdateExtensions(prefs))
				assertEquals(value, prefs.all[key])
			}
			prefs.edit().remove(key).commit()
			assertTrue(RestoredPreferencePolicy.repairAutoUpdateExtensions(prefs))
			assertFalse(prefs.contains(key))
		} finally {
			prefs.unregisterOnSharedPreferenceChangeListener(listener)
			prefs.edit().remove(key).remove("unrelated").commit()
		}
	}

	@Test
	fun externalWireRestoreRejectsCollisionAndPreservesNormalPrivateAndDynamicSettings() = runTest {
		val instrumentation = InstrumentationRegistry.getInstrumentation()
		val context = instrumentation.targetContext
		val backupManager = mihonBackupManagerProvider.get()
		val prefs = PreferenceManager.getDefaultSharedPreferences(context)
		val key = AppSettings.KEY_AUTO_UPDATE_EXTENSIONS
		val full = AppSettings.KEY_READER_FULLSCREEN
		val screen = AppSettings.KEY_READER_SCREEN_ON
		val volume = AppSettings.KEY_READER_VOLUME_BUTTONS
		val protectedKey = AppSettings.KEY_ONBOARDING_INSTALL_ID
		val foreignKeys = setOf("fullscreen", "pref_keep_screen_on_key", "reader_volume_keys", "automatic_ext_updates", "fixture_unknown")
		val keys = foreignKeys + setOf(key, full, screen, volume, protectedKey)
		val previous = prefs.all.filterKeys { it in keys }
		val sourceId = 93286123L
		val sourcePrefs = context.getSharedPreferences(SourceSettings.getStorageName("MIHON_$sourceId"), Context.MODE_PRIVATE)
		val dynamicKey = "fixture_extension_setting"
		val previousSource = sourcePrefs.all.filterKeys { it == dynamicKey }
		val readError = AtomicReference<Throwable?>()
		val callbackCount = AtomicInteger()
		val listener = SharedPreferences.OnSharedPreferenceChangeListener { store, changed ->
			if (changed == key || changed == full) {
				callbackCount.incrementAndGet()
				runCatching { store.getBoolean(key, false) }.exceptionOrNull()?.let { readError.set(it) }
			}
		}
		val file = File.createTempFile("restore_preference_collision", ".tachibk", context.cacheDir)
		try {
			val fixture = MihonBackup(
				backupManga = listOf(MihonBackupManga(source = sourceId, url = "https://fixture.example/manga/1", title = "Preference fixture", favorite = true, categories = listOf(1))),
				backupCategories = listOf(MihonBackupCategory(name = "Preference fixture", id = 1, order = 0)),
				backupPreferences = listOf(
					MihonBackupPreference(key, MihonIntPreferenceValue(2)),
					MihonBackupPreference("fullscreen", MihonBooleanPreferenceValue(false)),
					MihonBackupPreference("pref_keep_screen_on_key", MihonBooleanPreferenceValue(false)),
					MihonBackupPreference("reader_volume_keys", MihonBooleanPreferenceValue(true)),
					MihonBackupPreference(protectedKey, MihonStringPreferenceValue("foreign-install-id")),
					MihonBackupPreference("automatic_ext_updates", MihonBooleanPreferenceValue(true)),
					MihonBackupPreference("fixture_unknown", MihonStringPreferenceValue("foreign")),
				),
				backupSourcePreferences = listOf(MihonBackupSourcePreferences("source_$sourceId", listOf(MihonBackupPreference(dynamicKey, MihonStringPreferenceValue("portable"))))),
			)
			GZIPOutputStream(file.outputStream()).use { it.write(ProtoBuf.encodeToByteArray(MihonBackup.serializer(), fixture)) }
			val uri = Uri.fromFile(file)
			val mangaId = mihonMangaId("MIHON_$sourceId", "https://fixture.example/manga/1")
			for (target in listOf(MihonRestoreTarget.NORMAL, MihonRestoreTarget.PRIVATE)) {
				database.clearAllTables()
				prefs.edit().apply {
					foreignKeys.forEach { remove(it) }
					putBoolean(key, true).putBoolean(full, true).putBoolean(screen, true).putBoolean(volume, false)
					putString(protectedKey, "local-install-id")
				}.commit()
				instrumentation.waitForIdleSync()
				callbackCount.set(0)
				readError.set(null)
				prefs.registerOnSharedPreferenceChangeListener(listener)
				try {
					val report = backupManager.restoreBackup(uri, MihonBackupManager.Options(extensionRepoSettings = false), target)
					instrumentation.waitForIdleSync()
					assertNull(readError.get())
					assertTrue(callbackCount.get() > 0)
					assertEquals(true, prefs.all[key])
					assertEquals(false, prefs.all[full])
					assertEquals(false, prefs.all[screen])
					assertEquals(true, prefs.all[volume])
					assertEquals("local-install-id", prefs.getString(protectedKey, null))
					assertTrue(foreignKeys.none { prefs.contains(it) })
					assertEquals("portable", sourcePrefs.getString(dynamicKey, null))
					assertEquals(3, report.restoredSettingsCount)
					assertEquals(4, report.skippedSettingsCount)
					assertEquals(1, report.restoredMangaCount)
					if (target == MihonRestoreTarget.NORMAL) {
						assertTrue(database.getFavouritesDao().findCategoriesIds(mangaId).isNotEmpty())
						assertTrue(database.getPrivateFavouritesDao().findCategoriesIds(mangaId).isEmpty())
					} else {
						assertTrue(database.getPrivateFavouritesDao().findCategoriesIds(mangaId).isNotEmpty())
						assertTrue(database.getFavouritesDao().findCategoriesIds(mangaId).isEmpty())
					}
				} finally {
					prefs.unregisterOnSharedPreferenceChangeListener(listener)
				}
			}
			prefs.edit().putBoolean(full, true).commit()
			val report = backupManager.restoreBackup(uri, MihonBackupManager.Options(libraryEntries = false, appSettings = false, sourceSettings = false, extensionRepoSettings = false))
			assertEquals(true, prefs.all[full])
			assertEquals(0, report.restoredSettingsCount)
			assertEquals(0, report.skippedSettingsCount)
		} finally {
			prefs.unregisterOnSharedPreferenceChangeListener(listener)
			prefs.edit().apply { keys.forEach { remove(it) }; putAll(previous) }.commit()
			sourcePrefs.edit().apply { remove(dynamicKey); putAll(previousSource) }.commit()
			file.delete()
		}
	}

	@Test
	fun nativeZipAndSharedSyncBoundaryCannotReintroduceIntegerCollision() = runTest {
		val context = InstrumentationRegistry.getInstrumentation().targetContext
		val prefs = PreferenceManager.getDefaultSharedPreferences(context)
		val settings = AppSettings(context)
		val key = AppSettings.KEY_AUTO_UPDATE_EXTENSIONS
		val other = AppSettings.KEY_READER_FULLSCREEN
		val previous = prefs.all.filterKeys { it == key || it == other }
		try {
			prefs.edit().putBoolean(key, true).putBoolean(other, true).commit()
			val backup = ByteArrayOutputStream()
			ZipOutputStream(backup).use { output ->
				output.putNextEntry(ZipEntry(BackupSection.SETTINGS.entryName))
				output.write("""{"$key":{"_t":"int","v":2},"$other":{"_t":"bool","v":false}}""".toByteArray())
				output.closeEntry()
			}
			ZipInputStream(backup.toByteArray().inputStream()).use { input ->
				assertTrue(repository.restoreBackup(input, setOf(BackupSection.SETTINGS), null).isAllSuccess)
			}
			assertTrue(settings.isAutoUpdateExtensionsEnabled)
			assertFalse(settings.isReaderFullscreenEnabled)
			settings.upsertAll(mapOf(key to 2, other to true))
			assertTrue(settings.isAutoUpdateExtensionsEnabled)
			assertTrue(settings.isReaderFullscreenEnabled)
			settings.upsertAll(mapOf(key to false))
			assertFalse(settings.isAutoUpdateExtensionsEnabled)
		} finally {
			prefs.edit().apply { remove(key); remove(other); putAll(previous) }.commit()
		}
	}

	@Test
	fun discordTokenMigratesLocallyAndCannotBeExportedOrRestored() = runTest {
		val context = InstrumentationRegistry.getInstrumentation().targetContext
		val prefs = PreferenceManager.getDefaultSharedPreferences(context)
		val key = AppSettings.KEY_DISCORD_TOKEN
		val localToken = "fixture-device-only-token"
		val staleToken = "fixture-legacy-backup-token"
		val previous = AppSettings(context).discordToken
		val previousSkipNsfw = prefs.all[AppSettings.KEY_DISCORD_RPC_SKIP_NSFW] as? Boolean
		val settings = AppSettings(context)
		try {
			settings.discordToken = null
			prefs.edit().putString(key, localToken).commit()
			val upgraded = AppSettings(context)
			assertTrue(upgraded.discordToken == localToken)
			assertFalse(prefs.contains(key))

			// Even an old copy still in general settings must be filtered by the exporter.
			prefs.edit().putString(key, staleToken).commit()
			val backup = ByteArrayOutputStream()
			ZipOutputStream(backup).use { repository.createBackup(it, null) }
			ZipInputStream(backup.toByteArray().inputStream()).use { input ->
				var entry = input.nextEntry
				while (entry != null && entry.name != BackupSection.SETTINGS.entryName) {
					entry = input.nextEntry
				}
				assertTrue(entry != null)
				val exportedSettings = input.readBytes().toString(Charsets.UTF_8)
				assertFalse(exportedSettings.contains(key))
				assertFalse(exportedSettings.contains(localToken))
				assertFalse(exportedSettings.contains(staleToken))
			}

			prefs.edit().remove(key).putBoolean(AppSettings.KEY_DISCORD_RPC_SKIP_NSFW, false).commit()
			val oldBackup = ByteArrayOutputStream()
			ZipOutputStream(oldBackup).use { output ->
				output.putNextEntry(ZipEntry(BackupSection.SETTINGS.entryName))
				output.write(
					"""{"$key":{"_t":"string","v":"$staleToken"},"${AppSettings.KEY_DISCORD_RPC_SKIP_NSFW}":{"_t":"bool","v":true}}"""
						.toByteArray(),
				)
				output.closeEntry()
			}
			ZipInputStream(oldBackup.toByteArray().inputStream()).use { input ->
				assertTrue(repository.restoreBackup(input, setOf(BackupSection.SETTINGS), null).isAllSuccess)
			}
			assertTrue(upgraded.isDiscordRpcSkipNsfw)
			assertTrue(upgraded.discordToken == localToken)
			assertFalse(prefs.contains(key))
		} finally {
			settings.discordToken = previous
			val editor = prefs.edit()
			if (previousSkipNsfw == null) editor.remove(AppSettings.KEY_DISCORD_RPC_SKIP_NSFW)
			else editor.putBoolean(AppSettings.KEY_DISCORD_RPC_SKIP_NSFW, previousSkipNsfw)
			editor.apply()
		}
	}

	@Test
	fun androidRestoreDiscardsLegacyDiscordTokenBeforeSettingsMigration() {
		val context = InstrumentationRegistry.getInstrumentation().targetContext
		val prefs = PreferenceManager.getDefaultSharedPreferences(context)
		val key = AppSettings.KEY_DISCORD_TOKEN
		val otherKey = AppSettings.KEY_DISCORD_RPC_SKIP_NSFW
		val settings = AppSettings(context)
		val previousToken = settings.discordToken
		val previousOtherValue = prefs.all[otherKey] as? Boolean
		val agent = object : MiyorareAutoBackupAgent() {
			init {
				attachBaseContext(context)
			}
		}
		assertEquals(MiyorareAutoBackupAgent::class.java.name, context.applicationInfo.backupAgentName)

		try {
			settings.discordToken = null
			assertTrue(prefs.edit().putString(key, "fixture-restored-token").putBoolean(otherKey, true).commit())

			agent.onRestoreFinished()

			assertFalse(prefs.contains(key))
			assertNull(AppSettings(context).discordToken)
			assertTrue(prefs.getBoolean(otherKey, false))

			settings.discordToken = "fixture-device-only-token"
			assertTrue(prefs.edit().putString(key, "fixture-restored-token").commit())
			agent.onRestoreFinished()
			assertFalse(prefs.contains(key))
			assertEquals("fixture-device-only-token", AppSettings(context).discordToken)
		} finally {
			settings.discordToken = previousToken
			val editor = prefs.edit()
			if (previousOtherValue == null) editor.remove(otherKey)
			else editor.putBoolean(otherKey, previousOtherValue)
			editor.apply()
		}
	}

	@Test
	fun nativeBackupRestoreKeepsExactCategoryMangaMembership() = runTest {
		val categoriesDao = database.getFavouriteCategoriesDao()
		val categoryA = categoriesDao.insert(category(title = "Category A", sortKey = 1))
		val categoryB = categoriesDao.insert(category(title = "Category B", sortKey = 2))

		val manga12345 = manga(id = 12_345L, title = "Manga 12345", url = "/manga/12345")
		val manga67890 = manga(id = 67_890L, title = "Manga 67890", url = "/manga/67890")
		database.getMangaDao().upsert(manga12345, emptyList())
		database.getMangaDao().upsert(manga67890, emptyList())

		database.getFavouritesDao().upsert(
			FavouriteEntity(
				mangaId = manga12345.id,
				categoryId = categoryA,
				sortKey = 10,
				isPinned = false,
				createdAt = 1_000L,
				deletedAt = 0L,
			),
		)
		database.getFavouritesDao().upsert(
			FavouriteEntity(
				mangaId = manga67890.id,
				categoryId = categoryB,
				sortKey = 20,
				isPinned = true,
				createdAt = 2_000L,
				deletedAt = 0L,
			),
		)

		val context = InstrumentationRegistry.getInstrumentation().targetContext
		val file = File.createTempFile("local_backup_identity_", ".zip", context.cacheDir)
		try {
			ZipOutputStream(file.outputStream()).use { output ->
				repository.createBackup(output, progress = null)
			}

			database.clearAllTables()

			// Simulate a real restore target where the source backup's category id is already occupied
			// by an unrelated category/manga. Restore must remap the category id, never the manga id.
			val conflictingTargetCategory = category(title = "Existing target category", sortKey = 99)
				.copy(categoryId = categoryA.toInt())
			database.getFavouriteCategoriesDao().insert(conflictingTargetCategory)
			val unrelatedManga = manga(id = 99_999L, title = "Manga ABCDE", url = "/manga/abcde")
			database.getMangaDao().upsert(unrelatedManga, emptyList())
			database.getFavouritesDao().upsert(
				FavouriteEntity(
					mangaId = unrelatedManga.id,
					categoryId = categoryA,
					sortKey = 999,
					isPinned = false,
					createdAt = 999L,
					deletedAt = 0L,
				),
			)

			val result = ZipInputStream(file.inputStream()).use { input ->
				repository.restoreBackup(
					input = input,
					sections = setOf(BackupSection.CATEGORIES, BackupSection.FAVOURITES),
					progress = null,
				)
			}
			assertTrue("Native backup restore reported failures: ${result.failures}", result.isAllSuccess)

			val restoredCategories = database.getFavouriteCategoriesDao().findAll().associateBy { it.title }
			val restoredA = checkNotNull(restoredCategories["Category A"])
			val restoredB = checkNotNull(restoredCategories["Category B"])
			val unrelatedCategory = checkNotNull(restoredCategories["Existing target category"])

			val categoryAMangaIds = database.getFavouritesDao()
				.findAll(restoredA.categoryId.toLong())
				.mapTo(linkedSetOf()) { it.manga.id }
			val categoryBMangaIds = database.getFavouritesDao()
				.findAll(restoredB.categoryId.toLong())
				.mapTo(linkedSetOf()) { it.manga.id }
			val unrelatedMangaIds = database.getFavouritesDao()
				.findAll(unrelatedCategory.categoryId.toLong())
				.mapTo(linkedSetOf()) { it.manga.id }

			assertEquals(linkedSetOf(12_345L), categoryAMangaIds)
			assertEquals(linkedSetOf(67_890L), categoryBMangaIds)
			assertEquals(linkedSetOf(99_999L), unrelatedMangaIds)
		} finally {
			file.delete()
		}
	}


	@Test
	fun nativeBackupRestoreKeepsReaderOnlyProfileWithoutMetadataOverrides() = runTest {
		val manga = manga(id = 42_424L, title = "Reader Profile Only", url = "/manga/reader-profile")
		database.getMangaDao().upsert(manga, emptyList())
		val expected = MangaPrefsEntity(
			mangaId = manga.id,
			mode = 4,
			cfBrightness = 0.25f,
			cfContrast = -0.15f,
			cfInvert = true,
			cfGrayscale = true,
			cfBookEffect = true,
			titleOverride = null,
			coverUrlOverride = null,
			contentRatingOverride = null,
			authorOverride = null,
			artistOverride = null,
			descriptionOverride = null,
			mergeScanlators = false,
		)
		database.getPreferencesDao().upsert(expected)

		val context = InstrumentationRegistry.getInstrumentation().targetContext
		val file = File.createTempFile("local_backup_reader_profile_", ".zip", context.cacheDir)
		try {
			ZipOutputStream(file.outputStream()).use { output ->
				repository.createBackup(output, progress = null)
			}
			database.clearAllTables()

			val result = ZipInputStream(file.inputStream()).use { input ->
				repository.restoreBackup(
					input = input,
					sections = setOf(BackupSection.MANGA_PREFS),
					progress = null,
				)
			}
			assertTrue("Reader profile restore reported failures: ${result.failures}", result.isAllSuccess)

			val restored = checkNotNull(database.getPreferencesDao().find(manga.id))
			assertEquals(expected.mode, restored.mode)
			assertEquals(expected.cfBrightness, restored.cfBrightness)
			assertEquals(expected.cfContrast, restored.cfContrast)
			assertEquals(expected.cfInvert, restored.cfInvert)
			assertEquals(expected.cfGrayscale, restored.cfGrayscale)
			assertEquals(expected.cfBookEffect, restored.cfBookEffect)
			assertEquals(null, restored.titleOverride)
			assertEquals(null, restored.coverUrlOverride)
			assertFalse(restored.mergeScanlators)
		} finally {
			file.delete()
		}
	}


	@Test
	fun nativeBackupRoundTripPreservesLargeLibraryHistoryChaptersBookmarksAndPrivatePolicy() = runTest {
		val categoriesDao = database.getFavouriteCategoriesDao()
		val normalCategoryId = categoriesDao.insert(category("Acceptance Normal", 1))
		val privateCategoryId = categoriesDao.insert(
			category("Acceptance Private", 2, FavouriteSpace.PRIVATE),
		)

		val primary = manga(id = 700_001L, title = "Acceptance Primary", url = "/manga/acceptance-primary")
		database.getMangaDao().upsert(primary, emptyList())
		val chapter = ChapterEntity(
			chapterId = 710_001L,
			mangaId = primary.id,
			title = "Chapter 1",
			number = 1f,
			volume = 1,
			url = "/chapter/1",
			scanlator = "Acceptance",
			uploadDate = 123_456L,
			branch = "main",
			source = primary.source,
			index = 0,
		)
		database.getChaptersDao().replaceAll(primary.id, listOf(chapter))
		database.getFavouritesDao().upsert(
			FavouriteEntity(
				mangaId = primary.id,
				categoryId = normalCategoryId,
				sortKey = 1,
				isPinned = true,
				createdAt = 10L,
				deletedAt = 0L,
			),
		)
		database.getHistoryDao().upsert(
			HistoryEntity(
				mangaId = primary.id,
				createdAt = 100L,
				updatedAt = 200L,
				chapterId = chapter.chapterId,
				page = 7,
				scroll = 0.5f,
				percent = 0.25f,
				deletedAt = 0L,
				chaptersCount = 1,
			),
		)
		database.getBookmarksDao().upsert(
			listOf(
				BookmarkEntity(
					mangaId = primary.id,
					pageId = 720_001L,
					chapterId = chapter.chapterId,
					page = 7,
					scroll = 33,
					imageUrl = "https://fixture.example/page/7",
					createdAt = 300L,
					percent = 0.25f,
					note = "keep-me",
				),
			),
		)

		// Cross the 256-row keyset window more than twice so this is a real multi-batch backup,
		// not merely a source-code assertion about pagination.
		repeat(600) { index ->
			val item = manga(
				id = 701_000L + index,
				title = "Scale $index",
				url = "/manga/scale-$index",
			)
			database.getMangaDao().upsert(item, emptyList())
			database.getFavouritesDao().insert(
				FavouriteEntity(
					mangaId = item.id,
					categoryId = normalCategoryId,
					sortKey = index + 10,
					isPinned = false,
					createdAt = 1_000L + index,
					deletedAt = 0L,
				),
			)
		}

		val privateManga = manga(
			id = 799_001L,
			title = "Acceptance Private Manga",
			url = "/manga/acceptance-private",
		)
		database.getMangaDao().upsert(privateManga, emptyList())
		database.getPrivateFavouritesDao().upsert(
			PrivateFavouriteEntity(
				mangaId = privateManga.id,
				categoryId = privateCategoryId,
				sortKey = 5,
				isPinned = false,
				createdAt = 500L,
				deletedAt = 0L,
			),
		)

		val context = InstrumentationRegistry.getInstrumentation().targetContext
		val offFile = File.createTempFile("local_backup_private_off_", ".zip", context.cacheDir)
		val backupFile = File.createTempFile("local_backup_acceptance_", ".zip", context.cacheDir)
		try {
			privateSecurity.includePrivateInBackup = false
			ZipOutputStream(offFile.outputStream()).use { repository.createBackup(it, progress = null) }
			val offEntries = ZipInputStream(offFile.inputStream()).use { input ->
				buildSet {
					var entry = input.nextEntry
					while (entry != null) {
						add(entry.name)
						input.closeEntry()
						entry = input.nextEntry
					}
				}
			}
			assertFalse(
				"Private payload must not leak into a routine backup when opt-in is disabled",
				LocalBackupRepository.PRIVATE_FAVOURITES_ENTRY in offEntries,
			)

			privateSecurity.includePrivateInBackup = true
			ZipOutputStream(backupFile.outputStream()).use { repository.createBackup(it, progress = null) }
			val onEntries = ZipInputStream(backupFile.inputStream()).use { input ->
				buildSet {
					var entry = input.nextEntry
					while (entry != null) {
						add(entry.name)
						input.closeEntry()
						entry = input.nextEntry
					}
				}
			}
			assertTrue(LocalBackupRepository.PRIVATE_FAVOURITES_ENTRY in onEntries)

			database.clearAllTables()
			val sections = setOf(
				BackupSection.CATEGORIES,
				BackupSection.FAVOURITES,
				BackupSection.HISTORY,
				BackupSection.CHAPTERS,
				BackupSection.BOOKMARKS,
			)
			val restored = ZipInputStream(backupFile.inputStream()).use { input ->
				repository.restoreBackup(
					input = input,
					sections = sections,
					progress = null,
					restorePrivateFavourites = true,
				)
			}
			assertTrue("Full native restore reported failures: ${restored.failures}", restored.isAllSuccess)

			val restoredNormal = checkNotNull(
				database.getFavouriteCategoriesDao().findAll().singleOrNull { it.title == "Acceptance Normal" },
			)
			assertEquals(
				601,
				database.getFavouritesDao().findAll(restoredNormal.categoryId.toLong()).size,
			)
			val restoredHistory = checkNotNull(database.getHistoryDao().find(primary.id))
			assertEquals(chapter.chapterId, restoredHistory.chapterId)
			assertEquals(7, restoredHistory.page)
			assertEquals(0.25f, restoredHistory.percent)
			val restoredChapters = database.getChaptersDao().findAll(primary.id)
			assertEquals(1, restoredChapters.size)
			assertEquals(chapter.chapterId, restoredChapters.single().chapterId)
			val restoredBookmarks = database.getBookmarksDao().findAll(primary.id)
			assertEquals(1, restoredBookmarks.size)
			assertEquals("keep-me", restoredBookmarks.single().note)

			val restoredPrivateCategory = checkNotNull(
				database.getFavouriteCategoriesDao()
					.findAllInSpace(FavouriteSpace.PRIVATE.dbValue)
					.singleOrNull { it.title == "Acceptance Private" },
			)
			assertEquals(
				listOf(restoredPrivateCategory.categoryId.toLong()),
				database.getPrivateFavouritesDao().findCategoriesIds(privateManga.id),
			)
			assertTrue(database.getPrivateFavouritesDao().isPrivateOnly(privateManga.id))

			// Retry the same restore to prove idempotence: rows must not multiply.
			val retry = ZipInputStream(backupFile.inputStream()).use { input ->
				repository.restoreBackup(
					input = input,
					sections = sections,
					progress = null,
					restorePrivateFavourites = true,
				)
			}
			assertTrue("Retry restore reported failures: ${retry.failures}", retry.isAllSuccess)
			assertEquals(
				1,
				database.getFavouriteCategoriesDao().findAll().count { it.title == "Acceptance Normal" },
			)
			assertEquals(
				1,
				database.getFavouriteCategoriesDao()
					.findAllInSpace(FavouriteSpace.PRIVATE.dbValue)
					.count { it.title == "Acceptance Private" },
			)
			assertEquals(601, database.getFavouritesDao().findAll(restoredNormal.categoryId.toLong()).size)
			assertEquals(1, database.getChaptersDao().findAll(primary.id).size)
			assertEquals(1, database.getBookmarksDao().findAll(primary.id).size)
			assertEquals(
				listOf(restoredPrivateCategory.categoryId.toLong()),
				database.getPrivateFavouritesDao().findCategoriesIds(privateManga.id),
			)
		} finally {
			privateSecurity.includePrivateInBackup = false
			offFile.delete()
			backupFile.delete()
		}
	}

	@Test
	fun malformedNativeBackupNeverReportsSuccess() = runTest {
		val context = InstrumentationRegistry.getInstrumentation().targetContext
		val file = File.createTempFile("local_backup_malformed_", ".zip", context.cacheDir)
		try {
			ZipOutputStream(file.outputStream()).use { output ->
				output.putNextEntry(java.util.zip.ZipEntry(BackupSection.HISTORY.entryName))
				output.write("[{\"broken\":".toByteArray())
				output.closeEntry()
			}
			val outcome = runCatching {
				ZipInputStream(file.inputStream()).use { input ->
					repository.restoreBackup(
						input = input,
						sections = setOf(BackupSection.HISTORY),
						progress = null,
					)
				}
			}
			assertTrue(
				"Malformed backup must fail or return a CompositeResult with failures",
				outcome.isFailure || outcome.getOrThrow().isAllSuccess.not(),
			)
		} finally {
			file.delete()
		}
	}

	@Test
	fun chapterPersonalNativeBackupRestoresWithoutChapterCacheAndOldBackupsDoNotClearIt() = runTest {
		val owner = manga(1001L, "Annotated chapter", "/manga/1001")
		database.getMangaDao().upsert(owner)
		val annotation = org.koitharu.kotatsu.details.data.ChapterPersonalEntity(owner.id, owner.source, "/chapter/a", 5, "Peak chapter")
		database.getChapterPersonalDao().upsert(annotation)
		val context = InstrumentationRegistry.getInstrumentation().targetContext
		val file = File.createTempFile("chapter_personal_", ".zip", context.cacheDir)
		try {
			ZipOutputStream(file.outputStream()).use { repository.createBackup(it, null) }
			database.clearAllTables()
			val result = ZipInputStream(file.inputStream()).use { repository.restoreBackup(it, setOf(BackupSection.CHAPTERS), null) }
			assertTrue(result.failures.toString(), result.isAllSuccess)
			assertEquals(annotation, database.getChapterPersonalDao().findAll(listOf(owner.id)).single())
			val old = java.io.ByteArrayOutputStream()
			ZipOutputStream(old).use {
				it.putNextEntry(java.util.zip.ZipEntry("chapters"))
				it.write("[]".toByteArray())
				it.closeEntry()
			}
			ZipInputStream(old.toByteArray().inputStream()).use { repository.restoreBackup(it, setOf(BackupSection.CHAPTERS), null) }
			assertEquals(annotation, database.getChapterPersonalDao().findAll(listOf(owner.id)).single())
		} finally { file.delete() }
	}

	@Test
	fun privateChapterPersonalMetadataFollowsNativeBackupOptIn() = runTest {
		val owner = manga(1002L, "Private annotated", "/manga/1002")
		database.getMangaDao().upsert(owner)
		val privateCategory = database.getFavouriteCategoriesDao().insert(category("Private annotations", 42, FavouriteSpace.PRIVATE)).toLong()
		database.getPrivateFavouritesDao().upsert(PrivateFavouriteEntity(owner.id, privateCategory, 0, false, 1L, 0L))
		val annotation = org.koitharu.kotatsu.details.data.ChapterPersonalEntity(owner.id, owner.source, "/private/chapter", 5, "PRIVATE_NOTE_SENTINEL")
		database.getChapterPersonalDao().upsert(annotation)
		val bytes = java.io.ByteArrayOutputStream()
		ZipOutputStream(bytes).use { repository.createBackup(it, null) }
		val allEntries = StringBuilder()
		ZipInputStream(bytes.toByteArray().inputStream()).use { input ->
			while (input.nextEntry != null) allEntries.append(input.readBytes().toString(Charsets.UTF_8))
		}
		assertFalse(allEntries.contains("PRIVATE_NOTE_SENTINEL"))
		privateSecurity.includePrivateInBackup = true
		bytes.reset()
		ZipOutputStream(bytes).use { repository.createBackup(it, null) }
		database.clearAllTables()
		val result = ZipInputStream(bytes.toByteArray().inputStream()).use {
			repository.restoreBackup(it, emptySet(), null, restorePrivateFavourites = true)
		}
		assertTrue(result.failures.toString(), result.isAllSuccess)
		assertEquals(annotation, database.getChapterPersonalDao().findAll(listOf(owner.id)).single())
	}

	private fun category(title: String, sortKey: Int, space: FavouriteSpace = FavouriteSpace.NORMAL) = FavouriteCategoryEntity(
		categoryId = 0,
		createdAt = sortKey.toLong(),
		sortKey = sortKey,
		title = title,
		order = "NEWEST",
		track = true,
		downloadNewChapters = false,
		isVisibleInLibrary = true,
		deletedAt = 0L,
		space = space.dbValue,
	)

	private fun manga(id: Long, title: String, url: String) = MangaEntity(
		id = id,
		title = title,
		altTitles = null,
		url = url,
		publicUrl = "https://fixture.example$url",
		rating = -1f,
		isNsfw = false,
		contentRating = null,
		coverUrl = "",
		largeCoverUrl = null,
		state = null,
		authors = null,
		description = null,
		source = "MIHON_123",
		sourceTitle = "Fixture Source",
	)
}
