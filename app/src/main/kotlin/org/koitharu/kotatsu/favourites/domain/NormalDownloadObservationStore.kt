package org.koitharu.kotatsu.favourites.domain

import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.model.MangaSource
import org.koitharu.kotatsu.download.domain.DownloadDestinationStore
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
import org.koitharu.kotatsu.favourites.data.NormalDownloadObservationDao
import org.koitharu.kotatsu.favourites.data.NormalDownloadObservationEntity
import org.koitharu.kotatsu.local.data.NormalCandidateResolution
import org.koitharu.kotatsu.local.data.NormalDownloadDirectoryDiscovery
import org.koitharu.kotatsu.local.data.NormalScannerParentListing
import org.koitharu.kotatsu.local.data.NormalScannerVerificationInput
import org.koitharu.kotatsu.local.data.verifiedLocalProjection
import org.koitharu.kotatsu.local.domain.model.LocalManga
import org.koitharu.kotatsu.parsers.model.Manga
import java.io.File
import java.io.IOException
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
) : java.io.Serializable

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
	val localProjection: LocalManga? = null,
)

internal data class NormalObservationRequests(val full: Boolean, val ids: Set<Long>)

internal data class NormalObservationBatch(
	val works: List<NormalObservationWork>,
	val producerGeneration: Long,
	val verificationInput: NormalScannerVerificationInput?,
	val results: List<NormalCandidateResolution?>,
)

internal data class NormalLibraryVisibility(val eligible: Set<Long>, val present: Set<Long>, val resolved: Set<Long>)

