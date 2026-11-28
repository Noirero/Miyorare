package org.koitharu.kotatsu.favourites.domain

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.koitharu.kotatsu.SampleData
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.db.entity.toEntities
import org.koitharu.kotatsu.core.db.entity.toEntity
import org.koitharu.kotatsu.core.db.entity.toManga
import org.koitharu.kotatsu.core.model.MissingMangaSource
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.download.domain.DownloadDestinationStore
import org.koitharu.kotatsu.favourites.data.FavouriteCategoryEntity
import org.koitharu.kotatsu.favourites.data.FavouriteDownloadIndexEntity
import org.koitharu.kotatsu.favourites.data.FavouriteEntity
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
import org.koitharu.kotatsu.favourites.data.NormalDownloadObservationDao
import org.koitharu.kotatsu.favourites.data.NormalDownloadObservationEntity
import org.koitharu.kotatsu.list.domain.ListSortOrder
import org.koitharu.kotatsu.local.data.LocalStorageManager
import org.koitharu.kotatsu.local.data.NormalDownloadDirectoryDiscovery
import org.koitharu.kotatsu.local.data.NormalDownloadPriorIdentityReader
import org.koitharu.kotatsu.local.data.NormalScannerParentListing
import org.koitharu.kotatsu.local.data.index.LocalMangaIndex
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.sources.compat.SourceAliasRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLongArray
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.coroutines.coroutineContext

/**
 * Bounded scale attribution, NOT a full-library duration benchmark or a physical SD-card test.
 * All N titles/chapters, ownership rows and positive containers really exist. Eight spread
 * 32-title batches use the production prepare/publish methods on a fresh file-backed Room DB.
 * Independent proof/inventory samples overlap those stages; do not add them to pipeline time.
 * QueryCallback counts statement callbacks (including Room internals), not disk I/O or SQL time.
 * The injected LocalMangaIndex is used only for its existing read-only preference alias hints;
 * assert none exist for this synthetic ID range. No other DB is used by the scanner under test.
 */
