package org.koitharu.kotatsu.discovery.data

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koitharu.kotatsu.discovery.domain.*
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService

class TrackerCatalogRepository(
	providers: Set<TrackerCatalogProvider>,
	private val store: CatalogSnapshotStore,
	scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
	private val now: () -> Long = System::currentTimeMillis,
) {
	private val workScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
	private val providers = providers.associateBy { it.catalogService }
	val services: Set<ScrobblerService> get() = providers.keys
	private val mutex = Mutex()
	private val memory = LinkedHashMap<CatalogHomeRequest, TrackerCatalogHome>()
	private class Work(val task: Deferred<TrackerCatalogHome>, var readers: Int = 0)
	private val inFlight = mutableMapOf<CatalogHomeRequest, Work>()
	private val epochs = mutableMapOf<CatalogHomeRequest, Long>()
	private val cacheWrites = java.util.concurrent.ConcurrentHashMap<CatalogHomeRequest, Mutex>()
	private val detailsCache = LinkedHashMap<Pair<TrackerCatalogKey, Boolean>, Pair<Long, TrackerCatalogDetails>>()
	private val malCharactersCache = LinkedHashMap<TrackerCatalogKey, Pair<Long, List<TrackerCatalogPerson>>>()
	private val malStatisticsCache = LinkedHashMap<TrackerCatalogKey, Pair<Long, TrackerCatalogStatistics>>()

	fun provider(service: ScrobblerService): TrackerCatalogProvider = providers[service] ?: throw CatalogException(CatalogFailure(CatalogFailureKind.UNSUPPORTED))

	suspend fun cached(key: CatalogHomeRequest): TrackerCatalogHome? {
		val memoryValue = mutex.withLock { memory[key] }
		if (memoryValue != null && CatalogCachePolicy.isReadable(memoryValue.savedAt, now())) return memoryValue
		val disk = withContext(Dispatchers.IO) { store.read(key, now()) } ?: return null
		mutex.withLock { remember(key, disk) }
		return disk
	}

	/** Join identical public reads; the last departing subscriber cancels underlying provider work. */
	suspend fun refresh(key: CatalogHomeRequest): TrackerCatalogHome {
		val work = mutex.withLock {
			inFlight.getOrPut(key) {
				val epoch = (epochs[key] ?: 0) + 1
				epochs[key] = epoch
				Work(workScope.async(start = CoroutineStart.LAZY) { fetch(key, epoch) })
			}.also { it.readers++ }
		}
		work.task.start()
		return try { work.task.await() } finally {
			withContext(NonCancellable) { mutex.withLock {
				work.readers--
				if (work.readers == 0 && inFlight[key] === work) {
					inFlight.remove(key)
					if (!work.task.isCompleted) work.task.cancel()
				}
			} }
		}
	}

	private suspend fun fetch(key: CatalogHomeRequest, epoch: Long): TrackerCatalogHome {
		val previous = cached(key)
		val raw = provider(key.service).home(key.filter, key.allowAdult)
		currentCoroutineContext().ensureActive()
		if (raw.sections.isNotEmpty() && raw.sections.all { it.failure != null }) throw CatalogException(checkNotNull(raw.sections.first().failure))
		require(raw.sections.size <= CatalogCachePolicy.MAX_SECTIONS && raw.sections.distinctBy { it.key }.size == raw.sections.size)
		val sections = raw.sections.map { section ->
			require(section.key.service == key.service && section.key.kind.matches(key.filter))
			val allowedItemKinds = section.key.kind.itemKinds(key.filter)
			require(section.items.all { it.key.service == key.service && it.key.kind in allowedItemKinds })
			val items = section.items.mapNotNull { it.forPolicy(key.allowAdult) }.take(CatalogCachePolicy.HOME_PAGE_SIZE)
			if (section.failure != null) previous?.sections?.find { it.key == section.key }?.copy(failure = section.failure) ?: section.copy(items = emptyList())
			else section.copy(items = items)
		}
		// A partial refresh must not renew the lifetime of an older retained section indefinitely.
		val savedAt = if (sections.any { it.failure != null }) minOf(previous?.savedAt ?: now(), now() - CatalogCachePolicy.FRESH_MS - 1) else now()
		val home = TrackerCatalogHome(sections, savedAt)
		cacheWrites.getOrPut(key) { Mutex() }.withLock {
			currentCoroutineContext().ensureActive()
			if (mutex.withLock { epochs[key] == epoch }) {
				withContext(Dispatchers.IO) { store.write(key, home) }
				mutex.withLock { if (epochs[key] == epoch) remember(key, home) }
			}
		}
		return home
	}

	private fun remember(key: CatalogHomeRequest, home: TrackerCatalogHome) {
		memory.remove(key); memory[key] = home
		while (memory.size > CatalogCachePolicy.MAX_SNAPSHOTS) memory.remove(memory.keys.first())
	}

	suspend fun search(service: ScrobblerService, query: String, filter: CatalogFilter, adult: Boolean, page: Int): TrackerCatalogPage =
		provider(service).search(query, filter, adult, page).checked(service, filter.kinds, adult)

	suspend fun browse(key: CatalogSectionKey, adult: Boolean, page: Int): TrackerCatalogPage =
		provider(key.service).browseSection(key, adult, page).checked(key.service, key.kind.itemKinds(CatalogFilter.ALL), adult)

	suspend fun details(key: TrackerCatalogKey, adult: Boolean): TrackerCatalogDetails {
		val cacheKey = key to adult
		mutex.withLock { detailsCache[cacheKey] }?.takeIf { CatalogCachePolicy.isFresh(it.first, now()) }?.let { return it.second }
		val result = provider(key.service).details(key, adult)
		require(result.item.key == key && result.item.forPolicy(adult) != null && result.recommendations.all { it.key.service == key.service })
		val safe = result.copy(item = checkNotNull(result.item.forPolicy(adult)), recommendations = result.recommendations.mapNotNull { it.forPolicy(adult) }.take(20))
		if (!safe.partial) mutex.withLock {
			detailsCache.remove(cacheKey); detailsCache[cacheKey] = now() to safe
			while (detailsCache.size > 64) detailsCache.remove(detailsCache.keys.first())
		}
		return safe
	}

	internal suspend fun malCharacters(key: TrackerCatalogKey): List<TrackerCatalogPerson> {
		require(key.service == ScrobblerService.MAL)
		val mal = provider(ScrobblerService.MAL) as? MALCatalogProvider
			?: throw CatalogException(CatalogFailure(CatalogFailureKind.UNSUPPORTED))
		return cachedMALValue(malCharactersCache, key) { mal.supplementalCharacters(key) }
	}

	internal suspend fun malStatistics(key: TrackerCatalogKey): TrackerCatalogStatistics {
		require(key.service == ScrobblerService.MAL)
		val mal = provider(ScrobblerService.MAL) as? MALCatalogProvider
			?: throw CatalogException(CatalogFailure(CatalogFailureKind.UNSUPPORTED))
		return cachedMALValue(malStatisticsCache, key) { mal.supplementalStatistics(key) }
	}

	private suspend fun <T> cachedMALValue(
		cache: LinkedHashMap<TrackerCatalogKey, Pair<Long, T>>,
		key: TrackerCatalogKey,
		load: suspend () -> T,
	): T {
		mutex.withLock { cache[key] }?.takeIf { CatalogCachePolicy.isFresh(it.first, now()) }?.let { return it.second }
		val value = load()
		mutex.withLock {
			cache.remove(key)
			cache[key] = now() to value
			while (cache.size > 64) cache.remove(cache.keys.first())
		}
		return value
	}

	suspend fun characterDetails(service: ScrobblerService, person: TrackerCatalogPerson): TrackerCatalogCharacterDetails {
		val id = person.id?.toLongOrNull()?.takeIf { it > 0L && it.toString() == person.id }
			?: throw CatalogException(CatalogFailure(CatalogFailureKind.INVALID_DATA))
		val provider = provider(service)
		if (CatalogCapability.CHARACTER_DETAILS !in provider.catalogCapabilities) {
			throw CatalogException(CatalogFailure(CatalogFailureKind.UNSUPPORTED))
		}
		val result = provider.characterDetails(person)
		require(result.person.id == id.toString())
		return result.copy(person = result.person.copy(roles = person.roles))
	}

	private fun TrackerCatalogPage.checked(service: ScrobblerService, kinds: List<CatalogMediaKind>, adult: Boolean): TrackerCatalogPage {
		require(items.all { it.key.service == service && it.key.kind in kinds })
		return copy(items = items.mapNotNull { it.forPolicy(adult) }.distinctBy { it.key }, nextPage = nextPage?.takeIf { it in 2..CatalogCachePolicy.MAX_PAGE })
	}

	private fun CatalogMediaKind.matches(filter: CatalogFilter): Boolean =
		this in filter.kinds || this == CatalogMediaKind.MIXED && filter == CatalogFilter.ALL

	private fun CatalogMediaKind.itemKinds(filter: CatalogFilter): List<CatalogMediaKind> = when (this) {
		CatalogMediaKind.MIXED -> filter.kinds
		CatalogMediaKind.MANGA, CatalogMediaKind.NOVEL -> listOf(this)
	}
}