/**
 * Phase 3 boundary. Filesystem enumeration happens outside the mutex. Only metadata
 * checks, observation writes and read acceptance use it. Root edits use their existing
 * non-suspending lock, so Room writes are tagged then checked again at visibility.
 *
 * Persisted rows are not trusted across process restart: a new session must revalidate.
 * Concrete chapter identity is held only for a currently verified observation.
 * Producers must register intent BEFORE mutation and finish in a cancellation-safe
 * finally block. Library readers use the accepted snapshot, never raw persisted rows.
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
	private var producerGeneration = 0L
	private val revisions = HashMap<Long, Long>()
	private val tickets = HashMap<Long, Long>()
	private val mutations = HashMap<Long, MutableSet<Long>>()
	private val published = HashMap<Long, Published>()
	private val pending = LinkedHashSet<Long>()
	private var fullPending = true
	private val _changes = MutableStateFlow(0L)
	val changes = _changes.asStateFlow()
	val rootChanges get() = destinations.normalScannerRootChanges
	private val _requestChanges = MutableStateFlow(0L)
	val requestChanges = _requestChanges.asStateFlow()

	private fun wake() { _requestChanges.value += 1L }
	private fun enqueue(id: Long) {
		if (pending.add(id) && mutations[id].isNullOrEmpty()) wake()
	}

	private fun changed() { _changes.value += 1L }
	private fun revision(id: Long) = revisions[id] ?: 0L

	private fun invalidate(id: Long, notifyRequest: Boolean = true, notifyChange: Boolean = true) {
		revisions[id] = revision(id) + 1L
		tickets.remove(id)
		published.remove(id)
		if (notifyRequest) enqueue(id) else pending += id
		if (notifyChange) changed()
	}

	// Only short root stat/canonical checks, not a directory inventory.
	private suspend fun refreshRoot(notifyRequest: Boolean = true): NormalObservationRoot? {
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
				if (notifyRequest) wake()
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

	/** Read persisted context even during a registered explicit mutation; no publication authority. */
	internal suspend fun actionContext(manga: Manga, space: FavouriteSpace?): NormalObservationContext? =
		db.withTransaction { context(manga, space) }

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

	/**
	 * Fresh, read-only resolution for an eligible Reader or an action verifier.
	 * This neither consumes scanner tickets nor publishes a Room observation.
	 * A changed root/context/revision rejects the result, including a stale negative.
	 */
	internal suspend fun resolveForRead(manga: Manga, space: FavouriteSpace?): NormalCandidateResolution? {
		val captured = mutex.withLock {
			val active = refreshRoot() ?: throw IOException("Download destination changed")
			if (!mutations[manga.id].isNullOrEmpty()) throw IOException("Download is changing")
			val ctx = db.withTransaction { context(manga, space) }
				?: throw IOException("Download context changed")
			NormalObservationWork(manga, space, session, epoch, active, revision(manga.id), -1L, ctx)
		}
		val result = if (captured.root.available) discovery.resolveCandidates(manga, space) else null
		return mutex.withLock {
			val active = refreshRoot()
			val ctx = db.withTransaction { context(manga, space) }
			destinations.withDestinationMutationLock {
				if (captured.root != active || captured.rootEpoch != epoch ||
					captured.root.generation != rootChanges.value || captured.revision != revision(manga.id) ||
					!mutations[manga.id].isNullOrEmpty() || captured.context != ctx
				) throw IOException("Download context changed; try again")
				result
			}
		}
	}

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
		return NormalObservationFact(NormalObservationStatus.PRESENT, candidate.file.path, chapters, selectors)
	}

	/** Stale positive AND negative are rejected; a row written during a root switch is invisible. */
	suspend fun publish(work: NormalObservationWork, result: NormalCandidateResolution?): Boolean = mutex.withLock {
		val id = work.manga.id
		fun rejected(): Boolean { pending += id; return false }
		if (!valid(work, refreshRoot())) return@withLock rejected()
		val fact = fact(work, result)
		val projection = if (fact.status == NormalObservationStatus.PRESENT) result?.verifiedLocalProjection() else null
		val old = published[id]
		val same = old != null && old.context == work.context && old.snapshot.fact == fact &&
			old.snapshot.localProjection == projection
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
			published[id] = Published(NormalObservationSnapshot(row, fact, projection), work.context)
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

	/**
	 * Shared SQL/card/count boundary. Read persisted context and accepted in-memory revision in
	 * the same transaction as the consumer query. Never trust a raw positive observation row
	 * or a card's chapter-less seed. No directory/ZIP traversal; metadata queries are chunked.
	 * A root edit during a query invalidates the result before returning it.
	 */
	internal suspend fun <T> withLibraryVisibility(ids: Collection<Long>?, read: suspend (NormalLibraryVisibility) -> T): T {
		mutex.lock()
		try {
			while (true) {
				val active = refreshRoot() ?: continue
				val result = db.withTransaction {
					val members = if (ids == null) dao.findLibraryMembers()
						else ids.distinct().chunked(500).flatMap { dao.findLibraryMembers(it) }
					val byId = members.groupBy { it.mangaId }
					val supported = HashMap<String, Boolean>()
					val eligible = buildSet {
						for ((id, rows) in byId) {
							val source = rows.first().source
							val allowed = supported[source] ?: scope.supportsSource(MangaSource(source)).also { supported[source] = it }
							if (allowed) add(id)
						}
					}
					if (!active.available) return@withTransaction read(NormalLibraryVisibility(eligible, emptySet(), eligible))
					val accepted = eligible.filter { published.containsKey(it) && mutations[it].isNullOrEmpty() }
					val chunks = accepted.chunked(500)
					val observations = chunks.flatMap { dao.findCurrent(session, epoch, it) }.associateBy { it.mangaId }
					val privateCategories = chunks.flatMap { dao.findPrivateCategories(it) }.groupBy { it.mangaId }
					val locators = chunks.flatMap { dao.findChapterLocators(it) }.groupBy { it.mangaId }
					val present = HashSet<Long>()
					val resolved = HashSet<Long>()
					for (id in accepted) {
						val value = published[id] ?: continue
						val rows = byId.getValue(id)
						val first = rows.first()
						val ctx = value.context
						val validContext = first.source == ctx.source && first.title == ctx.title && first.url == ctx.url &&
							first.detailsRevision == ctx.detailsRevision && first.chaptersInitialized == ctx.chaptersInitialized &&
							rows.map { it.categoryId }.distinct().sorted() == ctx.normalCategories &&
							privateCategories[id].orEmpty().map { it.categoryId }.distinct().sorted() == ctx.privateCategories &&
							locators[id].orEmpty().map { Triple(it.chapterId, it.source, it.url) } == ctx.chapterLocators.orEmpty()
						if (!validContext || observations[id] != value.snapshot.row || value.snapshot.row.revision != revision(id)) {
							invalidate(id)
							continue
						}
						when (value.snapshot.fact.status) {
							NormalObservationStatus.PRESENT -> { present += id; resolved += id }
							NormalObservationStatus.ABSENT, NormalObservationStatus.UNAVAILABLE -> resolved += id
							NormalObservationStatus.UNKNOWN -> Unit
						}
					}
					read(NormalLibraryVisibility(eligible, present, resolved))
				}
				if (refreshRoot() == active && active.generation == rootChanges.value) return result
			}
		} finally {
			mutex.unlock()
		}
	}

	/** Apply accepted scoped Details facts under the same root/revision fence.
	 * A stale source caller may not invalidate a newer accepted observation.
	 */
	internal suspend fun withDetailsProjection(
		manga: Manga, space: FavouriteSpace?,
		apply: (scoped: Boolean, sourceCurrent: Boolean, local: LocalManga?) -> Unit,
	) = mutex.withLock {
		while (true) {
			val active = refreshRoot() ?: continue
			val applied = db.withTransaction {
				val scoped = scope.appliesTo(manga, space)
				val id = manga.id
				val ctx = if (scoped) context(manga, space) else null
				val locators = if (scoped) dao.findChapterLocators(listOf(id)).map { Triple(it.chapterId, it.source, it.url) } else emptyList()
				val sourceCurrent = !scoped || (ctx != null && locators == ctx.chapterLocators.orEmpty())
				val value = published[id]
				val valid = scoped && sourceCurrent && active.available && mutations[id].isNullOrEmpty() &&
					value != null && value.context == ctx && value.snapshot.row.revision == revision(id) &&
					dao.findCurrent(session, epoch, listOf(id)).singleOrNull() == value.snapshot.row
				if (scoped && sourceCurrent && active.available && !valid) enqueue(id)
				if (refreshRoot() != active) return@withTransaction false
				destinations.withDestinationMutationLock {
					if (active.generation != rootChanges.value) false else {
						apply(scoped, sourceCurrent, if (valid) value?.snapshot?.localProjection else null)
						true
					}
				}
			}
			if (applied) break
		}
	}

	internal fun observeDetailsChanges(): Flow<Unit> = merge(
		changes.map { Unit }, rootChanges.map { Unit },
		db.invalidationTracker.createFlow("favourites", "private_favourites", "manga", "chapters",
			"normal_download_observation", emitInitialState = true).map { Unit },
	)

	suspend fun isCurrent(manga: Manga, space: FavouriteSpace?, snapshot: NormalObservationSnapshot): Boolean =
		readVisible(listOf(manga), space)[manga.id] == snapshot

	/** Overlapping producers each hold a short intent token, not a full-operation lock. */
	suspend fun beginMutation(mangaId: Long): Long = mutex.withLock {
		val token = ++sequence
		producerGeneration += 1L
		mutations.getOrPut(mangaId) { HashSet() }.add(token)
		invalidate(mangaId)
		token
	}

	suspend fun finishMutation(mangaId: Long, token: Long) = mutex.withLock {
		val active = mutations[mangaId] ?: return@withLock
		if (!active.remove(token)) return@withLock
		producerGeneration += 1L
		if (active.isEmpty()) mutations.remove(mangaId)
		invalidate(mangaId)
		// The ID may already be pending from beginMutation. Completion must still wake the drain.
		wake()
	}

	suspend fun requestFull() = mutex.withLock {
		fullPending = true
		wake()
	}

	/** Only actual scope/identity changes invalidate observations, not Room emissions themselves. */
	internal suspend fun requestRevalidation(
		ids: Set<Long>, ownershipChanged: Boolean = false, ownerPaths: Set<String>? = emptySet(),
	) = mutex.withLock {
		if (ids.isEmpty() && !ownershipChanged) return@withLock
		producerGeneration += 1L
		val affected = ids.toMutableSet()
		if (ownershipChanged) {
			// A changed owner may affect another positive observation. Immediately hide
			// overlapping positives; revisit ambiguous/absent candidates in a full pass.
			// Unchanged independent observations retain their accepted revision and row.
			fun overlaps(a: String, b: String): Boolean =
				a == b || a.startsWith(b.trimEnd(File.separatorChar) + File.separator) ||
					b.startsWith(a.trimEnd(File.separatorChar) + File.separator)
			for ((id, value) in published) {
				val path = value.snapshot.fact.path ?: continue
				if (ownerPaths == null || ownerPaths.any { overlaps(it, path) }) affected += id
			}
			fullPending = true
		}
		for (id in affected) invalidate(id, notifyRequest = false, notifyChange = false)
		if (affected.isNotEmpty()) changed()
		wake()
	}

	/** Keep deferred work for a later external trigger, never spin on unresolved/mutating work. */
	internal suspend fun defer(ids: Collection<Long>) = mutex.withLock { pending.addAll(ids); Unit }

	internal suspend fun activeRootAvailable(): Boolean = mutex.withLock { refreshRoot()?.available == true }

	/** A new full request during an in-flight batch is not consumed by the old batch. */
	suspend fun takeRequests(refresh: Boolean = false): NormalObservationRequests = mutex.withLock {
		if (refresh) refreshRoot(notifyRequest = false)
		val ready = pending.filterTo(LinkedHashSet()) { mutations[it].isNullOrEmpty() }
		NormalObservationRequests(fullPending, ready).also {
			fullPending = false
			pending.removeAll(ready)
		}
	}

	/** All tickets precede IO; a shared inventory/proof is never reused across work batches.
	 * Do not inventory while another durable Normal writer owns an intent. Completion wakes
	 * deferred work; no blocking of the original writer or busy retry.
	 */
	internal suspend fun prepareBatch(mangas: List<Manga>): NormalObservationBatch? {
		val (works, generation) = mutex.withLock {
			val active = refreshRoot() ?: return null
			if (mutations.isNotEmpty()) {
				pending.addAll(mangas.map { it.id })
				return null
			}
			val works = db.withTransaction {
				mangas.mapNotNull { manga ->
					val ctx = context(manga, FavouriteSpace.NORMAL) ?: return@mapNotNull null
					val locators = dao.findChapterLocators(listOf(manga.id)).map { Triple(it.chapterId, it.source, it.url) }
					if (locators != ctx.chapterLocators.orEmpty()) return@mapNotNull null
					val ticket = ++sequence
					tickets[manga.id] = ticket
					NormalObservationWork(manga, FavouriteSpace.NORMAL, session, epoch, active,
						revision(manga.id), ticket, ctx)
				}
			}
			works to producerGeneration
		}
		if (works.isEmpty()) return NormalObservationBatch(works, generation, null, emptyList())
		val input = if (works.first().root.available) discovery.readVerificationInput() else null
		val evidence = input?.let { discovery.captureVerificationEvidence(it) }
		val listings = HashMap<File, NormalScannerParentListing?>()
		val results = works.map { work ->
			currentCoroutineContext().ensureActive()
			if (evidence == null) null else discovery.resolveCandidates(work.manga, work.space, evidence, listings)
		}
		return NormalObservationBatch(works, generation, input, results)
	}

	/** Validate shared raw proof in the same Room transaction as the rows; accept in memory
	 * only after commit and a second root/context/proof check. No FS inventory under mutex.
	 * Rejected rows are invisible to all existing scoped consumers.
	 */
	internal suspend fun publishBatch(batch: NormalObservationBatch): Map<Long, NormalObservationStatus> = mutex.withLock {
		val works = batch.works
		if (works.isEmpty()) return@withLock emptyMap()
		fun reject(work: NormalObservationWork) {
			if (tickets[work.manga.id] == work.ticket) invalidate(work.manga.id, notifyRequest = false)
			else pending += work.manga.id
		}
		fun rejectAll(): Map<Long, NormalObservationStatus> {
			works.forEach(::reject)
			return emptyMap()
		}
		val active = refreshRoot()
		if (batch.producerGeneration != producerGeneration || mutations.isNotEmpty() ||
			works.any { !valid(it, active) }) return@withLock rejectAll()
		data class Prepared(
			val work: NormalObservationWork, val value: Published, val same: Boolean,
		)
		val prepared = works.zip(batch.results).map { (work, result) ->
			val fact = fact(work, result)
			val projection = if (fact.status == NormalObservationStatus.PRESENT) result?.verifiedLocalProjection() else null
			val old = published[work.manga.id]
			val same = old != null && old.context == work.context && old.snapshot.fact == fact &&
				old.snapshot.localProjection == projection
			val nextRevision = if (same) work.revision else work.revision + 1L
			val row = NormalDownloadObservationEntity(work.manga.id, session, epoch, nextRevision,
				work.root.normal, fact.status.name, fact.path)
			Prepared(work, Published(NormalObservationSnapshot(row, fact, projection), work.context), same)
		}
		suspend fun matches(work: NormalObservationWork): Boolean =
			context(work.manga, work.space) == work.context &&
				dao.findChapterLocators(listOf(work.manga.id)).map { Triple(it.chapterId, it.source, it.url) } ==
					work.context.chapterLocators.orEmpty()
		val saved = db.withTransaction {
			if (batch.verificationInput != null && discovery.readVerificationInput() != batch.verificationInput)
				return@withTransaction emptyList()
			prepared.filter { matches(it.work) }.onEach {
				val row = it.value.snapshot.row
				if (dao.findCurrent(session, epoch, listOf(row.mangaId)).singleOrNull() != row) dao.upsert(row)
			}
		}
		if (active != refreshRoot() || works.any { !valid(it, active) }) return@withLock rejectAll()
		val current = db.withTransaction {
			if (batch.verificationInput != null && discovery.readVerificationInput() != batch.verificationInput)
				return@withTransaction emptyList()
			saved.filter { matches(it.work) }
		}
		destinations.withDestinationMutationLock {
			if (works.any { !valid(it, root) }) return@withDestinationMutationLock rejectAll()
			val accepted = current.associateBy { it.work.manga.id }
			var hasChanges = false
			for (work in works) {
				val item = accepted[work.manga.id]
				if (item == null) { reject(work); continue }
				val id = work.manga.id
				revisions[id] = item.value.snapshot.row.revision
				tickets.remove(id)
				published[id] = item.value
				pending.remove(id)
				hasChanges = hasChanges || !item.same
			}
			if (hasChanges) changed()
			accepted.mapValues { it.value.value.snapshot.fact.status }
		}
	}
}
