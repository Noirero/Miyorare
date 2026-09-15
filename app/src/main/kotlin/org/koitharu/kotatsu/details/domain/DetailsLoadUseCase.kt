package org.koitharu.kotatsu.details.domain

import android.text.Html
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import androidx.core.text.getSpans
import androidx.core.text.parseAsHtml
import coil3.request.CachePolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runInterruptible
import org.koitharu.kotatsu.core.model.isExternalSource
import org.koitharu.kotatsu.core.model.isLocal
import org.koitharu.kotatsu.core.model.MangaSource as ResolveMangaSource
import org.koitharu.kotatsu.core.nav.MangaIntent
import org.koitharu.kotatsu.core.parser.CachingMangaRepository
import org.koitharu.kotatsu.core.parser.MangaDataRepository
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.core.parser.ProgressiveMangaDetailsRepository
import org.koitharu.kotatsu.core.exceptions.UnsupportedSourceException
import org.koitharu.kotatsu.core.ui.model.MangaOverride
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.core.util.ext.sanitize
import org.koitharu.kotatsu.details.data.MangaDetails
import org.koitharu.kotatsu.download.domain.DownloadDestinationStore
import org.koitharu.kotatsu.explore.domain.RecoverMangaUseCase
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
import org.koitharu.kotatsu.local.data.LocalMangaRepository
import org.koitharu.kotatsu.local.data.findSavedMangaInRoot
import org.koitharu.kotatsu.local.domain.model.LocalManga
import org.koitharu.kotatsu.mihon.MihonExtensionManager
import org.koitharu.kotatsu.parsers.exception.NotFoundException
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.util.nullIfEmpty
import org.koitharu.kotatsu.parsers.util.recoverNotNull
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.tracker.domain.CheckNewChaptersUseCase
import java.io.File
import javax.inject.Inject
import javax.inject.Provider

