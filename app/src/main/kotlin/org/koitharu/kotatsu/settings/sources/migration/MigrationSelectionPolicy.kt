package org.koitharu.kotatsu.settings.sources.migration

/**
 * Presentation-only selection. Persisted manga/source identities are never rewritten.
 * Only ids still visible under the current Library/Private isolation policy can be selected.
 */
internal object MigrationSelectionPolicy {
	fun toggle(selected: Set<Long>, available: Set<Long>, id: Long): Set<Long> {
		if (id !in available) return selected.intersect(available)
		return selected.intersect(available).toMutableSet().apply {
			if (!add(id)) remove(id)
		}
	}

	fun replace(selected: Collection<Long>, available: Set<Long>): Set<Long> =
		selected.filterTo(linkedSetOf(), available::contains)

	fun toggleVisible(selected: Set<Long>, visible: Set<Long>): Set<Long> =
		if (visible.isNotEmpty() && visible.all(selected::contains)) selected - visible
		else selected + visible
}
