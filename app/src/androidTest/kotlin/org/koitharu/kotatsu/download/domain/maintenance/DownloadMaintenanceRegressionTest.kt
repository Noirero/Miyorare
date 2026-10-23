package org.koitharu.kotatsu.download.domain.maintenance

import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.preference.PreferenceManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koitharu.kotatsu.SampleData
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.model.MissingMangaSource
import org.koitharu.kotatsu.core.parser.MangaDataRepository
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.download.domain.DownloadDestinationStore
import org.koitharu.kotatsu.favourites.data.FavouriteDownloadIndexEntity
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
import org.koitharu.kotatsu.local.data.LegacyChapterDownloadCompat
import org.koitharu.kotatsu.local.data.LocalDownloadScanner
import org.koitharu.kotatsu.local.data.LocalMangaRepository
import org.koitharu.kotatsu.local.data.MangaIndex
import org.koitharu.kotatsu.local.data.index.LocalMangaIndex
import org.koitharu.kotatsu.local.data.input.LocalMangaParser
import org.koitharu.kotatsu.local.domain.model.LocalManga
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class DownloadMaintenanceRegressionTest {
	@get:Rule val hilt = HiltAndroidRule(this)
	@Inject lateinit var maintenance: DownloadMaintenanceUseCase
	@Inject lateinit var db: MangaDatabase
	@Inject lateinit var data: MangaDataRepository
	@Inject lateinit var index: LocalMangaIndex
	@Inject lateinit var local: LocalMangaRepository
	@Inject @org.koitharu.kotatsu.local.data.LocalStorageChanges lateinit var events: kotlinx.coroutines.flow.MutableSharedFlow<LocalManga?>
	private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
	private lateinit var root: File
	private lateinit var prefs: SharedPreferences
	private lateinit var savedPrefs: Map<String, *>
	private val ids = HashSet<Long>()

	@Before fun setUp() = runBlocking {
		hilt.inject()
		db.clearAllTables()
		root = File(context.cacheDir, "download-maintenance-${System.nanoTime()}").canonicalFile.apply { check(mkdirs()) }
		prefs = PreferenceManager.getDefaultSharedPreferences(context)
		savedPrefs = prefs.all.toMap()
		prefs.edit(commit = true) {
			putString(AppSettings.KEY_LOCAL_STORAGE, root.path)
			putString(DownloadDestinationStore.KEY_PRIVATE_DOWNLOAD_ROOT, File(root, "private").path)
			remove("normal_download_legacy_roots")
			remove("private_download_legacy_roots")
		}
		File(root, "private").mkdirs()
		Unit
	}

	@After fun tearDown() = runBlocking {
		for (id in ids) index.delete(id)
		prefs.edit(commit = true) {
			clear()
			for ((key, value) in savedPrefs) when (value) {
				is String -> putString(key, value)
				is Int -> putInt(key, value)
				is Long -> putLong(key, value)
				is Boolean -> putBoolean(key, value)
				is Float -> putFloat(key, value)
				is Set<*> -> putStringSet(key, value.filterIsInstance<String>().toSet())
			}
		}
		root.deleteRecursively()
		Unit
	}

	@Test fun currentSourceLayoutRemainsSidecarFreeAndSecondRunDoesNotMutate() = runBlocking {
		val remote = remote()
		val folder = folder("downloads/Source (EN)/Maintenance")
		val cbz = chapterFile(folder, "Chapter 1.cbz")
		register(remote, folder)
		val before = cbz.readBytes()
		maintenance.run()
		assertFalse(File(folder, "index.json").exists())
		assertArrayEquals(before, cbz.readBytes())
		assertEquals(folder.canonicalPath, db.getFavouriteDownloadIndexDao().findEntry(0, remote.id)?.path)
		maintenance.run()
		assertEquals(DownloadMaintenanceStatus.HEALTHY, result(folder).status)
		assertFalse(File(folder, "index.json").exists())
		assertArrayEquals(before, cbz.readBytes())
		assertEquals(remote.source.name, data.findMangaById(remote.id, true)?.source?.name)
	}

	@Test fun canonicalDiscoveryCoversCurrentAndHistoricalContainersAndReportsMissingRoots() {
		val paths = listOf("downloads/Source (EN)/Current", "downloads/00.Novel/Source (EN)/Novel", "Source/Legacy", "Source (en)/Previous", "Direct")
		val folders = paths.map { folder(it).also { f -> chapterFile(f, "Chapter 1.cbz") } }
		val unavailable = ArrayList<File>()
		val missing = File(root, "missing")
		val scanned = LocalDownloadScanner.scan(listOf(root, missing), unavailable::add)
		assertTrue(scanned.map { it.canonicalPath }.containsAll(folders.map { it.canonicalPath }))
		assertEquals(listOf(missing), unavailable)
	}

	@Test fun mandatoryPartialMatchCannotInstallAnIndexHidingPhysicalChapters() = runBlocking {
		val remote = remote()
		val folder = folder("Direct")
		chapterFile(folder, "Chapter 1.cbz")
		val unmatched = chapterFile(folder, "Unrecognized.cbz")
		val old = MangaIndex(null).apply { setMangaInfo(remote); addChapter(IndexedValue(0, remote.chapters!!.single()), "Chapter 1.cbz") }
		val indexFile = File(folder, "index.json").apply { writeText(old.toString()) }
		val before = indexFile.readBytes()
		val physical = LocalMangaParser(folder, true).getManga(true)
		val linked = LegacyChapterDownloadCompat.linkToRemote(remote, physical)
		val plan = DownloadIndexRepair.plan(folder, indexFile.readText(), remote, physical, linked)
		assertFalse(plan.complete)
		assertNull(plan.content)
		maintenance.run()
		assertArrayEquals(before, indexFile.readBytes())
		assertTrue(unmatched.isFile)
		assertEquals(DownloadMaintenanceStatus.UNRESOLVED, result(folder).status)
	}

	@Test fun staleIndexRetainsOfflineOnlyChapterAndCoverWhileDroppingMissingMapping() = runBlocking {
		val remote = remote()
		val offline = remote.chapters!!.single().copy(id = 107, title = "Removed chapter")
		val missing = offline.copy(id = 108)
		val folder = folder("Legacy")
		chapterFile(folder, "Chapter 1.cbz")
		chapterFile(folder, "Removed chapter.cbz")
		File(folder, "cover.jpg").writeBytes(byteArrayOf(1, 2, 3))
		val old = MangaIndex(null).apply {
			setMangaInfo(remote)
			addChapter(IndexedValue(0, remote.chapters!!.single()), "Chapter 1.cbz")
			addChapter(IndexedValue(1, offline), "Removed chapter.cbz")
			addChapter(IndexedValue(2, missing), "Missing.cbz")
			setCoverEntry("cover.jpg")
		}
		val indexFile = File(folder, "index.json").apply { writeText(old.toString()) }
		data.storeManga(remote, replaceExisting = true, detailsFetched = true)
		maintenance.run()
		val repaired = MangaIndex(indexFile.readText())
		assertEquals(setOf(101L, 107L), repaired.getMangaInfo()!!.chapters!!.map { it.id }.toSet())
		assertEquals("cover.jpg", repaired.getCoverEntry())
		val first = indexFile.readBytes()
		maintenance.run()
		assertArrayEquals(first, indexFile.readBytes())
	}

	@Test fun corruptIndexRequiresCompleteIndependentEvidence() = runBlocking {
		val remote = remote()
		val folder = folder("downloads/Maintenance")
		chapterFile(folder, "Chapter 1.cbz")
		register(remote, folder)
		val indexFile = File(folder, "index.json").apply { writeText("{broken") }
		maintenance.run()
		assertEquals(remote.id, MangaIndex(indexFile.readText()).getMangaInfo()?.id)
		assertEquals(1, LocalMangaParser(folder).getManga(true).manga.chapters?.size)
	}

	@Test fun corruptPartialIndexRemainsByteIdentical() = runBlocking {
		val folder = folder("Unresolved")
		chapterFile(folder, "unknown.cbz")
		val indexFile = File(folder, "index.json").apply { writeText("{broken") }
		maintenance.run()
		assertEquals("{broken", indexFile.readText())
		assertEquals(DownloadMaintenanceReason.CORRUPT_INDEX, result(folder).reason)
	}

	@Test fun offlineProviderWithPersistedMetadataNeedsNoNetworkAndRetainsRemovedArtifact() = runBlocking {
		val remote = remote()
		val folder = folder("downloads/Maintenance")
		chapterFile(folder, "Chapter 1.cbz")
		chapterFile(folder, "Removed chapter.cbz")
		register(remote, folder)
		maintenance.run()
		assertFalse(File(folder, "index.json").exists())
		assertEquals(2, LocalMangaParser(folder).getManga(true).manga.chapters?.size)
		assertEquals(2, local.findSavedMangaAtPath(remote, folder, rememberIdentity = false)?.manga?.chapters?.size)
	}

	@Test fun ambiguousBranchBasenameDoesNotSelectFirstRemoteChapter() = runBlocking {
		val one = remote().chapters!!.single().copy(branch = "A")
		val two = one.copy(id = 102, branch = "B")
		val remote = remote().copy(chapters = listOf(one, two))
		val folder = folder("Branches")
		chapterFile(folder, "Chapter 1.cbz")
		val physical = LocalMangaParser(folder).getManga(true)
		val linked = LegacyChapterDownloadCompat.linkToRemote(remote, physical)
		assertFalse(linked.manga.chapters!!.any { it.id == one.id || it.id == two.id })
	}

	@Test fun legacyHashedAndScanlatorNamesUseTheSharedMatcher() = runBlocking {
		val remote = remote().let { it.copy(chapters = listOf(it.chapters!!.single().copy(scanlator = "Group"))) }
		val chapter = remote.chapters!!.single()
		val hash = MessageDigest.getInstance("MD5").digest(chapter.url.toByteArray()).take(3).joinToString("") { "%02x".format(it.toInt() and 255) }
		for ((i, name) in listOf("Group_Chapter 1_$hash.cbz", "Group_Chapter 1 ($hash).cbz", "Chapter 1.cbz", "Group _ Chapter 1.cbz").withIndex()) {
			val folder = folder("Naming$i")
			chapterFile(folder, name)
			val linked = LegacyChapterDownloadCompat.linkToRemote(remote, LocalMangaParser(folder).getManga(true))
			assertEquals(chapter.id, linked.manga.chapters!!.single().id)
		}
	}

	@Test fun unavailablePrivateStorageKeepsItsOwnershipRow() = runBlocking {
		val remote = remote()
		data.storeManga(remote, replaceExisting = true, detailsFetched = true)
		val missing = File(root, "disconnected")
		prefs.edit(commit = true) { putString(DownloadDestinationStore.KEY_PRIVATE_DOWNLOAD_ROOT, missing.path) }
		val oldPath = File(missing, "downloads/Maintenance").path
		db.getFavouriteDownloadIndexDao().upsert(FavouriteDownloadIndexEntity(remote.id, 1, oldPath))
		maintenance.run()
		assertEquals(oldPath, db.getFavouriteDownloadIndexDao().findEntry(1, remote.id)?.path)
		assertEquals(DownloadMaintenanceStatus.UNAVAILABLE_STORAGE, result(missing).status)
	}

	@Test fun sharedRootWithoutOwnershipDoesNotCreateNormalOrPrivateRows() = runBlocking {
		val remote = remote()
		prefs.edit(commit = true) { remove(DownloadDestinationStore.KEY_PRIVATE_DOWNLOAD_ROOT) }
		val folder = folder("downloads/Maintenance")
		chapterFile(folder, "Chapter 1.cbz")
		register(remote, folder)
		maintenance.run()
		assertTrue(db.getFavouriteDownloadIndexDao().findEntries(listOf(remote.id)).isEmpty())
		assertEquals(DownloadMaintenanceReason.OWNERSHIP_UNKNOWN, result(folder).reason)
	}

	@Test fun concurrentTriggersShareOneRunAndDoNotPublishDuplicateRows() = runBlocking {
		val remote = remote()
		val folder = folder("downloads/Maintenance")
		chapterFile(folder, "Chapter 1.cbz")
		register(remote, folder)
		val first = async(start = CoroutineStart.UNDISPATCHED) { maintenance.run() }
		val second = async(start = CoroutineStart.UNDISPATCHED) { maintenance.run() }
		first.await(); second.await()
		assertFalse(maintenance.state.value.running)
		assertEquals(1, db.getFavouriteDownloadIndexDao().findEntries(listOf(remote.id)).size)
		assertEquals(1, maintenance.state.value.result!!.items.count { it.path == folder.path })
	}

	@Test fun novelPerChapterEpubKeepsEmbeddedIdentityWithoutDirectorySidecar() = runBlocking {
		val source = MissingMangaSource("LN_MAINTENANCE_TEST")
		val remote = remote().let { it.copy(source = source, chapters = it.chapters!!.map { ch -> ch.copy(source = source) }) }
		data.storeManga(remote.copy(chapters = null), replaceExisting = true, detailsFetched = false)
		val folder = folder("downloads/00.Novel/Source (EN)/Maintenance")
		val epub = File(folder, "Chapter 1.epub")
		val embedded = MangaIndex(null).apply { setMangaInfo(remote); addChapter(IndexedValue(0, remote.chapters!!.single()), "OEBPS/chapter.xhtml") }
		ZipOutputStream(epub.outputStream()).use { zip ->
			for ((name, text) in listOf("index.json" to embedded.toString(), "OEBPS/chapter.xhtml" to "<html><body>Offline</body></html>")) {
				zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry()
			}
		}
		val before = epub.readBytes()
		maintenance.run()
		assertTrue(result(folder).status in setOf(DownloadMaintenanceStatus.HEALTHY, DownloadMaintenanceStatus.REPAIRED))
		assertFalse(File(folder, "index.json").exists())
		assertArrayEquals(before, epub.readBytes())
		val chapter = LocalMangaParser(folder).getManga(true).manga.chapters!!.single()
		assertEquals(101L, chapter.id)
		assertEquals("OEBPS/chapter.xhtml", chapter.url.toUri().fragment)
	}

	@Test fun unavailableProviderReportsMissingMetadataInsteadOfFalseSuccess() = runBlocking {
		val remote = remote().copy(chapters = null)
		val folder = folder("downloads/Maintenance")
		chapterFile(folder, "Chapter 1.cbz")
		register(remote, folder)
		maintenance.run()
		assertEquals(DownloadMaintenanceStatus.UNRESOLVED, result(folder).status)
		assertEquals(DownloadMaintenanceReason.PROVIDER_UNAVAILABLE, result(folder).reason)
		assertFalse(File(folder, "index.json").exists())
	}

	@Test fun cancellationBeforeAtomicCommitRestoresPreviousIndex() {
		val file = File(root, "index.json").apply { writeText("previous") }
		val result = runCatching { DownloadIndexRepair.write(file, "replacement") { throw kotlinx.coroutines.CancellationException("cancel") } }
		assertTrue(result.exceptionOrNull() is kotlinx.coroutines.CancellationException)
		assertEquals("previous", file.readText())
		assertFalse(File(root, "index.json.new").exists())
	}

	@Test fun normalAndPrivateCopiesKeepIndependentOwnership() = runBlocking {
		val remote = remote()
		val normal = folder("downloads/Maintenance")
		val privateFolder = folder("private/downloads/Maintenance")
		chapterFile(normal, "Chapter 1.cbz")
		chapterFile(privateFolder, "Chapter 1.cbz")
		register(remote, normal)
		// Both explicit ownership rows are legitimate even though the global alias has one path.
		db.getFavouriteDownloadIndexDao().upsert(listOf(
			FavouriteDownloadIndexEntity(remote.id, 0, normal.path),
			FavouriteDownloadIndexEntity(remote.id, 1, privateFolder.path),
		))
		val collector = launch(start = CoroutineStart.UNDISPATCHED) {
			events.take(2).collect { value -> if (value != null) index.emit(value) }
		}
		maintenance.run()
		kotlinx.coroutines.withTimeout(30_000) { collector.join() }
		assertEquals(normal.path, index.getDownloadAliasPaths(listOf(remote.id))[remote.id])
		assertEquals(normal.path, db.getFavouriteDownloadIndexDao().findEntry(0, remote.id)?.path)
		assertEquals(privateFolder.path, db.getFavouriteDownloadIndexDao().findEntry(1, remote.id)?.path)
		assertFalse(File(normal, "index.json").exists())
		assertFalse(File(privateFolder, "index.json").exists())
	}

	@Test fun conflictingSourceIdentityLeavesIndexAndOwnershipUntouched() = runBlocking {
		val remote = remote()
		val folder = folder("downloads/Maintenance")
		chapterFile(folder, "Chapter 1.cbz")
		register(remote, folder)
		val other = remote.copy(id = remote.id + 1, source = MissingMangaSource("MAINTENANCE_TEST_PLUS"))
		val indexFile = File(folder, "index.json").apply { writeText(MangaIndex(null).apply {
			setMangaInfo(other); addChapter(IndexedValue(0, other.chapters!!.single()), "Chapter 1.cbz")
		}.toString()) }
		val before = indexFile.readBytes()
		maintenance.run()
		assertArrayEquals(before, indexFile.readBytes())
		assertEquals(DownloadMaintenanceReason.SOURCE_MISMATCH, result(folder).reason)
		assertTrue(db.getFavouriteDownloadIndexDao().findEntries(listOf(remote.id)).isEmpty())
	}

	@Test fun duplicateTitleFoldersWithoutDurableIdentityRemainAmbiguous() = runBlocking {
		val remote = remote()
		data.storeManga(remote, replaceExisting = true, detailsFetched = true)
		val one = folder("downloads/Maintenance")
		val two = folder("Maintenance")
		chapterFile(one, "Chapter 1.cbz")
		chapterFile(two, "Chapter 1.cbz")
		maintenance.run()
		assertEquals(DownloadMaintenanceStatus.AMBIGUOUS, result(one).status)
		assertEquals(DownloadMaintenanceStatus.AMBIGUOUS, result(two).status)
		assertTrue(db.getFavouriteDownloadIndexDao().findEntries(listOf(remote.id)).isEmpty())
	}

	@Test fun metadataRecoveryUsesOfflineEvidenceAndValidatesProviderResponses() = runBlocking {
		val remote = remote()
		var calls = 0
		val cached = DownloadMaintenanceMetadata.resolve(remote, null) { calls++; error("network must not run") }
		assertEquals(0, calls)
		assertEquals(remote, cached.manga)
		val fromIndex = DownloadMaintenanceMetadata.resolve(remote.copy(chapters = null), remote) { calls++; error("network must not run") }
		assertEquals(remote, fromIndex.manga)
		assertEquals(0, calls)
		val fetched = DownloadMaintenanceMetadata.resolve(remote.copy(chapters = null), null) { calls++; remote }
		assertTrue(fetched.fetched)
		assertEquals(1, calls)
		val failed = DownloadMaintenanceMetadata.resolve(remote.copy(chapters = null), null) { throw java.io.IOException("offline") }
		assertEquals(DownloadMaintenanceReason.PROVIDER_UNAVAILABLE, failed.failure)
		val mismatch = DownloadMaintenanceMetadata.resolve(remote.copy(chapters = null), null) { remote.copy(id = remote.id + 1) }
		assertEquals(DownloadMaintenanceReason.METADATA_UNAVAILABLE, mismatch.failure)
	}

	@Test fun recreatedViewModelOwnerKeepsProgressAndResult() = runBlocking {
		val remote = remote()
		val folder = folder("downloads/Maintenance")
		chapterFile(folder, "Chapter 1.cbz")
		register(remote, folder)
		val store = androidx.lifecycle.ViewModelStore()
		val factory = object : androidx.lifecycle.ViewModelProvider.Factory {
			@Suppress("UNCHECKED_CAST")
			override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T = org.koitharu.kotatsu.settings.DownloadMaintenanceViewModel(maintenance) as T
		}
		val first = androidx.lifecycle.ViewModelProvider(store, factory)[org.koitharu.kotatsu.settings.DownloadMaintenanceViewModel::class.java]
		val recreated = androidx.lifecycle.ViewModelProvider(store, factory)[org.koitharu.kotatsu.settings.DownloadMaintenanceViewModel::class.java]
		assertSame(first, recreated)
		InstrumentationRegistry.getInstrumentation().runOnMainSync { first.start(); recreated.start() }
		kotlinx.coroutines.withTimeout(30_000) { maintenance.state.first { !it.running && it.result != null } }
		assertSame(first.state, recreated.state)
		assertEquals(1, recreated.state.value.result!!.items.count { it.path == folder.path })
		InstrumentationRegistry.getInstrumentation().runOnMainSync { store.clear() }
	}

	@Test fun providerRemapCannotRewriteAValidLegacyChapterIdentity() = runBlocking {
		val oldRemote = remote()
		val folder = folder("LegacyStable")
		chapterFile(folder, "Chapter 1.cbz")
		val old = MangaIndex(null).apply { setMangaInfo(oldRemote); addChapter(IndexedValue(0, oldRemote.chapters!!.single()), "Chapter 1.cbz") }
		val physical = DownloadIndexRepair.restoreValidMappings(folder, old, LocalMangaParser(folder, true).getManga(true))
		val changedRemote = oldRemote.copy(chapters = oldRemote.chapters!!.map { it.copy(id = 999) })
		val linked = LegacyChapterDownloadCompat.linkToRemote(changedRemote, physical)
		val plan = DownloadIndexRepair.plan(folder, old.toString(), changedRemote, physical, linked)
		assertTrue(plan.complete)
		assertNull(plan.content)
	}

	@Test fun atomicFailureLeavesPreviousValidIndexUntouched() {
		val index = File(root, "index.json").apply { writeText("previous") }
		// AtomicFile cannot create its .new file when a directory occupies that name.
		File(root, "index.json.new").mkdir()
		assertTrue(runCatching { DownloadIndexRepair.write(index, "replacement") }.isFailure)
		assertEquals("previous", index.readText())
	}

	private fun remote(): Manga {
		val source = MissingMangaSource("MAINTENANCE_TEST")
		ids += 950_010L
		return SampleData.manga.copy(id = 950_010L, title = "Maintenance", source = source,
			chapters = listOf(SampleData.chapter.copy(id = 101, title = "Chapter 1", number = 1f, scanlator = null, branch = null, source = source, url = "https://example.invalid/chapter/1")))
	}
	private fun folder(path: String) = File(root, path).canonicalFile.apply { check(mkdirs() || isDirectory) }
	private fun chapterFile(folder: File, name: String): File = File(folder, name).apply {
		ZipOutputStream(outputStream()).use { zip -> zip.putNextEntry(ZipEntry("1.jpg")); zip.write(byteArrayOf(1, 2, 3)); zip.closeEntry() }
	}
	private suspend fun register(remote: Manga, folder: File) {
		data.storeManga(remote, replaceExisting = true, detailsFetched = true)
		val physical = LocalMangaParser(folder, true).getManga(true)
		ids += physical.manga.id
		index.registerDownloadAlias(remote.id, physical.manga.id, folder)
	}
	private fun result(file: File) = maintenance.state.value.result!!.items.single { it.path == file.canonicalPath }
}
