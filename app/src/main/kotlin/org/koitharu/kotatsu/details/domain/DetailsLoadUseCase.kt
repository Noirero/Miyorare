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

	operator fun invoke(intent: MangaIntent, force: Boolean, favouriteSpace: FavouriteSpace? = intent.favouriteSpace?.let { FavouriteSpace.fromArgument(it) }, preferLocalBeforeInitialSnapshot: Boolean = false): Flow<MangaDetails> = flow {
		val resolvedIntentManga = requireNotNull(mangaDataRepository.resolveIntent(intent, withChapters = true)) { "Cannot resolve intent $intent" }
		val manga = if (resolvedIntentManga.url.toUri().scheme == LOCAL_LIBRARY_SCHEME) resolvedIntentManga else downloadedMangaResolver.resolveCanonicalManga(resolvedIntentManga)
		val override = mangaDataRepository.getOverride(manga.id)
		if (manga.isLocal) loadLocal(manga, override) else {
			val cachedState = getCachedDetailsState(manga, force)
			val fastDescription = manga.description?.parseAsHtml(withImages = false)
			val savedManga = emitRemoteInitialSnapshot(manga, override, fastDescription, cachedState.initialized, cachedState.fresh, preferLocalBeforeInitialSnapshot, !preferLocalBeforeInitialSnapshot) { downloadedMangaResolver.findSavedManga(manga, favouriteSpace) }
			loadRemote(manga, override, force, savedManga, favouriteSpace, cachedState.fresh) { downloadedMangaResolver.findSavedManga(manga, favouriteSpace) }
		}
	}.map { details -> if (mangaDataRepository.isScanlatorsMerged(details.id)) details.withMergedBranches() else details }.distinctUntilChanged().flowOn(Dispatchers.Default)

	private suspend fun FlowCollector<MangaDetails>.loadLocal(manga: Manga, override: MangaOverride?) {
		val localDetails = if (manga.url.toUri().scheme == LOCAL_LIBRARY_SCHEME) mangaRepositoryFactory.create(manga.source).getDetails(manga) else localMangaRepository.getDetails(manga)
		val fastDescription = localDetails.description?.parseAsHtml(withImages = false)
		val visibleDetails = MangaDetails(manga = localDetails, localManga = null, override = override, description = fastDescription, isLoaded = true)
		emit(visibleDetails)
		val richDescription = localDetails.description?.parseAsHtml(withImages = true)
		if (richDescription != fastDescription) emit(visibleDetails.copy(description = richDescription))
	}

	private suspend fun FlowCollector<MangaDetails>.loadRemote(manga: Manga, override: MangaOverride?, force: Boolean, initialSavedManga: LocalManga?, favouriteSpace: FavouriteSpace?, cachedIsFresh: Boolean, findSavedManga: suspend () -> LocalManga?) = coroutineScope {
		val localLookup = async { runCatchingCancellable { initialSavedManga ?: findSavedManga() }.onFailure { it.printStackTraceDebug() }.getOrNull() }
		if (cachedIsFresh) {
			val fastDescription = manga.description?.parseAsHtml(withImages = false)
			val discoveredLocal = localLookup.await()
			if (discoveredLocal != null && initialSavedManga == null) emit(MangaDetails(manga = manga, localManga = discoveredLocal, override = override, description = fastDescription, isLoaded = true))
			val richDescription = manga.description?.parseAsHtml(withImages = true)
			if (richDescription != fastDescription) emit(MangaDetails(manga = manga, localManga = discoveredLocal ?: initialSavedManga, override = override, description = richDescription, isLoaded = true))
			return@coroutineScope
		}
		val observedRefreshAt = mangaDataRepository.getDetailsUpdatedAt(manga.id)
		var progressiveDescription: CharSequence? = null
		val refreshKey = RefreshKey(manga.source.name, manga.id)
		val remoteResult = singleFlightRefresh(refreshKey) {
			val latestRefreshAt = mangaDataRepository.getDetailsUpdatedAt(manga.id)
			if (latestRefreshAt > 0L && latestRefreshAt != observedRefreshAt) mangaDataRepository.findMangaById(manga.id, withChapters = true)?.let { return@singleFlightRefresh Result.success(it) }
			val cachedBeforeFetch = mangaDataRepository.findMangaById(manga.id, withChapters = true) ?: manga
			val cachedBeforeFetchState = getCachedDetailsState(cachedBeforeFetch, force = false)
			if (!force && cachedBeforeFetchState.fresh) return@singleFlightRefresh Result.success(cachedBeforeFetch)
			val progressiveRepository = if (!force && !cachedBeforeFetchState.initialized) mangaRepositoryFactory.create(cachedBeforeFetch.source) as? ProgressiveMangaDetailsRepository else null
			val result = if (progressiveRepository != null) runCatchingCancellable {
				progressiveRepository.getDetailsProgressively(cachedBeforeFetch) { partial ->
					if (progressiveDescription == null) progressiveDescription = partial.description?.parseAsHtml(withImages = false)
					emit(MangaDetails(manga = partial, localManga = initialSavedManga, override = override, description = progressiveDescription, isLoaded = false))
				}
			} else getDetails(seed = cachedBeforeFetch, fresh = force || cachedBeforeFetchState.initialized)
			if (result.isFailure) return@singleFlightRefresh result
			val remoteDetails = result.getOrThrow()
			if (!cachedBeforeFetch.chapters.isNullOrEmpty() && remoteDetails.chapters.isNullOrEmpty()) return@singleFlightRefresh Result.failure(IllegalStateException("Source returned an empty chapter list for cached manga ${manga.id}"))
			mangaDataRepository.storeManga(remoteDetails, replaceExisting = true, stripAppliedOverride = false, detailsFetched = true)
			Result.success(remoteDetails)
		}
		if (remoteResult.isFailure) {
			val discoveredLocal = localLookup.await()
			val fallback = MangaDetails(manga = manga, localManga = discoveredLocal, override = override, description = (manga.description ?: discoveredLocal?.manga?.description)?.parseAsHtml(withImages = false), isLoaded = true)
			emit(fallback)
			if (!discoveredLocal?.manga?.chapters.isNullOrEmpty()) return@coroutineScope
		}
		val remoteDetails = remoteResult.getOrThrow()
		val fastDescription = (remoteDetails.description ?: initialSavedManga?.manga?.description)?.parseAsHtml(withImages = false)
		var visibleDetails = MangaDetails(manga = remoteDetails, localManga = initialSavedManga, override = override, description = fastDescription, isLoaded = true)
		emit(visibleDetails)
		val discoveredLocal = localLookup.await() ?: downloadedMangaResolver.findSavedManga(remoteDetails, favouriteSpace)
		if (initialSavedManga == null && discoveredLocal != null) {
			visibleDetails = MangaDetails(manga = remoteDetails, localManga = discoveredLocal, override = override, description = fastDescription, isLoaded = true)
			emit(visibleDetails)
		}
		val richDescription = (remoteDetails.description ?: discoveredLocal?.manga?.description)?.parseAsHtml(withImages = true)
		if (richDescription != visibleDetails.description) emit(MangaDetails(manga = remoteDetails, localManga = discoveredLocal, override = override, description = richDescription, isLoaded = true))
		runCatchingCancellable { checkNewChaptersUseCase.get().invoke(remoteDetails) }.onFailure { it.printStackTraceDebug() }
	}

	private suspend fun singleFlightRefresh(key: RefreshKey, block: suspend () -> Result<Manga>): Result<Manga> {
		while (true) {
			val candidate = CompletableDeferred<Result<Manga>>()
			val active = inFlightRefreshes.putIfAbsent(key, candidate)
			if (active != null) {
				try { return active.await() } catch (_: CancellationException) { currentCoroutineContext().ensureActive(); continue }
			}
			return try { val result = block(); candidate.complete(result); result } catch (error: Throwable) { candidate.completeExceptionally(error); throw error } finally { inFlightRefreshes.remove(key, candidate) }
		}
	}

	private suspend fun getCachedDetailsState(manga: Manga, force: Boolean): CachedDetailsState {
		val updatedAt = mangaDataRepository.getDetailsUpdatedAt(manga.id)
		val initialized = mangaDataRepository.isChaptersInitialized(manga.id) || !manga.chapters.isNullOrEmpty()
		val fresh = !force && initialized && updatedAt > 0L && System.currentTimeMillis() - updatedAt < DETAILS_FRESHNESS_MS
		return CachedDetailsState(initialized, fresh)
	}

	private suspend fun getDetails(seed: Manga, fresh: Boolean) = runCatchingCancellable { loadDetails(seed, fresh, refreshExtensions = false) }.recoverCatching { error ->
		if (error is UnsupportedSourceException && seed.source.isExternalSource()) loadDetails(seed, fresh, refreshExtensions = true) else throw error
	}.recoverNotNull { e -> if (e is NotFoundException) recoverUseCase(seed) else null }

	private suspend fun loadDetails(seed: Manga, fresh: Boolean, refreshExtensions: Boolean): Manga {
		val resolvedSeed = if (seed.source.name.startsWith("MIHON_")) {
			mihonExtensionManager.ensureReady(forceRefresh = refreshExtensions)
			seed.copy(source = ResolveMangaSource(seed.source.name))
		} else seed
		val repository = mangaRepositoryFactory.create(resolvedSeed.source)
		return when {
			fresh && repository is FreshMangaDetailsRepository -> repository.getFreshDetails(resolvedSeed)
			repository is CachingMangaRepository -> repository.getDetails(resolvedSeed, CachePolicy.ENABLED)
			else -> repository.getDetails(resolvedSeed)
		}
	}

	private suspend fun String.parseAsHtml(withImages: Boolean): CharSequence? {
		val html = if (contains("<br", ignoreCase = true) || contains("<p", ignoreCase = true)) this else replace("\n", "<br>")
		return if (withImages) runInterruptible(Dispatchers.IO) { html.parseAsHtml(imageGetter = imageGetter) }.filterSpans() else runInterruptible(Dispatchers.Default) { html.parseAsHtml() }.filterSpans().sanitize().trim().nullIfEmpty()
	}

	private data class CachedDetailsState(val initialized: Boolean, val fresh: Boolean)
	private data class RefreshKey(val sourceName: String, val mangaId: Long)
	private companion object { val DETAILS_FRESHNESS_MS = java.util.concurrent.TimeUnit.HOURS.toMillis(12) }
	private fun Spanned.filterSpans(): Spanned {
		val spannable = SpannableString.valueOf(this)
		spannable.getSpans<ForegroundColorSpan>().forEach(spannable::removeSpan)
		return spannable
	}
}

internal suspend fun FlowCollector<MangaDetails>.emitRemoteInitialSnapshot(manga: Manga, override: MangaOverride?, description: CharSequence?, cachedInitialized: Boolean, cachedIsFresh: Boolean, preferLocalBeforeCached: Boolean = false, deferLocalLookup: Boolean = false, findSavedManga: suspend () -> LocalManga?): LocalManga? {
	if (preferLocalBeforeCached) {
		val savedManga = findSavedManga()
		emit(MangaDetails(manga = manga, localManga = savedManga, override = override, description = description, isLoaded = cachedIsFresh))
		return savedManga
	}
	if (cachedInitialized || deferLocalLookup) emit(MangaDetails(manga = manga, localManga = null, override = override, description = description, isLoaded = cachedIsFresh))
	if (deferLocalLookup) return null
	val savedManga = findSavedManga()
	if (!cachedInitialized || savedManga != null) emit(MangaDetails(manga = manga, localManga = savedManga, override = override, description = description, isLoaded = cachedIsFresh))
	return savedManga
}
