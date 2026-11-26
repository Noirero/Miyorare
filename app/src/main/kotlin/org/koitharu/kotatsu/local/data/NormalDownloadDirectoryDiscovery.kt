package org.koitharu.kotatsu.local.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.download.domain.ActiveNormalDownloadRoots
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
import org.koitharu.kotatsu.favourites.domain.NormalDownloadScope
import org.koitharu.kotatsu.local.data.index.LocalMangaIndex
import org.koitharu.kotatsu.local.data.output.LocalMangaOutput
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
}
