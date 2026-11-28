package org.koitharu.kotatsu.favourites.domain

import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.db.entity.toManga
import org.koitharu.kotatsu.core.model.MangaSource
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.favourites.data.NormalLibraryCategory
import org.koitharu.kotatsu.favourites.data.NormalLibraryLocator
import org.koitharu.kotatsu.favourites.data.NormalLibraryMember
import org.koitharu.kotatsu.local.data.NormalScannerVerificationInput
import org.koitharu.kotatsu.local.data.NormalDownloadDirectoryDiscovery
import org.koitharu.kotatsu.local.data.output.LocalMangaOutput
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

internal enum class NormalScanOutcome { IDLE, COMPLETE, UNAVAILABLE, PARTIAL, FAILED, CANCELLED }

internal data class NormalDownloadScanState(
	val running: Boolean = false,
	val processed: Int = 0,
	val total: Int = 0,
	val outcome: NormalScanOutcome = NormalScanOutcome.IDLE,
	val completedRuns: Long = 0L,
)

/** Single process-owned drain. Settings is only a request producer and state consumer.
 * Room reads and filesystem work run on IO; no network, UI lifecycle, or periodic polling.
 */
@Singleton
internal class NormalDownloadScanCoordinator @Inject constructor(
	private val db: MangaDatabase,
	private val policy: NormalDownloadScope,
	private val observations: NormalDownloadObservationStore,
	private val discovery: NormalDownloadDirectoryDiscovery,
) {
	private val started = AtomicBoolean()
	private val fullRequested = AtomicBoolean()
	private val wakeups = Channel<Unit>(Channel.CONFLATED)
	private val mutableState = MutableStateFlow(NormalDownloadScanState())
	val state = mutableState.asStateFlow()

	/** Non-suspending submission survives immediate Settings dismissal. */
	fun requestFull() {
		fullRequested.set(true)
		wakeups.trySend(Unit)
	}

	fun start(scope: CoroutineScope) {
		if (!started.compareAndSet(false, true)) return
		val initialRoot = observations.rootChanges.value
		scope.launch(Dispatchers.IO) {
			// Capture before registering the invalidation collector; its initial emission then
			// closes the registration gap. The initial full request already belongs to startup.
			var previous: Metadata? = null
			try { previous = metadata() } catch (error: CancellationException) { throw error }
			catch (error: Exception) { error.printStackTraceDebug() }
			scope.launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
				observations.requestChanges.collect { wakeups.trySend(Unit) }
			}
			scope.launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
				observations.rootChanges.collect { if (it != initialRoot) wakeups.trySend(Unit) }
			}
			scope.launch(Dispatchers.IO) {
				db.invalidationTracker.createFlow("favourites", "private_favourites", "manga", "chapters",
					"favourite_download_index", "local_index", emitInitialState = true).collect {
					try {
						val current = metadata()
						val old = previous
						previous = current
						if (old == null) observations.requestFull()
						else {
							val owners = (old.proof.owners - current.proof.owners) + (current.proof.owners - old.proof.owners)
							val ids = affected(old, current) + owners.map { it.mangaId }
								.filter { it in old.members || it in current.members }
							val paths = if (owners.isEmpty()) emptySet() else runCatching {
								owners.mapTo(HashSet()) {
									val file = File(it.path)
									check(file.isAbsolute)
									file.canonicalPath
								}
							}.getOrNull()
							observations.requestRevalidation(ids, owners.isNotEmpty(), paths)
						}
					} catch (error: CancellationException) { throw error }
					catch (error: Exception) { error.printStackTraceDebug() }
				}
			}
			for (ignored in wakeups) drain()
		}
	}

	private data class Member(
		val rows: List<NormalLibraryMember>,
		val privateCategories: List<NormalLibraryCategory>,
		val chapters: List<NormalLibraryLocator>,
		val indexPath: String?,
	)
	private data class Metadata(val members: Map<Long, Member>, val proof: NormalScannerVerificationInput)

	private suspend fun eligibleMembers(): Map<Long, List<NormalLibraryMember>> {
		val allowed = HashMap<String, Boolean>()
		return db.getNormalDownloadObservationDao().findLibraryMembers().groupBy { it.mangaId }.filterValues { rows ->
			val source = rows.first().source
			allowed[source] ?: policy.supportsSource(MangaSource(source)).also { allowed[source] = it }
		}
	}

	private suspend fun metadata(): Metadata = db.withTransaction {
		val rows = eligibleMembers()
		val members = HashMap<Long, Member>()
		val dao = db.getNormalDownloadObservationDao()
		for (ids in rows.keys.chunked(QUERY_SIZE)) {
			val privateRows = dao.findPrivateCategories(ids).groupBy { it.mangaId }
			val chapters = dao.findChapterLocators(ids).groupBy { it.mangaId }
			val index = db.getLocalMangaIndexDao().findEntries(ids).associateBy { it.mangaId }
			for (id in ids) members[id] = Member(rows.getValue(id).sortedBy { it.categoryId },
				privateRows[id].orEmpty().sortedBy { it.categoryId }, chapters[id].orEmpty(), index[id]?.path)
		}
		Metadata(members, discovery.readVerificationInput())
	}

	private fun affected(old: Metadata, current: Metadata): Set<Long> = buildSet {
		val ids = old.members.keys + current.members.keys
		for (id in ids) if (old.members[id] != current.members[id]) add(id)
		// Changed title claims can alter ambiguity for another title, not only the edited ID.
		val claims = (old.proof.claims - current.proof.claims) + (current.proof.claims - old.proof.claims)
		val keys = claims.flatMapTo(HashSet()) { LocalMangaOutput.normalScannerTitleKeys(it.title) }
		if (keys.isNotEmpty()) for (id in ids) {
			val title = (current.members[id] ?: old.members[id])?.rows?.firstOrNull()?.title ?: continue
			if (LocalMangaOutput.normalScannerTitleKeys(title).any(keys::contains)) add(id)
		}
	}

	private suspend fun drain() {
		// The full bit is consumed once per drain, not by a separate coroutine waiting
		// for the store mutex. Repeated requests during work coalesce into one next full.
		val explicitFull = fullRequested.getAndSet(false)
		val run = mutableState.value.completedRuns
		val remaining = LinkedHashSet<Long>()
		var outcome = NormalScanOutcome.COMPLETE
		var began = false
		var full = explicitFull
		try {
			val requests = observations.takeRequests(refresh = true)
			full = full || requests.full
			if (!full && requests.ids.isEmpty()) return
			began = true
			mutableState.value = NormalDownloadScanState(running = true, completedRuns = run)
			val generation = observations.rootChanges.value
			remaining += requests.ids
			val eligible = eligibleMembers().keys
			if (full) remaining += eligible
			remaining.retainAll(eligible)
			val total = remaining.size
			mutableState.value = mutableState.value.copy(total = total)
			var unavailable = !observations.activeRootAvailable()
			var partial = false
			for (ids in remaining.toList().chunked(WORK_SIZE)) {
				if (observations.rootChanges.value != generation) {
					partial = true
					observations.defer(remaining)
					break
				}
				val mangas = db.withTransaction {
					val chapters = db.getChaptersDao().findAll(ids).groupBy { it.mangaId }
					db.getMangaDao().findByIds(ids).map { row ->
						row.toManga(chapters[row.manga.id]?.takeUnless { it.isEmpty() })
					}
				}
				val batch = observations.prepareBatch(mangas)
				val accepted = batch?.let { observations.publishBatch(it) }.orEmpty()
				// Not accepted is deferred/changed, never success. An unresolved observation is
				// also not a successful verification, even if its UNKNOWN row was committed.
				unavailable = unavailable || accepted.values.any { it == NormalObservationStatus.UNAVAILABLE }
				partial = partial || accepted.size != ids.size || accepted.values.any { it == NormalObservationStatus.UNKNOWN }
				val deferred = ids.filterNot(accepted::containsKey)
				observations.defer(deferred)
				remaining.removeAll(ids.toSet())
				mutableState.value = mutableState.value.copy(processed = mutableState.value.processed + accepted.size)
			}
			unavailable = unavailable || !observations.activeRootAvailable()
			partial = partial || observations.rootChanges.value != generation
			outcome = when {
				unavailable -> NormalScanOutcome.UNAVAILABLE
				partial -> NormalScanOutcome.PARTIAL
				else -> NormalScanOutcome.COMPLETE
			}
		} catch (error: CancellationException) {
			outcome = NormalScanOutcome.CANCELLED
			throw error
		} catch (error: Exception) {
			began = true
			outcome = NormalScanOutcome.FAILED
			// Do not retry a persistent error in a hot loop. A later request can retry.
			if (full) fullRequested.set(true)
			observations.defer(remaining)
			error.printStackTraceDebug()
		} finally {
			if (began) mutableState.value = mutableState.value.copy(running = false, outcome = outcome, completedRuns = run + 1L)
		}
	}

	companion object {
		const val KEY_SCAN_ON_RESUME = "normal_download_scan_on_resume"
		// Owner-approved Q27 revision: only an unset preference defaults to OFF.
		const val DEFAULT_SCAN_ON_RESUME = false
		private const val QUERY_SIZE = 500
		private const val WORK_SIZE = 32
	}
}
