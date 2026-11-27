package org.koitharu.kotatsu.local.domain

import org.koitharu.kotatsu.favourites.data.FavouriteSpace
import org.koitharu.kotatsu.favourites.domain.NormalDownloadObservationStore
import org.koitharu.kotatsu.favourites.domain.NormalDownloadScope
import org.koitharu.kotatsu.local.domain.model.LocalManga
import org.koitharu.kotatsu.local.data.verifiedLocalProjection
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
		if (result.uniqueCandidate == null) return null
		return result.verifiedLocalProjection() ?: throw IOException("Cannot verify downloaded chapter identity")
	}

	internal fun observeDetailsChanges() = observations.observeDetailsChanges()

	internal suspend fun withDetailsProjection(
		manga: Manga, space: FavouriteSpace?, apply: (Boolean, Boolean, LocalManga?) -> Unit,
	) = observations.withDetailsProjection(manga, space, apply)

	internal suspend fun readDetailsCopy(manga: Manga, space: FavouriteSpace?): LocalManga? {
		var local: LocalManga? = null
		withDetailsProjection(manga, space) { scoped, _, copy -> if (scoped) local = copy }
		return local
	}
}
