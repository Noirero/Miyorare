package org.koitharu.kotatsu.alternatives.work

import android.database.sqlite.SQLiteDatabase
import androidx.hilt.work.HiltWorkerFactory
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.Configuration
import androidx.work.Data
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.ListenableWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import java.util.concurrent.atomic.AtomicInteger
import androidx.work.impl.WorkManagerImpl
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.koitharu.kotatsu.SampleData
import org.koitharu.kotatsu.alternatives.domain.*
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.db.entity.toEntity
import org.koitharu.kotatsu.core.model.MissingMangaSource
import org.koitharu.kotatsu.explore.data.MangaSourcesRepository
import org.koitharu.kotatsu.favourites.data.FavouriteCategoryEntity
import org.koitharu.kotatsu.favourites.data.FavouriteEntity
import javax.inject.Inject
import javax.inject.Provider

/** Runs in its own instrumentation process: older suites deliberately initialize a default factory. */
@HiltAndroidTest
class MigrationBatchWorkRuntimeTest {
	@get:Rule val hilt = HiltAndroidRule(this)
	@Inject lateinit var factory: HiltWorkerFactory
	@Inject lateinit var databaseProvider: Provider<MangaDatabase>
	@Inject lateinit var sourcesProvider: Provider<MangaSourcesRepository>
	private val context = InstrumentationRegistry.getInstrumentation().targetContext

