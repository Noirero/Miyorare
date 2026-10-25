package org.koitharu.kotatsu.discovery.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import org.koitharu.kotatsu.alternatives.domain.AlternativeSearchEvent
import org.koitharu.kotatsu.alternatives.domain.AlternativesUseCase
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.db.entity.toManga
import org.koitharu.kotatsu.core.model.LocalMangaSource
import org.koitharu.kotatsu.core.model.isNovelContent
import org.koitharu.kotatsu.core.model.isNsfw
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.explore.data.MangaSourcesRepository
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.search.domain.SearchSourceMode
import org.koitharu.kotatsu.search.domain.SearchSourcePreferences
import javax.inject.Inject

interface CatalogSourceResolution {
	suspend fun known(key: TrackerCatalogKey): List<Manga>
	suspend fun search(details: TrackerCatalogDetails, allSources: Boolean): Flow<AlternativeSearchEvent>
	suspend fun canOpen(manga: Manga, kind: CatalogMediaKind): Boolean
}

class CatalogSourceResolver @Inject constructor(
	private val alternatives: AlternativesUseCase,
	private val sources: MangaSourcesRepository,
	private val database: MangaDatabase,
	private val settings: AppSettings,
	private val preferences: SearchSourcePreferences,
) : CatalogSourceResolution {
	/** Only exact persisted tracker associations; no title lookup, parser work, or write. */
	override suspend fun known(key: TrackerCatalogKey): List<Manga> {
		val enabled = sources.getEnabledSources().toSet()
		return database.getScrobblingDao().findByTarget(key.service.id, key.remoteId.toLong()).mapNotNull { association ->
			if (database.getPrivateFavouritesDao().isPrivateOnly(association.mangaId)) return@mapNotNull null
			val manga = database.getMangaDao().find(association.mangaId)?.toManga() ?: return@mapNotNull null
			manga.takeIf { (it.source == LocalMangaSource || it.source in enabled) && eligibleCatalogCandidate(it, key.kind, !settings.isNsfwContentDisabled) }
		}.distinctBy { it.source.name to it.id }
	}

	/** Called only by the explicit reading CTA. The existing Alternatives engine owns source work. */
	override suspend fun search(details: TrackerCatalogDetails, allSources: Boolean): Flow<AlternativeSearchEvent> = alternatives.searchTitle(
		details.item.title, details.item.key.kind == CatalogMediaKind.NOVEL,
		if (allSources) SearchSourceMode.ALL_SOURCES else preferences.alternativeMode, preferences.preferredLanguages,
	).filter { event -> event !is AlternativeSearchEvent.Result || eligibleCatalogCandidate(event.manga, details.item.key.kind, !settings.isNsfwContentDisabled) }
	override suspend fun canOpen(manga: Manga, kind: CatalogMediaKind): Boolean =
		(manga.source == LocalMangaSource || manga.source in sources.getEnabledSources()) &&
			eligibleCatalogCandidate(manga, kind, !settings.isNsfwContentDisabled) && !database.getPrivateFavouritesDao().isPrivateOnly(manga.id)
}

internal fun eligibleCatalogCandidate(manga: Manga, kind: CatalogMediaKind, allowAdult: Boolean): Boolean =
	manga.isNovelContent == (kind == CatalogMediaKind.NOVEL) && (allowAdult || !manga.isNsfw())
