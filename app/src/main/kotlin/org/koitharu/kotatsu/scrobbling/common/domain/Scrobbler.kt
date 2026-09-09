package org.koitharu.kotatsu.scrobbling.common.domain

import androidx.annotation.FloatRange
import androidx.collection.LongSparseArray
import androidx.core.text.parseAsHtml
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.core.util.ext.findKeyByValue
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.core.util.ext.sanitize
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.util.findById
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.scrobbling.common.data.ScrobblerRepository
import org.koitharu.kotatsu.scrobbling.common.data.ScrobblingEntity
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerManga
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerMangaInfo
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerMangaType
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerUser
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblingInfo
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblingStatus
import java.util.EnumMap

abstract class Scrobbler(
	protected val db: MangaDatabase,
	val scrobblerService: ScrobblerService,
	private val repository: ScrobblerRepository,
	private val mangaRepositoryFactory: MangaRepository.Factory,
	private val ratingMax: Float = 1f,
) {

	private val infoCache = LongSparseArray<ScrobblerMangaInfo>()
	protected val statuses = EnumMap<ScrobblingStatus, String>(ScrobblingStatus::class.java)

	val user: Flow<ScrobblerUser> = flow {
		repository.cachedUser?.let { emit(it) }
		runCatchingCancellable { repository.loadUser() }
			.onSuccess { emit(it) }
			.onFailure { it.printStackTraceDebug() }
	}

	val isEnabled: Boolean
		get() = repository.isAuthorized

	suspend fun authorize(authCode: String): ScrobblerUser {
		repository.authorize(authCode)
		return repository.loadUser()
	}

	fun logout() = repository.logout()

	suspend fun findManga(query: String, offset: Int, type: ScrobblerMangaType): List<ScrobblerManga> =
		repository.findManga(query, offset, type)

	suspend fun linkManga(mangaId: Long, targetId: Long, fallbackStatus: ScrobblingStatus): Boolean {
		if (isPrivateOnly(mangaId)) return false
		val wasAlreadyTracked = repository.createRate(mangaId, targetId)
		if (wasAlreadyTracked) {
			return db.getScrobblingDao().find(scrobblerService.id, mangaId)?.chapter?.let { it <= 0 } != false
		}
		updateScrobblingInfo(mangaId, rating = 0f, fallbackStatus, comment = null, forceStartDate = true)
		return true
	}

	suspend fun scrobble(manga: Manga, chapterId: Long) {
		if (isPrivateOnly(manga.id)) return
		var chapters = manga.chapters
		if (chapters.isNullOrEmpty()) {
			chapters = mangaRepositoryFactory.create(manga.source).getDetails(manga).chapters
		}
		requireNotNull(chapters)
		val chapter = checkNotNull(chapters.findById(chapterId)) { "Chapter $chapterId not found in this manga" }
		val number = if (chapter.number > 0f) {
			chapter.number.toInt()
		} else {
			chapters = chapters.filter { x -> x.branch == chapter.branch }
			chapters.indexOf(chapter) + 1
		}
		val entity = db.getScrobblingDao().find(scrobblerService.id, manga.id) ?: return
		if (isPrivateOnly(manga.id)) return
		repository.updateRate(entity.id, entity.mangaId, number)
		if (isNotStarted(entity.status)) {
			updateScrobblingInfo(manga.id, entity.rating, ScrobblingStatus.READING, entity.comment)
		}
	}

	suspend fun getScrobblingInfoOrNull(mangaId: Long): ScrobblingInfo? {
		val entity = db.getScrobblingDao().find(scrobblerService.id, mangaId) ?: return null
		return if (isPrivateOnly(mangaId)) {
			entity.toLocalScrobblingInfo()
		} else {
			entity.toScrobblingInfo(checkPrivacy = true)
		}
	}

	/** Database-only state for migration/unlink flows; never fetches tracker metadata. */
	suspend fun getLocalScrobblingInfoOrNull(mangaId: Long): ScrobblingInfo? {
		val entity = db.getScrobblingDao().find(scrobblerService.id, mangaId) ?: return null
		return entity.toLocalScrobblingInfo()
	}

	suspend fun fetchLinkedMangaInfoOrNull(mangaId: Long): ScrobblerMangaInfo? {
		if (isPrivateOnly(mangaId)) return null
		val entity = db.getScrobblingDao().find(scrobblerService.id, mangaId) ?: return null
		if (isPrivateOnly(mangaId)) return null
		return repository.getMangaInfo(entity.targetId)
	}

	suspend fun refreshScrobblingOrNull(mangaId: Long): ScrobblingEntity? {
		if (isPrivateOnly(mangaId)) return null
		val entity = db.getScrobblingDao().find(scrobblerService.id, mangaId) ?: return null
		if (isPrivateOnly(mangaId)) return null
		return repository.refreshRate(entity)
	}

	fun isNotStarted(status: String?): Boolean = status == statuses[ScrobblingStatus.PLANNED]

	suspend fun updateScrobblingInfo(
		mangaId: Long,
		@FloatRange(from = 0.0, to = 1.0) rating: Float,
		status: ScrobblingStatus?,
		comment: String?,
		forceStartDate: Boolean = false,
	) {
		if (isPrivateOnly(mangaId)) return
		val entity = requireNotNull(db.getScrobblingDao().find(scrobblerService.id, mangaId)) {
			"Scrobbling info for manga $mangaId not found"
		}
		if (isPrivateOnly(mangaId)) return
		val statusString = statuses[status]
		val isStartingToRead = status == ScrobblingStatus.READING && entity.status != statusString
		repository.updateRate(
			rateId = entity.id,
			mangaId = entity.mangaId,
			rating = rating * ratingMax,
			status = statusString,
			comment = comment,
			setStartDate = forceStartDate || isStartingToRead,
		)
	}

	fun observeScrobblingInfo(mangaId: Long): Flow<ScrobblingInfo?> {
		return db.getScrobblingDao().observe(scrobblerService.id, mangaId)
			.map { entity ->
				when {
					entity == null -> null
					isPrivateOnly(mangaId) -> entity.toLocalScrobblingInfo()
					else -> entity.toScrobblingInfo(checkPrivacy = true)
				}
			}
	}

	fun observeAllScrobblingInfo(): Flow<List<ScrobblingInfo>> {
		return db.getScrobblingDao().observe(scrobblerService.id)
			.mapLatest { entities ->
				coroutineScope {
					entities.map { entity -> async { entity.toScrobblingInfo(checkPrivacy = false) } }
						.awaitAll()
				}.filterNotNull()
			}
	}

	/** Local-only unlink; repositories only delete the Room link and do not call the tracker service. */
	suspend fun unregisterScrobbling(mangaId: Long) = repository.unregister(mangaId)

	protected suspend fun getMangaInfo(id: Long): ScrobblerMangaInfo = repository.getMangaInfo(id)

	private suspend fun isPrivateOnly(mangaId: Long): Boolean =
		db.getPrivateFavouritesDao().isPrivateOnly(mangaId)

	private suspend fun ScrobblingEntity.toLocalScrobblingInfo(): ScrobblingInfo {
		val manga = db.getMangaDao().find(mangaId)?.manga
		return ScrobblingInfo(
			scrobbler = scrobblerService,
			mangaId = mangaId,
			targetId = targetId,
			status = statuses.findKeyByValue(status),
			chapter = chapter,
			totalChapters = 0,
			comment = comment,
			rating = rating,
			title = manga?.title.orEmpty(),
			coverUrl = manga?.coverUrl.orEmpty(),
			description = null,
			externalUrl = "",
		)
	}

	private suspend fun ScrobblingEntity.toScrobblingInfo(checkPrivacy: Boolean): ScrobblingInfo? {
		if (checkPrivacy && isPrivateOnly(mangaId)) return toLocalScrobblingInfo()
		var mangaInfo = infoCache.get(targetId)
		if (mangaInfo == null) {
			if (checkPrivacy && isPrivateOnly(mangaId)) return toLocalScrobblingInfo()
			mangaInfo = runCatchingCancellable { getMangaInfo(targetId) }
				.onFailure { it.printStackTraceDebug() }
				.getOrNull() ?: return null
			infoCache.put(targetId, mangaInfo)
		}
		if (checkPrivacy && isPrivateOnly(mangaId)) return toLocalScrobblingInfo()
		return ScrobblingInfo(
			scrobbler = scrobblerService,
			mangaId = mangaId,
			targetId = targetId,
			status = statuses.findKeyByValue(status),
			chapter = chapter,
			totalChapters = mangaInfo.totalChapters,
			comment = comment,
			rating = rating,
			title = mangaInfo.name,
			coverUrl = mangaInfo.cover,
			description = mangaInfo.descriptionHtml.parseAsHtml().sanitize(),
			externalUrl = mangaInfo.url,
		)
	}
}

suspend fun Scrobbler.tryScrobble(manga: Manga, chapterId: Long): Boolean {
	return runCatchingCancellable { scrobble(manga, chapterId) }
		.onFailure { it.printStackTraceDebug() }
		.isSuccess
}