internal class NormalScannerScaleProfiler(
	private val context: Context,
	private val settings: AppSettings,
	private val destinations: DownloadDestinationStore,
	private val aliases: SourceAliasRegistry,
	private val index: LocalMangaIndex,
	private val root: File,
) {
	private val space = FavouriteSpace.NORMAL

	suspend fun run(titleCount: Int) = withContext(Dispatchers.IO) {
		val meter = Meter()
		val name = "normal-scanner-profile-${titleCount}-${System.nanoTime()}.db"
		val db = Room.databaseBuilder(context, MangaDatabase::class.java, name)
			.setQueryCallback(RoomDatabase.QueryCallback { sql, _ -> meter.onQuery(sql) }, Executor { it.run() })
			.build()
		try {
			val start = SystemClock.elapsedRealtimeNanos()
			val titles = seed(db, titleCount)
			val expected = titles.filterIndexed { i, _ -> i % POSITIVE_STRIDE == 0 }.mapTo(HashSet()) { it.id }
			assertTrue(index.getDownloadAliasPaths(titles.map { it.id }).isEmpty())
			val scope = NormalDownloadScope(db, aliases, destinations)
			val discovery = NormalDownloadDirectoryDiscovery(scope, aliases, db, index, NormalDownloadPriorIdentityReader(db, settings))
			val writes = AtomicInteger()
			val realDao = db.getNormalDownloadObservationDao()
			val store = NormalDownloadObservationStore(db, destinations, scope, discovery,
				object : NormalDownloadObservationDao by realDao {
					override suspend fun upsert(row: NormalDownloadObservationEntity) {
						realDao.upsert(row)
						writes.incrementAndGet()
					}
				})
			val classifier = DownloadedContentClassifier(db, LocalStorageManager(context, settings, destinations), destinations, store)
			val proof = discovery.readVerificationInput()
			assertEquals(titleCount, proof.claims.size)
			assertEquals((titleCount + OWNED_STRIDE - 1) / OWNED_STRIDE, proof.owners.size)
			assertEquals(titleCount, realDao.findLibraryMembers().map { it.mangaId }.distinct().size)
			val completeBatches = titleCount / BATCH_SIZE
			val groups = (0 until SAMPLE_BATCHES).map { slot ->
				val offset = slot * (completeBatches - 1) / (SAMPLE_BATCHES - 1) * BATCH_SIZE
				titles.subList(offset, offset + BATCH_SIZE).map { it.id }
			}
			assertEquals(SAMPLE_BATCHES * BATCH_SIZE, groups.flatten().distinct().size)
			val sampledIds = groups.flatten().toSet()
			val expectedPresent = expected.intersect(sampledIds)
			val cards = groups.take(4).flatten()
			Log.i(TAG, "fixture titles=$titleCount chapters=${titleCount * CHAPTERS} positives=${expected.size} owners=${proof.owners.size} sample_batches=$SAMPLE_BATCHES sample_titles=${sampledIds.size} sample_positives=${expectedPresent.size} setup_ns=${SystemClock.elapsedRealtimeNanos() - start} file_backed=true")

			// Same batch-loading query and accepted-work path as the coordinator. Do not
			// run a second background drain which would contaminate per-stage attribution.
			suspend fun load(ids: List<Long>): List<Manga> = db.withTransaction {
				val chapters = db.getChaptersDao().findAll(ids).groupBy { it.mangaId }
				db.getMangaDao().findByIds(ids).map { row ->
					row.toManga(chapters[row.manga.id]?.takeUnless { it.isEmpty() })
				}
			}
			var priorRevision = store.changes.value
			for (pass in listOf("initial", "unchanged")) {
				val seen = HashSet<Long>()
				val visibleIds = if (pass == "unchanged") sampledIds.toMutableSet() else HashSet<Long>()
				for (ids in groups) {
					coroutineContext.ensureActive()
					val mangas = meter.measure("load_batch") { load(ids) }
					assertEquals(ids.toSet(), mangas.map { it.id }.toSet())
					val batch = requireNotNull(meter.measure("prepare") { store.prepareBatch(mangas) })
					assertEquals(ids.toSet(), batch.works.map { it.manga.id }.toSet())
					val accepted = meter.measure("publication") { store.publishBatch(batch) }
					assertEquals(ids.toSet(), accepted.keys)
					for ((id, status) in accepted) {
						assertEquals("Incorrect accepted fact for synthetic ID $id",
							if (id in expected) NormalObservationStatus.PRESENT else NormalObservationStatus.ABSENT, status)
					}
					seen += accepted.keys
					visibleIds += accepted.keys
					// Forced one refresh per batch; this measures actual consumer queries,
					// not the number of refreshes the ViewModel would schedule or UI draw time.
					val cardState = meter.measure("consumer_cards_128") { classifier.getDownloadState(space, cards) }
					assertEquals(expected.intersect(visibleIds).intersect(cards.toSet()), cardState.downloaded)
					assertEquals(visibleIds.intersect(cards.toSet()), cardState.resolved)
					val shelf = meter.measure("consumer_shelf_ids") { classifier.getDownloadedIds(space) }
					assertEquals(expected.intersect(visibleIds), shelf)
				}
				val count = writes.getAndSet(0)
				meter.report(titleCount, pass)
				Log.i(TAG, "pass titles=$titleCount kind=$pass accepted=${seen.size} writes=$count revision=${store.changes.value}")
				assertEquals(sampledIds, seen)
				assertEquals(if (pass == "initial") sampledIds.size else 0, count)
				if (pass == "unchanged") assertEquals(priorRevision, store.changes.value)
				priorRevision = store.changes.value
			}
			val finalVisibility = store.withLibraryVisibility(null) { it }
			assertEquals(titles.map { it.id }.toSet(), finalVisibility.eligible)
			assertEquals(sampledIds, finalVisibility.resolved)
			assertEquals(expectedPresent, finalVisibility.present)

			// Independent component measurements on the same loaded DB and root.
			// Inventory maps are freshly allocated per batch, just like production.
			var inventoriedParents = 0
			var resolvedParents = 0
			var verifiedCandidates = 0
			for (ids in groups.take(4)) {
				coroutineContext.ensureActive()
				val mangas = load(ids)
				val input = meter.measure("global_input") { discovery.readVerificationInput() }
				assertEquals(proof, input)
				val evidence = requireNotNull(meter.measure("global_evidence") { discovery.captureVerificationEvidence(input) })
				val inventory = HashMap<File, NormalScannerParentListing?>()
				meter.measure("directory_inventory") {
					for (manga in mangas) {
						val candidates = requireNotNull(discovery.discover(manga, space, inventory))
						assertTrue(candidates.available && candidates.inventoryComplete && candidates.unresolvedPaths.isEmpty())
					}
				}
				inventoriedParents += inventory.size
				val listings = HashMap<File, NormalScannerParentListing?>()
				meter.measure("candidate_resolution") {
					for (manga in mangas) {
						val result = requireNotNull(discovery.resolveCandidates(manga, space, evidence, listings))
						assertTrue(result.inventoryComplete && result.unresolvedPaths.isEmpty())
						assertEquals(manga.id in expected, result.uniqueCandidate != null)
						verifiedCandidates += result.verified.size
					}
				}
				resolvedParents += listings.size
			}
			meter.report(titleCount, "component_sample")
			Log.i(TAG, "inventory titles=$titleCount sample_batches=4 inventory_parent_listings=$inventoriedParents resolution_parent_listings=$resolvedParents verified_candidates=$verifiedCandidates")
			assertEquals(4 * BATCH_SIZE / POSITIVE_STRIDE, verifiedCandidates)
			assertTrue("Query callback did not capture the global title-claim statement", meter.sawGlobalClaims)
			Log.i(TAG, "complete titles=$titleCount sample_titles=${sampledIds.size} positive=${expectedPresent.size} resolved=${finalVisibility.resolved.size}")
		} finally {
			db.close()
			context.deleteDatabase(name)
		}
		Unit
	}

	private suspend fun seed(db: MangaDatabase, size: Int): List<Manga> {
		val source = MissingMangaSource("MIHON_2677079941490683989")
		val titles = (0 until size).map { offset ->
			val id = 920_000_000L + offset
			SampleData.manga.copy(id = id, title = "Normal Scale $id", altTitles = emptySet(),
				source = source, url = "/normal-scale/$id", contentRating = null,
				tags = emptySet(), coverUrl = null, largeCoverUrl = null,
				chapters = (1..CHAPTERS).map { chapter ->
					SampleData.chapter.copy(id = id * 10L + chapter, title = "Chapter $chapter",
						number = chapter.toFloat(), url = "/normal-scale/$id/chapter/$chapter", source = source, scanlator = null)
				})
		}
		val categoryId = db.getFavouriteCategoriesDao().insert(FavouriteCategoryEntity(
			categoryId = 0, createdAt = 1, sortKey = 0, title = "Normal scale fixture",
			order = ListSortOrder.NEWEST.name, track = false, downloadNewChapters = false,
			isVisibleInLibrary = true, deletedAt = 0, space = space.dbValue))
		val parent = File(root, "downloads/Bacami (ID)").canonicalFile.apply { check(mkdirs()) }
		val bytes = cbzBytes()
		for (chunk in titles.chunked(256)) {
			coroutineContext.ensureActive()
			db.withTransaction {
				for (manga in chunk) {
					db.getMangaDao().upsertWithCacheState(manga.toEntity().copy(chaptersInitialized = true))
					db.getChaptersDao().replaceAll(manga.id, manga.chapters.orEmpty().withIndex().toEntities(manga.id))
				}
				db.getFavouritesDao().insert(chunk.map { manga ->
					FavouriteEntity(manga.id, categoryId, 0, false, 1, 0)
				})
			}
			val owners = ArrayList<FavouriteDownloadIndexEntity>()
			for (manga in chunk) {
				coroutineContext.ensureActive()
				val offset = (manga.id - 920_000_000L).toInt()
				if (offset % POSITIVE_STRIDE != 0) continue
				val container = File(parent, manga.title).canonicalFile.apply { check(mkdirs()) }
				File(container, "Chapter 1.cbz").writeBytes(bytes)
				if (offset % OWNED_STRIDE == 0) owners += FavouriteDownloadIndexEntity(manga.id, space.dbValue, container.path)
			}
			if (owners.isNotEmpty()) db.getFavouriteDownloadIndexDao().upsert(owners)
		}
		return titles
	}

	private fun cbzBytes(): ByteArray {
		val png = ByteArrayOutputStream()
		val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
		try { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, png)) } finally { bitmap.recycle() }
		val archive = ByteArrayOutputStream()
		ZipOutputStream(archive).use { zip ->
			zip.putNextEntry(ZipEntry("1.png"))
			zip.write(png.toByteArray())
			zip.closeEntry()
		}
		return archive.toByteArray()
	}

	/** Count only while a labelled call is active; never retain SQL text or bind values. */
	private class Meter {
		private class Totals {
			val times = ArrayList<Long>()
			val queries = LongArray(7)
		}
		@Volatile private var active: AtomicLongArray? = null
		private val totals = LinkedHashMap<String, Totals>()
		var sawGlobalClaims = false
			private set

		fun onQuery(sql: String) {
			val counts = active ?: return
			counts.incrementAndGet(0)
			val query = sql.trim().lowercase(Locale.ROOT)
			if (query.startsWith("select")) {
				counts.incrementAndGet(1)
				if (query.startsWith("select manga_id as mangaid, source, title, url from manga where source !=")) counts.incrementAndGet(2)
				if (query == "select * from favourite_download_index where space = ?") counts.incrementAndGet(3)
				if (SINGLE_ID.containsMatchIn(query)) counts.incrementAndGet(4)
				if (query.contains("from chapters")) counts.incrementAndGet(5)
			}
			if (query.startsWith("insert") || query.startsWith("update") || query.startsWith("delete") || query.startsWith("replace")) counts.incrementAndGet(6)
		}

		suspend fun <T> measure(stage: String, block: suspend () -> T): T {
			check(active == null)
			val counts = AtomicLongArray(7)
			active = counts
			val start = SystemClock.elapsedRealtimeNanos()
			try { return block() } finally {
				val elapsed = SystemClock.elapsedRealtimeNanos() - start
				active = null
				val result = totals.getOrPut(stage) { Totals() }
				result.times += elapsed
				for (i in result.queries.indices) result.queries[i] += counts.get(i)
				sawGlobalClaims = sawGlobalClaims || counts.get(2) > 0
			}
		}

		fun report(titles: Int, pass: String) {
			for ((stage, result) in totals) {
				val times = result.times.sorted()
				val q = result.queries
				Log.i(TAG, "stage titles=$titles pass=$pass name=$stage calls=${times.size} sum_ns=${times.sum()} median_ns=${times[times.size / 2]} max_ns=${times.last()} sql_callbacks=${q[0]} select_callbacks=${q[1]} global_claim_reads=${q[2]} global_owner_reads=${q[3]} single_id_selects=${q[4]} chapter_selects=${q[5]} write_callbacks=${q[6]}")
			}
			totals.clear()
		}
	}

	private companion object {
		const val TAG = "NormalScannerScale"
		const val BATCH_SIZE = 32
		const val SAMPLE_BATCHES = 8
		const val POSITIVE_STRIDE = 8
		const val OWNED_STRIDE = 16
		const val CHAPTERS = 3
		val SINGLE_ID = Regex("(?:manga_id|mangaid)\\s*(?:=\\s*\\?|in\\s*\\(\\?\\))")
	}
}
