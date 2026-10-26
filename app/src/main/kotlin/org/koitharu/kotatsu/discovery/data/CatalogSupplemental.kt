package org.koitharu.kotatsu.discovery.data

import kotlinx.serialization.json.JsonObject
import org.koitharu.kotatsu.discovery.domain.CatalogRelatedMediaKind
import org.koitharu.kotatsu.discovery.domain.TrackerCatalogPerson
import org.koitharu.kotatsu.discovery.domain.TrackerCatalogRelatedWork
import org.koitharu.kotatsu.scrobbling.common.data.array
import org.koitharu.kotatsu.scrobbling.common.data.obj
import org.koitharu.kotatsu.scrobbling.common.data.text
import org.koitharu.kotatsu.scrobbling.common.data.trackerRelationType
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService
import org.koitharu.kotatsu.scrobbling.common.domain.model.TrackerPerson
import org.koitharu.kotatsu.scrobbling.common.domain.model.TrackerResult

internal data class CatalogSupplementalResult<T>(val items: List<T>, val partial: Boolean = false)

internal fun aniListCatalogPeople(media: JsonObject, field: String): List<TrackerCatalogPerson> =
	media.obj(field)?.array("edges").orEmpty().mapNotNull { raw ->
		val edge = raw as? JsonObject ?: return@mapNotNull null
		val node = edge.obj("node") ?: return@mapNotNull null
		val name = node.obj("name")?.text("userPreferred") ?: node.obj("name")?.text("full") ?: return@mapNotNull null
		TrackerCatalogPerson(
			id = node.text("id"),
			name = name,
			imageUrl = catalogUrl(node.obj("image")?.text("large") ?: node.obj("image")?.text("medium")),
			roles = listOfNotNull(edge.text("role")).distinct(),
			providerUrl = catalogUrl(node.text("siteUrl")),
		)
	}.distinctBy { it.id ?: "${it.name}|${it.roles.joinToString()}" }

internal fun aniListCatalogRelatedWorks(media: JsonObject, allowAdult: Boolean): List<TrackerCatalogRelatedWork> =
	media.obj("relations")?.array("edges").orEmpty().mapNotNull { raw ->
		val edge = raw as? JsonObject ?: return@mapNotNull null
		val node = edge.obj("node") ?: return@mapNotNull null
		val id = node.text("id")?.takeIf { it.toLongOrNull()?.let { value -> value > 0 && value.toString() == it } == true }
			?: return@mapNotNull null
		val adult = node.catalogBoolean("isAdult")
		if (!allowAdult && adult == true) return@mapNotNull null
		val titleNode = node.obj("title") ?: return@mapNotNull null
		val title = titleNode.text("userPreferred") ?: titleNode.text("english") ?: titleNode.text("romaji")
			?: titleNode.text("native") ?: return@mapNotNull null
		val format = node.text("format")
		val mediaKind = when (node.text("type")) {
			"MANGA" -> if (format == "NOVEL") CatalogRelatedMediaKind.NOVEL else CatalogRelatedMediaKind.MANGA
			"ANIME" -> CatalogRelatedMediaKind.ANIME
			else -> CatalogRelatedMediaKind.OTHER
		}
		TrackerCatalogRelatedWork(
			service = ScrobblerService.ANILIST,
			remoteId = id,
			title = title,
			coverUrl = if (allowAdult || adult == false) catalogUrl(node.obj("coverImage")?.text("large") ?: node.obj("coverImage")?.text("medium")) else null,
			providerUrl = catalogUrl(node.text("siteUrl")),
			relation = trackerRelationType(edge.text("relationType")),
			mediaKind = mediaKind,
			format = format,
			providerRelation = edge.text("relationType"),
		)
	}.distinctBy { "${it.mediaKind}:${it.remoteId}:${it.relation}" }

internal fun catalogPeople(result: TrackerResult<TrackerPerson>): CatalogSupplementalResult<TrackerCatalogPerson> = when (result) {
	is TrackerResult.Success -> CatalogSupplementalResult(result.items.map(::catalogPerson), result.next != null)
	is TrackerResult.Partial -> CatalogSupplementalResult(result.items.map(::catalogPerson), true)
	is TrackerResult.Empty -> CatalogSupplementalResult(emptyList(), result.next != null)
	is TrackerResult.Error -> CatalogSupplementalResult(emptyList(), true)
	TrackerResult.NotRequested, TrackerResult.Unsupported -> CatalogSupplementalResult(emptyList())
}

private fun catalogPerson(person: TrackerPerson) = TrackerCatalogPerson(
	id = person.id,
	name = person.name,
	imageUrl = catalogUrl(person.image),
	roles = person.roles,
	providerUrl = catalogUrl(person.url),
)

