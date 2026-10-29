package org.koitharu.kotatsu.discovery.data

import kotlinx.serialization.json.*
import org.koitharu.kotatsu.discovery.domain.*
import org.koitharu.kotatsu.scrobbling.common.data.array
import org.koitharu.kotatsu.scrobbling.common.data.obj
import org.koitharu.kotatsu.scrobbling.common.data.text

internal fun invalidCatalog(): Nothing = throw CatalogException(CatalogFailure(CatalogFailureKind.INVALID_DATA))
internal fun JsonObject.catalogId(key: String = "id"): String = text(key)?.takeIf { it.toLongOrNull()?.let { id -> id > 0 && id.toString() == it } == true } ?: invalidCatalog()
internal fun JsonObject.catalogInt(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull?.takeIf { it >= 0 }
internal fun JsonObject.catalogDouble(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() }
internal fun JsonObject.catalogBoolean(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
internal fun catalogUrl(value: String?): String? = value?.takeIf {
	runCatching { java.net.URI(it).let { uri -> uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null } }.getOrDefault(false)
}
internal fun JsonObject.catalogStrings(key: String): List<String> = array(key).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) }.distinct().take(30)

internal fun catalogRows(rows: JsonArray, convert: (JsonObject) -> TrackerCatalogItem?): List<TrackerCatalogItem> {
	var malformed = 0
	val result = rows.mapNotNull { value ->
		val row = value as? JsonObject
		if (row == null) { malformed++; null } else try { convert(row) }
		catch (e: CatalogException) { malformed++; null }
		catch (e: IllegalArgumentException) { malformed++; null }
	}.distinctBy { it.key }
	if (rows.isNotEmpty() && malformed == rows.size) invalidCatalog()
	return result
}

internal fun catalogPageNumber(page: Int) { require(page in 1..CatalogCachePolicy.MAX_PAGE) }

internal fun parseAniListCard(root: JsonObject, allowAdult: Boolean): TrackerCatalogItem? {
	if (root.text("type")?.let { it != "MANGA" } == true) return null
	val providerType = root.text("format")
	val kind = when (providerType) {
		"NOVEL" -> CatalogMediaKind.NOVEL
		"MANGA", "ONE_SHOT" -> CatalogMediaKind.MANGA
		else -> invalidCatalog()
	}
	val title = root.obj("title") ?: invalidCatalog()
	val name = title.text("userPreferred") ?: title.text("english") ?: title.text("romaji") ?: title.text("native") ?: invalidCatalog()
	return TrackerCatalogItem(
		TrackerCatalogKey(org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService.ANILIST, kind, root.catalogId()), name,
		catalogUrl(root.obj("coverImage")?.text("large")), catalogUrl(root.text("siteUrl")),
		root.catalogDouble("averageScore")?.takeIf { it in 0.0..100.0 }?.div(10), root.catalogBoolean("isAdult"), providerType,
	).forPolicy(allowAdult)
}

internal fun parseMALCard(root: JsonObject, allowAdult: Boolean): TrackerCatalogItem? {
	val providerType = root.text("media_type")
	val kind = when (providerType) {
		"novel", "light_novel" -> CatalogMediaKind.NOVEL
		"manga", "one_shot", "doujinshi", "manhwa", "manhua", "oel" -> CatalogMediaKind.MANGA
		else -> invalidCatalog()
	}
	val adult = when (root.text("nsfw")) { "white" -> false; "gray", "black" -> true; else -> null }
	val id = root.catalogId()
	return TrackerCatalogItem(
		TrackerCatalogKey(org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService.MAL, kind, id), root.text("title") ?: invalidCatalog(),
		catalogUrl(root.obj("main_picture")?.text("large") ?: root.obj("main_picture")?.text("medium")), "https://myanimelist.net/manga/$id",
		root.catalogDouble("mean")?.takeIf { it in 0.0..10.0 }, adult, providerType,
	).forPolicy(allowAdult)
}

internal fun parseKitsuCard(root: JsonObject, allowAdult: Boolean): TrackerCatalogItem? {
	if (root.text("type") != "manga") invalidCatalog()
	val attrs = root.obj("attributes") ?: invalidCatalog()
	val providerType = attrs.text("subtype")
	val kind = when (providerType) {
		"novel" -> CatalogMediaKind.NOVEL
		"manga", "manhwa", "manhua", "oneshot", "doujin", "oel" -> CatalogMediaKind.MANGA
		else -> invalidCatalog()
	}
	val adult = when (attrs.text("ageRating")) { "R18" -> true; "G", "PG", "R" -> false; else -> null }
	val id = root.catalogId()
	return TrackerCatalogItem(
		TrackerCatalogKey(org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService.KITSU, kind, id), attrs.text("canonicalTitle") ?: invalidCatalog(),
		catalogUrl(attrs.obj("posterImage")?.text("large") ?: attrs.obj("posterImage")?.text("medium")), "https://kitsu.app/manga/${attrs.text("slug")?.takeIf { it.matches(Regex("[a-zA-Z0-9_-]+")) } ?: id}",
		attrs.catalogDouble("averageRating")?.takeIf { it in 0.0..100.0 }?.div(10), adult, providerType,
	).forPolicy(allowAdult)
}
