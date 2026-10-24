package org.koitharu.kotatsu.favourites.domain

import java.util.Locale
import java.util.UUID

/** Shared by schema upgrade and pre-Set cloud compatibility. Never used for new category identity. */
object CategorySetIdentity {
	fun defaultSet(space: Int): String = "miyorare:category-set:default:$space"
	fun normalizeTitle(title: String): String = title.trim().lowercase(Locale.ROOT)
	fun legacyCategory(space: Int, title: String, occurrence: Int = 0): String = UUID.nameUUIDFromBytes(
		"miyorare:legacy-category:$space:${normalizeTitle(title)}:$occurrence".toByteArray(Charsets.UTF_8),
	).toString()
}
