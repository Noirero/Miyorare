package org.koitharu.kotatsu.sources.compat

import java.net.URI
import java.util.Locale

/**
 * Canonical E-Hentai/ExHentai family shared by the official Miyorare Global source and the
 * language-specific Mihon/Keiyoushi E-Hentai sources.
 *
 * Language is presentation/filter metadata only. It never changes gallery identity and never causes
 * download files to be moved or rewritten.
 */
internal data class EhentaiLanguagePreset(
	val code: String,
	val directoryCode: String,
	val displayName: String,
	val flag: String,
	val mihonSourceId: Long? = null,
)

internal object EhentaiSourceFamily {

	val CANONICAL_ID = CanonicalSourceId("miyorare:family:ehentai")
	const val OFFICIAL_SOURCE_NAME = "TSUKI:MIYORARE:miyorare-global:EXHENTAI"
	const val GALLERY_CATEGORY_TAG_PREFIX = "__ehcat:"

	fun isGalleryCategoryTagKey(key: String): Boolean = key.startsWith(GALLERY_CATEGORY_TAG_PREFIX)

	/**
	 * `all` is the unfiltered preset and therefore has no independent Mihon source id in the current
	 * upstream extension. The remaining ids are the verified source ids published by the E-Hentai
	 * extension. `none` and `other` are intentionally retained because existing downloads may use
	 * those language buckets.
	 */
	val languagePresets: List<EhentaiLanguagePreset> = listOf(
		preset("all", "ALL", "All", "🌐"),
		preset("en", "EN", "English", "🇬🇧", 57122881048805941L),
		preset("ja", "JA", "日本語", "🇯🇵", 8100626124886895451L),
		preset("zh", "ZH", "中文", "🇨🇳", 4678440076103929247L),
		preset("ko", "KO", "한국어", "🇰🇷", 825187715438990384L),
		preset("de", "DE", "Deutsch", "🇩🇪", 4348288691341764259L),
		preset("es", "ES", "Español", "🇪🇸", 3032959619549451093L),
		preset("fr", "FR", "Français", "🇫🇷", 3955189842350477641L),
		preset("it", "IT", "Italiano", "🇮🇹", 5759417018342755550L),
		preset("hu", "HU", "Magyar", "🇭🇺", 773611868725221145L),
		preset("nl", "NL", "Nederlands", "🇳🇱", 1876021963378735852L),
		preset("pl", "PL", "Polski", "🇵🇱", 6116711405602166104L),
		preset("pt-BR", "PT-BR", "Português (Brasil)", "🇧🇷", 7151438547982231541L),
		preset("ru", "RU", "Русский", "🇷🇺", 2171445159732592630L),
		preset("th", "TH", "ไทย", "🇹🇭", 5980349886941016589L),
		preset("vi", "VI", "Tiếng Việt", "🇻🇳", 6073266008352078708L),
		preset("none", "NONE", "None", "🌐", 5499077866612745456L),
		preset("other", "OTHER", "Other", "🌐", 6140480779421365791L),
	)

	private val byMihonSourceId = languagePresets
		.mapNotNull { preset -> preset.mihonSourceId?.let { it to preset } }
		.toMap()

	val legacySourceDirectoryNames: Set<String> = languagePresets
		.mapTo(LinkedHashSet()) { preset -> "E-Hentai (${preset.directoryCode})" }

	fun canonicalize(identity: CanonicalSourceIdentity): CanonicalSourceIdentity? {
		val isOfficial = identity.storedName == OFFICIAL_SOURCE_NAME
		val isKnownMihon = identity.catalogueSourceId?.let(byMihonSourceId::containsKey) == true
		if (!isOfficial && !isKnownMihon) return null
		return identity.copy(
			canonicalId = CANONICAL_ID,
			isAlias = identity.isAlias || !isOfficial,
		)
	}

	fun isOfficialSource(storedName: String): Boolean = storedName == OFFICIAL_SOURCE_NAME

	fun isKnownStoredSource(storedName: String): Boolean =
		canonicalize(StoredSourceIdentity.direct(storedName)) != null

	fun languageForMihonSourceId(sourceId: Long): EhentaiLanguagePreset? = byMihonSourceId[sourceId]

	/**
	 * Extract the stable gallery id from either an absolute e-hentai/exhentai URL or the relative
	 * `/g/<id>/<token>` URL stored by parsers. Pagination/query/fragment data is ignored.
	 */
	fun galleryId(vararg urls: String?): Long? {
		for (value in urls) {
			val raw = value?.trim()?.takeIf(String::isNotEmpty) ?: continue
			val uri = runCatching { URI(raw) }.getOrNull() ?: continue
			val host = uri.host?.lowercase(Locale.ROOT)
			if (host != null && host !in ALLOWED_HOSTS) continue
			val path = uri.path?.takeIf(String::isNotEmpty) ?: continue
			val match = GALLERY_PATH.find(path) ?: continue
			match.groupValues[1].toLongOrNull()?.let { return it }
		}
		return null
	}

	private fun preset(
		code: String,
		directoryCode: String,
		displayName: String,
		flag: String,
		mihonSourceId: Long? = null,
	) = EhentaiLanguagePreset(code, directoryCode, displayName, flag, mihonSourceId)

	private val GALLERY_PATH = Regex("(?:^|/)g/([0-9]+)(?:/|$)")
	private val ALLOWED_HOSTS = setOf("e-hentai.org", "www.e-hentai.org", "exhentai.org", "www.exhentai.org")
}
