package org.koitharu.kotatsu.stats.data

import org.koitharu.kotatsu.core.db.entity.TagEntity
import java.util.Locale

/**
 * Keeps dashboard insights human-readable and privacy-safe.
 *
 * Source-specific namespaces such as "female:", "male:" and "other:" are metadata facets, not
 * genres. They should never leak into Top Genres as raw technical strings.
 */
internal object StatsTagClassifier {

	private val matureExact = setOf(
		"adult", "hentai", "18+", "nsfw", "mature", "explicit", "smut", "ero", "erotica",
		"porn", "pornographic",
	)

	/**
	 * Namespaces commonly used for explicit/fetish facets. Treating them as a mature signal is
	 * intentionally privacy-first: a false positive hides identity in Private mode, whereas a false
	 * negative could reveal a sensitive title.
	 */
	private val matureNamespaces = setOf(
		"female", "male", "mixed", "fetish", "sexual", "adult", "hentai", "nsfw",
	)

	private val genreNamespaces = setOf(
		"genre", "genres", "theme", "themes", "demographic", "category", "categories",
	)

	private val nonGenre = matureExact + setOf(
		"ecchi",
		"manga", "manhwa", "manhua", "webtoon", "comic", "comics",
		"novel", "light novel", "light-novel", "web novel", "webnovel",
		"ongoing", "completed", "complete", "finished",
		"ai generated", "ai-generated", "translated", "translation", "uncensored", "censored",
		"full color", "full-colour", "colored", "colourized", "digital",
	)

	fun isMatureTag(tag: TagEntity): Boolean =
		isMatureToken(tag.title) || isMatureToken(tag.key)

	fun genreLabel(tag: TagEntity): String? {
		return genreLabel(tag.title) ?: genreLabel(tag.key)
	}

	fun formatLabel(isNovel: Boolean, tags: Collection<TagEntity>): String {
		val tokens = tags
			.asSequence()
			.flatMap { sequenceOf(it.title, it.key) }
			.map(::canonicalValue)
			.filter { it.isNotEmpty() }
			.toSet()
		return if (isNovel) {
			when {
				tokens.any { it == "light novel" || it == "lightnovel" || it == "ranobe" } -> "Light Novel"
				tokens.any { it == "web novel" || it == "webnovel" } -> "Web Novel"
				else -> "Novel"
			}
		} else {
			when {
				tokens.any { it == "manhwa" || it == "korean comic" } -> "Manhwa"
				tokens.any { it == "manhua" || it == "chinese comic" } -> "Manhua"
				tokens.any { it == "webtoon" || it == "web comic" } -> "Webtoon"
				else -> "Manga"
			}
		}
	}

	private fun genreLabel(raw: String): String? {
		val trimmed = raw.trim()
		if (trimmed.length < 2 || isMatureToken(trimmed)) return null

		val colon = trimmed.indexOf(':')
		val value = if (colon >= 0) {
			val namespace = trimmed.substring(0, colon).trim().lowercase(Locale.ROOT)
			if (namespace !in genreNamespaces) return null
			trimmed.substring(colon + 1).trim()
		} else {
			trimmed
		}
		if (value.length < 2) return null

		val canonical = canonicalValue(value)
		if (canonical.isEmpty() || canonical in nonGenre) return null
		if (canonical.any { it == ':' || it == '/' || it == '\\' }) return null

		return canonical
			.split(' ')
			.filter { it.isNotBlank() }
			.joinToString(" ") { word ->
				word.replaceFirstChar { char ->
					if (char.isLowerCase()) char.titlecase(Locale.getDefault()) else char.toString()
				}
			}
			.takeIf { it.isNotBlank() }
	}

	private fun isMatureToken(raw: String): Boolean {
		val canonical = raw.trim().lowercase(Locale.ROOT)
		if (canonical in matureExact) return true
		val colon = canonical.indexOf(':')
		if (colon > 0) {
			val namespace = canonical.substring(0, colon).trim()
			if (namespace in matureNamespaces) return true
			val value = canonical.substring(colon + 1).trim()
			if (value in matureExact) return true
		}
		return false
	}

	private fun canonicalValue(raw: String): String {
		val trimmed = raw.trim().lowercase(Locale.ROOT)
		val value = trimmed.substringAfter(':', trimmed)
		return value
			.replace('_', ' ')
			.replace('-', ' ')
			.replace(Regex("\\s+"), " ")
			.trim()
	}
}
