package org.koitharu.kotatsu.download.ui.list

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadSizeHydrationTest {

	@get:Rule val folder = TemporaryFolder()

	@Test
	fun stableRequestsRehydrateChangedAndDeletedFilesWithoutNewSnapshot() = runTest {
		val file = folder.newFile().apply { writeText("old") }
		val id = UUID.randomUUID()
		var lookups = 0
		val hydration = DownloadSizeHydration<File>(backgroundScope) { _, candidate ->
			lookups++
			candidate.length()
		}
		hydration.replaceRequests(mapOf(id to file))
		runCurrent()
		assertEquals(mapOf(id to 3L), hydration.sizes.value)
		hydration.replaceRequests(mapOf(id to file))
		runCurrent()
		assertEquals(1, lookups)
		file.writeText("new size")
		hydration.invalidate()
		runCurrent()
		assertEquals(mapOf(id to 8L), hydration.sizes.value)
		check(file.delete())
		hydration.invalidate()
		runCurrent()
		assertEquals(mapOf(id to 0L), hydration.sizes.value)
	}

	@Test
	fun invalidationDuringLookupRejectsOldResultAndCoalescesBurst() = runTest {
		val id = UUID.randomUUID()
		val started = CompletableDeferred<Unit>()
		val releaseOld = CompletableDeferred<Unit>()
		val releaseFresh = CompletableDeferred<Unit>()
		var calls = 0
		val hydration = DownloadSizeHydration<String>(backgroundScope) { _, _ ->
			if (++calls == 1) {
				started.complete(Unit)
				releaseOld.await()
				10L
			} else {
				releaseFresh.await()
				20L
			}
		}
		hydration.replaceRequests(mapOf(id to "artifact"))
		runCurrent()
		started.await()
		repeat(100) { hydration.invalidate() }
		runCurrent()
		assertEquals(1, calls)
		releaseOld.complete(Unit)
		runCurrent()
		assertFalse(hydration.sizes.value.containsKey(id))
		assertEquals(2, calls)
		releaseFresh.complete(Unit)
		runCurrent()
		assertEquals(mapOf(id to 20L), hydration.sizes.value)
		hydration.replaceRequests(emptyMap())
		hydration.invalidate()
		runCurrent()
		assertEquals(emptyMap<UUID, Long>(), hydration.sizes.value)
		assertEquals(2, calls)
	}
}
