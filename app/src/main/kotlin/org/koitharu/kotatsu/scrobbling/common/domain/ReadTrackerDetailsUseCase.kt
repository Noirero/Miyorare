package org.koitharu.kotatsu.scrobbling.common.domain

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.scrobbling.common.data.ScrobblingEntity
import org.koitharu.kotatsu.scrobbling.common.data.TrackerRelationsReader
import org.koitharu.kotatsu.scrobbling.common.domain.model.*
import javax.inject.Inject

class ReadTrackerDetailsUseCase @Inject constructor(
	private val database: MangaDatabase,
	private val repositories: ScrobblerRepositoryMap,
	private val relationsReader: TrackerRelationsReader,
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
		// Relations live in the same post-chapter discovery section as recommendations, so they share
		// its lazy demand without sharing its domain/result semantics.
		val effectiveRequested = if (TrackerContent.RECOMMENDATIONS in requested && TrackerContent.RELATIONS !in requested) {
			requested + (TrackerContent.RELATIONS to checkNotNull(requested[TrackerContent.RECOMMENDATIONS]))
		} else {
			requested
		}
		val associations = database.getScrobblingDao().findAll(mangaId)
		services.map { service ->
			currentCoroutineContext().ensureActive()
			// A corrupt/ambiguous persisted mapping is not permission to choose a target or fuzzy-link.
			val target = associatedTrackerTarget(service, associations)
			if (target == null) {
				AssociatedTrackerDetails(service, null, TrackerDetailsReadState.NO_MAPPING)
			} else {
				readAssociatedTrackerDetails(target, repositories[service] as? TrackerDetailsProvider, effectiveRequested, relationsReader::load)
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
	loadRelations: suspend (TrackerTarget, TrackerPage) -> TrackerResult<TrackerRelation> = { _, _ -> TrackerResult.Unsupported },
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
	suspend fun readRelations(): TrackerResult<TrackerRelation> {
		val page = requested[TrackerContent.RELATIONS] ?: return TrackerResult.NotRequested
		currentCoroutineContext().ensureActive()
		if (!provider.isAuthorized) return TrackerResult.NotRequested
		return try {
			loadRelations(target, page).also { currentCoroutineContext().ensureActive() }
		} catch (e: CancellationException) {
			throw e
		} catch (e: Exception) {
			currentCoroutineContext().ensureActive()
			TrackerResult.Error(e)
		}
	}
	val relations = readRelations()
	val recommendations = read(TrackerContent.RECOMMENDATIONS) { provider.loadRecommendations(target, it) }
		.withRelationNavigation(relations)
	val result = AssociatedTrackerDetails(
		target.service, target, TrackerDetailsReadState.LOADED,
		characters = read(TrackerContent.CHARACTERS) { provider.loadCharacters(target, it) },
		staff = read(TrackerContent.STAFF) { provider.loadStaff(target, it) },
		relations = relations,
		recommendations = recommendations,
	)
	return if (provider.isAuthorized) result else AssociatedTrackerDetails(target.service, target, TrackerDetailsReadState.AUTH_REQUIRED)
}

internal fun TrackerRelation.navigationRecommendation(): TrackerRecommendation = TrackerRecommendation(
	target = target,
	title = title,
	image = image,
	kind = TrackerRecommendationKind.RELATION,
)

private fun TrackerResult<TrackerRelation>.relationNavigationItems(): List<TrackerRecommendation> = when (this) {
	is TrackerResult.Success -> items.map(TrackerRelation::navigationRecommendation)
	is TrackerResult.Partial -> items.map(TrackerRelation::navigationRecommendation)
	else -> emptyList()
}

internal fun TrackerResult<TrackerRecommendation>.withRelationNavigation(
	relations: TrackerResult<TrackerRelation>,
): TrackerResult<TrackerRecommendation> {
	val proxies = relations.relationNavigationItems()
	if (proxies.isEmpty()) return this
	return when (this) {
		is TrackerResult.Success -> copy(items = proxies + items)
		is TrackerResult.Partial -> copy(items = proxies + items)
		is TrackerResult.Empty -> TrackerResult.Success(proxies, next)
		TrackerResult.Unsupported, TrackerResult.NotRequested -> TrackerResult.Success(proxies)
		is TrackerResult.Error -> TrackerResult.Partial(proxies, setOf(TrackerPartialReason.PROVIDER_ERROR))
	}
}

