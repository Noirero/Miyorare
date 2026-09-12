package org.koitharu.kotatsu.sources.compat

import java.util.Locale

/**
 * Provider-neutral identity used to decide whether two stored source names represent the same
 * catalogue. The value is deliberately independent from display names: two unrelated sources may
 * share a title, and changing a translated/display title must never move user data.
 */
@JvmInline
value class CanonicalSourceId(val value: String) {
	init {
		require(value.isNotBlank()) { "Canonical source id must not be blank" }
	}

	override fun toString(): String = value

	companion object {
		fun catalogue(sourceId: Long): CanonicalSourceId = CanonicalSourceId("catalogue:$sourceId")
	}
}

enum class SourceBackend {
	/** Reserved for official Miyorare source packs. */
	MIYORARE,
	/** Mihon/Keiyoushi-compatible extension APK. */
	MIHON,
	/** Tsuki/Usagi plugin JAR, including UMA/Gekkoushi. */
	TSUKI,
	/** LNReader-compatible JavaScript plugin. */
	LNREADER,
	/** Built-in/legacy Kotatsu parser source. */
	KOTATSU,
	/** LOCAL/UNKNOWN, invalid legacy rows and other app-internal identities. */
	INTERNAL,
}

data class CanonicalSourceIdentity(
	val canonicalId: CanonicalSourceId,
	val backend: SourceBackend,
	/** Exact value persisted by Miyorare. Never rewrite this merely to canonicalize it. */
	val storedName: String,
	/** Mihon CatalogueSource.id when this identity has a known Mihon-compatible target. */
	val catalogueSourceId: Long? = null,
	val mappedSourceName: String? = null,
	val mappedPackageName: String? = null,
	/** True only when an explicit compatibility map, rather than the stored provider key, resolved it. */
	val isAlias: Boolean = false,
)

/**
 * Pure parser for stored source identities. It performs no filesystem, database, plugin loading or
 * network work and is therefore safe on hot library/download paths.
 */
object StoredSourceIdentity {
	const val MIYORARE_PREFIX = "MIYORARE:"
	const val MIHON_PREFIX = "MIHON_"
	const val TSUKI_PREFIX = "TSUKI:"
	const val LNREADER_PREFIX = "LN_"
	private const val MIYORARE_TSUKI_PREFIX = "MIYORARE:"

	fun direct(storedName: String): CanonicalSourceIdentity {
		val name = storedName.trim()
		if (name.isEmpty()) {
			// Corrupt/very old backup rows must not crash the migration screen. Preserve the raw row and
			// isolate it instead of guessing a provider.
			return CanonicalSourceIdentity(
				canonicalId = CanonicalSourceId("internal:missing-source"),
				backend = SourceBackend.INTERNAL,
				storedName = storedName,
			)
		}

		if (name == "LOCAL" || name == "UNKNOWN") {
			return CanonicalSourceIdentity(
				canonicalId = CanonicalSourceId("internal:${name.lowercase(Locale.ROOT)}"),
				backend = SourceBackend.INTERNAL,
				storedName = name,
			)
		}

		if (name.startsWith(MIYORARE_PREFIX)) {
			val value = name.removePrefix(MIYORARE_PREFIX).takeIf(String::isNotBlank)
			return CanonicalSourceIdentity(
				canonicalId = CanonicalSourceId(value?.let { "miyorare:$it" } ?: "internal:invalid-miyorare-source"),
				backend = if (value == null) SourceBackend.INTERNAL else SourceBackend.MIYORARE,
				storedName = name,
			)
		}

		if (name.startsWith(MIHON_PREFIX)) {
			val raw = name.removePrefix(MIHON_PREFIX)
			val sourceId = raw.substringBefore(':').toLongOrNull()
			return CanonicalSourceIdentity(
				canonicalId = if (sourceId != null) {
					CanonicalSourceId.catalogue(sourceId)
				} else {
					CanonicalSourceId("provider:mihon:$raw")
				},
				backend = SourceBackend.MIHON,
				storedName = name,
				catalogueSourceId = sourceId,
			)
		}

		if (name.startsWith(TSUKI_PREFIX)) {
			val raw = name.removePrefix(TSUKI_PREFIX)
			val isOfficialMiyorare = raw.startsWith(MIYORARE_TSUKI_PREFIX)
			return CanonicalSourceIdentity(
				// Keep the encoded provider/plugin/source tuple byte-for-byte. Source names may be case-sensitive.
				canonicalId = if (isOfficialMiyorare) {
					CanonicalSourceId("miyorare:${raw.removePrefix(MIYORARE_TSUKI_PREFIX)}")
				} else {
					CanonicalSourceId("provider:tsuki:$raw")
				},
				backend = if (isOfficialMiyorare) SourceBackend.MIYORARE else SourceBackend.TSUKI,
				storedName = name,
			)
		}

		if (name.startsWith(LNREADER_PREFIX)) {
			return CanonicalSourceIdentity(
				canonicalId = CanonicalSourceId("provider:lnreader:${name.removePrefix(LNREADER_PREFIX)}"),
				backend = SourceBackend.LNREADER,
				storedName = name,
			)
		}

		return CanonicalSourceIdentity(
			canonicalId = CanonicalSourceId("kotatsu:${name.uppercase(Locale.ROOT)}"),
			backend = SourceBackend.KOTATSU,
			storedName = name,
		)
	}

	fun mappedLegacy(
		storedName: String,
		catalogueSourceId: Long,
		sourceName: String? = null,
		packageName: String? = null,
	): CanonicalSourceIdentity = CanonicalSourceIdentity(
		canonicalId = CanonicalSourceId.catalogue(catalogueSourceId),
		backend = SourceBackend.KOTATSU,
		storedName = storedName.trim(),
		catalogueSourceId = catalogueSourceId,
		mappedSourceName = sourceName,
		mappedPackageName = packageName,
		isAlias = true,
	)
}