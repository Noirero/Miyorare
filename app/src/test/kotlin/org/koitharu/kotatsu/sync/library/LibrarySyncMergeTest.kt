package org.koitharu.kotatsu.sync.library

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class LibrarySyncMergeTest {
	private fun entry(
		id: String = "7",
		service: LibrarySyncServiceId = LibrarySyncServiceId.ANILIST,
		progress: Int = 1,
		at: Long = 1,
	) = SyncEntry(service, id, null, "Title", progress, "CURRENT", Instant.ofEpochMilli(at))

	@Test
	fun `newer remote wins even when progress decreases`() {
		val remote = entry(progress = 2, at = 20)
		assertEquals(
			remote,
			LibrarySyncMerge.lastWriteWins(listOf(entry(progress = 10, at = 10)), listOf(remote))
				.single(),
		)
	}

	@Test
	fun `newer local wins`() {
		val local = entry(progress = 5, at = 30)
		assertEquals(
			local,
			LibrarySyncMerge.lastWriteWins(listOf(local), listOf(entry(at = 20))).single(),
		)
	}

	@Test
	fun `exact timestamp tie chooses local deterministically`() {
		val local = entry(progress = 1, at = 1000)
		assertEquals(
			local,
			LibrarySyncMerge.lastWriteWins(listOf(local), listOf(entry(progress = 99, at = 1000)))
				.single(),
		)
	}

	@Test
	fun `IDs are scoped by service`() {
		assertEquals(
			2,
			LibrarySyncMerge.lastWriteWins(
					listOf(entry()),
					listOf(entry(service = LibrarySyncServiceId.KITSU)),
				)
				.size,
		)
	}

	@Test
	fun `unmapped remote entries remain unmapped`() {
		assertNull(
			LibrarySyncMerge.lastWriteWins(emptyList(), listOf(entry())).single().localMangaId
		)
	}

	@Test
	fun `merge is idempotent`() {
		val local = listOf(entry(at = 20))
		val remote = listOf(entry(at = 10), entry(id = "9"))
		val merged = LibrarySyncMerge.lastWriteWins(local, remote)
		assertEquals(merged, LibrarySyncMerge.lastWriteWins(merged, remote))
	}
}
