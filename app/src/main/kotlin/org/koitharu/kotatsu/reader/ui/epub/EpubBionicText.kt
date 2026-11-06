package org.koitharu.kotatsu.reader.ui.epub

/**
 * Bionic prefix ranges relative to the requested display-text window.
 *
 * Page binds must never run a chapter-wide regex on the UI thread. The small context around each
 * edge preserves the same prefix for words split by pagination; exceptionally long unbroken words
 * are skipped at the truncated edge instead of styling an incorrect middle-of-word prefix.
 */
internal object EpubBionicText {
    private const val MAX_WORD_CONTEXT = 128
    private val wordPattern = Regex("[\\p{L}\\p{Nd}]*\\p{L}[\\p{L}\\p{Nd}]*")
    private val unspacedScript = Regex("[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}\\p{IsHangul}\\p{IsThai}\\p{IsLao}\\p{IsKhmer}]")

    data class Window(val start: Int, val endExclusive: Int)

    fun contextWindow(source: CharSequence, start: Int, endExclusive: Int): Window {
        val from = start.coerceIn(0, source.length)
        val to = endExclusive.coerceIn(from, source.length)
        var left = from
        var right = to
        repeat(MAX_WORD_CONTEXT) {
            if (left > 0 && source[left - 1].isLetterOrDigit()) left--
        }
        repeat(MAX_WORD_CONTEXT) {
            if (right < source.length && source[right].isLetterOrDigit()) right++
        }
        return Window(left, right)
    }

    fun prefixRanges(source: CharSequence, start: Int, endExclusive: Int): List<IntRange> {
        val from = start.coerceIn(0, source.length)
        val to = endExclusive.coerceIn(from, source.length)
        if (from == to) return emptyList()
        val context = contextWindow(source, from, to)
        val slice = source.subSequence(context.start, context.endExclusive)
        val incompleteFirstWord = context.start > 0 &&
            source[context.start - 1].isLetterOrDigit() && source[context.start].isLetterOrDigit()
        val incompleteLastWord = context.endExclusive < source.length &&
            source[context.endExclusive - 1].isLetterOrDigit() && source[context.endExclusive].isLetterOrDigit()
        val ranges = ArrayList<IntRange>()
        wordPattern.findAll(slice).forEach { match ->
            if (incompleteFirstWord && match.range.first == 0) return@forEach
            if (incompleteLastWord && match.range.last == slice.lastIndex) return@forEach
            if (unspacedScript.containsMatchIn(match.value)) return@forEach
            val prefixLength = bionicPrefixLength(match.value.length)
            if (prefixLength <= 0) return@forEach
            val absoluteStart = context.start + match.range.first
            val localStart = (absoluteStart - from).coerceAtLeast(0)
            val localEnd = (absoluteStart + prefixLength - from).coerceAtMost(to - from)
            if (localStart < localEnd) ranges += localStart until localEnd
        }
        return ranges
    }
}
