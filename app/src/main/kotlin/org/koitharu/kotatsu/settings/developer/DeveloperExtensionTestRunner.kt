package org.koitharu.kotatsu.settings.developer

import android.content.Context
import coil3.ImageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.size.Size
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.kanade.tachiyomi.network.HttpException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.exceptions.CloudFlareException
import org.koitharu.kotatsu.core.exceptions.InteractiveActionRequiredException
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.util.ext.getDrawableOrThrow
import org.koitharu.kotatsu.core.util.ext.mangaExtra
import org.koitharu.kotatsu.mihon.MihonExtensionManager
import org.koitharu.kotatsu.mihon.MihonFilterHost
import org.koitharu.kotatsu.mihon.model.MihonMangaSource
import org.koitharu.kotatsu.mihon.resolveActiveMihonLanguage
import org.koitharu.kotatsu.parsers.exception.AuthRequiredException
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.settings.sources.catalog.extensionDisplayName
import org.jsoup.HttpStatusException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import javax.inject.Inject
import java.util.Locale
import kotlin.random.Random

data class ExtensionTestCandidate<T>(
	val packageName: String,
	val extensionName: String,
	val sources: List<T>,
)

data class SelectedExtensionTest<T>(
	val packageName: String,
	val extensionName: String,
	val source: T?,
)

fun <T> selectOneSourcePerExtension(
	candidates: List<ExtensionTestCandidate<T>>,
	random: Random,
): List<SelectedExtensionTest<T>> = candidates.map { candidate ->
	SelectedExtensionTest(
		packageName = candidate.packageName,
		extensionName = candidate.extensionName,
		source = candidate.sources.randomOrNull(random),
	)
}

internal fun <T> selectLanguageVariant(
	variants: List<T>,
	language: String?,
	languageOf: (T) -> String,
): T? = variants.firstOrNull { languageOf(it) == language } ?: variants.firstOrNull()

internal fun searchQueryForTitle(title: String): String {
	val words = Regex("[\\p{L}\\p{N}]+").findAll(title).map { it.value }.toList()
	return words.firstOrNull { it.length >= 3 && it.lowercase() !in SEARCH_STOP_WORDS }
		?: words.firstOrNull()
		?: title.trim()
}

internal fun isBlockedTestFailure(error: Throwable): Boolean {
	return generateSequence(error) { it.cause }.any { cause ->
		cause is CloudFlareException ||
			cause is InteractiveActionRequiredException ||
			cause is AuthRequiredException ||
			cause is UnknownHostException ||
			cause is SocketTimeoutException ||
			cause is ConnectException ||
			cause is NoRouteToHostException ||
			cause is SocketException ||
			cause is SSLException ||
			cause is HttpException && cause.code.isAvailabilityStatus() ||
			cause is HttpStatusException && cause.statusCode.isAvailabilityStatus()
	}
}

private fun Int.isAvailabilityStatus(): Boolean = this == 401 || this == 403 || this == 408 || this == 429 || this >= 500

private val SEARCH_STOP_WORDS = setOf("the", "and", "for", "official")

