package org.koitharu.kotatsu.alternatives.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MigrationSearchScopeTest {

	@Test fun `all scope accepts every source origin`() {
		for (origin in MigrationSourceOrigin.entries) {
			assertTrue(MigrationSearchScope.ALL.matches(origin))
		}
	}

	@Test fun `specific scopes accept only their authoritative origin`() {
		assertOnly(MigrationSearchScope.STORE, MigrationSourceOrigin.STORE)
		assertOnly(MigrationSearchScope.UMA, MigrationSourceOrigin.UMA)
		assertOnly(MigrationSearchScope.GEKKOUSHI, MigrationSourceOrigin.GEKKOUSHI)
		assertOnly(MigrationSearchScope.MIYORARE, MigrationSourceOrigin.MIYORARE)
	}

	@Test fun `provider failure keeps successful providers searchable`() {
		val result = mergeMigrationProviderResults(
			listOf(
				Result.success(listOf("store-a", "store-b")),
				Result.failure(IllegalStateException("UMA unavailable")),
				Result.success(listOf("miyorare-a")),
			),
		)

		assertEquals(listOf("store-a", "store-b", "miyorare-a"), result.values)
		assertEquals(1, result.failureCount)
	}

	@Test fun `availability reports zero without inventing sources`() {
		val availability = MigrationSearchAvailability(
			counts = mapOf(MigrationSearchScope.ALL to 3, MigrationSearchScope.STORE to 2),
			providerFailureCount = 1,
		)
		assertEquals(3, availability.count(MigrationSearchScope.ALL))
		assertEquals(2, availability.count(MigrationSearchScope.STORE))
		assertEquals(0, availability.count(MigrationSearchScope.UMA))
		assertEquals(1, availability.providerFailureCount)
	}

	private fun assertOnly(scope: MigrationSearchScope, expected: MigrationSourceOrigin) {
		for (origin in MigrationSourceOrigin.entries) {
			if (origin == expected) assertTrue(scope.matches(origin)) else assertFalse(scope.matches(origin))
		}
	}
}
