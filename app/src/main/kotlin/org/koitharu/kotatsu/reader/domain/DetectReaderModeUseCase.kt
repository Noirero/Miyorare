package org.koitharu.kotatsu.reader.domain

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Size
import androidx.core.net.toFile
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import okhttp3.OkHttpClient
import org.koitharu.kotatsu.core.model.isNovelSource
import org.koitharu.kotatsu.core.network.MangaHttpClient
import org.koitharu.kotatsu.core.network.imageproxy.ImageProxyInterceptor
import org.koitharu.kotatsu.core.parser.MangaDataRepository
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.ReaderMode
import org.koitharu.kotatsu.core.util.ext.isFileUri
import org.koitharu.kotatsu.local.data.isEpub
import org.koitharu.kotatsu.core.util.ext.isZipUri
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.reader.ui.ReaderState
import org.koitharu.kotatsu.sources.compat.EhentaiSourceFamily
import java.io.InputStream
import java.util.zip.ZipFile
import javax.inject.Inject
import kotlin.math.roundToInt

class DetectReaderModeUseCase @Inject constructor(
	private val dataRepository: MangaDataRepository,
	private val settings: AppSettings,
	private val mangaRepositoryFactory: MangaRepository.Factory,
	@MangaHttpClient private val okHttpClient: OkHttpClient,
	private val imageProxyInterceptor: ImageProxyInterceptor,
	@ApplicationContext private val context: Context,
) {

	suspend operator fun invoke(manga: Manga, state: ReaderState?): ReaderMode {
		dataRepository.getReaderMode(manga.id)?.let { return it }
		val defaultMode = settings.defaultReaderMode
		if (manga.isEpub || manga.source.isNovelSource) return defaultMode
		if (!settings.isReaderModeDetectionEnabled || defaultMode == ReaderMode.WEBTOON) return defaultMode
		if (EhentaiSourceFamily.isOfficialSource(manga.source.name)) return defaultMode
		val chapter = state?.let { manga.findChapterById(it.chapterId) }
			?: manga.chapters?.firstOrNull()
			?: error("There are no chapters in this manga")
		val repo = mangaRepositoryFactory.create(chapter.source)
		val pages = repo.getPages(chapter)
		return runCatchingCancellable {
			if (guessMangaIsWebtoon(repo, pages)) ReaderMode.WEBTOON else defaultMode
		}.onSuccess { dataRepository.saveReaderMode(manga, it) }
			.onFailure { it.printStackTraceDebug() }
			.getOrDefault(defaultMode)
	}

	private suspend fun guessMangaIsWebtoon(repository: MangaRepository, pages: List<MangaPage>): Boolean {
		val samples = getSampleIndices(pages.size).mapNotNull { index ->
			val page = pages.getOrNull(index) ?: return@mapNotNull null
			runCatchingCancellable { page to repository.getPageUrl(page) }.getOrNull()
		}
		check(samples.isNotEmpty()) { "No pages could be sampled for webtoon detection" }
		val zipUris = samples.map { (_, url) -> url.toUri() }
		val sharedZipPath = zipUris.firstOrNull()
			?.takeIf { first -> first.isZipUri() && zipUris.all { it.isZipUri() && it.schemeSpecificPart == first.schemeSpecificPart } }
			?.schemeSpecificPart
		if (sharedZipPath != null) {
			val (webtoonVotes, totalVotes) = runInterruptible(Dispatchers.IO) {
				ZipFile(sharedZipPath).use { zip ->
					var votes = 0
					var total = 0
					for (uri in zipUris) {
						val isWebtoon = runCatching {
							val entryName = requireNotNull(uri.fragment)
							val entry = requireNotNull(zip.getEntry(entryName))
							val size = zip.getInputStream(entry).use(::getBitmapSize)
							size.width * MIN_WEBTOON_RATIO < size.height
						}.getOrNull() ?: continue
						total++
						if (isWebtoon) votes++
					}
					votes to total
				}
			}
			check(totalVotes > 0) { "No CBZ pages could be sampled for webtoon detection" }
			return webtoonVotes * 2 > totalVotes
		}
		var webtoonVotes = 0
		var totalVotes = 0
		for ((page, url) in samples) {
			val isWebtoon = runCatchingCancellable {
				val size = getImageSize(url, page, repository)
				size.width * MIN_WEBTOON_RATIO < size.height
			}.getOrNull() ?: continue
			totalVotes++
			if (isWebtoon) webtoonVotes++
		}
		check(totalVotes > 0) { "No pages could be sampled for webtoon detection" }
		return webtoonVotes * 2 > totalVotes
	}

	private suspend fun getImageSize(url: String, page: MangaPage, repository: MangaRepository): Size {
		val uri = url.toUri()
		return when {
			uri.isZipUri() -> runInterruptible(Dispatchers.IO) {
				ZipFile(uri.schemeSpecificPart).use { zip ->
					val entry = zip.getEntry(uri.fragment)
					zip.getInputStream(entry).use { getBitmapSize(it) }
				}
			}
			uri.isFileUri() -> runInterruptible(Dispatchers.IO) {
				uri.toFile().inputStream().use { getBitmapSize(it) }
			}
			uri.scheme == "content" -> runInterruptible(Dispatchers.IO) {
				context.contentResolver.openInputStream(uri).use { getBitmapSize(it) }
			}
			else -> {
				val response = repository.getImageStream(url, page)
					?: imageProxyInterceptor.interceptPageRequest(PageLoader.createPageRequest(url, page.source), okHttpClient)
				response.use { runInterruptible(Dispatchers.IO) { getBitmapSize(it.body.byteStream()) } }
			}
		}
	}

	private fun getSampleIndices(pageCount: Int): List<Int> = when {
		pageCount < 4 -> if (pageCount > 0) listOf(pageCount / 2) else emptyList()
		else -> listOf((pageCount * 0.25).roundToInt(), (pageCount * 0.5).roundToInt(), (pageCount * 0.75).roundToInt()).distinct()
	}

	companion object {
		private const val MIN_WEBTOON_RATIO = 1.8
		private fun getBitmapSize(input: InputStream?): Size {
			val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
			BitmapFactory.decodeStream(input, null, options)?.recycle()
			val imageHeight = options.outHeight
			val imageWidth = options.outWidth
			check(imageHeight > 0 && imageWidth > 0)
			return Size(imageWidth, imageHeight)
		}
	}
}
