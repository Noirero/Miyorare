package org.koitharu.kotatsu.local.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.download.domain.ActiveNormalDownloadRoots
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
import org.koitharu.kotatsu.favourites.domain.NormalDownloadScope
import org.koitharu.kotatsu.local.data.index.LocalMangaIndex
import org.koitharu.kotatsu.local.data.output.LocalMangaOutput
import org.koitharu.kotatsu.local.domain.model.LocalManga
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.sources.compat.SourceAliasRegistry
import org.koitharu.kotatsu.sources.compat.VerifiedSourceAliases
import java.io.File
import javax.inject.Inject

/** Bounded candidates, not a confirmed presence/absence observation or authorization. */
internal data class NormalDirectoryCandidates(
	val roots: ActiveNormalDownloadRoots,
	val available: Boolean,
	val deterministicPaths: Set<File>,
	val persistedHints: Set<File>,
)

/**
 * Enumerate exact title directory candidates under the active root before looking at any artifact.
 * Persisted aliases/index/ownership are hints only; they must still pass container identity and
 * space isolation checks. A stale hint never masks a deterministic active candidate.
 */
internal class NormalDownloadDirectoryDiscovery @Inject constructor(
	private val scope: NormalDownloadScope,
	private val sourceAliases: SourceAliasRegistry,
	private val db: MangaDatabase,
	private val index: LocalMangaIndex,
) {
	suspend fun discover(manga: Manga, space: FavouriteSpace?): NormalDirectoryCandidates? {
		val roots = scope.resolveActiveLookup(manga, space) ?: return null
		return withContext(Dispatchers.IO) {
			val root = roots.normalRoot
			if (root == null || !root.isDirectory || !root.canRead()) {
				return@withContext NormalDirectoryCandidates(roots, false, emptySet(), emptySet())
			}
			val canonicalSource = sourceAliases.canonicalId(manga.source.name)
			val alias = VerifiedSourceAliases.entries.singleOrNull { it.canonicalId == canonicalSource }
			val paths = LocalMangaOutput.normalScannerDirectoryCandidates(
				root, manga, alias?.let { it.canonicalDisplayName to it.language },
			)
			val hints = buildList {
				db.getFavouriteDownloadIndexDao().findEntry(FavouriteSpace.NORMAL.dbValue, manga.id)?.path?.let { add(File(it)) }
				db.getLocalMangaIndexDao().findPath(manga.id)?.let { add(File(it)) }
				index.getDownloadAliasPaths(listOf(manga.id))[manga.id]?.let { add(File(it)) }
			}
			fun containedDirectories(files: Collection<File>): Set<File> = files.mapNotNullTo(LinkedHashSet()) { file ->
				if (!scope.isWithinActiveRoot(file, root, roots.privateRoot)) return@mapNotNullTo null
				runCatching {
					file.canonicalFile.takeIf { it.isDirectory && !it.name.startsWith(".") &&
						!File(it, ".notamanga").exists() }
				}.getOrNull()
			}
			NormalDirectoryCandidates(roots, true, containedDirectories(paths), containedDirectories(hints))
		}
	}

	/**
	 * Verify one indexed candidate at the discovery boundary. Not an authoritative observation,
	 * a complete search, or action authorization. Sidecar-free/other layouts need their own
	 * positive container proof. Root and ownership changes must be fenced before publication.
	 */
	suspend fun verifyIndexedCandidate(manga: Manga, space: FavouriteSpace?, candidate: File): LocalManga? {
		val candidates = discover(manga, space) ?: return null
		if (!candidates.available) return null
		return withContext(Dispatchers.IO) {
			runCatchingCancellable {
				val physical = candidate.canonicalFile
				if (physical !in candidates.deterministicPaths && physical !in candidates.persistedHints) {
					return@runCatchingCancellable null
				}
				val privateRoot = candidates.roots.privateRoot?.canonicalFile ?: return@runCatchingCancellable null
				if (privateRoot != candidates.roots.normalRoot?.canonicalFile &&
					privateRoot.path.startsWith(physical.path + File.separator)
				) return@runCatchingCancellable null
				// Ownership writers persist canonical paths. Include ancestors and descendants:
				// a foreign chapter row inside a directory must not be hidden by a Normal parent.
				val paths = generateSequence(physical) { it.parentFile }.map { it.path }.toList()
				val owners = db.getFavouriteDownloadIndexDao().findOverlappingCanonicalPaths(
					paths, physical.path + File.separator, physical.path + "0",
				)
				if (owners.any { it.space != FavouriteSpace.NORMAL.dbValue || it.mangaId != manga.id }) {
					return@runCatchingCancellable null
				}
				val exactNormal = owners.any { it.path == physical.path }
				val inPrivate = physical == privateRoot || physical.path.startsWith(
					privateRoot.path.trimEnd(File.separatorChar) + File.separator,
				)
				if (inPrivate && !exactNormal) return@runCatchingCancellable null

				NormalDownloadDirectoryReconciler.reconcileIndexed(manga, physical)?.let {
					return@runCatchingCancellable it
				}
				// Alias preparation can precede the Room transfer. Require committed Normal
				// ownership in addition to the exact alias and indexed source/locator proof.
				if (!exactNormal) return@runCatchingCancellable null
				val alias = index.getDownloadAliasForScanner(manga.id) ?: return@runCatchingCancellable null
				NormalDownloadDirectoryReconciler.reconcileMigratedIndexed(manga, physical, alias, sourceAliases)
			}.getOrNull()
		}
	}
}
