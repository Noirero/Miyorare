package org.koitharu.kotatsu.reader.ui.epub.translation

/** Presentation-only state. Original source text remains canonical in every mode. */
enum class NovelTranslationDisplayMode {
	ORIGINAL,
	BILINGUAL,
	TRANSLATED_ONLY,
}

internal data class NovelTranslationDisplaySegment(
	val start: Int,
	val end: Int,
	val translatedText: String,
)

/**
 * Pure source/display projection helpers shared by Reader rendering, navigation and selection.
 * Translation text is derived presentation; every mapping is anchored in source offsets.
 */
internal object NovelTranslationProjection {

	fun displayText(
		original: CharSequence,
		segments: List<NovelTranslationDisplaySegment>,
		mode: NovelTranslationDisplayMode,
	): String = when (mode) {
		NovelTranslationDisplayMode.ORIGINAL -> original.toString()
		NovelTranslationDisplayMode.BILINGUAL -> buildBilingual(original, normalized(original.length, segments))
		NovelTranslationDisplayMode.TRANSLATED_ONLY -> buildTranslatedOnly(original, normalized(original.length, segments))
	}

	fun sourceToDisplayOffset(
		sourceLength: Int,
		segments: List<NovelTranslationDisplaySegment>,
		mode: NovelTranslationDisplayMode,
		sourceOffset: Int,
		afterBoundary: Boolean = false,
	): Int {
		val clamped = sourceOffset.coerceIn(0, sourceLength.coerceAtLeast(0))
		val items = normalized(sourceLength, segments)
		return when (mode) {
			NovelTranslationDisplayMode.ORIGINAL -> clamped
			NovelTranslationDisplayMode.BILINGUAL -> sourceToBilingual(items, clamped, afterBoundary)
			NovelTranslationDisplayMode.TRANSLATED_ONLY -> sourceToTranslatedOnly(items, clamped, afterBoundary)
		}
	}

	fun displayToSourceOffset(
		sourceLength: Int,
		segments: List<NovelTranslationDisplaySegment>,
		mode: NovelTranslationDisplayMode,
		displayOffset: Int,
	): Int {
		val items = normalized(sourceLength, segments)
		return when (mode) {
			NovelTranslationDisplayMode.ORIGINAL -> displayOffset.coerceIn(0, sourceLength.coerceAtLeast(0))
			NovelTranslationDisplayMode.BILINGUAL -> bilingualToSource(sourceLength, items, displayOffset)
			NovelTranslationDisplayMode.TRANSLATED_ONLY -> translatedOnlyToSource(sourceLength, items, displayOffset)
		}
	}

	fun sourceRangeToDisplaySegments(
		sourceLength: Int,
		segments: List<NovelTranslationDisplaySegment>,
		mode: NovelTranslationDisplayMode,
		sourceStart: Int,
		sourceEnd: Int,
	): List<Pair<Int, Int>> {
		val start = sourceStart.coerceIn(0, sourceLength.coerceAtLeast(0))
		val end = sourceEnd.coerceIn(start, sourceLength.coerceAtLeast(0))
		if (start >= end) return emptyList()
		if (mode != NovelTranslationDisplayMode.BILINGUAL) {
			val displayStart = sourceToDisplayOffset(sourceLength, segments, mode, start, afterBoundary = true)
			val displayEnd = sourceToDisplayOffset(sourceLength, segments, mode, end)
			return if (displayStart < displayEnd) listOf(displayStart to displayEnd) else emptyList()
		}

		val boundaries = normalized(sourceLength, segments)
			.map { it.end }
			.filter { it > start && it < end }
			.distinct()
			.sorted()
		val result = ArrayList<Pair<Int, Int>>(boundaries.size + 1)
		var cursor = start
		(boundaries + end).forEach { boundary ->
			val displayStart = sourceToDisplayOffset(sourceLength, segments, mode, cursor, afterBoundary = true)
			val displayEnd = sourceToDisplayOffset(sourceLength, segments, mode, boundary)
			if (displayStart < displayEnd) result += displayStart to displayEnd
			cursor = boundary
		}
		return result
	}

	/** Only bilingual inserted blocks are unmappable. Translated-only replacements map to source. */
	fun displayRangeTouchesUnmappedTranslation(
		sourceLength: Int,
		segments: List<NovelTranslationDisplaySegment>,
		mode: NovelTranslationDisplayMode,
		displayStart: Int,
		displayEnd: Int,
	): Boolean {
		if (mode != NovelTranslationDisplayMode.BILINGUAL || displayStart >= displayEnd) return false
		var delta = 0
		for (item in normalized(sourceLength, segments)) {
			val insertionStart = item.end + delta
			val insertionEnd = insertionStart + bilingualBlock(item).length
			if (displayStart < insertionEnd && displayEnd > insertionStart) return true
			delta = insertionEnd - item.end
		}
		return false
	}

