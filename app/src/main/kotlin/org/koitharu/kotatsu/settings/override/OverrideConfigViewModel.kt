package org.koitharu.kotatsu.settings.override

import android.content.Context
import androidx.core.net.toUri
import androidx.core.text.parseAsHtml
import androidx.lifecycle.SavedStateHandle
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import okio.buffer
import okio.sink
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.model.parcelable.ParcelableManga
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.parser.MangaDataRepository
import org.koitharu.kotatsu.core.ui.BaseViewModel
import org.koitharu.kotatsu.core.ui.model.MangaOverride
import org.koitharu.kotatsu.core.util.MimeTypes
import org.koitharu.kotatsu.core.util.ext.MutableEventFlow
import org.koitharu.kotatsu.core.util.ext.call
import org.koitharu.kotatsu.core.util.ext.isFileUri
import org.koitharu.kotatsu.core.util.ext.isNetworkUri
import org.koitharu.kotatsu.core.util.ext.openSource
import org.koitharu.kotatsu.core.util.ext.require
import org.koitharu.kotatsu.core.util.ext.sanitize
import org.koitharu.kotatsu.core.util.ext.toFileOrNull
import org.koitharu.kotatsu.core.util.ext.toMimeTypeOrNull
import org.koitharu.kotatsu.core.util.ext.toUriOrNull
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.util.md5
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.scrobbling.common.domain.Scrobbler
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService
import java.io.File
import javax.inject.Inject

private const val DIR_COVERS = "covers"

data class TrackerMetadataCandidate(
	val service: ScrobblerService,
	val title: String?,
	val author: String?,
	val artist: String?,
	val description: String?,
	val coverUrl: String?,
)

@HiltViewModel
class OverrideConfigViewModel @Inject constructor(
	savedStateHandle: SavedStateHandle,
	@ApplicationContext private val context: Context,
	private val dataRepository: MangaDataRepository,
	private val database: MangaDatabase,
	scrobbblers: Set<@JvmSuppressWildcards Scrobbler>,
) : BaseViewModel() {

	private val manga = savedStateHandle.require<ParcelableManga>(AppRouter.KEY_MANGA).manga
	private val scrobblers = scrobbblers.sortedBy { it.scrobblerService.id }

	val data = MutableStateFlow<Pair<Manga, MangaOverride>?>(null)
	val onSaved = MutableEventFlow<Unit>()
	val onTrackerMetadata = MutableEventFlow<List<TrackerMetadataCandidate>>()
	val onTrackerMetadataUnavailable = MutableEventFlow<Unit>()

	init {
		launchLoadingJob(Dispatchers.Default) {
			val sourceManga = dataRepository.findMangaById(manga.id, false) ?: manga
			val base = dataRepository.getOverride(manga.id) ?: emptyOverride()
			val prefs = database.getPreferencesDao().find(manga.id)
			data.value = sourceManga to base.copy(
				author = prefs?.authorOverride,
				artist = prefs?.artistOverride,
				description = prefs?.descriptionOverride,
			)
		}
	}

	fun fetchTrackerMetadata() {
		launchLoadingJob(Dispatchers.Default) {
			val enabled = scrobblers.filter { it.isEnabled }
			if (enabled.isEmpty()) {
				onTrackerMetadataUnavailable.call(Unit)
				return@launchLoadingJob
			}
			val attempts = coroutineScope {
				enabled.map { scrobbler ->
					async {
						runCatchingCancellable {
							scrobbler.fetchLinkedMangaInfoOrNull(manga.id)?.let { info ->
								TrackerMetadataCandidate(
									service = scrobbler.scrobblerService,
									title = info.name.cleanMetadataValue(),
									author = info.author.cleanMetadataValue(),
									artist = info.artist.cleanMetadataValue(),
									description = info.descriptionHtml
										.takeIf { it.isNotBlank() }
										?.parseAsHtml()
										?.sanitize()
										?.toString()
										.cleanMetadataValue(),
									coverUrl = info.cover.cleanMetadataValue(),
								)
							}
						}
					}
				}.awaitAll()
			}
			val candidates = attempts.mapNotNull { it.getOrNull() }
			if (candidates.isNotEmpty()) {
				onTrackerMetadata.call(candidates)
				return@launchLoadingJob
			}
			val fetchError = attempts.firstNotNullOfOrNull { it.exceptionOrNull() }
			if (fetchError != null) {
				errorEvent.call(fetchError)
			} else {
				onTrackerMetadataUnavailable.call(Unit)
			}
		}
	}

	fun save(title: String?, author: String?, artist: String?, description: String?) {
		launchLoadingJob(Dispatchers.Default) {
			val (sourceManga, draftOverride) = checkNotNull(data.value)
			val previousCover = dataRepository.getOverride(sourceManga.id)?.coverUrl
			val override = draftOverride.copy(
				title = title,
				coverUrl = draftOverride.coverUrl?.cachedFile(),
			)
			val savedOverride = dataRepository.setOverride(sourceManga, override)
			database.getPreferencesDao().updateExtendedOverrides(
				mangaId = sourceManga.id,
				author = author.normalizedAgainst(sourceManga.authors.joinToString(", ")),
				artist = artist?.trim()?.takeIf { it.isNotEmpty() },
				description = description.normalizedAgainst(sourceManga.description.orEmpty()),
			)
			deleteStaleCachedCover(previousCover, savedOverride?.coverUrl)
			onSaved.call(Unit)
		}
	}

	fun updateCover(coverUri: String?) {
		val snapshot = data.value ?: return
		data.value = snapshot.first to snapshot.second.copy(coverUrl = coverUri)
	}

	private fun String?.normalizedAgainst(original: String): String? {
		val value = this?.trim()?.takeIf { it.isNotEmpty() } ?: return null
		return value.takeUnless { it == original.trim() }
	}

	private fun String?.cleanMetadataValue(): String? = this
		?.trim()
		?.takeIf { it.isNotEmpty() }

	private suspend fun String.cachedFile(): String {
		val uri = toUriOrNull()
		if (uri == null || uri.isFileUri() || uri.isNetworkUri()) {
			return this
		}
		val cacheDir = context.getExternalFilesDir(DIR_COVERS) ?: return this
		val cr = context.contentResolver
		val ext = cr.getType(uri)?.toMimeTypeOrNull()?.let { MimeTypes.getExtension(it) }
		val fileName = buildString {
			append(this@cachedFile.md5())
			if (!ext.isNullOrEmpty()) {
				append('.')
				append(ext)
			}
		}
		return withContext(Dispatchers.IO) {
			val dest = File(cacheDir, fileName)
			cr.openSource(uri).use { source ->
				dest.sink().buffer().use { sink -> sink.writeAll(source) }
			}
			dest
		}.toUri().toString()
	}

	private suspend fun deleteStaleCachedCover(oldCover: String?, newCover: String?) {
		if (oldCover.isNullOrEmpty() || oldCover == newCover) return
		withContext(Dispatchers.IO) {
			runCatching {
				val cacheDir = context.getExternalFilesDir(DIR_COVERS)?.canonicalFile ?: return@runCatching
				val file = oldCover.toUriOrNull()?.toFileOrNull()?.canonicalFile ?: return@runCatching
				if (file.parentFile?.canonicalPath == cacheDir.canonicalPath) file.delete()
			}
		}
	}

	private fun emptyOverride() = MangaOverride(null, null, null)
}
