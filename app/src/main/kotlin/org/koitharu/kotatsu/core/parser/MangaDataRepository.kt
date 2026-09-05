package org.koitharu.kotatsu.core.parser

import androidx.collection.LongObjectMap
import androidx.collection.MutableLongObjectMap
import androidx.core.net.toUri
import androidx.room.withTransaction
import dagger.Reusable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.db.TABLE_FAVOURITES
import org.koitharu.kotatsu.core.db.TABLE_FAVOURITE_CATEGORIES
import org.koitharu.kotatsu.core.db.TABLE_PREFERENCES
import org.koitharu.kotatsu.core.db.entity.ContentRating
import org.koitharu.kotatsu.core.db.entity.MangaEntity
import org.koitharu.kotatsu.core.db.entity.MangaPrefsEntity
import org.koitharu.kotatsu.core.db.entity.toEntities
import org.koitharu.kotatsu.core.db.entity.toEntity
import org.koitharu.kotatsu.core.db.entity.toManga
import org.koitharu.kotatsu.core.db.entity.toMangaChapters
import org.koitharu.kotatsu.core.db.entity.toMangaTags
import org.koitharu.kotatsu.core.model.LocalMangaSource
import org.koitharu.kotatsu.core.model.MangaSource as ResolveMangaSource
import org.koitharu.kotatsu.core.model.isLocal
import org.koitharu.kotatsu.core.nav.MangaIntent
import org.koitharu.kotatsu.core.os.AppShortcutManager
import org.koitharu.kotatsu.core.prefs.ReaderMode
import org.koitharu.kotatsu.core.ui.model.MangaOverride
import org.koitharu.kotatsu.core.util.ext.toFileOrNull
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.util.nullIfEmpty
import org.koitharu.kotatsu.reader.domain.ReaderColorFilter
import javax.inject.Inject
import javax.inject.Provider

