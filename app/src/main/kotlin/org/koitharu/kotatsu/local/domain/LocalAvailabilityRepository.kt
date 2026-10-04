package org.koitharu.kotatsu.local.domain

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
import org.koitharu.kotatsu.local.data.LocalFavouritesRepository
import javax.inject.Inject

class LocalAvailabilityRepository @Inject constructor(
	private val localFavouritesRepository: LocalFavouritesRepository,
) {
	/** Emit immediately, then load the durable Normal snapshot. Source search never waits for storage
	 * discovery, and updates from the Local shelf refresh decorate results already on screen. Private
	 * files remain excluded by the existing space-aware Local projection.
	 */
	fun titleKeys(): Flow<Set<String>> = flow {
		emit(emptySet())
		localFavouritesRepository.ensureSnapshotInitialized(FavouriteSpace.NORMAL)
		emitAll(localFavouritesRepository.items(FavouriteSpace.NORMAL).map { it.localTitleKeys() })
	}.catch {
		it.printStackTraceDebug()
		emit(emptySet())
	}.distinctUntilChanged().flowOn(Dispatchers.Default)
}
