package org.koitharu.kotatsu.kotatsu.model

import org.koitharu.kotatsu.parsers.model.MangaSource

/**
 * Stable host-side identity for a source supplied by the isolated Kotatsu Runtime Pack.
 *
 * The runtime revision is deliberately not part of [name]. Parser updates must never change the
 * identity used by favourites, history, downloads, source settings, or cache ownership.
 */
data class KotatsuMangaSource(
	val sourceKey: String,
	val displayName: String,
	val language: String,
	val contentType: String,
	val isBroken: Boolean = false,
) : MangaSource {

	init {
		require(SOURCE_KEY.matches(sourceKey)) { "Invalid Kotatsu source key: $sourceKey" }
	}

	override val name: String = PREFIX + sourceKey

	override fun equals(other: Any?): Boolean = other is MangaSource && other.name == name

	override fun hashCode(): Int = name.hashCode()

	companion object {
		const val PREFIX = "KOTATSU:"
		private val SOURCE_KEY = Regex("[A-Za-z0-9_.-]+")

		fun sourceKeyOf(storedName: String): String? = storedName
			.takeIf { it.startsWith(PREFIX) }
			?.removePrefix(PREFIX)
			?.takeIf { it.isNotBlank() && SOURCE_KEY.matches(it) }
	}
}