	@Test fun actualWorkersPersistResultsRejectOverlappingBatchesAndRecoverCancellationAndReplay() = runBlocking {
		hilt.inject()
		LargeBatchGate.started = CompletableDeferred()
		LargeBatchGate.starts.set(0)
		val testFactory = object : WorkerFactory() {
			override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker? {
				// Only the large-graph concurrency probe blocks deliberately. The preceding three items
				// and replay below instantiate the actual production worker through Hilt.
				if (workerClassName == MigrationBatchWorker::class.java.name && workerParameters.inputData.getString("request_source") == "BATCH_LARGE") {
					return LargeBatchGateWorker(appContext, workerParameters)
				}
				return factory.createWorker(appContext, workerClassName, workerParameters)
			}
		}
		WorkManager.initialize(context, Configuration.Builder().setWorkerFactory(testFactory).build())
		val manager = WorkManager.getInstance(context)
		val scheduler = MigrationBatchScheduler(manager)
		val database = databaseProvider.get()
		val sources = sourcesProvider.get()
		sources.ensureExternalSourcesReady()
		assertTrue("Fixture requires the clean CI installation without enabled external extensions", sources.getEnabledSources().isEmpty())
		val ids = listOf(986001L, 986002L, 986003L)
		val categoryId = 986101
		try {
			database.getFavouriteCategoriesDao().upsert(FavouriteCategoryEntity(categoryId, 1, 0, "Migration batch fixture", "NAME", false, false, true, 0))
			for (id in ids) {
				database.getMangaDao().upsert(SampleData.manga.copy(id = id, source = MissingMangaSource("BATCH_RUNTIME")).toEntity())
				database.getFavouritesDao().upsert(FavouriteEntity(id, categoryId.toLong(), 0, false, 1, 0))
			}
			val requests = ids.map { MigrationBatchRequest(it, "BATCH_RUNTIME", MigrationBatchAction.CHECK) }
			assertTrue(scheduler.enqueue(requests + requests.first()))
			val finished = withTimeout(120000) { scheduler.observe().first { works ->
				val batch = works.mapNotNull(MigrationBatchWork::snapshot).filter { it.source == "BATCH_RUNTIME" }
				batch.size == 3 && batch.none { it.running }
			} }
			val results = MigrationBatchPolicy.latest(finished.mapNotNull(MigrationBatchWork::snapshot).filter { it.source == "BATCH_RUNTIME" })
			assertEquals(3, results.size)
			assertTrue(results.values.all { it.outcome.status == MigrationBatchStatus.NO_REPLACEMENT })
			// A fresh SQLite connection reads committed WorkManager data, not the ViewModel's memory.
			val path = checkNotNull((manager as WorkManagerImpl).workDatabase.openHelper.writableDatabase.path) {
				"Durability fixture requires a file-backed WorkManager database"
			}
			SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READONLY).use { reopened ->
				for (info in finished.filter { MigrationBatchWork.snapshot(it)?.source == "BATCH_RUNTIME" }) {
					reopened.rawQuery("SELECT state, input, output FROM workspec WHERE id = ?", arrayOf(info.id.toString())).use { cursor ->
						assertTrue(cursor.moveToFirst())
						assertEquals(2, cursor.getInt(0)) // WorkManager SUCCEEDED; domain result remains explicit.
						assertEquals("BATCH_RUNTIME", MigrationBatchWork.request(Data.fromByteArray(cursor.getBlob(1))).source)
						assertEquals("NO_REPLACEMENT", Data.fromByteArray(cursor.getBlob(2)).getString("result_status"))
					}
				}
			}
			val restored = MigrationBatchScheduler(manager).observe().first().mapNotNull(MigrationBatchWork::snapshot)
			assertEquals(results, MigrationBatchPolicy.latest(restored.filter { it.source == "BATCH_RUNTIME" }))
			// Model a restart after source membership already committed/moved: replay must not mutate again.
			database.getFavouritesDao().delete(ids.first())
			val target = MigrationSelection("BATCH_NEW", 986900L, "stale-ready-fingerprint")
			assertTrue(scheduler.enqueue(listOf(MigrationBatchRequest(ids.first(), "BATCH_RUNTIME", MigrationBatchAction.MIGRATE, target))))
			val replay = withTimeout(60000) { scheduler.observe().first { works ->
				MigrationBatchPolicy.latest(works.mapNotNull(MigrationBatchWork::snapshot))[ids.first()]?.let {
					it.generation > results.getValue(ids.first()).generation && !it.running
				} == true
			} }
			assertEquals(MigrationBatchStatus.INTERRUPTED, MigrationBatchPolicy.latest(replay.mapNotNull(MigrationBatchWork::snapshot))[ids.first()]?.outcome?.status)
			assertNull(database.getMangaDao().find(target.id))
			// Exercise a large graph; no giant Data payload and no second batch can interleave it.
			val large = (1L..500L).map { MigrationBatchRequest(9_860_000L + it, "BATCH_LARGE", MigrationBatchAction.CHECK) }
			assertTrue(scheduler.enqueue(large))
			withTimeout(60000) { LargeBatchGate.started.await() }
			assertEquals(1, LargeBatchGate.starts.get())
			assertFalse(scheduler.enqueue(requests))
			val queued = withTimeout(60000) { scheduler.observe().first { works -> works.count { MigrationBatchWork.snapshot(it)?.source == "BATCH_LARGE" } == 500 } }
			val queuedLarge = queued.filter { MigrationBatchWork.snapshot(it)?.source == "BATCH_LARGE" }
			println("P4_BEFORE_CANCEL count=${queuedLarge.size} states=${queuedLarge.groupingBy { it.state }.eachCount()}")
			val batchName = queued.mapNotNull(MigrationBatchWork::snapshot).first { it.source == "BATCH_LARGE" }.batchName
			scheduler.cancel(batchName)
			val cancelled = withTimeout(60000) { scheduler.observe().first { works ->
				works.mapNotNull(MigrationBatchWork::snapshot).filter { it.batchName == batchName }.let { it.size == 500 && it.none { entry -> entry.running } }
			} }
			assertTrue(cancelled.mapNotNull(MigrationBatchWork::snapshot).filter { it.batchName == batchName }
				.all { it.outcome.status == MigrationBatchStatus.INTERRUPTED })

			// Force fresh reads after cancel. The flow above can legally emit an older snapshot, so it is
			// not sufficient evidence that the marker enqueue transaction committed.
			val terminalTag = MigrationBatchWork.terminalTag(MigrationBatchStatus.INTERRUPTED)
			val afterCancel = manager.getWorkInfosByTag(MigrationBatchWork.TAG).await()
			val afterLarge = afterCancel.filter { MigrationBatchWork.snapshot(it)?.source == "BATCH_LARGE" }
			val originalAfter = afterLarge.filter { MigrationBatchWork.snapshot(it)?.batchName == batchName }
			val markerInfos = afterLarge.filter { terminalTag in it.tags }
			println("P4_AFTER_ORIGINAL count=${originalAfter.size} states=${originalAfter.groupingBy { it.state }.eachCount()}")
			println("P4_MARKERS_DIRECT workinfo=${markerInfos.size} states=${markerInfos.groupingBy { it.state }.eachCount()}")

			var markerRows = 0
			var zeroEnqueueTimes = 0
			val markerDbStates = linkedMapOf<Int, Int>()
			SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READONLY).use { reopened ->
				reopened.rawQuery(
					"""
					SELECT w.state, w.last_enqueue_time, w.minimum_retention_duration
					FROM workspec AS w
					INNER JOIN worktag AS t ON t.work_spec_id = w.id
					WHERE t.tag = ?
					""".trimIndent(),
					arrayOf(terminalTag),
				).use { cursor ->
					while (cursor.moveToNext()) {
						markerRows++
						val state = cursor.getInt(0)
						markerDbStates[state] = (markerDbStates[state] ?: 0) + 1
						if (cursor.getLong(1) <= 0L) zeroEnqueueTimes++
					}
				}
			}
			println("P4_MARKER_DB rows=$markerRows zeroEnqueueTimes=$zeroEnqueueTimes states=$markerDbStates")
			assertEquals("Every never-started large item needs one terminal marker", 499, markerInfos.size)
			assertEquals("Terminal markers must be committed in WorkDatabase before process death", 499, markerRows)
		} finally {
			for (id in ids) database.getMangaDao().find(id)?.let { database.getMangaDao().delete(listOf(it.manga)) }
		}
	}
}

private object LargeBatchGate {
	var started = CompletableDeferred<Unit>()
	val starts = AtomicInteger()
}

private class LargeBatchGateWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
	override suspend fun doWork(): Result {
		LargeBatchGate.starts.incrementAndGet()
		LargeBatchGate.started.complete(Unit)
		awaitCancellation()
	}
}
