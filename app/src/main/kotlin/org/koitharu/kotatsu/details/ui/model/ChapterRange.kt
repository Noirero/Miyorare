package org.koitharu.kotatsu.details.ui.model

import org.koitharu.kotatsu.list.ui.model.ListModel
import org.koitharu.kotatsu.parsers.util.formatSimple

/** Derived navigation header. Chapter objects and their metadata remain owned by the chapter flow. */
data class ChapterRange(
	val key: String,
	val start: Long?,
	val end: Float?,
	val chapters: List<ChapterListItem>,
	val expanded: Boolean = false,
) : ListModel {
	val readCount: Int get() = chapters.count { !it.isUnread }
	val totalCount: Int get() = chapters.size
	val containsCurrent: Boolean get() = chapters.any { it.isCurrent }
	val numberLabel: String? get() = end?.let {
		"$start–${it.formatSimple()}"
	}

	override fun areItemsTheSame(other: ListModel): Boolean = other is ChapterRange && key == other.key
}
