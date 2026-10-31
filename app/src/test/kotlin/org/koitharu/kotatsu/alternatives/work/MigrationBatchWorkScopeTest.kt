package org.koitharu.kotatsu.alternatives.work

import androidx.work.Data
import org.junit.Assert.assertEquals
import org.junit.Test
import org.koitharu.kotatsu.alternatives.domain.MigrationBatchAction
import org.koitharu.kotatsu.alternatives.domain.MigrationBatchDiscoveryMode
import org.koitharu.kotatsu.alternatives.domain.MigrationBatchRequest
import org.koitharu.kotatsu.alternatives.domain.MigrationBatchOutcome
import org.koitharu.kotatsu.alternatives.domain.MigrationBatchStatus
import org.koitharu.kotatsu.alternatives.domain.MigrationSearchScope

class MigrationBatchWorkScopeTest {

	@Test fun `selected scope and mode round trip through durable work input`() {
		val original = MigrationBatchRequest(
			mangaId = 42L,
			source = "OLD",
			action = MigrationBatchAction.CHECK,
			searchScope = MigrationSearchScope.GEKKOUSHI,
			discoveryMode = MigrationBatchDiscoveryMode.DIRECT_SEARCH,
		)

		val restored = MigrationBatchWork.request(MigrationBatchWork.input(original))

		assertEquals(original, restored)
	}

	@Test fun `scope and mode are durable in work tags used by snapshots`() {
		val request = MigrationBatchRequest(
			mangaId = 42L,
			source = "OLD",
			action = MigrationBatchAction.CHECK,
			searchScope = MigrationSearchScope.MIYORARE,
			discoveryMode = MigrationBatchDiscoveryMode.DIRECT_SEARCH,
		)
		val tags = MigrationBatchWork.tags(request, generation = 7L, batch = "batch").toSet()

		assertEquals(MigrationSearchScope.MIYORARE, MigrationBatchWork.searchScope(tags))
		assertEquals(MigrationBatchDiscoveryMode.DIRECT_SEARCH, MigrationBatchWork.discoveryMode(tags))
	}

	@Test fun `candidate title is retained with the per item work outcome`() {
		val data = MigrationBatchWork.output(MigrationBatchOutcome(
			status = MigrationBatchStatus.NEEDS_REVIEW,
			destinationTitle = "Ambiguous Candidate",
		))
		assertEquals("Ambiguous Candidate", data.getString("result_target_title"))
	}

	@Test fun `legacy queued work defaults to all sources and current source check`() {
		val legacy = Data.Builder()
			.putLong("request_id", 42L)
			.putString("request_source", "OLD")
			.putString("request_action", MigrationBatchAction.CHECK.name)
			.build()

		val restored = MigrationBatchWork.request(legacy)

		assertEquals(MigrationSearchScope.ALL, restored.searchScope)
		assertEquals(MigrationBatchDiscoveryMode.CHECK_CURRENT, restored.discoveryMode)
		assertEquals(MigrationSearchScope.ALL, MigrationBatchWork.searchScope(emptySet()))
		assertEquals(MigrationBatchDiscoveryMode.CHECK_CURRENT, MigrationBatchWork.discoveryMode(emptySet()))
	}
}
