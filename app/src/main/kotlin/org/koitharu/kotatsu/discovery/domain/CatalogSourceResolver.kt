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
import org.koitharu.kotatsu.core.model.isNovelContentSource
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.explore.data.MangaSourcesRepository
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.search.domain.SearchSourceMode
import org.koitharu.kotatsu.search.domain.SearchSourcePreferences
import javax.inject.Inject

sealed interface CatalogSearchScope {
	data object Preferred : CatalogSearchScope
	data object AllEnabled : CatalogSearchScope
	data class Single(val sourceName: String) : CatalogSearchScope
}

data class CatalogReadingSource(val source: MangaSource, val pinned: Boolean)

data class CatalogReadingSearch(
	val events: Flow<AlternativeSearchEvent>?,
	val canExpand: Boolean,
)

interface CatalogSourceResolution {
	suspend fun known(key: TrackerCatalogKey): List<Manga>
	suspend fun search(details: TrackerCatalogDetails, scope: CatalogSearchScope): CatalogReadingSearch
	suspend fun availableSources(kind: CatalogMediaKind): List<CatalogReadingSource>
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
	override suspend fun search(details: TrackerCatalogDetails, scope: CatalogSearchScope): CatalogReadingSearch {
		val kind = details.item.key.kind
		val selection = when (scope) {
			is CatalogSearchScope.Single -> {
				sources.ensureExternalSourcesReady()
				val enabled = sources.getEnabledSources().filter {
					it != LocalMangaSource && it.isNovelContentSource == (kind == CatalogMediaKind.NOVEL)
				}
				val selected = selectCatalogSource(enabled, kind, scope.sourceName)
				AlternativesUseCase.TitleSourceSelection(selected, enabled.any { it.name != scope.sourceName })
			}
			CatalogSearchScope.Preferred, CatalogSearchScope.AllEnabled -> alternatives.getTitleSources(
				kind == CatalogMediaKind.NOVEL,
				if (scope == CatalogSearchScope.AllEnabled) SearchSourceMode.ALL_SOURCES else preferences.alternativeMode,
				preferences.preferredLanguages,
			)
		}
		val events = selection.sources.takeIf { it.isNotEmpty() }?.let { pool ->
			alternatives.searchTitle(details.item.title, pool).filter { event ->
				event !is AlternativeSearchEvent.Result || eligibleCatalogCandidate(event.manga, kind, !settings.isNsfwContentDisabled)
			}
		}
		return CatalogReadingSearch(events, selection.canExpand)
	}

	override suspend fun availableSources(kind: CatalogMediaKind): List<CatalogReadingSource> {
		sources.ensureExternalSourcesReady()
		val pinned = sources.getPinnedSources()
		return sources.getEnabledSources()
			.filter { it != LocalMangaSource && it.isNovelContentSource == (kind == CatalogMediaKind.NOVEL) }
			.distinctBy { it.name }
			.map { CatalogReadingSource(it, it in pinned) }
			.sortedByDescending { it.pinned }
	}

	override suspend fun canOpen(manga: Manga, kind: CatalogMediaKind): Boolean =
		(manga.source == LocalMangaSource || manga.source in sources.getEnabledSources()) &&
			eligibleCatalogCandidate(manga, kind, !settings.isNsfwContentDisabled) && !database.getPrivateFavouritesDao().isPrivateOnly(manga.id)
}

internal fun eligibleCatalogCandidate(manga: Manga, kind: CatalogMediaKind, allowAdult: Boolean): Boolean =
	manga.isNovelContent == (kind == CatalogMediaKind.NOVEL) && (allowAdult || !manga.isNsfw())

/** Scope is applied to the registry snapshot before any title requests are started. */
internal fun selectCatalogSource(enabled: List<MangaSource>, kind: CatalogMediaKind, sourceName: String): List<MangaSource> =
	listOfNotNull(enabled.firstOrNull {
		it.name == sourceName && it != LocalMangaSource && it.isNovelContentSource == (kind == CatalogMediaKind.NOVEL)
	})