class DetailsLoadUseCase @Inject constructor(
	private val mangaDataRepository: MangaDataRepository,
	private val localMangaRepository: LocalMangaRepository,
	private val downloadDestinationStore: DownloadDestinationStore,
	private val mangaRepositoryFactory: MangaRepository.Factory,
	private val recoverUseCase: RecoverMangaUseCase,
	private val imageGetter: Html.ImageGetter,
	private val mihonExtensionManager: MihonExtensionManager,
	private val checkNewChaptersUseCase: Provider<CheckNewChaptersUseCase>,
) {

	operator fun invoke(
		intent: MangaIntent,
		force: Boolean,
		favouriteSpace: FavouriteSpace? = intent.favouriteSpace?.let { FavouriteSpace.fromArgument(it) },
	): Flow<MangaDetails> = flow {
		val manga = requireNotNull(mangaDataRepository.resolveIntent(intent, withChapters = true)) {
			"Cannot resolve intent $intent"
		}
		val override = mangaDataRepository.getOverride(manga.id)
		if (manga.isLocal) {
			// Local is authoritative. Do not replace a filesystem-backed title with its historical
			// remote/source identity: the user must always be able to open downloaded/imported content
			// even when the original extension is missing, broken, offline, or no longer installed.
			// Also make the first collected Local snapshot chapter-complete so Details never renders an
			// avoidable empty chapter state while waiting for source enrichment.
			loadLocal(manga, override)
		} else {
			// Details/chapter loading is the critical path. Before the source request, only use the
			// deterministic/indexed download lookup. Legacy/root scanning remains available later as
			// compatibility enrichment, after a chapter snapshot is already renderable.
			val savedManga = findSavedManga(manga, favouriteSpace, preferIndexed = true)
			emit(
				MangaDetails(
					manga = manga,
					localManga = savedManga,
					override = override,
					description = manga.description?.parseAsHtml(withImages = false),
					isLoaded = false,
				),
			)
			loadRemote(manga, override, force, savedManga, favouriteSpace)
		}
	}.map { details ->
		if (mangaDataRepository.isScanlatorsMerged(details.id)) {
			details.withMergedBranches()
		} else {
			details
		}
	}.distinctUntilChanged()
		.flowOn(Dispatchers.Default)

	private suspend fun FlowCollector<MangaDetails>.loadLocal(manga: Manga, override: MangaOverride?) {
		val localDetails = localMangaRepository.getDetails(manga)
		val fastDescription = localDetails.description?.parseAsHtml(withImages = false)
		val visibleDetails = MangaDetails(
			manga = localDetails,
			localManga = null,
			override = override,
			description = fastDescription,
			isLoaded = true,
		)
		emit(visibleDetails)

		// Rich local descriptions are presentation-only. Loading them after the chapter-complete
		// snapshot keeps Local opening responsive and never introduces a dependency on the old source.
		val richDescription = localDetails.description?.parseAsHtml(withImages = true)
		if (richDescription != fastDescription) {
			emit(visibleDetails.copy(description = richDescription))
		}
	}

	private suspend fun FlowCollector<MangaDetails>.loadRemote(
		manga: Manga,
		override: MangaOverride?,
		force: Boolean,
		savedManga: LocalManga?,
		favouriteSpace: FavouriteSpace?,
	) = coroutineScope {
		if (!force && !manga.chapters.isNullOrEmpty() &&
			System.currentTimeMillis() - mangaDataRepository.getDetailsUpdatedAt(manga.id) < DETAILS_FRESHNESS_MS
		) {
			val fastDescription = manga.description?.parseAsHtml(withImages = false)
			var visibleDetails = MangaDetails(
				manga = manga,
				localManga = savedManga,
				override = override,
				description = fastDescription,
				isLoaded = true,
			)
			emit(visibleDetails)
			val discoveredLocal = if (savedManga == null) {
				findSavedManga(manga, favouriteSpace)
			} else {
				savedManga
			}
			if (savedManga == null && discoveredLocal != null) {
				visibleDetails = MangaDetails(
					manga = manga,
					localManga = discoveredLocal,
					override = override,
					description = fastDescription,
					isLoaded = true,
				)
				emit(visibleDetails)
			}
			val richDescription = manga.description?.parseAsHtml(withImages = true)
			if (richDescription != visibleDetails.description) {
				emit(
					MangaDetails(
						manga = manga,
						localManga = discoveredLocal,
						override = override,
						description = richDescription,
						isLoaded = true,
					),
				)
			}
			return@coroutineScope
		}

		val progressiveRepository = if (!force && manga.chapters.isNullOrEmpty()) {
			mangaRepositoryFactory.create(manga.source) as? ProgressiveMangaDetailsRepository
		} else {
			null
		}
		var progressiveDescription: CharSequence? = null
		val remoteResult = if (progressiveRepository != null) {
			runCatchingCancellable {
				progressiveRepository.getDetailsProgressively(manga) { partial ->
					if (progressiveDescription == null) {
						progressiveDescription = partial.description?.parseAsHtml(withImages = false)
					}
					emit(
						MangaDetails(
							manga = partial,
							localManga = savedManga,
							override = override,
							description = progressiveDescription,
							isLoaded = false,
						),
					)
				}
			}
		} else {
			async { getDetails(manga, force) }.await()
		}
		if (remoteResult.isFailure) {
			val localManga = savedManga ?: findSavedManga(manga, favouriteSpace)
			emit(
				MangaDetails(
					manga = manga,
					localManga = localManga,
					override = override,
					description = (manga.description ?: localManga?.manga?.description)?.parseAsHtml(withImages = false),
					isLoaded = true,
				),
			)
		}
		val remoteDetails = remoteResult.getOrThrow()
		val fastDescription = (remoteDetails.description ?: savedManga?.manga?.description)?.parseAsHtml(withImages = false)

		// Start persistence immediately, but do not keep the complete source snapshot hidden behind it.
		// The UI/Reader can consume fresh chapters now; we still await the write before compatibility
		// enrichment and tracker work so those downstream paths observe the persisted refresh.
		val storeDeferred = async {
			mangaDataRepository.storeManga(
				remoteDetails,
				replaceExisting = true,
				stripAppliedOverride = false,
				detailsFetched = true,
			)
		}
		var visibleDetails = MangaDetails(
			manga = remoteDetails,
			localManga = savedManga,
			override = override,
			description = fastDescription,
			isLoaded = true,
		)
		emit(visibleDetails)
		storeDeferred.await()

		val discoveredLocal = if (savedManga == null) {
			findSavedManga(remoteDetails, favouriteSpace)
		} else {
			savedManga
		}
		if (savedManga == null && discoveredLocal != null) {
			visibleDetails = MangaDetails(
				manga = remoteDetails,
				localManga = discoveredLocal,
				override = override,
				description = fastDescription,
				isLoaded = true,
			)
			emit(visibleDetails)
		}

		val richDescription = (remoteDetails.description ?: discoveredLocal?.manga?.description)?.parseAsHtml(withImages = true)
		if (richDescription != visibleDetails.description) {
			emit(
				MangaDetails(
					manga = remoteDetails,
					localManga = discoveredLocal,
					override = override,
					description = richDescription,
					isLoaded = true,
				),
			)
		}

		runCatchingCancellable {
			checkNewChaptersUseCase.get().invoke(remoteDetails)
		}.onFailure { e ->
			e.printStackTraceDebug()
		}
	}

	private suspend fun findSavedManga(
		manga: Manga,
		favouriteSpace: FavouriteSpace?,
		preferIndexed: Boolean = false,
	): LocalManga? {
		if (preferIndexed) {
			// Hot path: never scan legacy roots before the source has had a chance to return chapters.
			// findSavedMangaIndexed uses deterministic paths/local_index only and deliberately skips
			// the broad reconnect scan used by the compatibility fallback below.
			val indexed = localMangaRepository.findSavedMangaIndexed(manga) ?: return null
			if (favouriteSpace == FavouriteSpace.PRIVATE) {
				val inPrivate = downloadDestinationStore.readableRoots(FavouriteSpace.PRIVATE)
					.any { indexed.file.isInside(it) }
				if (!inPrivate) return null
			}
			if (favouriteSpace == FavouriteSpace.NORMAL && downloadDestinationStore.privateUsesOwnRoot()) {
				val inNormal = downloadDestinationStore.readableRoots(FavouriteSpace.NORMAL)
					.any { indexed.file.isInside(it) }
				val inPrivate = downloadDestinationStore.readableRoots(FavouriteSpace.PRIVATE)
					.any { indexed.file.isInside(it) }
				if (inPrivate && !inNormal) return null
			}
			return indexed
		}

		if (favouriteSpace != null) {
			for (root in downloadDestinationStore.readableRoots(favouriteSpace)) {
				localMangaRepository.findSavedMangaInRoot(manga, root, withDetails = true)?.let { return it }
			}
			if (favouriteSpace == FavouriteSpace.PRIVATE) {
				// A scoped Private screen must never reuse a Normal/global copy just because that copy is
				// the one currently represented by local_index.
				return null
			}
		}

		val fallback = localMangaRepository.findSavedManga(manga, withDetails = true) ?: return null

		if (favouriteSpace == FavouriteSpace.NORMAL && downloadDestinationStore.privateUsesOwnRoot()) {
			val inNormal = downloadDestinationStore.readableRoots(FavouriteSpace.NORMAL).any { fallback.file.isInside(it) }
			val inPrivate = downloadDestinationStore.readableRoots(FavouriteSpace.PRIVATE).any { fallback.file.isInside(it) }
			if (inPrivate && !inNormal) {
				return null
			}
		}
		return fallback
	}

	private fun File.isInside(root: File): Boolean {
		val rootPath = runCatching { root.canonicalFile }.getOrDefault(root.absoluteFile).path.trimEnd(File.separatorChar)
		val filePath = runCatching { canonicalFile }.getOrDefault(absoluteFile).path
		return filePath == rootPath || filePath.startsWith(rootPath + File.separator)
	}

	private suspend fun getDetails(seed: Manga, force: Boolean) = runCatchingCancellable {
		loadDetails(seed, force, refreshExtensions = false)
	}.recoverCatching { error ->
		if (error is UnsupportedSourceException && seed.source.isExternalSource()) {
			loadDetails(seed, force, refreshExtensions = true)
		} else {
			throw error
		}
	}.recoverNotNull { e ->
		if (e is NotFoundException) recoverUseCase(seed) else null
	}

	private suspend fun loadDetails(seed: Manga, force: Boolean, refreshExtensions: Boolean): Manga {
		val resolvedSeed = if (seed.source.name.startsWith("MIHON_")) {
			// Opening a title must not rescan every installed extension just because the Parcelable/DB
			// seed was reconstructed as a MissingMangaSource. ensureReady() resolves the normal startup
			// race; a full refresh is reserved for the retry after UnsupportedSourceException.
			mihonExtensionManager.ensureReady(forceRefresh = refreshExtensions)
			val resolvedSource = ResolveMangaSource(seed.source.name)
			seed.copy(source = resolvedSource)
		} else {
			seed
		}
		val repository = mangaRepositoryFactory.create(resolvedSeed.source)
		return if (repository is CachingMangaRepository) {
			repository.getDetails(
				resolvedSeed,
				if (force) CachePolicy.WRITE_ONLY else CachePolicy.ENABLED,
			)
		} else {
			repository.getDetails(resolvedSeed)
		}
	}

	private suspend fun String.parseAsHtml(withImages: Boolean): CharSequence? {
		val html = if (contains("<br", ignoreCase = true) || contains("<p", ignoreCase = true)) {
			this
		} else {
			replace("\n", "<br>")
		}
		return if (withImages) {
			runInterruptible(Dispatchers.IO) {
				html.parseAsHtml(imageGetter = imageGetter)
			}.filterSpans()
		} else {
			runInterruptible(Dispatchers.Default) {
				html.parseAsHtml()
			}.filterSpans().sanitize()
		}.trim().nullIfEmpty()
	}

	private companion object {
		val DETAILS_FRESHNESS_MS = java.util.concurrent.TimeUnit.HOURS.toMillis(12)
	}

	private fun Spanned.filterSpans(): Spanned {
		val spannable = SpannableString.valueOf(this)
		val spans = spannable.getSpans<ForegroundColorSpan>()
		for (span in spans) {
			spannable.removeSpan(span)
		}
		return spannable
	}
}
