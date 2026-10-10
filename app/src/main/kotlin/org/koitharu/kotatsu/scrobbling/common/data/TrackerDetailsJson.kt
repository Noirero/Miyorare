package org.koitharu.kotatsu.scrobbling.common.data

import kotlinx.serialization.json.*
import org.koitharu.kotatsu.scrobbling.common.domain.model.*
import java.io.IOException

internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
internal fun JsonObject.array(key: String): JsonArray? = this[key] as? JsonArray
internal fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)
	?.takeUnless { it is JsonNull }?.content?.trim()?.takeIf { it.isNotEmpty() }
internal fun JsonObject.id(key: String = "id"): String? = text(key)?.toLongOrNull()
	?.takeIf { it > 0 }?.toString()
internal fun JsonObject.hasErrors(): Boolean = array("errors")?.isNotEmpty() == true
internal fun invalidTrackerResponse(): TrackerResult.Error = TrackerResult.Error(IOException("Invalid supplemental tracker response"))

internal fun <T> trackerPage(
	rows: JsonArray?,
	limit: Int,
	next: TrackerPage? = null,
	providerError: Boolean = false,
	convert: (JsonObject) -> T?,
): TrackerResult<T> {
	if (rows == null) return invalidTrackerResponse()
	val reasons = mutableSetOf<TrackerPartialReason>()
	if (providerError) reasons += TrackerPartialReason.PROVIDER_ERROR
	if (rows.size > limit) reasons += TrackerPartialReason.TRUNCATED
	val items = rows.take(limit).mapNotNull {
		val value = (it as? JsonObject)?.let(convert)
		if (value == null) reasons += TrackerPartialReason.MISSING_RECORDS
		value
	}
	return when {
		reasons.isNotEmpty() -> TrackerResult.Partial(items, reasons, next)
		items.isEmpty() -> TrackerResult.Empty(next)
		else -> TrackerResult.Success(items, next)
	}
}

/** Merge roles only for a supplied identity within one provider and one people section. */
internal fun TrackerResult<TrackerPerson>.mergePersonRoles(): TrackerResult<TrackerPerson> {
	fun merge(items: List<TrackerPerson>): List<TrackerPerson> {
		val result = ArrayList<TrackerPerson>()
		val positions = HashMap<String, Int>()
		for (person in items) {
			val position = person.id?.let(positions::get)
			if (position == null) {
				person.id?.let { positions[it] = result.size }
				result += person
			} else {
				val old = result[position]
				result[position] = old.copy(image = old.image ?: person.image, roles = (old.roles + person.roles).distinct(), url = old.url ?: person.url)
			}
		}
		return result
	}
	return when (this) {
		is TrackerResult.Success -> copy(items = merge(items))
		is TrackerResult.Partial -> copy(items = merge(items))
		else -> this
	}
}

