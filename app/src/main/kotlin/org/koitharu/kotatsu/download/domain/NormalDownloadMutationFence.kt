package org.koitharu.kotatsu.download.domain

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.core.util.MultiMutex
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
import org.koitharu.kotatsu.favourites.domain.NormalDownloadObservationStore
import org.koitharu.kotatsu.favourites.domain.NormalDownloadScope
import org.koitharu.kotatsu.parsers.model.Manga
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Durable producer boundary, not a download lock or permission to mutate.
 * A pinned old-root task invalidates the affected title, never publishes its old path.
 * The existing writer owns its actual operation and result; revalidation is queued
 * even if an operation throws after a partial commit or is cancelled.
 */
@Singleton
class NormalDownloadMutationFence @Inject internal constructor(
	private val scope: NormalDownloadScope,
	private val observations: NormalDownloadObservationStore,
) {
	// Stable manga ID, not structural Manga equality. Serialize only durable mutations
	// with explicit Normal actions, not the page/network transfer.
	private val durableLocks = MultiMutex<Long>()

	internal suspend fun <T> mutate(manga: Manga, space: FavouriteSpace, block: suspend () -> T): T {
		if (space == FavouriteSpace.PRIVATE) return block()
		val applies = try {
			scope.appliesTo(manga, space)
		} catch (error: CancellationException) {
			throw error
		} catch (error: Exception) {
			// An extra observation-only eligibility lookup must not prevent the
			// original writer/partial cleanup. Conservatively invalidate this ID;
			// the next scan still requires actual eligibility and active-root proof.
			error.printStackTraceDebug()
			true
		}
		if (!applies) return block()
		return durableLocks.withLock(manga.id) { withIntent(manga.id, block) }
	}

	/** Verify before registering intent, while excluding other Normal durable writers. */
	internal suspend fun <T> mutateVerified(
		manga: Manga, space: FavouriteSpace?, verify: suspend () -> Unit, block: suspend () -> T,
	): T = durableLocks.withLock(manga.id) {
		check(scope.appliesTo(manga, space)) { "Normal download context changed" }
		verify()
		withIntent(manga.id, block)
	}

	private suspend fun <T> withIntent(mangaId: Long, block: suspend () -> T): T {
		var token: Long? = null
		try {
			// Assign inside the context: a cancellation on return must not lose the token.
			withContext(NonCancellable) { token = observations.beginMutation(mangaId) }
			currentCoroutineContext().ensureActive()
			return block()
		} finally {
			token?.let { acquired ->
				withContext(NonCancellable) { observations.finishMutation(mangaId, acquired) }
			}
		}
	}
}
