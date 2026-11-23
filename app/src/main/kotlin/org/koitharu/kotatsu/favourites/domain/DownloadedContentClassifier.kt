package org.koitharu.kotatsu.favourites.domain

import android.database.DatabaseUtils.sqlEscapeString
import dagger.Reusable
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.model.isNovelContentPath
import org.koitharu.kotatsu.download.domain.DownloadDestinationStore
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
import org.koitharu.kotatsu.local.data.LocalStorageManager
import org.koitharu.kotatsu.local.data.index.LocalMangaIndexEntity
import org.koitharu.kotatsu.local.data.output.LocalMangaOutput
import org.koitharu.kotatsu.sources.compat.EhentaiSourceFamily
import java.io.File
import javax.inject.Inject

/**
 * Index-backed classifier for the virtual Downloaded shelf.
 *
 * Interactive library code must stay filesystem-free. DownloadWorker, LocalMangaIndex and
 * FavouriteDownloadOwnershipIndex maintain the durable indexes when storage changes; list rendering
 * only reads those indexes. This mirrors Mihon's download-cache principle: filesystem discovery is
 * maintenance work, never work triggered by scrolling a new card into view.
 */
@Reusable
class DownloadedContentClassifier @Inject constructor(
	private val db: MangaDatabase,
	private val storageManager: LocalStorageManager,
	private val downloadDestinationStore: DownloadDestinationStore,
) {

	/**
	 * Return downloads physically owned by [space] from persisted indexes only.
	 *
	 * favourite_download_index carries explicit Normal/Private ownership for app downloads. local_index
	 * is accepted only when Normal and Private have distinct physical roots. When both spaces share a
	 * root, path alone cannot prove ownership and only favourite_download_index may classify the item.
	 */
	suspend fun getDownloadedIds(space: FavouriteSpace): Set<Long> {
		val allowedRoots = getDownloadRoots(space)
		if (allowedRoots.isEmpty()) return emptySet()
		val legacyRoots = getActiveLegacyExhentaiIndexRoots(space)
		val candidates = ArrayList<Pair<Long, File>>()
		for (entry in db.getFavouriteDownloadIndexDao().findEntries(space.dbValue)) {
			val path = File(entry.path)
			if (path.isInsideAny(allowedRoots) || path.isInsideAny(legacyRoots)) candidates += entry.mangaId to path
		}
		if (canInferSpaceFromPath()) {
			findIndexedEntriesInRoots(allowedRoots).forEach { candidates += it.mangaId to File(it.path) }
		}
		return filterActiveExhentaiCandidates(space, candidates)
	}

	/**
	 * Bounded batch lookup for library cards, filters and counts.
	 *
	 * There is deliberately no alias probing, title matching, chapter materialization or filesystem
	 * reconciliation here. A missing index row is simply unknown/not-downloaded until normal storage
	 * maintenance records it.
	 */
	suspend fun getDownloadedIds(space: FavouriteSpace, mangaIds: Collection<Long>): Set<Long> {
		if (mangaIds.isEmpty()) return emptySet()
		val roots = getDownloadRoots(space)
		if (roots.isEmpty()) return emptySet()
		val legacyRoots = getActiveLegacyExhentaiIndexRoots(space)
		val candidates = ArrayList<Pair<Long, File>>()
		val requested = mangaIds.toSet()
		for (chunk in requested.chunked(INDEX_QUERY_CHUNK_SIZE)) {
			for (entry in db.getFavouriteDownloadIndexDao().findEntries(space.dbValue, chunk)) {
				val path = File(entry.path)
				if (path.isInsideAny(roots) || path.isInsideAny(legacyRoots)) candidates += entry.mangaId to path
			}
		}
		if (canInferSpaceFromPath()) {
			for (chunk in requested.chunked(INDEX_QUERY_CHUNK_SIZE)) {
				db.getLocalMangaIndexDao().findEntries(chunk)
					.filterToDownloadRoots(roots)
					.forEach { candidates += it.mangaId to File(it.path) }
			}
		}
		return filterActiveExhentaiCandidates(space, candidates)
	}

	/** Fast alias used by interactive counts/cards; always index-only and filesystem-free. */
	suspend fun getKnownDownloadedIds(space: FavouriteSpace, mangaIds: Collection<Long>): Set<Long> =
		getDownloadedIds(space, mangaIds)

	/**
	 * Space-scoped persisted-download predicate used as a coarse SQL filter.
	 *
	 * favourite_download_index is always authoritative. local_index is additionally usable only when
	 * the two spaces have distinct roots; on a shared root its path carries no Normal/Private identity.
	 */
	fun getDownloadedCondition(space: FavouriteSpace, mangaIdColumn: String): String {
		val allRoots = getDownloadRoots(space)
			.map { it.normalizedAbsolutePath().trimEnd(File.separatorChar) }.distinct()
		val defaultCondition = downloadConditionForRoots(space, mangaIdColumn, allRoots)

		val activeRoot = downloadDestinationStore.effectiveRoot(space)?.absoluteFile?.normalize()?.path
		val activeDownloadRoots = listOfNotNull(activeRoot?.let {
			File(it, LocalMangaOutput.DOWNLOADS_DIR_NAME).normalizedAbsolutePath()
		}) + getActiveLegacyExhentaiIndexRoots(space).map { it.normalizedAbsolutePath() }
		val otherSpace = if (space == FavouriteSpace.NORMAL) FavouriteSpace.PRIVATE else FavouriteSpace.NORMAL
		val otherRoot = downloadDestinationStore.effectiveRoot(otherSpace)?.absoluteFile?.normalize()?.path
		val exclusions = if (otherRoot != null && activeRoot != null && otherRoot != activeRoot &&
			otherRoot.startsWith(activeRoot.trimEnd(File.separatorChar) + File.separator)
		) listOf(otherRoot) else emptyList()
		val activeCondition = downloadConditionForRoots(
			space, mangaIdColumn, activeDownloadRoots, exclusions,
		)
		val sourceColumn = "(SELECT ms.source FROM manga ms WHERE ms.manga_id = $mangaIdColumn)"
		val mainSource = sqlEscapeString(
			org.koitharu.kotatsu.sources.compat.EhentaiSourceFamily.OFFICIAL_SOURCE_NAME,
		)
		val gekkoSource = sqlEscapeString("TSUKI:GEKKOUSHI:gekkoushi:EXHENTAI")
		val scopedSource = "$sourceColumn IN ($mainSource, $gekkoSource)"
		return "((($scopedSource) AND $activeCondition) OR (NOT($scopedSource) AND $defaultCondition))"
	}

	private fun downloadConditionForRoots(
		space: FavouriteSpace,
		mangaIdColumn: String,
		roots: List<String>,
		excluded: List<String> = emptyList(),
	): String {
		val localCondition = if (canInferSpaceFromPath()) {
			buildSqlPathExists(
				table = "local_index",
				idColumn = "local_index.manga_id",
				pathColumn = "local_index.path",
				mangaIdColumn = mangaIdColumn,
				rootPaths = roots,
				excludedRoots = excluded,
			)
		} else "0"
		val ownerPath = sqlPathPredicate("fdi.path", roots, excluded)
		val ownerCondition = "EXISTS(SELECT 1 FROM favourite_download_index fdi " +
			"WHERE fdi.space = ${space.dbValue} AND fdi.manga_id = $mangaIdColumn AND $ownerPath)"
		return "($localCondition OR $ownerCondition)"
	}

	private fun sqlPathPredicate(pathColumn: String, roots: List<String>, excluded: List<String>): String {
		if (roots.isEmpty()) return "0"
		fun atOrBelow(rootPath: String): String {
			val path = sqlEscapeString(rootPath)
			val prefix = sqlEscapeString(rootPath.trimEnd(File.separatorChar) + File.separator)
			return "($pathColumn = $path OR instr($pathColumn, $prefix) = 1)"
		}
		val allowed = roots.joinToString(" OR ") { atOrBelow(it) }
		return if (excluded.isEmpty()) "($allowed)" else {
			val denied = excluded.joinToString(" OR ") { atOrBelow(it) }
			"(($allowed) AND NOT ($denied))"
		}
	}

	private fun buildSqlPathExists(
		table: String,
		idColumn: String,
		pathColumn: String,
		mangaIdColumn: String,
		rootPaths: List<String>,
		excludedRoots: List<String> = emptyList(),
	): String {
		if (rootPaths.isEmpty()) return "0"
		return "EXISTS(SELECT 1 FROM $table WHERE $idColumn = $mangaIdColumn AND " +
			sqlPathPredicate(pathColumn, rootPaths, excludedRoots) + ")"
	}

	/**
	 * Keep the database query broad for Downloaded-only pagination, then apply the exact persisted
	 * space-owned index snapshot to the bounded result window. This is still database-only.
	 */
	@Suppress("UNUSED_PARAMETER")
	fun getAnyDownloadedCondition(mangaIdColumn: String): String = "1"

	/**
	 * LOCAL-source helpers used for content-type classification. They read the persisted local index
	 * under configured roots and never scan the filesystem.
	 */
	suspend fun getLocalDownloadedIds(): Set<Long> {
		val downloadRoots = storageManager.getConfiguredDirs().map {
			File(it, LocalMangaOutput.DOWNLOADS_DIR_NAME)
		}
		return findIndexedEntriesInRoots(downloadRoots)
			.mapTo(HashSet()) { it.mangaId }
	}

	suspend fun getLocalNovelIds(): Set<Long> {
		val result = HashSet<Long>()
		val downloadRoots = storageManager.getConfiguredDirs().map {
			File(it, LocalMangaOutput.DOWNLOADS_DIR_NAME)
		}
		for (entry in findIndexedEntriesInRoots(downloadRoots)) {
			if (entry.path.isNovelContentPath()) {
				result += entry.mangaId
			}
		}
		return result
	}

	/**
	 * Keep other sources' historical-root classification untouched. Main/Gekko are
	 * active-destination-only even when an old ownership/index row remains in Room.
	 * All work here is index-backed; never scan CBZ files while rendering Favorites.
	 */
	private suspend fun filterActiveExhentaiCandidates(
		space: FavouriteSpace,
		candidates: List<Pair<Long, File>>,
	): Set<Long> {
		if (candidates.isEmpty()) return emptySet()
		val sourceById = HashMap<Long, String>()
		for (chunk in candidates.map { it.first }.distinct().chunked(INDEX_QUERY_CHUNK_SIZE)) {
			db.getMangaDao().findSourceNamesByIds(chunk).forEach { sourceById[it.mangaId] = it.source }
		}
		val standardRoots = getDownloadRoots(space)
		val legacyRoots = getActiveLegacyExhentaiIndexRoots(space)
		return candidates.asSequence()
			.filter { (id, file) ->
				when (sourceById[id]) {
					EhentaiSourceFamily.OFFICIAL_SOURCE_NAME,
					"TSUKI:GEKKOUSHI:gekkoushi:EXHENTAI" ->
						downloadDestinationStore.isVisibleInActiveExhentaiIndex(space, file) &&
							(file.isInsideAny(standardRoots) || file.isInsideAny(legacyRoots))
					// Do not let another source acquire Downloaded through a legacy ExHentai path.
					else -> file.isInsideAny(standardRoots)
				}
			}
			.mapTo(HashSet()) { it.first }
	}

	private fun File.isInsideAny(roots: Collection<File>): Boolean {
		val path = normalizedAbsolutePath()
		return roots.any { root ->
			val rootPath = root.normalizedAbsolutePath().trimEnd(File.separatorChar)
			path == rootPath || path.startsWith(rootPath + File.separator)
		}
	}

	private fun getDownloadRoots(space: FavouriteSpace): List<File> =
		downloadDestinationStore.readableRoots(space).map {
			File(it, LocalMangaOutput.DOWNLOADS_DIR_NAME)
		}

	/** Historical pre-downloads layout: <active root>/<recognized E-Hentai source>/<gallery>. */
	private fun getActiveLegacyExhentaiIndexRoots(space: FavouriteSpace): List<File> {
		val active = downloadDestinationStore.effectiveRoot(space) ?: return emptyList()
		return (sequenceOf("ExHentai (OTHER)") + EhentaiSourceFamily.legacySourceDirectoryNames.asSequence())
			.map { File(active, it) }.toList()
	}

	private fun canInferSpaceFromPath(): Boolean =
		downloadDestinationStore.privateUsesOwnRoot() && !downloadDestinationStore.rootsOverlap()

	private suspend fun findIndexedEntriesInRoots(downloadRoots: List<File>): List<LocalMangaIndexEntity> {
		if (downloadRoots.isEmpty()) return emptyList()
		val dao = db.getLocalMangaIndexDao()
		val result = LinkedHashMap<Long, LocalMangaIndexEntity>()
		for (rootPath in downloadRoots.map { it.normalizedAbsolutePath().trimEnd(File.separatorChar) }.distinct()) {
			for (entry in dao.findEntriesUnderRoot(rootPath, rootPath + File.separator)) {
				result.putIfAbsent(entry.mangaId, entry)
			}
		}
		return result.values.toList()
	}

	private fun List<LocalMangaIndexEntity>.filterToDownloadRoots(
		downloadRoots: List<File>,
	): List<LocalMangaIndexEntity> {
		if (isEmpty() || downloadRoots.isEmpty()) return emptyList()
		val rootPaths = downloadRoots.map { it.normalizedAbsolutePath().trimEnd(File.separatorChar) }
		return filter { entry ->
			val path = File(entry.path).normalizedAbsolutePath()
			rootPaths.any { rootPath ->
				path == rootPath || path.startsWith(rootPath + File.separator)
			}
		}
	}

	private fun File.normalizedAbsolutePath(): String =
		absoluteFile.path.trimEnd(File.separatorChar)

	private companion object {
		const val INDEX_QUERY_CHUNK_SIZE = 500
	}
}
