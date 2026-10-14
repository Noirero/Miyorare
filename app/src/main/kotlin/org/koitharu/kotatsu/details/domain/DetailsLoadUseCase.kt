package org.koitharu.kotatsu.details.domain

import android.text.Html
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import androidx.core.net.toUri
import androidx.core.text.getSpans
import androidx.core.text.parseAsHtml
import coil3.request.CachePolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runInterruptible
import org.koitharu.kotatsu.core.diagnostics.DiagnosticsSession
import org.koitharu.kotatsu.core.diagnostics.PerformanceDiagnostics
import org.koitharu.kotatsu.core.exceptions.UnsupportedSourceException
import org.koitharu.kotatsu.core.model.MangaSource as ResolveMangaSource
import org.koitharu.kotatsu.core.model.isExternalSource
import org.koitharu.kotatsu.core.model.isLocal
import org.koitharu.kotatsu.core.nav.MangaIntent
import org.koitharu.kotatsu.core.parser.CachingMangaRepository
import org.koitharu.kotatsu.core.parser.FreshMangaDetailsRepository
import org.koitharu.kotatsu.core.parser.MangaDataRepository
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.core.parser.ProgressiveMangaDetailsRepository
import org.koitharu.kotatsu.core.ui.model.MangaOverride
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.core.util.ext.sanitize
import org.koitharu.kotatsu.details.data.MangaDetails
import org.koitharu.kotatsu.explore.domain.RecoverMangaUseCase
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
import org.koitharu.kotatsu.local.data.LocalMangaRepository
import org.koitharu.kotatsu.local.domain.DownloadedMangaResolver
import org.koitharu.kotatsu.local.domain.model.LocalManga
import org.koitharu.kotatsu.local.library.LOCAL_LIBRARY_SCHEME
import org.koitharu.kotatsu.mihon.MihonExtensionManager
import org.koitharu.kotatsu.parsers.exception.NotFoundException
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.util.nullIfEmpty
import org.koitharu.kotatsu.parsers.util.recoverNotNull
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.tracker.domain.CheckNewChaptersUseCase
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