class DeveloperExtensionTestRunner @Inject constructor(
	@ApplicationContext private val context: Context,
	private val extensionManager: MihonExtensionManager,
	private val repositoryFactory: MangaRepository.Factory,
	private val settings: AppSettings,
	private val imageLoader: ImageLoader,
) {

	/** The extensions available for testing, one randomly picked catalogue source each. */
	suspend fun prepareTargets(): List<SelectedExtensionTest<MihonMangaSource>> {
		extensionManager.ensureReady()
		return selectOneSourcePerExtension(
			candidates = extensionManager.installedExtensions.value.map { extension ->
				ExtensionTestCandidate(
					packageName = extension.pkgName,
					extensionName = extensionDisplayName(extension.appName),
					sources = selectActiveLanguageSources(extension.catalogueSources.mapNotNull { source ->
						extensionManager.getMihonMangaSourceById(source.id)
					}),
				)
			},
			random = Random.Default,
		).sortedBy { it.extensionName.lowercase() }
	}

	fun pendingResultOf(target: SelectedExtensionTest<MihonMangaSource>) = pendingResult(target)

	/** Runs the full suite for a single extension — the per-row test button. */
	suspend fun test(target: SelectedExtensionTest<MihonMangaSource>) = testExtension(target)

	suspend fun run(
		targets: List<SelectedExtensionTest<MihonMangaSource>>,
		onStarted: suspend (packageName: String) -> Unit,
		onResult: suspend (result: DeveloperExtensionTestResult) -> Unit,
	): List<DeveloperExtensionTestResult> {
		val semaphore = Semaphore(MAX_CONCURRENT_EXTENSIONS)
		val callbackMutex = Mutex()
		return coroutineScope {
			targets.map { target ->
				async {
					semaphore.withPermit {
						callbackMutex.withLock { onStarted(target.packageName) }
						val result = testExtension(target)
						callbackMutex.withLock { onResult(result) }
						result
					}
				}
			}.awaitAll()
		}
	}

	private fun selectActiveLanguageSources(sources: List<MihonMangaSource>): List<MihonMangaSource> {
		return sources.groupBy { it.catalogueSource.name }.values.mapNotNull { variants ->
			val first = variants.first()
			val stored = settings.getMihonActiveLang(first.pkgName, first.catalogueSource.name)
			val language = resolveActiveMihonLanguage(
				availableLangs = variants.map { it.language },
				storedLang = stored,
				appLang = Locale.getDefault().language,
			)
			selectLanguageVariant(variants, language) { it.language }
		}
	}

	private fun pendingResult(target: SelectedExtensionTest<MihonMangaSource>): DeveloperExtensionTestResult {
		val source = target.source
		return DeveloperExtensionTestResult(
			packageName = target.packageName,
			extensionName = target.extensionName,
			sourceName = source?.displayName ?: context.getString(R.string.developer_no_catalogue_source),
			language = source?.languageDisplayName.orEmpty(),
			stages = emptyList(),
			durationMillis = 0,
			state = DeveloperExtensionStatus.PENDING,
			sourceId = source?.name,
		)
	}

	fun loadingStageLabel(): String = stageLoad

	private val stageLoad: String get() = context.getString(R.string.developer_stage_extension_loading)
	private val stageList: String get() = context.getString(R.string.developer_stage_popular_listing)
	private val stageSearch: String get() = context.getString(R.string.developer_stage_search)
	private val stageDetails: String get() = context.getString(R.string.developer_stage_manga_details)
	private val stagePages: String get() = context.getString(R.string.developer_stage_chapter_pages)
	private val stageImage: String get() = context.getString(R.string.developer_stage_page_image)
	private val stageCover: String get() = context.getString(R.string.developer_stage_cover_image)
	private val stageLatest: String get() = context.getString(R.string.developer_stage_latest_listing)
	private val stagePagination: String get() = context.getString(R.string.developer_stage_pagination)
	private val stageFilters: String get() = context.getString(R.string.developer_stage_filters)
	private val stageRelated: String get() = context.getString(R.string.developer_stage_related_manga)

	private suspend fun testExtension(
		target: SelectedExtensionTest<MihonMangaSource>,
	): DeveloperExtensionTestResult {
		val started = System.nanoTime()
		val source = target.source ?: return DeveloperExtensionTestResult(
			packageName = target.packageName,
			extensionName = target.extensionName,
			sourceName = context.getString(R.string.developer_no_catalogue_source),
			language = "",
			stages = listOf(failedStage(stageLoad, context.getString(R.string.developer_extension_no_catalogue_source))),
			durationMillis = elapsedMillis(started),
		)
		val repository = repositoryFactory.create(source)
		val stages = mutableListOf<DeveloperTestStageResult>()
		stages += passedStage(stageLoad, "${source.displayName} (${source.languageDisplayName})")

		val listing = requiredStage(stages, stageList) {
			loadPopularListing(repository)
		} ?: return result(target, source, stages, started)
		val details = requiredStage(stages, stageDetails) {
			findUsableManga(repository, listing)
		} ?: return result(target, source, stages, started)

		requiredStage(stages, stageSearch) {
			repository.getList(
				offset = 0,
				order = SortOrder.RELEVANCE,
				filter = MangaListFilter(query = searchQueryForTitle(details.title)),
			)
		}

		val pages = requiredStage(stages, stagePages) {
			findUsablePages(repository, details.chapters.orEmpty())
		} ?: return result(target, source, stages, started)

		requiredStage(stages, stageImage) {
			validateAnyPageImage(pages)
		}

		optionalStage(
			stages,
			stageCover,
			listOfNotNull(details.largeCoverUrl, details.coverUrl).firstOrNull { it.isNotBlank() },
		) { coverUrl ->
			validateCoverImage(details, coverUrl)
		}

		optionalStage(stages, stageLatest, source.takeIf { it.supportsLatest }) {
			repository.getList(0, SortOrder.UPDATED, null)
		}

		requiredStage(stages, stagePagination) {
			repository.getList(listing.size, SortOrder.POPULARITY, null)
		}

		requiredStage(stages, stageFilters) {
			(repository as? MihonFilterHost)?.loadDefaultFilterList()
				?: error(context.getString(R.string.developer_filter_list_unavailable))
		}

		requiredStage(stages, stageRelated) {
			repository.getRelated(details)
		}

		return result(target, source, stages, started)
	}

	private suspend fun loadPopularListing(repository: MangaRepository): List<Manga> {
		var lastFailure: Throwable? = null
		repeat(MAX_LISTING_ATTEMPTS) { attempt ->
			try {
				val listing = repository.getList(0, SortOrder.POPULARITY, null)
				if (listing.isNotEmpty()) return listing
				lastFailure = IllegalStateException(context.getString(R.string.developer_popular_empty))
			} catch (e: Throwable) {
				if (e is CancellationException) throw e
				lastFailure = e
			}
			if (attempt < MAX_LISTING_ATTEMPTS - 1) {
				delay(LISTING_RETRY_DELAY_MILLIS * (attempt + 1))
			}
		}
		throw lastFailure ?: IllegalStateException(context.getString(R.string.developer_popular_failed))
	}

	private suspend fun findUsableManga(repository: MangaRepository, listing: List<Manga>): Manga {
		var lastFailure: Throwable? = null
		for (candidate in listing.shuffled().take(MAX_ENTITY_ATTEMPTS)) {
			try {
				val details = repository.getDetails(candidate)
				if (details.chapters?.isNotEmpty() == true) return details
				lastFailure = IllegalStateException(context.getString(R.string.developer_details_no_chapters))
			} catch (e: Throwable) {
				if (e is CancellationException || isBlockedTestFailure(e)) throw e
				lastFailure = e
			}
		}
		throw lastFailure ?: IllegalStateException(context.getString(R.string.developer_no_usable_manga))
	}

	private suspend fun findUsablePages(
		repository: MangaRepository,
		chapters: List<MangaChapter>,
	): List<MangaPage> {
		var lastFailure: Throwable? = null
		for (chapter in chapters.asReversed().take(MAX_CHAPTER_ATTEMPTS)) {
			try {
				val pages = repository.getPages(chapter)
				if (pages.isNotEmpty()) return pages
				lastFailure = IllegalStateException(context.getString(R.string.developer_chapter_no_pages))
			} catch (e: Throwable) {
				if (e is CancellationException || isBlockedTestFailure(e)) throw e
				lastFailure = e
			}
		}
		throw lastFailure ?: IllegalStateException(context.getString(R.string.developer_details_no_chapters))
	}

	private suspend fun validateAnyPageImage(pages: List<MangaPage>) {
		var lastFailure: Throwable? = null
		for (page in pages.shuffled().take(MAX_PAGE_ATTEMPTS)) {
			try {
				validatePageImage(page)
				return
			} catch (e: Throwable) {
				if (e is CancellationException || isBlockedTestFailure(e)) throw e
				lastFailure = e
			}
		}
		throw lastFailure ?: IllegalStateException(context.getString(R.string.developer_chapter_no_pages))
	}

	private suspend fun validatePageImage(page: MangaPage) {
		imageLoader.execute(
			ImageRequest.Builder(context)
				.data(page)
				.size(Size(96, 96))
				.memoryCachePolicy(CachePolicy.DISABLED)
				.diskCachePolicy(CachePolicy.DISABLED)
				.build(),
		).getDrawableOrThrow()
	}

	private suspend fun validateCoverImage(manga: Manga, coverUrl: String) {
		imageLoader.execute(
			ImageRequest.Builder(context)
				.data(coverUrl)
				.mangaExtra(manga)
				.size(Size(96, 144))
				.memoryCachePolicy(CachePolicy.DISABLED)
				.diskCachePolicy(CachePolicy.DISABLED)
				.build(),
		).getDrawableOrThrow()
	}

	private suspend fun <T> requiredStage(
		stages: MutableList<DeveloperTestStageResult>,
		name: String,
		block: suspend () -> T,
	): T? {
		val outcome = executeStage(name, block)
		stages += outcome.result
		return outcome.value
	}

	private suspend fun <T : Any> optionalStage(
		stages: MutableList<DeveloperTestStageResult>,
		name: String,
		input: T?,
		block: suspend (T) -> Unit,
	) {
		if (input == null) {
			stages += skippedStage(name, context.getString(R.string.developer_not_supported))
			return
		}
		requiredStage(stages, name) { block(input) }
	}

	private suspend fun <T> executeStage(name: String, block: suspend () -> T): StageOutcome<T> {
		val started = System.nanoTime()
		return try {
			val value = withTimeout(STAGE_TIMEOUT_MILLIS) { block() }
			StageOutcome(value, passedStage(name, durationMillis = elapsedMillis(started)))
		} catch (_: TimeoutCancellationException) {
			StageOutcome(
				value = null,
				result = DeveloperTestStageResult(
					name = name,
					status = DeveloperTestStageStatus.FAILED,
					message = context.getString(R.string.developer_timeout_seconds, STAGE_TIMEOUT_MILLIS / 1_000),
					durationMillis = elapsedMillis(started),
				),
			)
		} catch (e: CancellationException) {
			throw e
		} catch (e: Throwable) {
			val status = if (isBlockedTestFailure(e)) {
				DeveloperTestStageStatus.BLOCKED
			} else {
				DeveloperTestStageStatus.FAILED
			}
			StageOutcome(
				value = null,
				result = DeveloperTestStageResult(
					name = name,
					status = status,
					message = e.message?.take(MAX_ERROR_LENGTH) ?: e.javaClass.simpleName,
					durationMillis = elapsedMillis(started),
				),
			)
		}
	}

	private fun result(
		target: SelectedExtensionTest<MihonMangaSource>,
		source: MihonMangaSource,
		stages: List<DeveloperTestStageResult>,
		started: Long,
	) = DeveloperExtensionTestResult(
		packageName = target.packageName,
		extensionName = target.extensionName,
		sourceName = source.displayName,
		language = source.languageDisplayName,
		stages = stages,
		durationMillis = elapsedMillis(started),
		sourceId = source.name,
	)

	private data class StageOutcome<T>(
		val value: T?,
		val result: DeveloperTestStageResult,
	)

	private companion object {
		const val MAX_CONCURRENT_EXTENSIONS = 3
		const val MAX_LISTING_ATTEMPTS = 3
		const val MAX_ENTITY_ATTEMPTS = 3
		const val MAX_CHAPTER_ATTEMPTS = 3
		const val MAX_PAGE_ATTEMPTS = 3
		const val LISTING_RETRY_DELAY_MILLIS = 750L
		const val STAGE_TIMEOUT_MILLIS = 60_000L
		const val MAX_ERROR_LENGTH = 240
	}
}

private fun elapsedMillis(startedNanos: Long): Long = (System.nanoTime() - startedNanos) / 1_000_000

private fun passedStage(
	name: String,
	message: String? = null,
	durationMillis: Long = 0,
) = DeveloperTestStageResult(name, DeveloperTestStageStatus.PASSED, message, durationMillis)

private fun failedStage(name: String, message: String) =
	DeveloperTestStageResult(name, DeveloperTestStageStatus.FAILED, message, 0)

private fun skippedStage(name: String, message: String) =
	DeveloperTestStageResult(name, DeveloperTestStageStatus.SKIPPED, message, 0)
