package org.koitharu.kotatsu.favourites.groups.domain

import java.math.BigDecimal

/** Human-friendly title ordering: Collection 2 sorts before Collection 10, and 1.5 stays between 1 and 2. */
object NaturalTitleComparator : Comparator<String> {

	override fun compare(left: String, right: String): Int {
		val a = tokenize(left)
		val b = tokenize(right)
		val count = minOf(a.size, b.size)
		for (index in 0 until count) {
			val x = a[index]
			val y = b[index]
			val compared = when {
				x.number != null && y.number != null -> x.number.compareTo(y.number)
				x.number == null && y.number == null -> x.text.compareTo(y.text, ignoreCase = true)
				else -> x.text.compareTo(y.text, ignoreCase = true)
			}
			if (compared != 0) return compared
		}
		return a.size.compareTo(b.size).takeIf { it != 0 }
			?: left.compareTo(right, ignoreCase = true)
	}

	private fun tokenize(value: String): List<Token> {
		val result = ArrayList<Token>()
		var start = 0
		var numeric = value.firstOrNull()?.isDigit() == true
		for (index in 1..value.length) {
			val nextNumeric = index < value.length && (value[index].isDigit() || numeric && value[index] == '.')
			if (index == value.length || nextNumeric != numeric) {
				val text = value.substring(start, index)
				result += Token(text, text.toBigDecimalOrNull())
				start = index
				numeric = nextNumeric
			}
		}
		return result
	}

	private fun String.toBigDecimalOrNull(): BigDecimal? = try {
		BigDecimal(this)
	} catch (_: NumberFormatException) {
		null
	}

	private data class Token(val text: String, val number: BigDecimal?)
}
