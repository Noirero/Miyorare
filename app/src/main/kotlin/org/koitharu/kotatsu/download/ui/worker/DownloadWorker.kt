package org.koitharu.kotatsu.download.ui.worker

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.await
import dagger.Reusable
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.internal.closeQuietly
import okio.IOException
import okio.buffer
import okio.sink
import okio.use
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.image.BitmapDecoderCompat
import org.koitharu.kotatsu.core.model.ids
import org.koitharu.kotatsu.core.model.isLocal
import org.koitharu.kotatsu.core.network.MangaHttpClient
import org.koitharu.kotatsu.core.network.imageproxy.ImageProxyInterceptor
import org.koitharu.kotatsu.core.parser.MangaDataRepository
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.util.MimeTypes
import org.koitharu.kotatsu.core.util.Throttler
import org.koitharu.kotatsu.core.util.ext.MimeType
import org.koitharu.kotatsu.core.util.ext.awaitFinishedWorkInfosByTag
import org.koitharu.kotatsu.core.util.ext.awaitUpdateWork
import org.koitharu.kotatsu.core.util.ext.awaitWorkInfosByTag
import org.koitharu.kotatsu.core.util.ext.deleteAwait
import org.koitharu.kotatsu.core.util.ext.deleteWork
import org.koitharu.kotatsu.core.util.ext.deleteWorks
import org.koitharu.kotatsu.core.util.ext.ensureSuccess
import org.koitharu.kotatsu.core.util.ext.getDisplayMessage
import org.koitharu.kotatsu.core.util.ext.getWorkInputData
import org.koitharu.kotatsu.core.util.ext.getWorkSpec
import org.koitharu.kotatsu.core.util.ext.openSource
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.core.util.ext.toFileOrNull
import org.koitharu.kotatsu.core.util.ext.toMimeType
import org.koitharu.kotatsu.core.util.ext.toMimeTypeOrNull
import org.koitharu.kotatsu.core.util.ext.withTicker
import org.koitharu.kotatsu.core.util.ext.writeAllCancellable
import org.koitharu.kotatsu.core.util.progress.RealtimeEtaEstimator
import org.koitharu.kotatsu.download.domain.DownloadProgress
import org.koitharu.kotatsu.download.domain.DownloadState
import org.koitharu.kotatsu.local.data.LocalMangaRepository
import org.koitharu.kotatsu.local.data.LocalStorageCache
import org.koitharu.kotatsu.local.data.LocalStorageChanges
import org.koitharu.kotatsu.local.data.PageCache
import org.koitharu.kotatsu.local.data.TempFileFilter
import org.koitharu.kotatsu.local.data.input.LocalMangaParser
import org.koitharu.kotatsu.local.data.output.LocalMangaOutput
import org.koitharu.kotatsu.local.domain.MangaLock
import org.koitharu.kotatsu.local.domain.model.LocalManga
import org.koitharu.kotatsu.mihon.getResumableImageStream
import org.koitharu.kotatsu.parsers.exception.TooManyRequestExceptions
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.util.ifNullOrEmpty
import org.koitharu.kotatsu.parsers.util.mapToSet
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.reader.domain.PageLoader
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject

@HiltWorker
class DownloadWorker @AssistedInject constructor(
	@Assisted appContext: Context,
	@Assisted params: WorkerParameters,
	@MangaHttpClient private val okHttp: OkHttpClient,
	@PageCache private val cache: LocalStorageCache,
	private val database: MangaDatabase,
	private val localMangaRepository: LocalMangaRepository,
	private val mangaLock: MangaLock,
	private val mangaDataRepository: MangaDataRepository,
	private val mangaRepositoryFactory: MangaRepository.Factory,
	private val settings: AppSettings,
	private val performanceSettings: DownloadPerformanceSettings,
	private val concurrencyController: DownloadConcurrencyController,
	@LocalStorageChanges private val localStorageChanges: MutableSharedFlow<LocalManga?>,
	private val slowdownDispatcher: DownloadSlowdownDispatcher,
	private val imageProxyInterceptor: ImageProxyInterceptor,
	notificationFactoryFactory: DownloadNotificationFactory.Factory,
) : CoroutineWorker(appContext, params) {

	private val task = DownloadTask(params.inputData)
	private val notificationFactory = notificationFactoryFactory.create(uuid = params.id, isSilent = task.isSilent)
	private val notificationManager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

	@Volatile
	private var lastPublishedState: DownloadState? = null
	private val currentState: DownloadState
		get() = checkNotNull(lastPublishedState)

	private val etaEstimator = RealtimeEtaEstimator()
	private val notificationThrottler = Throttler(400)
	private val statePublishMutex = Mutex()

	override suspend fun doWork(): Result {
		setForeground(getForegroundInfo())
		val manga = mangaDataRepository.findMangaById(task.mangaId, withChapters = true) ?: run {
			DownloadPauseStore.clear(applicationContext, id)
			return Result.failure()
		}
		val privacyRefreshJob = CoroutineScope(currentCoroutineContext()).launch {
			database.getPrivateFavouritesDao()
				.observePrivateOnly(manga.id)
				.distinctUntilChanged()
				.collect { refreshNotificationForPrivacy() }
		}
		publishState(DownloadState(manga = manga, isIndeterminate = true).also { lastPublishedState = it })
		pruneResumeCache()
		val downloadedIds = getDoneChapters(manga)
		val pausingHandle = PausingHandle()
		if (DownloadPauseStore.getPaused(applicationContext, id) ?: task.isPaused) {
			pausingHandle.pause()
		}
		val pausingReceiver = PausingReceiver(id, pausingHandle)
		ContextCompat.registerReceiver(
			applicationContext,
			pausingReceiver,
			PausingReceiver.createIntentFilter(id),
			ContextCompat.RECEIVER_NOT_EXPORTED,
		)
		return try {
			withContext(pausingHandle) {
				val pauseStateJob = launch {
					pausingHandle.pauseState.drop(1).collect { paused ->
						publishState(
							currentState.copy(
								isPaused = paused,
								eta = if (paused) -1L else currentState.eta,
								isStuck = if (paused) false else currentState.isStuck,
							),
						)
					}
				}
				try {
					concurrencyController.withPermit(performanceSettings.parallelSourceLimit) {
						checkIsPaused()
						downloadMangaImpl(manga, task, downloadedIds)
					}
				} finally {
					pauseStateJob.cancel()
				}
			}
			clearResumeMangaDir(manga.id)
			DownloadPauseStore.clear(applicationContext, id)
			Result.success(currentState.toWorkData())
		} catch (_: CancellationException) {
			withContext(NonCancellable) {
				val notification = notificationFactory.create(currentState.copy(isStopped = true))
				notificationManager.notify(id.hashCode(), notification)
			}
			Result.failure(currentState.copy(eta = -1L, isStuck = false).toWorkData())
		} catch (e: Exception) {
			e.printStackTraceDebug()
			DownloadPauseStore.clear(applicationContext, id)
			Result.failure(
				currentState.copy(
					error = e,
					errorMessage = e.getDisplayMessage(applicationContext.resources),
					eta = -1L,
					isStuck = false,
				).toWorkData(),
			)
		} finally {
			privacyRefreshJob.cancelAndJoin()
			runCatching { applicationContext.unregisterReceiver(pausingReceiver) }
			notificationManager.cancel(id.hashCode())
		}
	}

	override suspend fun getForegroundInfo() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
		ForegroundInfo(
			id.hashCode(),
			notificationFactory.create(lastPublishedState),
			ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
		)
	} else {
		ForegroundInfo(id.hashCode(), notificationFactory.create(lastPublishedState))
	}

	private suspend fun downloadMangaImpl(subject: Manga, task: DownloadTask, excludedIds: Set<Long>) {
		var manga = subject
		val chaptersToSkip = excludedIds.toMutableSet()
		mangaLock.withLock(manga) {
			val destination = localMangaRepository.getOutputDir(manga, task.destination)
			checkNotNull(destination) { applicationContext.getString(R.string.cannot_find_available_storage) }
			var output: LocalMangaOutput? = null
			var isCompleted = false
			try {
				if (manga.isLocal) {
					manga = localMangaRepository.getRemoteManga(manga)
						?: error("Cannot obtain remote manga instance")
				}
				val repo = mangaRepositoryFactory.create(manga.source)
				val mangaDetails = if (manga.chapters.isNullOrEmpty() || manga.description.isNullOrEmpty()) repo.getDetails(manga) else manga
				output = LocalMangaOutput.getOrCreate(
					root = destination,
					manga = mangaDetails,
					format = task.format ?: settings.preferredDownloadFormat,
				)
				val coverUrl = mangaDetails.largeCoverUrl.ifNullOrEmpty { mangaDetails.coverUrl }
				if (!coverUrl.isNullOrEmpty()) {
					downloadFile(coverUrl, destination, repo).let { file ->
						output.addCover(file, getMediaType(coverUrl, file))
						file.deleteAwait()
					}
				}
				val chapters = getChapters(mangaDetails, task)
				for ((chapterIndex, chapter) in chapters.withIndex()) {
					checkIsPaused()
					if (chaptersToSkip.remove(chapter.value.id)) {
						clearResumeChapterDir(mangaDetails.id, chapter.value.id)
						publishState(currentState.copy(downloadedChapters = currentState.downloadedChapters + 1))
						continue
					}
					val pages = runFailsafe { repo.getPages(chapter.value) } ?: continue
					val resumeDir = getResumeChapterDir(mangaDetails.id, chapter.value.id)
					val downloadedPages = arrayOfNulls<DownloadedPage>(pages.size)
					val pageCounter = AtomicInteger(0)
					channelFlow {
						val semaphore = Semaphore(performanceSettings.parallelPageLimit)
						for ((pageIndex, page) in pages.withIndex()) {
							checkIsPaused()
							launch {
								semaphore.withPermit {
									val downloadedPage = runFailsafe {
										val url = repo.getPageUrl(page)
										val cachedFile = cache[url]
										if (cachedFile != null) {
											DownloadedPage(url, cachedFile, getMediaType(url, cachedFile))
										} else {
											val file = downloadFile(
												url = url,
												destination = resumeDir,
												repo = repo,
												page = page,
												resumeKey = buildResumeKey(pageIndex, page),
											)
											DownloadedPage(url, file, getMediaType(url, file))
										}
									}
									if (downloadedPage != null) downloadedPages[pageIndex] = downloadedPage
									send(pageIndex)
								}
							}
						}
					}.map {
						DownloadProgress(
							totalChapters = chapters.size,
							currentChapter = chapterIndex,
							totalPages = pages.size,
							currentPage = pageCounter.getAndIncrement(),
						)
					}.withTicker(500L, TimeUnit.MILLISECONDS).collect { progress ->
						publishState(
							currentState.copy(
								totalChapters = progress.totalChapters,
								currentChapter = progress.currentChapter,
								totalPages = progress.totalPages,
								currentPage = progress.currentPage,
								isIndeterminate = false,
								eta = etaEstimator.getEta(),
								isStuck = etaEstimator.isStuck(),
							),
						)
					}

					// Never turn a user-skipped/failed page into a corrupt-looking "completed" chapter. No page
					// has been written to the output yet, so skipping the whole chapter here is atomic and leaves
					// existing completed chapters untouched.
					if (downloadedPages.any { it == null }) {
						continue
					}
					for ((pageIndex, downloadedPage) in downloadedPages.withIndex()) {
						checkIsPaused()
						val page = checkNotNull(downloadedPage)
						output.addPage(
							chapter = chapter,
							file = page.file,
							pageNumber = pageIndex,
							type = page.type,
						)
					}
					if (output.flushChapter(chapter.value)) {
						runCatchingCancellable {
							localStorageChanges.emit(LocalMangaParser(output.rootFile).getManga(withDetails = false))
						}.onFailure(Throwable::printStackTraceDebug)
					}
					clearResumeChapterDir(mangaDetails.id, chapter.value.id)
					publishState(currentState.copy(downloadedChapters = currentState.downloadedChapters + 1))
				}
				publishState(currentState.copy(isIndeterminate = true, eta = -1L, isStuck = false))
				output.mergeWithExisting()
				output.finish()
				val localManga = LocalMangaParser(output.rootFile).getManga(withDetails = false)
				localStorageChanges.emit(localManga)
				publishState(currentState.copy(localManga = localManga, eta = -1L, isStuck = false))
				isCompleted = true
			} catch (e: Exception) {
				if (e !is CancellationException) {
					publishState(
						currentState.copy(error = e, errorMessage = e.getDisplayMessage(applicationContext.resources)),
					)
				}
				throw e
			} finally {
				withContext(NonCancellable) {
					runCatchingCancellable { output?.cleanup() }.onFailure(Throwable::printStackTraceDebug)
					output?.closeQuietly()
					if (!isCompleted && output != null && output.rootFile.exists()) {
						runCatchingCancellable {
							localStorageChanges.emit(LocalMangaParser(output.rootFile).getManga(withDetails = false))
						}.onFailure(Throwable::printStackTraceDebug)
					}
					destination.listFiles(TempFileFilter())?.forEach { it.deleteAwait() }
				}
			}
		}
	}

	private suspend fun <R> runFailsafe(block: suspend () -> R): R? {
		checkIsPaused()
		var retriesRemaining = MAX_FAILSAFE_RETRIES
		var ordinaryRetryIndex = 0
		while (true) {
			try {
				return block()
			} catch (e: IOException) {
				val retryDelay = if (e is TooManyRequestExceptions) {
					e.getRetryDelay()
				} else {
					DOWNLOAD_ERROR_DELAY * (1L shl ordinaryRetryIndex.coerceAtMost(MAX_BACKOFF_SHIFT))
				}
				if (retriesRemaining <= 0 || retryDelay < 0 || retryDelay > MAX_RETRY_DELAY) {
					val pausingHandle = PausingHandle.current()
					if (pausingHandle.skipAllErrors()) return null
					publishState(
						currentState.copy(
							isPaused = true,
							error = e,
							errorMessage = e.getDisplayMessage(applicationContext.resources),
							eta = -1L,
							isStuck = false,
						),
					)
					retriesRemaining = MAX_FAILSAFE_RETRIES
					ordinaryRetryIndex = 0
					pausingHandle.pause()
					try {
						pausingHandle.awaitResumed()
						if (pausingHandle.skipCurrentError()) return null
					} finally {
						publishState(currentState.copy(isPaused = false, error = null, errorMessage = null))
					}
				} else {
					retriesRemaining--
					if (e !is TooManyRequestExceptions) ordinaryRetryIndex++
					delay(retryDelay)
				}
			}
		}
	}

	private suspend fun checkIsPaused() {
		val pausingHandle = PausingHandle.current()
		if (pausingHandle.isPaused) {
			publishState(currentState.copy(isPaused = true, eta = -1L, isStuck = false))
			try {
				pausingHandle.awaitResumed()
			} finally {
				publishState(currentState.copy(isPaused = false))
			}
		}
	}

	private suspend fun getMediaType(url: String, file: File): MimeType? = runInterruptible(Dispatchers.IO) {
		BitmapDecoderCompat.probeMimeType(file)?.let { return@runInterruptible it }
		MimeTypes.getMimeTypeFromUrl(url)
	}

	private suspend fun downloadFile(
		url: String,
		destination: File,
		repo: MangaRepository,
		page: MangaPage? = null,
		resumeKey: String? = null,
	): File {
		if (!destination.exists()) {
			check(destination.mkdirs() || destination.isDirectory) { "Cannot create download directory $destination" }
		}
		val readyFile = resumeKey?.let { File(destination, "$it.ready") }
		if (readyFile != null && readyFile.isFile && readyFile.length() > 0L) return readyFile
		if (readyFile != null && readyFile.exists()) readyFile.delete()
		val partialFile = resumeKey?.let { File(destination, "$it.part") }

		if (url.startsWith("content:", ignoreCase = true) || url.startsWith("file:", ignoreCase = true)) {
			val uri = url.toUri()
			val cr = applicationContext.contentResolver
			val ext = uri.toFileOrNull()?.let { MimeTypes.getNormalizedExtension(it.name) }
				?: cr.getType(uri)?.toMimeTypeOrNull()?.let { MimeTypes.getExtension(it) }
			val file = partialFile ?: destination.createTempFile(ext)
			try {
				cr.openSource(uri).use { input ->
					file.sink(append = false).buffer().use { it.writeAllCancellable(input) }
				}
			} catch (e: Exception) {
				if (partialFile == null) file.delete()
				throw e
			}
			return if (readyFile != null) finalizeResumeFile(file, readyFile) else file
		}

		val source = repo.source
		val existingSize = partialFile?.takeIf { it.isFile }?.length() ?: 0L
		slowdownDispatcher.delay(source)
		val response = (if (page != null) {
			if (existingSize > 0L) repo.getResumableImageStream(url, page, existingSize)
			else repo.getImageStream(url, page)
		} else {
			repo.getCoverStream(url)
		}) ?: run {
			val imageHeaders = page?.let { repo.getImageRequestHeaders(url, it) }
			val baseRequest = PageLoader.createPageRequest(url, source, imageHeaders)
			val request = if (existingSize > 0L) {
				baseRequest.newBuilder().header("Range", "bytes=$existingSize-").build()
			} else {
				baseRequest
			}
			imageProxyInterceptor.interceptPageRequest(request, okHttp)
		}

		if (existingSize > 0L && response.code == HTTP_RANGE_NOT_SATISFIABLE && partialFile != null) {
			response.closeQuietly()
			partialFile.delete()
			return downloadFile(url, destination, repo, page, resumeKey)
		}

		return response.ensureSuccess().use { r ->
			val body = r.body
			val file = partialFile ?: destination.createTempFile(
				ext = MimeTypes.getExtension(body.contentType()?.toMimeType()),
			)
			try {
				val append = existingSize > 0L && r.code == HTTP_PARTIAL_CONTENT
				file.sink(append = append).buffer().use { it.writeAllCancellable(body.source()) }
			} catch (e: Exception) {
				if (partialFile == null) file.delete()
				throw e
			}
			if (readyFile != null) finalizeResumeFile(file, readyFile) else file
		}
	}

	private suspend fun finalizeResumeFile(partial: File, ready: File): File = runInterruptible(Dispatchers.IO) {
		ready.delete()
		if (!partial.renameTo(ready)) {
			partial.copyTo(ready, overwrite = true)
			partial.delete()
		}
		ready.setLastModified(System.currentTimeMillis())
		ready
	}

	private fun buildResumeKey(pageIndex: Int, page: MangaPage): String = "$pageIndex-${page.id}"

	private fun getResumeChapterDir(mangaId: Long, chapterId: Long): File =
		File(getResumeMangaDir(mangaId), chapterId.toString()).also { dir ->
			check(dir.exists() || dir.mkdirs()) { "Cannot create resume directory $dir" }
		}

	private fun getResumeMangaDir(mangaId: Long): File = File(
		File(applicationContext.cacheDir, RESUME_CACHE_DIR),
		mangaId.toString(),
	)

	private suspend fun clearResumeChapterDir(mangaId: Long, chapterId: Long) = runInterruptible(Dispatchers.IO) {
		File(getResumeMangaDir(mangaId), chapterId.toString()).deleteRecursively()
	}

	private suspend fun clearResumeMangaDir(mangaId: Long) = runInterruptible(Dispatchers.IO) {
		getResumeMangaDir(mangaId).deleteRecursively()
	}

	private suspend fun pruneResumeCache() = runInterruptible(Dispatchers.IO) {
		val root = File(applicationContext.cacheDir, RESUME_CACHE_DIR)
		if (!root.isDirectory) return@runInterruptible
		val cutoff = System.currentTimeMillis() - RESUME_CACHE_TTL
		root.walkBottomUp().forEach { file ->
			when {
				file.isFile && file.lastModified() < cutoff -> file.delete()
				file.isDirectory && file != root && file.list()?.isEmpty() == true -> file.delete()
			}
		}
	}

	private fun File.createTempFile(ext: String?) = File(
		this,
		buildString {
			append(UUID.randomUUID().toString())
			if (!ext.isNullOrEmpty()) {
				append('.')
				append(ext)
			}
			append(".tmp")
		},
	)

	private suspend fun refreshNotificationForPrivacy() = statePublishMutex.withLock {
		val state = lastPublishedState ?: return@withLock
		val notification = notificationFactory.create(state)
		if (state.isFinalState) {
			if (!notificationFactory.isSilent) {
				notificationManager.notify(id.toString(), id.hashCode(), notification)
			}
		} else {
			notificationManager.notify(id.hashCode(), notification)
		}
	}

	private suspend fun publishState(state: DownloadState) = statePublishMutex.withLock {
		val previousState = currentState
		lastPublishedState = state
		if (previousState.isParticularProgress && state.isParticularProgress) {
			etaEstimator.onProgressChanged(state.progress, state.max)
		} else {
			etaEstimator.reset()
			notificationThrottler.reset()
		}
		val notification = notificationFactory.create(state)
		if (state.isFinalState) {
			if (!notificationFactory.isSilent) {
				notificationManager.notify(id.toString(), id.hashCode(), notification)
			}
		} else if (notificationThrottler.throttle()) {
			notificationManager.notify(id.hashCode(), notification)
		}
		setProgress(state.toWorkData())
	}

	private suspend fun getDoneChapters(manga: Manga) = runCatchingCancellable {
		localMangaRepository.getDetails(manga).chapters?.ids()
	}.getOrNull().orEmpty()

	private fun getChapters(manga: Manga, task: DownloadTask): List<IndexedValue<MangaChapter>> {
		val chapters = checkNotNull(manga.chapters) { "Chapters list must not be null" }
		val chaptersIdsSet = task.chaptersIds?.toMutableSet()
		val result = ArrayList<IndexedValue<MangaChapter>>((chaptersIdsSet ?: chapters).size)
		val counters = HashMap<String?, Int>()
		for (chapter in chapters) {
			val index = counters[chapter.branch] ?: 0
			counters[chapter.branch] = index + 1
			if (chaptersIdsSet != null && !chaptersIdsSet.remove(chapter.id)) continue
			result.add(IndexedValue(index, chapter))
		}
		if (chaptersIdsSet != null) {
			check(chaptersIdsSet.isEmpty()) {
				"${chaptersIdsSet.size} of ${task.chaptersIds.size} requested chapters not found in manga"
			}
		}
		check(result.isNotEmpty()) { "Chapters list must not be empty" }
		return result
	}

	@Reusable
	class Scheduler @Inject constructor(
		@ApplicationContext private val context: Context,
		private val mangaDataRepository: MangaDataRepository,
		private val workManager: WorkManager,
	) {

		fun observeWorks(): Flow<List<WorkInfo>> = workManager.getWorkInfosByTagFlow(TAG)

		@SuppressLint("RestrictedApi")
		suspend fun getInputData(id: UUID): Data? {
			val spec = workManager.getWorkSpec(id) ?: return null
			return Data.Builder()
				.putAll(spec.input)
				.putLong(DownloadState.DATA_TIMESTAMP, spec.scheduleRequestedAt)
				.build()
		}

		suspend fun getTask(workId: UUID): DownloadTask? =
			workManager.getWorkInputData(workId)?.let { DownloadTask(it) }

		suspend fun cancel(id: UUID) {
			DownloadPauseStore.clear(context, id)
			workManager.cancelWorkById(id)
		}

		suspend fun cancelAll() {
			DownloadPauseStore.clearAll(context)
			workManager.cancelAllWorkByTag(TAG)
		}

		fun pause(id: UUID) {
			DownloadPauseStore.setPaused(context, id, true)
			context.sendBroadcast(PausingReceiver.getPauseIntent(context, id))
		}

		fun resume(id: UUID) {
			DownloadPauseStore.setPaused(context, id, false)
			context.sendBroadcast(PausingReceiver.getResumeIntent(context, id))
		}

		fun skip(id: UUID) = context.sendBroadcast(PausingReceiver.getSkipIntent(context, id))

		fun skipAll(id: UUID) = context.sendBroadcast(PausingReceiver.getSkipAllIntent(context, id))

		suspend fun delete(id: UUID) {
			DownloadPauseStore.clear(context, id)
			workManager.deleteWork(id)
		}

		suspend fun delete(ids: Collection<UUID>) {
			val wm = workManager
			val cancellationOperations = ids.map { id ->
				DownloadPauseStore.clear(context, id)
				wm.cancelWorkById(id)
			}
			for (operation in cancellationOperations) operation.await()
			wm.deleteWorks(ids)
		}

		suspend fun removeCompleted() {
			val finishedWorks = workManager.awaitFinishedWorkInfosByTag(TAG)
			finishedWorks.forEach { DownloadPauseStore.clear(context, it.id) }
			workManager.deleteWorks(finishedWorks.mapToSet { it.id })
		}

		suspend fun updateConstraints(allowMeteredNetwork: Boolean) {
			val constraints = createConstraints(allowMeteredNetwork)
			val works = workManager.awaitWorkInfosByTag(TAG)
			for (work in works) {
				if (work.state.isFinished) continue
				val inputData = workManager.getWorkInputData(work.id) ?: continue
				val request = OneTimeWorkRequestBuilder<DownloadWorker>()
					.setConstraints(constraints)
					.addTag(TAG)
					.keepResultsForAtLeast(30, TimeUnit.DAYS)
					.setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.SECONDS)
					.setInputData(inputData)
					.setId(work.id)
					.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
					.build()
				workManager.awaitUpdateWork(request)
			}
		}

		suspend fun schedule(tasks: Collection<Pair<Manga, DownloadTask>>) {
			if (tasks.isEmpty()) return
			val requests = tasks.map { (manga, task) ->
				mangaDataRepository.storeManga(manga, replaceExisting = true)
				OneTimeWorkRequestBuilder<DownloadWorker>()
					.setConstraints(createConstraints(task.allowMeteredNetwork))
					.addTag(TAG)
					.keepResultsForAtLeast(30, TimeUnit.DAYS)
					.setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.SECONDS)
					.setInputData(task.toData())
					.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
					.build()
			}
			workManager.enqueue(requests).await()
		}

		private fun createConstraints(allowMeteredNetwork: Boolean) = Constraints.Builder()
			.setRequiredNetworkType(if (allowMeteredNetwork) NetworkType.CONNECTED else NetworkType.UNMETERED)
			.build()
	}

	private data class DownloadedPage(
		val url: String,
		val file: File,
		val type: MimeType?,
	)

	private companion object {
		const val MAX_FAILSAFE_RETRIES = 3
		const val MAX_BACKOFF_SHIFT = 2
		const val DOWNLOAD_ERROR_DELAY = 2_000L
		const val MAX_RETRY_DELAY = 7_200_000L
		const val HTTP_PARTIAL_CONTENT = 206
		const val HTTP_RANGE_NOT_SATISFIABLE = 416
		const val RESUME_CACHE_DIR = "download-resume"
		const val RESUME_CACHE_TTL = 7L * 24L * 60L * 60L * 1_000L
		const val TAG = "download"
	}
}
