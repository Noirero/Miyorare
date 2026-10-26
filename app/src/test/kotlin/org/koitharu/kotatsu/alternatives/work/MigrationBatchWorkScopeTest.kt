package org.koitharu.kotatsu.alternatives.work

import androidx.work.Data
import org.junit.Assert.assertEquals
import org.junit.Test
import org.koitharu.kotatsu.alternatives.domain.MigrationBatchAction
import org.koitharu.kotatsu.alternatives.domain.MigrationBatchRequest
import org.koitharu.kotatsu.alternatives.domain.MigrationSearchScope

class MigrationBatchWorkScopeTest {

	@Test fun `selected scope round trips through durable work input`() {
		val original = MigrationBatchRequest(
			mangaId = 42L,
			source = "OLD",
			action = MigrationBatchAction.CHECK,
			searchScope = MigrationSearchScope.GEKKOUSHI,
		)

		val restored = MigrationBatchWork.request(MigrationBatchWork.input(original))

		assertEquals(original, restored)
	}

	@Test fun `legacy queued work without scope remains all sources`() {
		val legacy = Data.Builder()
			.putLong("request_id", 42L)
			.putString("request_source", "OLD")
			.putString("request_action", MigrationBatchAction.CHECK.name)
			.build()

		val restored = MigrationBatchWork.request(legacy)

		assertEquals(MigrationSearchScope.ALL, restored.searchScope)
	}
}
