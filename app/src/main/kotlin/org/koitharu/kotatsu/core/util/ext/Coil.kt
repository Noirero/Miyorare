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
 * Pins a source cover's disk-cache entry to a stable key derived from the manga id instead of the
 * transport URL. Remote covers can rotate signed/CDN URLs, while Smart Local covers use a virtual
 * `smart-local://cover/<id>` URL whose extracted bytes are expensive enough that they should share
 * the same persistent cover tier.
 *
 * Skipped for user-set cover overrides (a URL that differs from the source cover): those keep their
 * own cache key so they cannot collide with the source cover cached under `cover:$mangaId`.
 */
fun ImageRequest.Builder.stableMangaCoverKey(manga: Manga?, coverUrl: String?): ImageRequest.Builder = apply {
	if (manga != null && (isRemoteCoverUrl(coverUrl) || isSmartLocalCoverUrl(coverUrl)) &&
		(coverUrl == manga.coverUrl || coverUrl == manga.largeCoverUrl)
	) {
		diskCacheKey(mangaCoverDiskCacheKey(manga.id))
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