	private fun buildBilingual(original: CharSequence, segments: List<NovelTranslationDisplaySegment>): String {
		val output = StringBuilder(original.toString())
		for (item in segments.asReversed()) output.insert(item.end, bilingualBlock(item))
		return output.toString()
	}

	private fun buildTranslatedOnly(original: CharSequence, segments: List<NovelTranslationDisplaySegment>): String {
		val output = StringBuilder(original.toString())
		for (item in segments.asReversed()) output.replace(item.start, item.end, translatedText(item))
		return output.toString()
	}

	private fun sourceToBilingual(
		segments: List<NovelTranslationDisplaySegment>,
		sourceOffset: Int,
		afterBoundary: Boolean,
	): Int {
		var delta = 0
		for (item in segments) {
			if (item.end > sourceOffset || (!afterBoundary && item.end == sourceOffset)) continue
			delta += bilingualBlock(item).length
		}
		return sourceOffset + delta
	}

	private fun sourceToTranslatedOnly(
		segments: List<NovelTranslationDisplaySegment>,
		sourceOffset: Int,
		afterBoundary: Boolean,
	): Int {
		var delta = 0
		for (item in segments) {
			val replacementLength = translatedText(item).length
			if (sourceOffset < item.start || (!afterBoundary && sourceOffset == item.start)) return sourceOffset + delta
			if (sourceOffset <= item.end) {
				val sourceSpan = (item.end - item.start).coerceAtLeast(1)
				val relative = (sourceOffset - item.start).coerceIn(0, sourceSpan)
				return item.start + delta + (relative.toLong() * replacementLength / sourceSpan).toInt()
			}
			delta += replacementLength - (item.end - item.start)
		}
		return sourceOffset + delta
	}

	private fun bilingualToSource(
		sourceLength: Int,
		segments: List<NovelTranslationDisplaySegment>,
		displayOffset: Int,
	): Int {
		var delta = 0
		for (item in segments) {
			val insertionStart = item.end + delta
			val insertionEnd = insertionStart + bilingualBlock(item).length
			if (displayOffset < insertionStart) return clampSource(displayOffset - delta, sourceLength)
			if (displayOffset <= insertionEnd) return item.end
			delta = insertionEnd - item.end
		}
		return clampSource(displayOffset - delta, sourceLength)
	}

	private fun translatedOnlyToSource(
		sourceLength: Int,
		segments: List<NovelTranslationDisplaySegment>,
		displayOffset: Int,
	): Int {
		var delta = 0
		for (item in segments) {
			val replacementLength = translatedText(item).length
			val replacementStart = item.start + delta
			val replacementEnd = replacementStart + replacementLength
			if (displayOffset < replacementStart) return clampSource(displayOffset - delta, sourceLength)
			if (displayOffset <= replacementEnd) {
				val sourceSpan = item.end - item.start
				if (replacementLength == 0 || sourceSpan == 0) return item.end
				val relative = (displayOffset - replacementStart).coerceIn(0, replacementLength)
				return clampSource(item.start + (relative.toLong() * sourceSpan / replacementLength).toInt(), sourceLength)
			}
			delta += replacementLength - (item.end - item.start)
		}
		return clampSource(displayOffset - delta, sourceLength)
	}

	private fun normalized(
		sourceLength: Int,
		segments: List<NovelTranslationDisplaySegment>,
	): List<NovelTranslationDisplaySegment> {
		if (sourceLength <= 0 || segments.isEmpty()) return emptyList()
		val result = ArrayList<NovelTranslationDisplaySegment>(segments.size)
		var previousEnd = 0
		for (item in segments.sortedBy { it.start }) {
			val start = item.start.coerceIn(previousEnd, sourceLength)
			val end = item.end.coerceIn(start, sourceLength)
			if (start >= end || item.translatedText.isBlank()) continue
			result += item.copy(start = start, end = end)
			previousEnd = end
		}
		return result
	}

	private fun translatedText(item: NovelTranslationDisplaySegment): String = item.translatedText
	private fun bilingualBlock(item: NovelTranslationDisplaySegment): String = "\n${item.translatedText.trim()}\n"
	private fun clampSource(value: Int, sourceLength: Int): Int = value.coerceIn(0, sourceLength.coerceAtLeast(0))
}
