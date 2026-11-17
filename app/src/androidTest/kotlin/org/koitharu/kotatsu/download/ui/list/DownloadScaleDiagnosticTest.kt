package org.koitharu.kotatsu.download.ui.list

import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.Configuration
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.await
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koitharu.kotatsu.BuildConfig
import org.koitharu.kotatsu.SampleData
import org.koitharu.kotatsu.core.model.MangaNovelSection
import org.koitharu.kotatsu.core.model.MissingMangaSource
import org.koitharu.kotatsu.core.model.matchesMangaNovelSection
import org.koitharu.kotatsu.core.parser.MangaDataRepository
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.core.prefs.DownloadFormat
import org.koitharu.kotatsu.core.util.ext.getWorkSpec
import org.koitharu.kotatsu.download.domain.DownloadState
import org.koitharu.kotatsu.download.ui.worker.DownloadTask
import org.koitharu.kotatsu.download.ui.worker.DownloadWorker
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
import org.koitharu.kotatsu.favourites.domain.FavouritesRepository
import org.koitharu.kotatsu.local.data.LocalStorageChanges
import org.koitharu.kotatsu.local.data.LocalMangaRepository
import org.koitharu.kotatsu.local.domain.DeleteLocalMangaUseCase
import org.koitharu.kotatsu.local.domain.model.LocalManga
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.sources.compat.EhentaiSourceFamily
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Provider
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * Opt-in only: run on a fresh disposable debug emulator, not a personal installation.
 * Measures WorkManager -> DownloadsViewModel, not Activity rendering or real worker throughput.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class DownloadScaleDiagnosticTest {
	@get:Rule val hilt = HiltAndroidRule(this)
	@Inject lateinit var schedulerProvider: Provider<DownloadWorker.Scheduler>
	@Inject lateinit var data: MangaDataRepository
	@Inject lateinit var repositories: MangaRepository.Factory
	@Inject lateinit var local: LocalMangaRepository
	@Inject lateinit var deleteLocal: DeleteLocalMangaUseCase
	@Inject lateinit var favourites: FavouritesRepository
	@Inject lateinit var sizeResolver: DownloadSizeResolver
	@Inject @LocalStorageChanges lateinit var changes: MutableSharedFlow<LocalManga?>

	@Test fun oneGroup() = measure(1)
	@Test fun fiftyGroups() = measure(50)
	@Test fun fourHundredGroups() = measure(400)
	@Test fun fourHundredSeventyNineGroups() = measure(479)

	@SuppressLint("RestrictedApi")
	private fun measure(groups: Int) = runBlocking {
		check(BuildConfig.DEBUG)
		val instrumentation = InstrumentationRegistry.getInstrumentation()
		val context = instrumentation.targetContext
		hilt.inject()
		if (!WorkManager.isInitialized()) {
			WorkManager.initialize(context, Configuration.Builder().build())
		}
		val wm = WorkManager.getInstance(context)
		val scheduler = schedulerProvider.get()
		check(scheduler.observeWorks().first().isEmpty()) {
			"Run only on a fresh disposable debug emulator with empty download history."
		}
		val root = File(context.cacheDir, "scale-01-${UUID.randomUUID()}").apply { check(mkdir()) }
		val ids = LinkedHashMap<UUID, WorkInfo.State>()
		val activeId = UUID.randomUUID()
		val pulses = Channel<ScalePulse>(Channel.RENDEZVOUS)
		scalePulses[activeId] = pulses
		try {
			val source = MissingMangaSource(EhentaiSourceFamily.OFFICIAL_SOURCE_NAME)
			val requests = ArrayList<OneTimeWorkRequest>()
			for (index in 0 until groups) {
				val gid = 900_000_000L + groups * 1_000L + index
				val url = "https://exhentai.org/g/$gid/scale01/"
				val manga = SampleData.manga.copy(
					id = gid, title = "Scale fixture $gid", url = url, publicUrl = url,
					source = source, chapters = null, coverUrl = null, largeCoverUrl = null,
				)
				check(manga.matchesMangaNovelSection(MangaNovelSection.MANGA))
				data.storeManga(manga, replaceExisting = true, detailsFetched = true)
				val state = when {
					index == 0 -> WorkInfo.State.RUNNING
					index % 4 == 0 -> WorkInfo.State.CANCELLED
					index % 4 == 1 -> WorkInfo.State.FAILED
					else -> WorkInfo.State.SUCCEEDED
				}
				val copies = if (index > 0 && index % 5 == 0) 2 else 1
				repeat(copies) { copy ->
					val id = if (index == 0) activeId else UUID.randomUUID()
					ids[id] = state
					requests += request(id, manga, root, state, gid * 10 + copy)
				}
			}
			wm.enqueue(requests).await()
			for ((id, state) in ids) {
				if (state == WorkInfo.State.CANCELLED) wm.cancelWorkById(id).await()
			}
			val raw = withTimeout(120_000) {
				scheduler.observeWorks().first { works ->
					works.size == ids.size && works.all { ids[it.id] == it.state }
				}
			}
			val mix = raw.groupingBy { it.state.name }.eachCount().toSortedMap()
				.entries.joinToString(",") { "${it.key}:${it.value}" }
			report("fixture groups=$groups workspecs=${raw.size} states=$mix")
			var nextProgress = 0
			var baselineRows: List<RowSignature>? = null
			// Two counterbalanced pairs in one fixture; these are not independent emulator sessions.
			val arms = if (groups == 1 || groups == 400) listOf("A", "B", "B", "A") else listOf("A")
			for ((block, arm) in arms.withIndex()) {
				val snapshot = ids.keys.associateWith { id ->
					val spec = checkNotNull(wm.getWorkSpec(id))
					DownloadScaleProbe.Input(spec.input, Data.Builder().putAll(spec.input)
						.putLong(DownloadState.DATA_TIMESTAMP, spec.scheduleRequestedAt).build())
				}
				repeat(4) { sample ->
					val handle = SavedStateHandle()
					val events = ConcurrentLinkedQueue<String>()
					fun trace(message: String) { events.add(message) }
					val ticks = ArrayList<Int>(5)
					DownloadScaleProbe.install(handle, DownloadScaleProbe(snapshot.takeIf { arm == "B" }, activeId, ::trace))
					val vmStarted = SystemClock.elapsedRealtimeNanos()
					val vm = DownloadsViewModel(handle, scheduler, data, repositories,
						changes, local, deleteLocal, favourites, sizeResolver)
					try {
						withTimeout(60_000) {
							val first = vm.items.first { rows ->
								rows.any { it is DownloadItemModel && activeId in it.workIds }
							}
							val firstNs = SystemClock.elapsedRealtimeNanos() - vmStarted
							val full = vm.items.first { rows -> rows.count { it is DownloadItemModel } == groups }
							val fullNs = SystemClock.elapsedRealtimeNanos() - vmStarted
							check(full.filterIsInstance<DownloadItemModel>().flatMap { it.workIds }.toSet() == ids.keys)
							val signature = full.filterIsInstance<DownloadItemModel>().map {
								RowSignature(it.workIds, it.workState)
							}
							if (baselineRows == null) baselineRows = signature else check(signature.toSet() == baselineRows?.toSet())
							val startup = "first_groups=${first.count { it is DownloadItemModel }} first_ns=$firstNs all_ns=$fullNs"
							trace("stage=startup $startup")
							if (block == 0) report("startup groups=$groups sample=$sample $startup")
							repeat(5) {
								val tick = ++nextProgress
								ticks += tick
								val workObserved = async(start = CoroutineStart.UNDISPATCHED) {
									scheduler.observeWorks().first { works ->
										works.any { it.id == activeId && DownloadState.getProgress(it.progress) == tick }
									}
									SystemClock.elapsedRealtimeNanos()
								}
								val modelObserved = async(start = CoroutineStart.UNDISPATCHED) {
									vm.items.first { rows ->
										rows.any { it is DownloadItemModel && activeId in it.workIds && it.progress == tick }
									}
									SystemClock.elapsedRealtimeNanos()
								}
								val pulse = ScalePulse(tick)
								pulses.send(pulse)
								val workNs = workObserved.await()
								val modelNs = modelObserved.await()
								val returned = pulse.returnedNs.await()
								trace("stage=progress tick=$tick publish_ns=${pulse.publishedNs} return_ns=$returned work_ns=$workNs model_ns=$modelNs")
								if (block == 0) report("progress groups=$groups sample=$sample tick=$tick publish_to_work_ns=${workNs - pulse.publishedNs} publish_to_model_ns=${modelNs - pulse.publishedNs}")
							}
						}
					} finally {
						withContext(NonCancellable) {
							vm.viewModelScope.coroutineContext[Job]?.cancelAndJoin()
						}
						for (event in events) {
							report("ab groups=$groups block=$block arm=$arm sample=$sample work=$activeId $event")
						}
					}
					for (tick in ticks) {
						for (stage in listOf("vm_work", "convert", "group", "list", "progress")) {
							check(events.any { it.startsWith("stage=$stage tick=$tick ") }) {
								"Missing SCALE-01 stage=$stage block=$block sample=$sample tick=$tick"
							}
						}
					}
				}
			}
		} finally {
			withContext(NonCancellable) {
				try {
					scheduler.delete(ids.keys)
				} finally {
					scalePulses.remove(activeId)
					pulses.close()
					root.deleteRecursively()
				}
			}
		}
	}

	private data class RowSignature(val ids: Set<UUID>, val state: WorkInfo.State)

	private fun request(id: UUID, manga: Manga, root: File, state: WorkInfo.State, chapter: Long): OneTimeWorkRequest {
		val input = DownloadTask(
			mangaId = manga.id, isPaused = false, isSilent = true, chaptersIds = longArrayOf(chapter),
			destination = root, format = DownloadFormat.MULTIPLE_CBZ,
			allowMeteredNetwork = true, favouriteSpace = FavouriteSpace.NORMAL,
		).toData()
		return OneTimeWorkRequest.Builder(DownloadScaleFixtureWorker::class.java)
			.setId(id)
			.addTag("download")
			.setInputData(Data.Builder().putAll(input).putString("scale_state", state.name).build())
			.apply { if (state == WorkInfo.State.CANCELLED) setInitialDelay(1, TimeUnit.DAYS) }
			.build()
	}

	private fun report(message: String) {
		Log.i("DownloadScale01", message)
		println("SCALE01 $message")
	}
}

private class ScalePulse(val progress: Int) {
	@Volatile var publishedNs: Long = 0
	val returnedNs = CompletableDeferred<Long>()
}

private val scalePulses = ConcurrentHashMap<UUID, Channel<ScalePulse>>()

/** No network or archive writes; RUNNING emits one controlled progress update per request. */
class DownloadScaleFixtureWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
	override suspend fun doWork(): Result = when (inputData.getString("scale_state")) {
		"SUCCEEDED" -> Result.success(inputData)
		"FAILED" -> Result.failure(inputData)
		"RUNNING" -> {
			for (pulse in checkNotNull(scalePulses[id])) {
				val progress = Data.Builder().putAll(inputData)
					.putInt("max", 80).putInt("progress", pulse.progress).build()
				pulse.publishedNs = SystemClock.elapsedRealtimeNanos()
				setProgress(progress)
				pulse.returnedNs.complete(SystemClock.elapsedRealtimeNanos())
			}
			Result.success(inputData)
		}
		else -> Result.failure(inputData)
	}
}
