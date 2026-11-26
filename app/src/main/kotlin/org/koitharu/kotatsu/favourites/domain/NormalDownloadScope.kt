package org.koitharu.kotatsu.favourites.domain

import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.model.isLocal
import org.koitharu.kotatsu.core.model.isNovelContent
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
import org.koitharu.kotatsu.download.domain.ActiveNormalDownloadRoots
import org.koitharu.kotatsu.download.domain.DownloadDestinationStore
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.sources.compat.EhentaiSourceFamily
import org.koitharu.kotatsu.sources.compat.SourceAliasRegistry
import java.io.File
import javax.inject.Inject

/**
 * Applicability and physical boundary for the Normal download scanner.
 *
 * This is not an ownership, readability or action authorization check. A caller must still
 * establish artifact identity and shared-root isolation, and revalidate before publication.
 * Do not use this per card or chapter binding; resolve scope at the work boundary.
 */
class NormalDownloadScope @Inject constructor(
	private val db: MangaDatabase,
	private val sourceAliases: SourceAliasRegistry,
	private val destinations: DownloadDestinationStore,
) {

	/**
	 * The caller supplies verified navigation provenance, not a defaulted Normal value.
	 * Without explicit provenance only an actual Normal-only member opts into this policy.
	 * Root availability never changes applicability.
	 */
	suspend fun appliesTo(manga: Manga, space: FavouriteSpace?): Boolean {
		if (space == FavouriteSpace.PRIVATE || manga.source.isLocal || manga.isNovelContent) return false
		val source = manga.source.name
		if (EhentaiSourceFamily.supportsTitleMatchedDownloads(source)) return false
		val eligibleMembership = db.withTransaction {
			db.getFavouritesDao().findCategoriesCount(manga.id) != 0 &&
				(space == FavouriteSpace.NORMAL || db.getPrivateFavouritesDao().findCategoriesCount(manga.id) == 0)
		}
		return eligibleMembership && sourceAliases.canonicalId(source) != EhentaiSourceFamily.CANONICAL_ID
	}

	/**
	 * Entry point for active Normal discovery. Null means outside scanner scope, never
	 * that an eligible title may use historical roots. An eligible result can contain an
	 * unavailable or unresolved root; callers must not fall back to the legacy lookup.
	 * This selects roots only: it does not resolve an artifact or authorize any action.
	 */
	suspend fun resolveActiveLookup(manga: Manga, space: FavouriteSpace?): ActiveNormalDownloadRoots? {
		if (!appliesTo(manga, space)) return null
		return withContext(Dispatchers.IO) { destinations.captureNormalScannerRoots() }
	}

	/**
	 * Check only the captured active destinations, never the historical readable-root list.
	 * An unavailable root fails closed without creating directories or selecting a fallback.
	 * A shared root passes containment only; it does not prove the artifact belongs to Normal.
	 */
	fun isWithinActiveRoot(file: File, normalRoot: File?, privateRoot: File?): Boolean {
		if (normalRoot == null) return false
		return runCatching {
			val active = normalRoot.canonicalFile
			if (!active.isDirectory || !active.canRead()) return@runCatching false
			val target = file.canonicalFile
			if (!target.isBelow(active)) return@runCatching false
			val other = privateRoot?.canonicalFile
			other == null || !other.isBelow(active) || (target != other && !target.isBelow(other))
		}.getOrDefault(false)
	}

	private fun File.isBelow(root: File): Boolean =
		path.startsWith(root.path.trimEnd(File.separatorChar) + File.separator) && this != root
}
