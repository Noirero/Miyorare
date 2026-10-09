package org.koitharu.kotatsu.scrobbling.mangaupdates.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class MangaUpdatesProgressQueueTest {
	private fun entry(manga: Long = 1, target: Long = 17360452316L, chapter: Int = 10, generation: Long = 0) =
		MangaUpdatesPendingProgress(manga, target, chapter, false, MangaUpdatesAuthTicket("fixture-session", generation))

	@Test fun `rapid reader updates coalesce without regressing progress or truncating target`() = runTest {
		val written = mutableListOf<MangaUpdatesPendingProgress>()
		val queue = MangaUpdatesProgressQueue(backgroundScope) { written += it }
		queue.enqueue(entry(chapter = 12)); queue.enqueue(entry(chapter = 17)); queue.enqueue(entry(chapter = 8))
		runCurrent()
		assertEquals(1, written.size)
		assertEquals(17, written.single().chapter)
		assertEquals(17360452316L, written.single().targetId)
	}

	@Test fun `rebound target does not inherit pending progress from another title`() = runTest {
		val written = mutableListOf<MangaUpdatesPendingProgress>()
		val queue = MangaUpdatesProgressQueue(backgroundScope) { written += it }
		queue.enqueue(entry(chapter = 80)); queue.enqueue(entry(target = 70994361491L, chapter = 2))
		runCurrent()
		assertEquals(70994361491L, written.single().targetId)
		assertEquals(2, written.single().chapter)
	}

	@Test fun `failure is visible and explicit retry uses the same absolute progress`() = runTest {
		var fail = true
		val written = mutableListOf<Int>()
		val queue = MangaUpdatesProgressQueue(backgroundScope) { if (fail) throw IOException("fixture failure") else written += it.chapter }
		queue.enqueue(entry()); runCurrent(); assertEquals(setOf(1L), queue.failedManga.value)
		fail = false; queue.retry(1); runCurrent()
		assertEquals(listOf(10), written)
		assertTrue(queue.failedManga.value.isEmpty())
	}

	@Test fun `new account cancels an in flight write and rejects old pending failures`() = runTest {
		val gate = CompletableDeferred<Unit>()
		val written = mutableListOf<Long>()
		val queue = MangaUpdatesProgressQueue(backgroundScope) { value -> if (value.ticket.generation == 0L) gate.await(); written += value.ticket.generation }
		queue.enqueue(entry()); runCurrent()
		queue.enqueue(entry(manga = 2, generation = 1)); queue.invalidateGeneration(1); runCurrent()
		gate.complete(Unit); runCurrent()
		assertEquals(listOf(1L), written)
		assertTrue(queue.failedManga.value.isEmpty())
	}

	@Test fun `an older failed response cannot mark newer confirmed progress as failed`() = runTest {
		val gate = CompletableDeferred<Unit>()
		val written = mutableListOf<Int>()
		val queue = MangaUpdatesProgressQueue(backgroundScope) {
			if (it.chapter == 10) { gate.await(); throw IOException("Old fixture failure") }
			written += it.chapter
		}
		queue.enqueue(entry(chapter = 10)); runCurrent()
		queue.enqueue(entry(chapter = 20)); gate.complete(Unit); runCurrent()
		assertEquals(listOf(20), written)
		assertTrue(queue.failedManga.value.isEmpty())
	}

	@Test fun `logout removes queued writes and the queue is bounded`() = runTest {
		var writes = 0
		val queue = MangaUpdatesProgressQueue(backgroundScope) { writes++ }
		repeat(64) { queue.enqueue(entry(manga = it.toLong())) }
		assertThrows(IllegalStateException::class.java) { queue.enqueue(entry(manga = 65)) }
		queue.reset(); runCurrent()
		assertEquals(0, writes)
	}
}
