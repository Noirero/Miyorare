package org.koitharu.kotatsu.favourites.domain

import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.download.domain.DownloadDestinationStore
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
import org.koitharu.kotatsu.favourites.data.NormalDownloadObservationDao
import org.koitharu.kotatsu.favourites.data.NormalDownloadObservationEntity
import org.koitharu.kotatsu.local.data.NormalCandidateResolution
import org.koitharu.kotatsu.local.data.NormalDownloadDirectoryDiscovery
import org.koitharu.kotatsu.parsers.model.Manga
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

internal enum class NormalObservationStatus { PRESENT, ABSENT, UNKNOWN, UNAVAILABLE }

/** Immutable minimum read identity, never physical action authorization. */
internal data class NormalObservedChapter(val id: Long, val uri: String, val remoteId: Long?)

internal data class NormalObservationFact(
	val status: NormalObservationStatus,
	val path: String? = null,
	// Null is unresolved identity, empty is a concrete empty projection.
	val chapters: List<NormalObservedChapter>? = null,
	// Embedded Reader selectors are not re-keyed personal-state chapters.
	val archiveSelectors: List<NormalObservedChapter>? = null,
)

internal data class NormalObservationRoot(
	val generation: Long,
	val normal: String?,
	val privatePath: String?,
	val historicalPrivate: List<String>,
	val available: Boolean,
)

internal data class NormalObservationContext(
	val source: String,
	val title: String,
	val url: String,
	val detailsRevision: Long,
	val chaptersInitialized: Boolean,
	val normalCategories: List<Long>,
	val privateCategories: List<Long>,
	val chapterLocators: List<Triple<Long, String, String>>?,
)

internal data class NormalObservationWork(
	val manga: Manga,
	val space: FavouriteSpace?,
	val session: String,
	val rootEpoch: Long,
	val root: NormalObservationRoot,
	val revision: Long,
	val ticket: Long,
	val context: NormalObservationContext,
)

internal data class NormalObservationSnapshot(
	val row: NormalDownloadObservationEntity,
	val fact: NormalObservationFact,
)

internal data class NormalObservationRequests(val full: Boolean, val ids: Set<Long>)

/**
 * Phase 3 boundary. Filesystem enumeration happens outside the mutex. Only metadata
 * checks, observation writes and read acceptance use it. Root edits use their existing
 * non-suspending lock, so Room writes are tagged then checked again at visibility.
 *
 * Persisted rows are not trusted across process restart: a new session must revalidate.
 * Concrete chapter identity is held only for a currently verified observation.
 * Producers must register intent BEFORE mutation and finish in a cancellation-safe
 * finally block; this core does not yet hook actual workers, membership edits or external files.
 * No consumer uses this as authority yet.
 */
