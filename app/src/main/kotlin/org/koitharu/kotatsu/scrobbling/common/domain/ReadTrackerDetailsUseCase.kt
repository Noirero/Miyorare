package org.koitharu.kotatsu.scrobbling.common.domain

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.scrobbling.common.data.ScrobblingEntity
import org.koitharu.kotatsu.scrobbling.common.domain.model.*
import javax.inject.Inject

class ReadTrackerDetailsUseCase @Inject constructor(
	private val database: MangaDatabase,
	private val repositories: ScrobblerRepositoryMap,
) {
	/** Screen-owned call: no jobs, discovery, writes, cache, or automatic retries. */
	suspend operator fun invoke(
		mangaId: Long,
		services: Set<ScrobblerService>,
		requested: Map<TrackerContent, TrackerPage>,
		policy: TrackerDetailsReadPolicy = TrackerDetailsReadPolicy(),
	): List<AssociatedTrackerDetails> = withContext(Dispatchers.IO) {
		if (!policy.allowsNetwork || requested.isEmpty()) {
			return@withContext services.map { AssociatedTrackerDetails(it, null, TrackerDetailsReadState.SUPPRESSED) }
		}
		val associations = database.getScrobblingDao().findAll(mangaId)
		services.map { service ->
			currentCoroutineContext().ensureActive()
			// A corrupt/ambiguous persisted mapping is not permission to choose a target or fuzzy-link.
			val target = associatedTrackerTarget(service, associations)
			if (target == null) {
				AssociatedTrackerDetails(service, null, TrackerDetailsReadState.NO_MAPPING)
			} else {
				readAssociatedTrackerDetails(target, repositories[service] as? TrackerDetailsProvider, requested)
			}
		}
	}
}

internal fun associatedTrackerTarget(service: ScrobblerService, associations: List<ScrobblingEntity>): TrackerTarget? {
	val ids = associations.filter { it.scrobbler == service.id }.map { it.targetId }.distinct()
	return ids.singleOrNull()?.takeIf { it > 0 }?.let { TrackerTarget(service, it.toString()) }
}

/** Each capability fails independently. Sequential requests keep provider fan-out bounded to one. */
internal suspend fun readAssociatedTrackerDetails(
	target: TrackerTarget,
	provider: TrackerDetailsProvider?,
	requested: Map<TrackerContent, TrackerPage>,
): AssociatedTrackerDetails {
	if (provider == null) return AssociatedTrackerDetails(target.service, target, TrackerDetailsReadState.UNSUPPORTED)
	require(provider.detailsService == target.service)
	if (!provider.isAuthorized) return AssociatedTrackerDetails(target.service, target, TrackerDetailsReadState.AUTH_REQUIRED)
	suspend fun <T> read(content: TrackerContent, load: suspend (TrackerPage) -> TrackerResult<T>): TrackerResult<T> {
		val page = requested[content] ?: return TrackerResult.NotRequested
		if (content !in provider.detailsCapabilities) return TrackerResult.Unsupported
		currentCoroutineContext().ensureActive()
		if (!provider.isAuthorized) return TrackerResult.NotRequested
		return try {
			load(page).also { currentCoroutineContext().ensureActive() }
		} catch (e: CancellationException) {
			throw e
		} catch (e: Exception) {
			currentCoroutineContext().ensureActive()
			TrackerResult.Error(e)
		}
	}
	val result = AssociatedTrackerDetails(
		target.service, target, TrackerDetailsReadState.LOADED,
		characters = read(TrackerContent.CHARACTERS) { provider.loadCharacters(target, it) },
		staff = read(TrackerContent.STAFF) { provider.loadStaff(target, it) },
		recommendations = read(TrackerContent.RECOMMENDATIONS) { provider.loadRecommendations(target, it) },
	)
	return if (provider.isAuthorized) result else AssociatedTrackerDetails(target.service, target, TrackerDetailsReadState.AUTH_REQUIRED)
}