@Singleton
class DetailsLoadUseCase @Inject constructor(
	private val mangaDataRepository: MangaDataRepository,
	private val localMangaRepository: LocalMangaRepository,
	private val downloadedMangaResolver: DownloadedMangaResolver,
	private val mangaRepositoryFactory: MangaRepository.Factory,
	private val recoverUseCase: RecoverMangaUseCase,
	private val imageGetter: Html.ImageGetter,
	private val mihonExtensionManager: MihonExtensionManager,
	private val checkNewChaptersUseCase: Provider<CheckNewChaptersUseCase>,
) {

	private val inFlightRefreshes = ConcurrentHashMap<RefreshKey, CompletableDeferred<Result<Manga>>>()

	operator fun invoke(
		intent: MangaIntent,
		force: Boolean,
		favouriteSpace: FavouriteSpace? = intent.favouriteSpace?.let { FavouriteSpace.fromArgument(it) },
		preferLocalBeforeInitialSnapshot: Boolean = false,
	): Flow<MangaDetails> = flow {
		val trace = PerformanceDiagnostics.currentTrace
		val resolvedIntentManga = requireNotNull(PerformanceDiagnostics.measure("repository", "details_resolve_intent", trace) { mangaDataRepository.resolveIntent(intent, withChapters = true) }) {
			"Cannot resolve intent $intent"
		}
		val manga = if (resolvedIntentManga.url.toUri().scheme == LOCAL_LIBRARY_SCHEME) {
			resolvedIntentManga
		} else PerformanceDiagnostics.measure("repository", "details_canonical_local", trace) { downloadedMangaResolver.resolveCanonicalManga(resolvedIntentManga) }
		val override = PerformanceDiagnostics.measure("repository", "details_override", trace) { mangaDataRepository.getOverride(manga.id) }
		if (manga.isLocal) {
			loadLocal(manga, override, trace)
		} else {
			val cachedState = PerformanceDiagnostics.measure("repository", "details_cache_state", trace) { getCachedDetailsState(manga, force) }
			val fastDescription = manga.description?.parseAsHtml(withImages = false)
			val savedManga = emitRemoteInitialSnapshot(
				manga = manga,
				override = override,
				description = fastDescription,
				cachedInitialized = cachedState.initialized,
				cachedIsFresh = cachedState.fresh,
				preferLocalBeforeCached = preferLocalBeforeInitialSnapshot,
				deferLocalLookup = !preferLocalBeforeInitialSnapshot,
			) {
				PerformanceDiagnostics.measure("repository", "details_download_local", trace) { downloadedMangaResolver.findSavedManga(manga, favouriteSpace) }
			}
			loadRemote(
				manga = manga,
				override = override,
				force = force,
				initialSavedManga = savedManga,
				favouriteSpace = favouriteSpace,
				cachedIsFresh = cachedState.fresh,
				trace = trace,
				findSavedManga = {
					PerformanceDiagnostics.measure("repository", "details_download_local", trace) { downloadedMangaResolver.findSavedManga(manga, favouriteSpace) }
				},
			)
		}
	}.map { details ->
		if (mangaDataRepository.isScanlatorsMerged(details.id)) {
			details.withMergedBranches()
		} else {
			details
		}
	}.distinctUntilChanged()
		.flowOn(Dispatchers.Default)

	private suspend fun FlowCollector<MangaDetails>.loadLocal(
		manga: Manga, override: MangaOverride?, trace: DiagnosticsSession.Trace?,
	) {
		// A Local list/index item may already carry a complete chapter snapshot. Publish that durable
		// identity immediately; reparsing the filesystem is enrichment and must not hold a known chapter
		// list off-screen. Smart Local stays on its indexed repository path and is intentionally not
		// short-circuited here.
		val isSmartLocal = manga.url.toUri().scheme == LOCAL_LIBRARY_SCHEME
		val fastDescription = manga.description?.parseAsHtml(withImages = false)
		if (!isSmartLocal && !manga.chapters.isNullOrEmpty()) {
			emit(
				MangaDetails(
					manga = manga,
					localManga = null,
					override = override,
					description = fastDescription,
					isLoaded = false,
				),
			)
		}

		val localDetails = PerformanceDiagnostics.measure("repository", "details_local", trace) {
			if (isSmartLocal) {
				mangaRepositoryFactory.create(manga.source).getDetails(manga)
			} else localMangaRepository.getDetails(manga)
		}
		PerformanceDiagnostics.milestone(trace, "download_local_state_ready")
		val parsedDescription = localDetails.description?.parseAsHtml(withImages = false)
		val visibleDetails = MangaDetails(
			manga = localDetails,
			localManga = null,
			override = override,
			description = parsedDescription,
			isLoaded = true,
		)
		emit(visibleDetails)

		val richDescription = localDetails.description?.parseAsHtml(withImages = true)
		if (richDescription != parsedDescription) {
			emit(visibleDetails.copy(description = richDescription))
		}
	}

	private suspend fun FlowCollector<MangaDetails>.loadRemote(
		manga: Manga,
		override: MangaOverride?,
		force: Boolean,
		initialSavedManga: LocalManga?,
		favouriteSpace: FavouriteSpace?,
		cachedIsFresh: Boolean,
		trace: DiagnosticsSession.Trace?,
		findSavedManga: suspend () -> LocalManga?,
	) = coroutineScope {
		val localLookup = async {
			runCatchingCancellable {
				initialSavedManga ?: findSavedManga()
			}.onFailure { error ->
				error.printStackTraceDebug()
			}.getOrNull().also { PerformanceDiagnostics.milestone(trace, "download_local_state_ready") }
		}
		if (cachedIsFresh) {
			val fastDescription = manga.description?.parseAsHtml(withImages = false)
			val discoveredLocal = localLookup.await()
			if (discoveredLocal != null && initialSavedManga == null) {
				emit(MangaDetails(manga, discoveredLocal, override, fastDescription, true))
			}
			val richDescription = manga.description?.parseAsHtml(withImages = true)
			if (richDescription != fastDescription) {
				emit(MangaDetails(manga, discoveredLocal ?: initialSavedManga, override, richDescription, true))
			}
			return@coroutineScope
		}

		val observedRefreshAt = mangaDataRepository.getDetailsUpdatedAt(manga.id)
		var progressiveDescription: CharSequence? = null
		val refreshKey = RefreshKey(manga.source.name, manga.id)
		val remoteResult = singleFlightRefresh(refreshKey, trace) {
			val latestRefreshAt = mangaDataRepository.getDetailsUpdatedAt(manga.id)
			if (latestRefreshAt > 0L && latestRefreshAt != observedRefreshAt) {
				mangaDataRepository.findMangaById(manga.id, withChapters = true)?.let { return@singleFlightRefresh Result.success(it) }
			}
			val cachedBeforeFetch = mangaDataRepository.findMangaById(manga.id, withChapters = true) ?: manga
			val cachedBeforeFetchState = getCachedDetailsState(cachedBeforeFetch, force = false)
			if (!force && cachedBeforeFetchState.fresh) return@singleFlightRefresh Result.success(cachedBeforeFetch)
			val progressiveRepository = if (!force && !cachedBeforeFetchState.initialized) {
				mangaRepositoryFactory.create(cachedBeforeFetch.source) as? ProgressiveMangaDetailsRepository
			} else null
			val sourceWork = PerformanceDiagnostics.begin("source", if (manga.source.isExternalSource()) "external_details" else "native_details", trace)
			val result = try {
				if (progressiveRepository != null) {
					runCatchingCancellable {
						progressiveRepository.getDetailsProgressively(cachedBeforeFetch) { partial ->
							if (progressiveDescription == null) progressiveDescription = partial.description?.parseAsHtml(withImages = false)
							emit(MangaDetails(partial, initialSavedManga, override, progressiveDescription, false))
						}
					}
				} else getDetails(seed = cachedBeforeFetch, fresh = force || cachedBeforeFetchState.initialized)
			} catch (error: Throwable) {
				PerformanceDiagnostics.end(sourceWork, if (error is CancellationException) "cancel" else "failure")
				throw error
			}
			PerformanceDiagnostics.end(sourceWork, if (result.isSuccess) "complete" else "failure")
			if (result.isFailure) return@singleFlightRefresh result
			val remoteDetails = result.getOrThrow()
			if (!cachedBeforeFetch.chapters.isNullOrEmpty() && remoteDetails.chapters.isNullOrEmpty()) {
				return@singleFlightRefresh Result.failure(IllegalStateException("Source returned an empty chapter list for cached manga ${manga.id}"))
			}
			PerformanceDiagnostics.measure("repository", "details_store_snapshot", trace) {
				mangaDataRepository.storeManga(remoteDetails, replaceExisting = true, stripAppliedOverride = false, detailsFetched = true)
			}
			Result.success(remoteDetails)
		}

		if (remoteResult.isFailure) {
			val discoveredLocal = localLookup.await()
			val fallback = MangaDetails(
				manga = manga,
				localManga = discoveredLocal,
				override = override,
				description = (manga.description ?: discoveredLocal?.manga?.description)?.parseAsHtml(withImages = false),
				isLoaded = true,
			)
			emit(fallback)
			if (!discoveredLocal?.manga?.chapters.isNullOrEmpty()) return@coroutineScope
		}
		val remoteDetails = remoteResult.getOrThrow()
		val fastDescription = (remoteDetails.description ?: initialSavedManga?.manga?.description)?.parseAsHtml(withImages = false)
		var visibleDetails = MangaDetails(remoteDetails, initialSavedManga, override, fastDescription, true)
		emit(visibleDetails)
		val discoveredLocal = localLookup.await()
			?: PerformanceDiagnostics.measure("repository", "details_download_local_retry", trace) { downloadedMangaResolver.findSavedManga(remoteDetails, favouriteSpace) }
		if (initialSavedManga == null && discoveredLocal != null) {
			visibleDetails = MangaDetails(remoteDetails, discoveredLocal, override, fastDescription, true)
			emit(visibleDetails)
		}
		val richDescription = (remoteDetails.description ?: discoveredLocal?.manga?.description ?: initialSavedManga?.manga?.description)?.parseAsHtml(withImages = true)
		if (richDescription != fastDescription) emit(visibleDetails.copy(description = richDescription))
	}

	private suspend fun getDetails(seed: Manga, fresh: Boolean): Result<Manga> = runCatchingCancellable {
		val repository = mangaRepositoryFactory.create(seed.source)
		if (repository is FreshMangaDetailsRepository) repository.getDetails(seed, fresh) else repository.getDetails(seed)
	}.recoverNotNull { e ->
		if (e is NotFoundException) {
			val recovered = recoverUseCase(seed)
			if (recovered != null) mangaRepositoryFactory.create(recovered.source).getDetails(recovered) else null
		} else null
	}.recoverNotNull { e ->
		if (e is UnsupportedSourceException && seed.source.isExternalSource()) {
			mihonExtensionManager.ensureReady(forceRefresh = false)
			val repository = mangaRepositoryFactory.create(seed.source)
			if (repository is FreshMangaDetailsRepository) repository.getDetails(seed, fresh) else repository.getDetails(seed)
		} else null
	}.map { details -> details.copy(description = details.description?.sanitize()) }

	private suspend fun singleFlightRefresh(
		key: RefreshKey,
		trace: DiagnosticsSession.Trace?,
		block: suspend () -> Result<Manga>,
	): Result<Manga> {
		while (true) {
			currentCoroutineContext().ensureActive()
			val candidate = CompletableDeferred<Result<Manga>>()
			val existing = inFlightRefreshes.putIfAbsent(key, candidate)
			if (existing != null) return existing.await()
			try {
				val result = block()
				candidate.complete(result)
				return result
			} catch (error: Throwable) {
				candidate.completeExceptionally(error)
				throw error
			} finally {
				inFlightRefreshes.remove(key, candidate)
			}
		}
	}

	private suspend fun getCachedDetailsState(manga: Manga, force: Boolean): CachedDetailsState {
		if (force) return CachedDetailsState(initialized = false, fresh = false)
		val repository = mangaRepositoryFactory.create(manga.source)
		val updatedAt = mangaDataRepository.getDetailsUpdatedAt(manga.id)
		if (updatedAt <= 0L) return CachedDetailsState(initialized = false, fresh = false)
		val fresh = repository is CachingMangaRepository && updatedAt + repository.cacheTime > System.currentTimeMillis()
		return CachedDetailsState(initialized = true, fresh = fresh)
	}

	private data class CachedDetailsState(val initialized: Boolean, val fresh: Boolean)
	private data class RefreshKey(val sourceName: String, val mangaId: Long)
}

internal suspend fun FlowCollector<MangaDetails>.emitRemoteInitialSnapshot(
	manga: Manga,
	override: MangaOverride?,
	description: CharSequence?,
	cachedInitialized: Boolean = !manga.chapters.isNullOrEmpty(),
	cachedIsFresh: Boolean,
	preferLocalBeforeCached: Boolean = false,
	deferLocalLookup: Boolean = false,
	findSavedManga: suspend () -> LocalManga?,
): LocalManga? {
	if (!preferLocalBeforeCached && (cachedInitialized || deferLocalLookup)) {
		emit(MangaDetails(manga, null, override, description, cachedIsFresh))
		return null
	}
	val savedManga = runCatchingCancellable { findSavedManga() }.onFailure { it.printStackTraceDebug() }.getOrNull()
	emit(MangaDetails(manga, savedManga, override, description, cachedIsFresh))
	return savedManga
}