internal fun kitsuCatalogRelatedWorks(root: JsonObject, allowAdult: Boolean): List<TrackerCatalogRelatedWork> {
	val included = root.array("included").orEmpty().filterIsInstance<JsonObject>()
		.associateBy { it.text("type") to it.text("id") }
	return root.array("data").orEmpty().mapNotNull { raw ->
		val edge = raw as? JsonObject ?: return@mapNotNull null
		if (edge.text("type") != "mediaRelationships") return@mapNotNull null
		val ref = edge.obj("relationships")?.obj("destination")?.obj("data") ?: return@mapNotNull null
		val destinationType = ref.text("type") ?: return@mapNotNull null
		val id = ref.text("id")?.takeIf { it.toLongOrNull()?.let { value -> value > 0 && value.toString() == it } == true }
			?: return@mapNotNull null
		val destination = included[destinationType to id] ?: return@mapNotNull null
		val attrs = destination.obj("attributes") ?: return@mapNotNull null
		val adult = when (attrs.text("ageRating")) {
			"R18" -> true
			"G", "PG", "R" -> false
			else -> null
		}
		if (!allowAdult && adult == true) return@mapNotNull null
		val title = attrs.text("canonicalTitle") ?: attrs.obj("titles")?.text("en")
			?: attrs.obj("titles")?.text("en_jp") ?: attrs.obj("titles")?.text("ja_jp") ?: return@mapNotNull null
		val subtype = attrs.text("subtype")
		val mediaKind = when (destinationType.lowercase()) {
			"manga" -> if (subtype == "novel") CatalogRelatedMediaKind.NOVEL else CatalogRelatedMediaKind.MANGA
			"anime" -> CatalogRelatedMediaKind.ANIME
			else -> CatalogRelatedMediaKind.OTHER
		}
		val slug = attrs.text("slug")?.takeIf { it.matches(Regex("[a-zA-Z0-9_-]+")) }
		val providerUrl = slug?.takeIf { mediaKind != CatalogRelatedMediaKind.OTHER }
			?.let { "https://kitsu.app/${destinationType.lowercase()}/$it" }
		val rawRelation = edge.obj("attributes")?.text("role")
		TrackerCatalogRelatedWork(
			service = ScrobblerService.KITSU,
			remoteId = id,
			title = title,
			coverUrl = if (allowAdult || adult == false) catalogUrl(
				attrs.obj("posterImage")?.text("large") ?: attrs.obj("posterImage")?.text("medium") ?: attrs.obj("posterImage")?.text("small"),
			) else null,
			providerUrl = catalogUrl(providerUrl),
			relation = trackerRelationType(rawRelation),
			mediaKind = mediaKind,
			format = subtype,
			providerRelation = rawRelation,
		)
	}.distinctBy { "${it.mediaKind}:${it.remoteId}:${it.relation}" }
}

internal fun malCatalogRelatedWorks(root: JsonObject, allowAdult: Boolean): List<TrackerCatalogRelatedWork> {
	val result = ArrayList<TrackerCatalogRelatedWork>()
	fun append(field: String, anime: Boolean) {
		root.array(field).orEmpty().forEach { raw ->
			val edge = raw as? JsonObject ?: return@forEach
			val node = edge.obj("node") ?: return@forEach
			val id = node.text("id")?.takeIf { it.toLongOrNull()?.let { value -> value > 0 && value.toString() == it } == true }
				?: return@forEach
			val title = node.text("title") ?: return@forEach
			val adult = when (node.text("nsfw")) {
				"white" -> false
				"gray", "black" -> true
				else -> null
			}
			if (!allowAdult && adult == true) return@forEach
			val mediaKind = if (anime) CatalogRelatedMediaKind.ANIME else when (node.text("media_type")) {
				"novel", "light_novel" -> CatalogRelatedMediaKind.NOVEL
				else -> CatalogRelatedMediaKind.MANGA
			}
			val rawRelation = edge.text("relation_type")
			result += TrackerCatalogRelatedWork(
				service = ScrobblerService.MAL,
				remoteId = id,
				title = title,
				coverUrl = if (allowAdult || adult == false) catalogUrl(node.obj("main_picture")?.text("large") ?: node.obj("main_picture")?.text("medium")) else null,
				providerUrl = if (anime) "https://myanimelist.net/anime/$id" else "https://myanimelist.net/manga/$id",
				relation = trackerRelationType(rawRelation),
				mediaKind = mediaKind,
				format = node.text("media_type"),
				providerRelation = edge.text("relation_type_formatted") ?: rawRelation,
			)
		}
	}
	append("related_manga", false)
	append("related_anime", true)
	return result.distinctBy { "${it.mediaKind}:${it.remoteId}:${it.relation}" }
}
