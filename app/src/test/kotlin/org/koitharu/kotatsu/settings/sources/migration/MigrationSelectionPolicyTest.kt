package org.koitharu.kotatsu.settings.sources.migration

import org.junit.Assert.assertEquals
import org.junit.Test

class MigrationSelectionPolicyTest {
	@Test fun singleMangaSelectionIsExplicitAndReversible() {
		val available = setOf(1L, 2L)
		assertEquals(setOf(1L), MigrationSelectionPolicy.toggle(emptySet(), available, 1L))
		assertEquals(emptySet<Long>(), MigrationSelectionPolicy.toggle(setOf(1L), available, 1L))
	}

	@Test fun multipleMangaSelectionDoesNotIncludeInvisibleOrPrivateOnlyIds() {
		val available = setOf(1L, 2L)
		assertEquals(setOf(1L, 2L), MigrationSelectionPolicy.replace(listOf(1L, 2L, 99L), available))
		assertEquals(setOf(1L), MigrationSelectionPolicy.toggle(setOf(1L, 99L), available, 99L))
	}

	@Test fun visibleFilterSelectAllDoesNotDropOtherSelectedItems() {
		val before = setOf(1L, 3L)
		assertEquals(setOf(1L, 2L, 3L), MigrationSelectionPolicy.toggleVisible(before, setOf(1L, 2L)))
		assertEquals(setOf(3L), MigrationSelectionPolicy.toggleVisible(setOf(1L, 2L, 3L), setOf(1L, 2L)))
	}

	@Test fun emptyVisibleFilterIsANoop() {
		assertEquals(setOf(1L), MigrationSelectionPolicy.toggleVisible(setOf(1L), emptySet()))
	}
}
