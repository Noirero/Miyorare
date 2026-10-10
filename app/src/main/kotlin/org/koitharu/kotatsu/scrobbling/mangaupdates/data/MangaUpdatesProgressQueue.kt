package org.koitharu.kotatsu.scrobbling.mangaupdates.data

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class MangaUpdatesPendingProgress(val mangaId: Long, val targetId: Long, val chapter: Int, val nsfw: Boolean, val ticket: MangaUpdatesAuthTicket)

/** Reader writes are coalesced per local association, with one bounded provider-owned worker. */
internal class MangaUpdatesProgressQueue(
	private val scope: CoroutineScope,
	private val write: suspend (MangaUpdatesPendingProgress) -> Unit,
) {
	private val lock = Any()
	private val pending = LinkedHashMap<Long, MangaUpdatesPendingProgress>()
	private val failed = LinkedHashMap<Long, MangaUpdatesPendingProgress>()
	private val signals = Channel<Unit>(Channel.CONFLATED)
	private var worker: Job? = null
	private var active: MangaUpdatesPendingProgress? = null
	private val failures = MutableStateFlow<Set<Long>>(emptySet())
	val failedManga = failures.asStateFlow()

	fun enqueue(value: MangaUpdatesPendingProgress) = synchronized(lock) {
		check(value.mangaId in pending || pending.size < 64) { "MangaUpdates progress queue is full" }
		pending[value.mangaId] = highest(value, pending[value.mangaId], active, failed[value.mangaId])
		failed.remove(value.mangaId)
		failures.value = failed.keys.toSet()
		signals.trySend(Unit)
		if (worker == null) {
			val job = scope.launch(start = CoroutineStart.LAZY) {
				for (signal in signals) {
					while (true) {
						currentCoroutineContext().ensureActive()
						val entry = synchronized(lock) { pending.entries.firstOrNull()?.let { pending.remove(it.key) }?.also { active = it } } ?: break
						try {
							write(entry)
							synchronized(lock) {
								val old = failed[entry.mangaId]
								if (old != null && sameAssociation(old, entry) && old.chapter <= entry.chapter) failed.remove(entry.mangaId)
								failures.value = failed.keys.toSet()
							}
						} catch (e: CancellationException) {
							currentCoroutineContext().ensureActive() // A stale ticket does not stop a new account's worker.
						} catch (_: Exception) {
							currentCoroutineContext().ensureActive()
							synchronized(lock) {
								val next = pending[entry.mangaId]
								if (next != null) {
									if (sameAssociation(next, entry)) pending[entry.mangaId] = highest(next, entry)
								} else {
									if (failed.size >= 64) failed.remove(failed.keys.first())
									failed[entry.mangaId] = highest(entry, failed[entry.mangaId])
								}
								failures.value = failed.keys.toSet()
							}
						} finally {
							synchronized(lock) { if (active == entry) active = null }
						}
					}
				}
			}
			worker = job
			job.start()
		}
	}

	private fun sameAssociation(a: MangaUpdatesPendingProgress, b: MangaUpdatesPendingProgress) =
		a.mangaId == b.mangaId && a.targetId == b.targetId && a.ticket.generation == b.ticket.generation

	private fun highest(value: MangaUpdatesPendingProgress, vararg others: MangaUpdatesPendingProgress?): MangaUpdatesPendingProgress =
		value.copy(chapter = others.filterNotNull().filter { sameAssociation(value, it) }.fold(value.chapter) { chapter, other -> maxOf(chapter, other.chapter) })

	fun retry(mangaId: Long) = synchronized(lock) { failed[mangaId]?.let(::enqueue) }
	fun remove(mangaId: Long) = synchronized(lock) {
		pending.remove(mangaId); failed.remove(mangaId); failures.value = failed.keys.toSet()
	}
	fun reset() = synchronized(lock) {
		worker?.cancel(); worker = null; active = null; pending.clear(); failed.clear(); failures.value = emptySet()
	}
	fun invalidateGeneration(generation: Long) = synchronized(lock) {
		pending.entries.removeAll { it.value.ticket.generation != generation }
		failed.entries.removeAll { it.value.ticket.generation != generation }
		failures.value = failed.keys.toSet()
		if (active?.ticket?.generation?.let { it != generation } == true) {
			worker?.cancel(); worker = null; active = null
			pending.values.firstOrNull()?.let(::enqueue)
		}
	}
}