@Singleton
internal class NormalDownloadObservationStore(
	private val db: MangaDatabase,
	private val destinations: DownloadDestinationStore,
	private val scope: NormalDownloadScope,
	private val discovery: NormalDownloadDirectoryDiscovery,
	private val dao: NormalDownloadObservationDao,
) {
	@Inject constructor(
		db: MangaDatabase, destinations: DownloadDestinationStore, scope: NormalDownloadScope,
		discovery: NormalDownloadDirectoryDiscovery,
	) : this(db, destinations, scope, discovery, db.getNormalDownloadObservationDao())

	private data class Published(val snapshot: NormalObservationSnapshot, val context: NormalObservationContext)
	private val mutex = Mutex()
	private val session = UUID.randomUUID().toString()
	private var root: NormalObservationRoot? = null
	private var epoch = 0L
	private var sequence = 0L
	private val revisions = HashMap<Long, Long>()
	private val tickets = HashMap<Long, Long>()
	private val mutations = HashMap<Long, MutableSet<Long>>()
	private val published = HashMap<Long, Published>()
	private val pending = LinkedHashSet<Long>()
	private var fullPending = true
	private val _changes = MutableStateFlow(0L)
	val changes = _changes.asStateFlow()
	val rootChanges get() = destinations.normalScannerRootChanges

	private fun changed() { _changes.value += 1L }
	private fun revision(id: Long) = revisions[id] ?: 0L

	private fun invalidate(id: Long) {
		revisions[id] = revision(id) + 1L
		tickets.remove(id)
		published.remove(id)
		pending += id
		changed()
	}

	// Only short root stat/canonical checks, not a directory inventory.
	private suspend fun refreshRoot(): NormalObservationRoot? {
		val captured = withContext(Dispatchers.IO) {
			val roots = destinations.captureNormalScannerRoots()
			fun path(file: File?) = file?.let { rootFile ->
				runCatching { rootFile.canonicalPath }.getOrElse { rootFile.absoluteFile.normalize().path }
			}
			NormalObservationRoot(
				roots.generation, path(roots.normalRoot), path(roots.privateRoot),
				roots.historicalPrivateRoots.mapNotNull(::path).distinct().sorted(),
				roots.normalRoot?.let { it.isDirectory && it.canRead() } == true,
			)
		}
		return destinations.withDestinationMutationLock {
			if (captured.generation != rootChanges.value) return@withDestinationMutationLock null
			if (root != captured) {
				root = captured
				epoch += 1L
				published.clear()
				tickets.clear()
				fullPending = true
				changed()
			}
			captured
		}
	}

	private suspend fun context(manga: Manga, space: FavouriteSpace?): NormalObservationContext? {
		if (!scope.appliesTo(manga, space)) return null
		val stored = db.getMangaDao().find(manga.id)?.manga ?: return null
		if (stored.source != manga.source.name || stored.title != manga.title || stored.url != manga.url) return null
		return NormalObservationContext(
			stored.source, stored.title, stored.url, stored.detailsUpdatedAt, stored.chaptersInitialized,
			db.getFavouritesDao().findCategoriesIds(manga.id).sorted(),
			db.getPrivateFavouritesDao().findCategoriesIds(manga.id).sorted(),
			manga.chapters?.map { Triple(it.id, it.source.name, it.url) },
		)
	}

	suspend fun begin(manga: Manga, space: FavouriteSpace?): NormalObservationWork? = mutex.withLock {
		val active = refreshRoot() ?: return@withLock null
		val id = manga.id
		if (!mutations[id].isNullOrEmpty()) return@withLock null
		val ctx = db.withTransaction { context(manga, space) } ?: return@withLock null
		val ticket = ++sequence
		tickets[id] = ticket
		NormalObservationWork(manga, space, session, epoch, active, revision(id), ticket, ctx)
	}

	private fun valid(work: NormalObservationWork, active: NormalObservationRoot?) =
		work.session == session && work.rootEpoch == epoch && work.root == active &&
			work.root.generation == rootChanges.value && revision(work.manga.id) == work.revision &&
			tickets[work.manga.id] == work.ticket && mutations[work.manga.id].isNullOrEmpty()

	suspend fun scan(manga: Manga, space: FavouriteSpace?): Boolean {
		val work = begin(manga, space) ?: return false
		val result = if (work.root.available) discovery.resolveCandidates(manga, space) else null
		return publish(work, result)
	}

	private fun fact(work: NormalObservationWork, result: NormalCandidateResolution?): NormalObservationFact {
		if (!work.root.available) return NormalObservationFact(NormalObservationStatus.UNAVAILABLE)
		if (result == null || !result.inventoryComplete || result.unresolvedPaths.isNotEmpty() ||
			result.verified.size > 1
		) return NormalObservationFact(NormalObservationStatus.UNKNOWN)
		val candidate = result.uniqueCandidate
		if (candidate == null || candidate.manga.chapters.isNullOrEmpty()) {
			return NormalObservationFact(NormalObservationStatus.ABSENT, chapters = emptyList())
		}
		val active = work.root.normal ?: return NormalObservationFact(NormalObservationStatus.UNKNOWN)
		if (!candidate.file.path.startsWith(active.trimEnd(File.separatorChar) + File.separator)) {
			return NormalObservationFact(NormalObservationStatus.UNKNOWN)
		}
		val currentIds = work.manga.chapters.orEmpty().mapTo(HashSet()) { it.id }
		val chapters = result.directoryIdentity?.projection?.manga?.chapters?.map {
			NormalObservedChapter(it.id, it.url, it.id.takeIf(currentIds::contains))
		}
		val selectors = result.migratedArchiveIdentity?.selectorsByRemoteId?.entries?.map {
			NormalObservedChapter(it.value.id, it.value.url, it.key)
		}
		// Direct and migrated archive personal-state projections remain unresolved here.
		return NormalObservationFact(NormalObservationStatus.PRESENT, candidate.file.path, chapters, selectors)
	}

	/** Stale positive AND negative are rejected; a row written during a root switch is invisible. */
	suspend fun publish(work: NormalObservationWork, result: NormalCandidateResolution?): Boolean = mutex.withLock {
		val id = work.manga.id
		fun rejected(): Boolean { pending += id; return false }
		if (!valid(work, refreshRoot())) return@withLock rejected()
		val fact = fact(work, result)
		val old = published[id]
		val same = old != null && old.context == work.context && old.snapshot.fact == fact
		val nextRevision = if (same) work.revision else work.revision + 1L
		val row = NormalDownloadObservationEntity(id, session, epoch, nextRevision,
			work.root.normal, fact.status.name, fact.path)
		val saved = db.withTransaction {
			if (context(work.manga, work.space) != work.context) return@withTransaction false
			val existing = dao.findCurrent(session, epoch, listOf(id)).singleOrNull()
			if (existing != row) dao.upsert(row)
			true
		}
		if (!saved || !valid(work, refreshRoot()) ||
			db.withTransaction { context(work.manga, work.space) } != work.context
		) {
			if (tickets[id] == work.ticket) invalidate(id)
			return@withLock rejected()
		}
		destinations.withDestinationMutationLock {
			if (!valid(work, root)) return@withDestinationMutationLock rejected()
			revisions[id] = nextRevision
			tickets.remove(id)
			published[id] = Published(NormalObservationSnapshot(row, fact), work.context)
			pending.remove(id)
			if (!same) changed()
			true
		}
	}

	/**
	 * Bounded query boundary, not a raw DAO feed. SQL-derived facts and cached chapter
	 * projection must share row/session/epoch/revision and membership/source context.
	 * A late asynchronous consumer must revalidate its snapshot before applying it.
	 */
	suspend fun readVisible(mangas: Collection<Manga>, space: FavouriteSpace?): Map<Long, NormalObservationSnapshot> =
		mutex.withLock {
			val active = refreshRoot() ?: return@withLock emptyMap()
			val capturedEpoch = epoch
			val result = db.withTransaction {
				val rows = mangas.map { it.id }.distinct().chunked(500).flatMap {
					dao.findCurrent(session, capturedEpoch, it)
				}.associateBy { it.mangaId }
				buildMap<Long, NormalObservationSnapshot> {
					for (manga in mangas) {
						val id = manga.id
						val ctx = context(manga, space)
						val value = published[id]
						if (ctx == null || (value != null && value.context != ctx)) {
							if (value != null || tickets.containsKey(id)) invalidate(id)
							continue
						}
						if (!active.available) {
							put(id, NormalObservationSnapshot(
								NormalDownloadObservationEntity(id, session, capturedEpoch, revision(id),
									active.normal, NormalObservationStatus.UNAVAILABLE.name, null),
								NormalObservationFact(NormalObservationStatus.UNAVAILABLE),
							))
							continue
						}
						if (!mutations[id].isNullOrEmpty() || value == null) continue
						if (rows[id] == value.snapshot.row && value.snapshot.row.revision == revision(id)) {
							put(id, value.snapshot)
						}
					}
				}
			}
			if (refreshRoot() != active || epoch != capturedEpoch) return@withLock emptyMap()
			destinations.withDestinationMutationLock {
				if (active.generation == rootChanges.value) result else emptyMap()
			}
		}

	suspend fun isCurrent(manga: Manga, space: FavouriteSpace?, snapshot: NormalObservationSnapshot): Boolean =
		readVisible(listOf(manga), space)[manga.id] == snapshot

	/** Overlapping producers each hold a short intent token, not a full-operation lock. */
	suspend fun beginMutation(mangaId: Long): Long = mutex.withLock {
		val token = ++sequence
		mutations.getOrPut(mangaId) { HashSet() }.add(token)
		invalidate(mangaId)
		token
	}

	suspend fun finishMutation(mangaId: Long, token: Long) = mutex.withLock {
		val active = mutations[mangaId] ?: return@withLock
		if (!active.remove(token)) return@withLock
		if (active.isEmpty()) mutations.remove(mangaId)
		invalidate(mangaId)
	}

	suspend fun requestFull() = mutex.withLock { fullPending = true }

	/** A new full request during an in-flight batch is not consumed by the old batch. */
	suspend fun takeRequests(): NormalObservationRequests = mutex.withLock {
		NormalObservationRequests(fullPending, pending.toSet()).also {
			fullPending = false
			pending.clear()
		}
	}
}
