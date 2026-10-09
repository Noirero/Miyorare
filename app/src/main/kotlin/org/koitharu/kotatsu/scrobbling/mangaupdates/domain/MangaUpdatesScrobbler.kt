package org.koitharu.kotatsu.scrobbling.mangaupdates.domain

import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.model.isNsfw
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.scrobbling.common.domain.Scrobbler
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblingStatus
import org.koitharu.kotatsu.scrobbling.mangaupdates.data.MangaUpdatesRepository
import org.koitharu.kotatsu.scrobbling.mangaupdates.data.MangaUpdatesEditContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MangaUpdatesScrobbler @Inject constructor(
	private val repository: MangaUpdatesRepository, db: MangaDatabase, mangaRepositoryFactory: MangaRepository.Factory,
) : Scrobbler(db, ScrobblerService.MANGAUPDATES, repository, mangaRepositoryFactory) {
	init {
		statuses[ScrobblingStatus.PLANNED] = "wish"
		statuses[ScrobblingStatus.READING] = "read"
		statuses[ScrobblingStatus.COMPLETED] = "complete"
		statuses[ScrobblingStatus.ON_HOLD] = "hold"
		statuses[ScrobblingStatus.DROPPED] = "unfinished"
	}
	fun captureEdit(mangaId: Long, targetId: Long): MangaUpdatesEditContext? = repository.captureEdit(mangaId, targetId)

	suspend fun updateScrobblingInfo(context: MangaUpdatesEditContext, rating: Float, status: ScrobblingStatus?) =
		repository.updateRate(context, rating, statuses[status])

	suspend fun linkManga(context: MangaUpdatesEditContext, fallbackStatus: ScrobblingStatus): Boolean {
		if (repository.createRate(context)) {
			return db.getScrobblingDao().find(scrobblerService.id, context.mangaId)?.chapter?.let { it <= 0 } != false
		}
		repository.updateRate(context, 0f, statuses[fallbackStatus])
		return true
	}

	suspend fun scrobble(context: MangaUpdatesEditContext, manga: Manga, chapterId: Long) {
		if (db.getPrivateFavouritesDao().isPrivateOnly(manga.id) || db.getScrobblingDao().find(scrobblerService.id, manga.id)?.targetId != context.targetId) return
		repository.enqueueProgress(context, scrobbleChapterNumber(manga, chapterId), manga.isNsfw())
	}

	override suspend fun scrobble(manga: Manga, chapterId: Long) {
		if (db.getPrivateFavouritesDao().isPrivateOnly(manga.id) || db.getScrobblingDao().find(scrobblerService.id, manga.id) == null) return
		repository.enqueueProgress(manga.id, scrobbleChapterNumber(manga, chapterId), manga.isNsfw())
	}
}
