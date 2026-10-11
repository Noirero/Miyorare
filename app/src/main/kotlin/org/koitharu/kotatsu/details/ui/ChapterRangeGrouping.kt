package org.koitharu.kotatsu.details.ui

import org.koitharu.kotatsu.details.ui.model.ChapterListItem
import org.koitharu.kotatsu.details.ui.model.ChapterRange
import org.koitharu.kotatsu.details.ui.pager.ChapterGroupingMode
import org.koitharu.kotatsu.list.ui.model.ListHeader
import org.koitharu.kotatsu.list.ui.model.ListModel
import kotlin.math.floor

/**
 * Group consecutive numeric buckets, preserving the caller's exact sort order. Non-number sorts
 * and sources that restart numbering can produce several sections of the same numeric bucket.
 * Search projects those sections without changing their identity, bounds, or Automatic size.
 */
internal fun groupChapterRanges(
	collection: List<ChapterListItem>,
	mode: ChapterGroupingMode,
	visibleChapters: List<ChapterListItem> = collection,
): List<ChapterRange> {
	val size = mode.rangeSize(collection.size) ?: return emptyList()
	if (collection.isEmpty() || visibleChapters.isEmpty()) return emptyList()
	val visible = visibleChapters.toHashSet()
	val maximum = collection.mapNotNull { it.chapter.number.takeIf(::isRangeNumber) }.maxOrNull() ?: 0f
	val occurrences = mutableMapOf<Long?, Int>()
	val result = ArrayList<ChapterRange>()
	var index = 0
	while (index < collection.size) {
		val bucket = chapterBucket(collection[index].chapter.number, size)
		val from = index++
		while (index < collection.size && chapterBucket(collection[index].chapter.number, size) == bucket) index++
		val occurrence = occurrences.getOrDefault(bucket, 0)
		occurrences[bucket] = occurrence + 1
		val members = collection.subList(from, index)
		val represented = members.filter { it in visible }
		if (represented.isEmpty()) continue
		val start = bucket?.let { it * size + 1 }
		val end = bucket?.let {
			// 50.5 belongs with 50, not 51. Make that fractional boundary explicit in the label.
			maxOf(minOf(((it + 1) * size).toFloat(), maximum), members.maxOf { item -> item.chapter.number })
		}
		result += ChapterRange("$size:${bucket ?: "special"}:$occurrence", start, end, represented)
	}
	return result
}

private fun isRangeNumber(number: Float): Boolean = number.isFinite() && number >= 1f && number.toDouble() < Long.MAX_VALUE / 2.0

private fun chapterBucket(number: Float, size: Int): Long? =
	if (isRangeNumber(number)) (floor(number.toDouble()).toLong() - 1) / size else null

/** Search expansion is temporary; explicit toggles survive search, sorting, and Off -> Automatic. */
internal fun List<ChapterRange>.withRangeExpansion(
	overrides: Map<String, Boolean>,
	searchActive: Boolean,
	hasReadingPosition: Boolean = true,
): List<ChapterRange> {
	val hasCurrent = hasReadingPosition && any { it.containsCurrent }
	return mapIndexed { index, range ->
		range.copy(expanded = searchActive || (overrides[range.key] ?:
			((hasReadingPosition && range.containsCurrent) || (!hasCurrent && index == 0))))
	}
}

/** Retain existing volume/missing-chapter decorations and adapter rows inside expanded sections. */
internal fun List<ChapterRange>.withRangeHeaders(decoratedChapters: List<ListModel>): List<ListModel> {
	if (isEmpty()) return decoratedChapters
	val blocks = HashMap<ChapterListItem, MutableList<ListModel>>()
	val pending = mutableListOf<ListModel>()
	var previous: ChapterListItem? = null
	for (row in decoratedChapters) {
		when (row) {
			is ChapterListItem -> {
				blocks[row] = (pending + row).toMutableList()
				pending.clear()
				previous = row
			}
			is ListHeader -> pending.add(row)
			else -> previous?.let { blocks[it]?.add(row) }
		}
	}
	return buildList {
		for (range in this@withRangeHeaders) {
			add(range)
			if (range.expanded) {
				for (chapter in range.chapters) addAll(blocks[chapter] ?: listOf(chapter))
			}
		}
	}
}
