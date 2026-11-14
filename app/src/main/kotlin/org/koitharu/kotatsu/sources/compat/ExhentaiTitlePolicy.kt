package org.koitharu.kotatsu.sources.compat

/** Presentation and pre-upgrade folder lookup deliberately use different transformations. */
internal object ExhentaiTitlePolicy {
	private val legacySegments = Regex("(\\[.*?]|\\([C0-9]*\\))")
	private val legacySpaces = Regex("(^\\s+|\\s+\$|\\s+(?=\\s))")
	private val spaces = Regex("\\s+")

	/** Reproduce the pinned pre-upgrade parser. The result is a path candidate, never ownership. */
	fun legacyFolderTitle(title: String): String =
		title.replace(legacySegments, "").replace(legacySpaces, "")

	/** Only for source-provided display titles, after the caller has checked for a custom title. */
	fun compactTitle(title: String): String {
		var depth = 0
		var foundSegment = false
		val visible = StringBuilder(title.length)
		for (c in title) {
			when (c) {
				'[' -> {
					if (depth == 0) visible.append(' ')
					depth++
					foundSegment = true
				}
				']' -> {
					if (depth == 0) return title
					depth--
				}
				else -> if (depth == 0) visible.append(c)
			}
		}
		if (depth != 0 || !foundSegment) return title
		return visible.toString().replace(spaces, " ").trim().ifEmpty { title }
	}
}
