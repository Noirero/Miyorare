package org.koitharu.kotatsu.settings.sources.catalog

import kotlinx.serialization.Serializable

/**
 * The media family a repository is intentionally assigned to by the user/app.
 *
 * This is deliberately NOT inferred from the current catalogue contents. A repository keeps its
 * assigned family even when it is empty, temporarily unavailable, or publishes an unusual entry.
 */
@Serializable
enum class ExtensionStoreContentType {
	MANGA,
	NOVEL,
	ANIME,
}

data class BuiltInExtensionStore(
	val indexUrl: String,
	val name: String,
	val shortName: String? = null,
	val contentType: ExtensionStoreContentType,
)

val DEFAULT_EXTENSION_STORES = listOf(
	BuiltInExtensionStore(
		indexUrl = "https://raw.githubusercontent.com/keiyoushi/extensions/repo/index.pb",
		name = "Keiyoushi",
		shortName = "KEI",
		contentType = ExtensionStoreContentType.MANGA,
	),
	BuiltInExtensionStore(
		indexUrl = "https://raw.githubusercontent.com/yuzono/cursed-manga-repo/repo/index.pb",
		name = "Cursed",
		contentType = ExtensionStoreContentType.MANGA,
	),
	BuiltInExtensionStore(
		indexUrl = "https://raw.githubusercontent.com/novelsourcery/extensions/repo/index.pb",
		name = "NovelSourcery",
		shortName = "NS",
		contentType = ExtensionStoreContentType.NOVEL,
	),
	BuiltInExtensionStore(
		indexUrl = "https://raw.githubusercontent.com/LNReader/lnreader-plugins/plugins/v3.0.0/.dist/plugins.min.json",
		name = "LNReader",
		contentType = ExtensionStoreContentType.NOVEL,
	),
)

/**
 * Optional anime repository. It is never seeded automatically; the Manage stores UI only offers it
 * as a one-tap choice when the user explicitly chooses to add an Anime repository.
 *
 * Yuzono transferred the repository to the Mojuru organisation; repo.json still identifies itself
 * as Yūzōnō and carries the same signing fingerprint.
 */
const val OPTIONAL_YUZONO_ANIME_STORE_URL =
	"https://raw.githubusercontent.com/mojuru/anime-repo/repo/repo.json"

internal const val DEFAULT_EXTENSION_STORES_VERSION = 1

/**
 * One-time compatibility mapping for stores saved before explicit media families existed. This is
 * migration only; newly added repositories always use the type selected by the user.
 */
fun legacyExtensionStoreContentType(indexUrl: String): ExtensionStoreContentType {
	val value = indexUrl.lowercase()
	return when {
		"novelsourcery/extensions" in value -> ExtensionStoreContentType.NOVEL
		"lnreader/lnreader-plugins" in value -> ExtensionStoreContentType.NOVEL
		("yuzono/anime-repo" in value || "mojuru/anime-repo" in value) -> ExtensionStoreContentType.ANIME
		else -> ExtensionStoreContentType.MANGA
	}
}
