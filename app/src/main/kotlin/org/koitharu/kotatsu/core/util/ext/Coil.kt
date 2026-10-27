package org.koitharu.kotatsu.core.util.ext

import android.graphics.drawable.Drawable
import androidx.annotation.CheckResult
import coil3.Extras
import coil3.ImageLoader
import coil3.asDrawable
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.SourceFetchResult
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.ImageResult
import coil3.request.Options
import coil3.request.SuccessResult
import coil3.request.bitmapConfig
import coil3.toBitmap
import okio.buffer
import org.koitharu.kotatsu.bookmarks.domain.Bookmark
import org.koitharu.kotatsu.core.image.RegionBitmapDecoder
import org.koitharu.kotatsu.core.image.StoredMangaCoverRequest
import org.koitharu.kotatsu.core.image.storedMangaCoverRequestKey
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaSource

fun ImageRequest.Builder.enqueueWith(loader: ImageLoader) = loader.enqueue(build())

fun ImageResult.getDrawableOrThrow() = when (this) {
	is SuccessResult -> image.asDrawable(request.context.resources)
	is ErrorResult -> throw throwable
}

val ImageResult.drawable: Drawable?
	get() = image?.asDrawable(request.context.resources)

fun ImageResult.toBitmapOrNull() = when (this) {
	is SuccessResult -> try {
		image.toBitmap(image.width, image.height, request.bitmapConfig)
	} catch (_: Throwable) {
		null
	}

	is ErrorResult -> null
}

fun ImageRequest.Builder.decodeRegion(
	scroll: Int = RegionBitmapDecoder.SCROLL_UNDEFINED,
): ImageRequest.Builder = apply {
	decoderFactory(RegionBitmapDecoder.Factory)
	extras[RegionBitmapDecoder.regionScrollKey] = scroll
}

fun ImageRequest.Builder.mangaSourceExtra(source: MangaSource?): ImageRequest.Builder = apply {
	extras[mangaSourceKey] = source
}

fun ImageRequest.Builder.mangaExtra(manga: Manga?): ImageRequest.Builder = apply {
	extras[mangaKey] = manga
	mangaSourceExtra(manga?.source)
}

/**
 * Opts an original remote source cover into Miyorare's durable collection-cover pipeline.
 *
 * Browse/search/details requests leave [persist] and [forceRefresh] false, so their transport URL
 * remains a normal disposable Coil cache key. Collection surfaces set [persist] to keep a compact
 * 512px representation in app Data. Details refresh uses [forceRefresh] to update that stored copy
 * only when the manga was already durable; merely viewing a manga never makes it persistent.
 *
 * Smart Local is intentionally excluded because its expensive extraction already has a dedicated
 * persistent derived-thumbnail owner in SmartLocalCoverCache.
 */
fun ImageRequest.Builder.stableMangaCoverKey(
	manga: Manga?,
	coverUrl: String?,
	persist: Boolean = false,
	forceRefresh: Boolean = false,
): ImageRequest.Builder = apply {
	if (
		manga != null &&
		coverUrl != null &&
		isRemoteCoverUrl(coverUrl) &&
		(coverUrl == manga.coverUrl || coverUrl == manga.largeCoverUrl) &&
		(persist || forceRefresh)
	) {
		diskCacheKey(mangaCoverDiskCacheKey(manga.id))
		extras[storedMangaCoverRequestKey] = StoredMangaCoverRequest(
			mangaId = manga.id,
			sourceUrl = coverUrl,
			persist = persist,
			forceRefresh = forceRefresh,
		)
	}
}

fun mangaCoverDiskCacheKey(mangaId: Long): String = "cover:$mangaId"

fun isRemoteCoverUrl(url: String?): Boolean =
	url != null && (url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true))

fun isSmartLocalCoverUrl(url: String?): Boolean =
	url?.startsWith("smart-local://cover/", ignoreCase = true) == true

fun ImageRequest.Builder.bookmarkExtra(bookmark: Bookmark): ImageRequest.Builder = apply {
	extras[bookmarkKey] = bookmark
	mangaSourceExtra(bookmark.manga.source)
}

suspend fun ImageLoader.fetch(data: Any, options: Options): FetchResult? {
	val mappedData = components.map(data, options)
	val fetcher = components.newFetcher(mappedData, options, this)?.first
	return fetcher?.fetch()
}

val mangaKey = Extras.Key<Manga?>(null)
val bookmarkKey = Extras.Key<Bookmark?>(null)
val mangaSourceKey = Extras.Key<MangaSource?>(null)

@CheckResult
fun SourceFetchResult.copyWithNewSource(): SourceFetchResult = SourceFetchResult(
	source = ImageSource(
		source = source.fileSystem.source(source.file()).buffer(),
		fileSystem = source.fileSystem,
		metadata = source.metadata,
	),
	mimeType = mimeType,
	dataSource = dataSource,
)
