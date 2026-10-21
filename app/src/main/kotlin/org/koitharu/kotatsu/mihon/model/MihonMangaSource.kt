package org.koitharu.kotatsu.mihon.model

import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.isNovelSource
import org.koitharu.kotatsu.extensions.runtime.getExternalExtensionLangCode
import org.koitharu.kotatsu.extensions.runtime.getExternalExtensionLanguageAutonym
import org.koitharu.kotatsu.parsers.model.MangaSource

/** Every novel-extension package is named `…novelextension.<lang>.<site>`, whichever repo built it. */
private const val NOVEL_PACKAGE_MARKER = "novelextension."

data class MihonMangaSource(
	val catalogueSource: CatalogueSource,
	val pkgName: String,
	val isNsfw: Boolean = false,
	/** True when this source has sibling language variants in the same package. */
	val hasLanguageSuffix: Boolean = false,
) : MangaSource {
	override val name: String
		get() = "MIHON_${catalogueSource.id}"

	/**
	 * The source's display name without a language suffix. Explore keeps every source id separate
	 * and surfaces [language] as secondary metadata.
	 */
	val displayName: String
		get() = catalogueSource.name

	/** The active language in its own native form, e.g. "Français", "日本語". */
	val languageDisplayName: String
		get() = getExternalExtensionLanguageAutonym(language)

	/** Canonical BCP-47 language key used by Explore and source filtering. */
	val language: String
		get() = getExternalExtensionLangCode(catalogueSource.lang)

	val sourceId: Long
		get() = catalogueSource.id

	val supportsLatest: Boolean
		get() = catalogueSource.supportsLatest

	/**
	 * True for a prose extension. Prefer the source-api compatibility contract because newer
	 * extensions may implement the NovelSource marker without overriding the legacy property.
	 * The package name remains a fallback for malformed novel packages that declare neither signal.
	 */
	val isNovel: Boolean
		get() = catalogueSource.isNovelSource() || pkgName.contains(NOVEL_PACKAGE_MARKER)

	override fun equals(other: Any?): Boolean {
		if (this === other) return true
		if (other !is MangaSource) return false
		val raw = other.name.removePrefix("MIHON_").substringBefore(':')
		return raw.toLongOrNull() == sourceId
	}

	override fun hashCode(): Int {
		return sourceId.hashCode()
	}

	override fun toString(): String {
		return "MihonMangaSource(id=${catalogueSource.id}, name=${catalogueSource.name}, lang=$language)"
	}
}
