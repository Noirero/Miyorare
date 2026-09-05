package org.koitharu.kotatsu.list.ui.model

import org.koitharu.kotatsu.list.domain.ListFilterOption

data class ExtensionFilter(
	val options: List<ListFilterOption.Source>,
	val selectedOptions: Set<ListFilterOption.Source>,
	val readingProgress: ListFilterOption.ReadingProgress? = null,
	val publicationState: ListFilterOption.State? = null,
	val isAdvanced: Boolean = false,
) {

	val isActive: Boolean
		get() = selectedOptions.isNotEmpty() || readingProgress != null || publicationState != null
}
