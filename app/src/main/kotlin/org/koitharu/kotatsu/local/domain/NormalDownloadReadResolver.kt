package org.koitharu.kotatsu.local.domain

import org.koitharu.kotatsu.favourites.data.FavouriteSpace
import org.koitharu.kotatsu.favourites.domain.NormalDownloadObservationStore
import org.koitharu.kotatsu.favourites.domain.NormalDownloadScope
import org.koitharu.kotatsu.local.domain.model.LocalManga
import org.koitharu.kotatsu.parsers.model.Manga
import java.io.IOException
import javax.inject.Inject

/**
 * New Reader opens use current Normal proof, not a historical saved-copy lookup.
 * Unavailable/confirmed absence may use the online source; ambiguous or changing
 * evidence is not confirmed absence. Read resolution never authorizes mutation.
 */
class NormalDownloadReadResolver @Inject internal constructor(
	private val scope: NormalDownloadScope,
	private val observations: NormalDownloadObservationStore,
) {
	internal suspend fun appliesTo(manga: Manga, space: FavouriteSpace?) = scope.appliesTo(manga, space)

	internal suspend fun resolve(manga: Manga, space: FavouriteSpace?): LocalManga? {
		val result = observations.resolveForRead(manga, space) ?: return null
		if (!result.inventoryComplete || result.unresolvedPaths.isNotEmpty() || result.verified.size > 1) {
			throw IOException("Cannot verify the current Normal download")
		}
		val physical = result.uniqueCandidate ?: return null
		val migrated = result.migratedArchiveIdentity
		if (migrated != null) {
			// MangaIndex selects by embedded ID. Keep that selector separate from the
			// logical remote ID; do not synthesize a new personal identity for old extras.
			val selectors = migrated.selectorsByRemoteId
			return physical.copy(
				manga = physical.manga.copy(chapters = selectors.map { (id, chapter) -> chapter.copy(id = id) }),
				normalReaderSelectors = selectors,
			)
		}
		val projection = if (physical.file.isDirectory) {
			result.directoryIdentity?.projection ?: throw IOException("Cannot verify downloaded chapter identity")
		} else physical
		return projection.copy(normalReaderSelectors = projection.manga.chapters.orEmpty().associateBy { it.id })
	}
}