@Reusable
class MangaDataRepository @Inject constructor(
	private val db: MangaDatabase,
	private val resolverProvider: Provider<MangaLinkResolver>,
	private val appShortcutManagerProvider: Provider<AppShortcutManager>,
) {

	suspend fun saveReaderMode(manga: Manga, mode: ReaderMode) {
		db.withTransaction {
			storeMangaLocked(manga, replaceExisting = false)
			val entity = db.getPreferencesDao().find(manga.id) ?: newEntity(manga.id)
			db.getPreferencesDao().upsert(entity.copy(mode = mode.id))
		}
	}

	suspend fun saveColorFilter(manga: Manga, colorFilter: ReaderColorFilter?) {
		db.withTransaction {
			storeMangaLocked(manga, replaceExisting = false)
			val entity = db.getPreferencesDao().find(manga.id) ?: newEntity(manga.id)
			db.getPreferencesDao().upsert(
				entity.copy(
					cfBrightness = colorFilter?.brightness ?: 0f,
					cfContrast = colorFilter?.contrast ?: 0f,
					cfInvert = colorFilter?.isInverted == true,
					cfGrayscale = colorFilter?.isGrayscale == true,
				),
			)
		}
	}

	suspend fun resetColorFilters() {
		db.getPreferencesDao().resetColorFilters()
	}

	suspend fun getReaderMode(mangaId: Long): ReaderMode? {
		return db.getPreferencesDao().find(mangaId)?.let { ReaderMode.valueOf(it.mode) }
	}

	suspend fun getColorFilter(mangaId: Long): ReaderColorFilter? {
		return db.getPreferencesDao().find(mangaId)?.getColorFilterOrNull()
	}

	suspend fun isScanlatorsMerged(mangaId: Long): Boolean {
		return db.getPreferencesDao().find(mangaId)?.mergeScanlators == true
	}

	suspend fun setScanlatorsMerged(manga: Manga, isMerged: Boolean) {
		db.withTransaction {
			storeMangaLocked(manga, replaceExisting = false)
			val entity = db.getPreferencesDao().find(manga.id) ?: newEntity(manga.id)
			db.getPreferencesDao().upsert(entity.copy(mergeScanlators = isMerged))
		}
	}

	suspend fun getOverride(mangaId: Long): MangaOverride? {
		return db.getPreferencesDao().find(mangaId)?.getOverrideOrNull()
	}

	suspend fun getOverrides(): LongObjectMap<MangaOverride> {
		val entities = db.getPreferencesDao().getOverrides()
		val map = MutableLongObjectMap<MangaOverride>(entities.size)
		for (entity in entities) {
			map[entity.mangaId] = entity.getOverrideOrNull() ?: continue
		}
		return map
	}

	suspend fun setOverride(manga: Manga, override: MangaOverride?): MangaOverride? {
		return db.withTransaction {
			storeMangaLocked(manga, replaceExisting = false)
			val dao = db.getPreferencesDao()
			val source = db.getMangaDao().find(manga.id)?.manga ?: manga.toEntity()
			val entity = dao.find(manga.id) ?: newEntity(manga.id)
			val normalizedOverride = override.normalizedAgainst(source)
			dao.upsert(
				entity.copy(
					titleOverride = normalizedOverride?.title?.nullIfEmpty(),
					coverUrlOverride = normalizedOverride?.coverUrl?.nullIfEmpty(),
					contentRatingOverride = normalizedOverride?.contentRating?.name,
					authorOverride = normalizedOverride?.author?.nullIfEmpty(),
					artistOverride = normalizedOverride?.artist?.nullIfEmpty(),
					descriptionOverride = normalizedOverride?.description?.nullIfEmpty(),
				),
			)
			normalizedOverride
		}
	}

	fun observeColorFilter(mangaId: Long): Flow<ReaderColorFilter?> {
		return db.getPreferencesDao().observe(mangaId)
			.map { it?.getColorFilterOrNull() }
			.distinctUntilChanged()
	}

	suspend fun findMangaById(mangaId: Long, withChapters: Boolean): Manga? {
		val chapters = if (withChapters) {
			db.getChaptersDao().findAll(mangaId).takeUnless { it.isEmpty() }
		} else null
		return db.getMangaDao().find(mangaId)?.toManga(chapters)
	}

	/** Attach cached chapters to lightweight list rows with one indexed query. */
	suspend fun attachCachedChapters(manga: Collection<Manga>): List<Manga> {
		if (manga.isEmpty()) return emptyList()
		val chaptersByManga = db.getChaptersDao()
			.findAll(manga.map { it.id })
			.groupBy { it.mangaId }
		return manga.map { item ->
			val chapters = chaptersByManga[item.id]
			if (chapters.isNullOrEmpty()) item else item.copy(chapters = chapters.toMangaChapters())
		}
	}

	suspend fun findMangaByPublicUrl(publicUrl: String): Manga? {
		return db.getMangaDao().findByPublicUrl(publicUrl)?.toManga()
	}

	suspend fun resolveIntent(intent: MangaIntent, withChapters: Boolean): Manga? = when {
		intent.manga != null -> {
			val direct = intent.manga.copy(source = ResolveMangaSource(intent.manga.source.name))
			val resolved = if (!direct.isLocal) {
				findMangaById(direct.id, withChapters)?.let { stored ->
					stored.copy(source = ResolveMangaSource(stored.source.name))
				} ?: direct
			} else direct
			resolved.withCachedChaptersIfNeeded(withChapters)
		}
		intent.mangaId != 0L -> findMangaById(intent.mangaId, withChapters)
		intent.uri != null -> resolverProvider.get().resolve(intent.uri).withCachedChaptersIfNeeded(withChapters)
		else -> null
	}

	suspend fun storeManga(
		manga: Manga,
		replaceExisting: Boolean,
		stripAppliedOverride: Boolean = true,
		detailsFetched: Boolean = false,
	) = db.withTransaction {
		storeMangaLocked(manga, replaceExisting, stripAppliedOverride, detailsFetched)
	}

	suspend fun getDetailsUpdatedAt(mangaId: Long): Long {
		return db.getMangaDao().getDetailsUpdatedAt(mangaId) ?: 0L
	}

	suspend fun gcChaptersCache() {
		db.getChaptersDao().gc()
	}

	suspend fun findTags(source: MangaSource): Set<MangaTag> {
		return db.getTagsDao().findTags(source.name).toMangaTags()
	}

	suspend fun cleanupLocalManga() {
		val dao = db.getMangaDao()
		val broken = dao.findAllBySource(LocalMangaSource.name)
			.filter { x -> x.manga.url.toUri().toFileOrNull()?.exists() == false }
		if (broken.isNotEmpty()) dao.delete(broken.map { it.manga })
	}

	suspend fun cleanupDatabase() {
		db.withTransaction {
			gcChaptersCache()
			val idsFromShortcuts = appShortcutManagerProvider.get().getMangaShortcuts()
			db.getMangaDao().cleanup(idsFromShortcuts)
		}
	}

	fun observeOverridesTrigger(emitInitialState: Boolean) = db.invalidationTracker.createFlow(
		tables = arrayOf(TABLE_PREFERENCES),
		emitInitialState = emitInitialState,
	)

	fun observeFavoritesTrigger(emitInitialState: Boolean) = db.invalidationTracker.createFlow(
		tables = arrayOf(TABLE_FAVOURITES, TABLE_FAVOURITE_CATEGORIES),
		emitInitialState = emitInitialState,
	)

	private suspend fun Manga.withCachedChaptersIfNeeded(flag: Boolean): Manga = if (flag && !isLocal && chapters.isNullOrEmpty()) {
		val cachedChapters = db.getChaptersDao().findAll(id)
		if (cachedChapters.isEmpty()) this else copy(chapters = cachedChapters.toMangaChapters())
	} else this

	private suspend fun storeMangaLocked(
		manga: Manga,
		replaceExisting: Boolean,
		stripAppliedOverride: Boolean = true,
		detailsFetched: Boolean = false,
	) {
		val mangaDao = db.getMangaDao()
		val existing = mangaDao.find(manga.id)?.manga
		if (!replaceExisting && existing != null) return
		if (manga.isLocal && existing != null && existing.source != manga.source.name) return
		val override = if (stripAppliedOverride) db.getPreferencesDao().find(manga.id)?.getOverrideOrNull() else null
		val sourceManga = manga.withoutAppliedOverride(existing, override)
		val tags = sourceManga.tags.toEntities()
		db.getTagsDao().upsert(tags)
		val entity = sourceManga.toEntity().copy(
			detailsUpdatedAt = if (detailsFetched) System.currentTimeMillis() else existing?.detailsUpdatedAt ?: 0L,
		)
		mangaDao.upsert(entity, tags)
		if (!sourceManga.isLocal) {
			sourceManga.chapters?.let { chapters ->
				db.getChaptersDao().replaceAll(sourceManga.id, chapters.withIndex().toEntities(sourceManga.id))
			}
		}
	}

	private fun Manga.withoutAppliedOverride(existing: MangaEntity?, override: MangaOverride?): Manga {
		if (existing == null || override == null) return this
		var result = this
		if (!override.title.isNullOrEmpty() && title == override.title) result = result.copy(title = existing.title)
		if (!override.coverUrl.isNullOrEmpty()) {
			result = result.copy(
				coverUrl = if (coverUrl == override.coverUrl) existing.coverUrl else coverUrl,
				largeCoverUrl = if (largeCoverUrl == override.coverUrl) existing.largeCoverUrl else largeCoverUrl,
			)
		}
		if (override.contentRating != null && contentRating == override.contentRating) {
			result = result.copy(contentRating = ContentRating(existing.contentRating))
		}
		if (!override.author.isNullOrEmpty() && authors.joinToString(", ") == override.author) {
			result = result.copy(authors = existing.authors.toAuthorSet())
		}
		if (!override.description.isNullOrEmpty() && description == override.description) {
			result = result.copy(description = existing.description)
		}
		if (result.description == null && chapters == null && existing.description != null) {
			result = result.copy(description = existing.description)
		}
		return result
	}

	private fun MangaOverride?.normalizedAgainst(source: MangaEntity): MangaOverride? {
		if (this == null) return null
		val normalized = copy(
			title = title?.trim()?.nullIfEmpty()?.takeUnless { it == source.title },
			coverUrl = coverUrl?.nullIfEmpty()?.takeUnless { it == source.coverUrl || it == source.largeCoverUrl },
			contentRating = contentRating?.takeUnless { it == ContentRating(source.contentRating) },
			author = author?.trim()?.nullIfEmpty()?.takeUnless { it == source.authors.toAuthorSet().joinToString(", ") },
			artist = artist?.trim()?.nullIfEmpty(),
			description = description?.trim()?.nullIfEmpty()?.takeUnless { it == source.description?.trim() },
		)
		return if (
			normalized.title.isNullOrEmpty() && normalized.coverUrl.isNullOrEmpty() &&
			normalized.contentRating == null && normalized.author.isNullOrEmpty() &&
			normalized.artist.isNullOrEmpty() && normalized.description.isNullOrEmpty()
		) null else normalized
	}

	private fun MangaPrefsEntity.getColorFilterOrNull(): ReaderColorFilter? {
		return if (cfBrightness != 0f || cfContrast != 0f || cfInvert || cfGrayscale || cfBookEffect) {
			ReaderColorFilter(
				brightness = cfBrightness,
				contrast = cfContrast,
				isInverted = cfInvert,
				isGrayscale = cfGrayscale,
				isBookBackground = cfBookEffect,
			)
		} else null
	}

	private fun MangaPrefsEntity.getOverrideOrNull(): MangaOverride? {
		return if (
			titleOverride.isNullOrEmpty() && coverUrlOverride.isNullOrEmpty() && contentRatingOverride.isNullOrEmpty() &&
			authorOverride.isNullOrEmpty() && artistOverride.isNullOrEmpty() && descriptionOverride.isNullOrEmpty()
		) null else MangaOverride(
			coverUrl = coverUrlOverride?.nullIfEmpty(),
			title = titleOverride?.nullIfEmpty(),
			contentRating = ContentRating(contentRatingOverride),
			author = authorOverride?.nullIfEmpty(),
			artist = artistOverride?.nullIfEmpty(),
			description = descriptionOverride?.nullIfEmpty(),
		)
	}

	private fun newEntity(mangaId: Long) = MangaPrefsEntity(
		mangaId = mangaId,
		mode = -1,
		cfBrightness = ReaderColorFilter.EMPTY.brightness,
		cfContrast = ReaderColorFilter.EMPTY.contrast,
		cfInvert = ReaderColorFilter.EMPTY.isInverted,
		cfGrayscale = ReaderColorFilter.EMPTY.isGrayscale,
		cfBookEffect = ReaderColorFilter.EMPTY.isBookBackground,
		titleOverride = null,
		coverUrlOverride = null,
		contentRatingOverride = null,
		authorOverride = null,
		artistOverride = null,
		descriptionOverride = null,
		mergeScanlators = false,
	)

	private fun String?.toAuthorSet(): Set<String> = this
		?.split('\n')
		?.map { it.trim() }
		?.filter { it.isNotEmpty() }
		?.toSet()
		.orEmpty()
}
