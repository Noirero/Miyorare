package org.koitharu.kotatsu.settings.about.whatsnew

import org.json.JSONObject

internal data class ReleaseIdentity(val version: String, val code: Long)

internal data class LocalizedReleaseText(val en: String, val id: String) {
    fun forLanguage(language: String): String = if (language == "id" || language == "in") id else en
}

internal enum class ReleaseCategory { NEW, IMPROVED, FIXED }

internal enum class FeatureDestination {
    SCREEN_TRANSITIONS, APPEARANCE, READER_JOURNEY, DISCOVERY_HUB,
    DOWNLOADS, SOURCES_EXTENSIONS, BACKUP_RESTORE, NAVIGATION_CONFIGURATION, WHATS_NEW;

    companion object {
        fun parse(value: String?): FeatureDestination? = entries.firstOrNull { it.name == value }
    }
}

internal data class ReleaseEntry(
    val id: String,
    val category: ReleaseCategory,
    val title: LocalizedReleaseText,
    val description: LocalizedReleaseText,
    val destination: FeatureDestination?,
)

internal data class ReleaseHighlights(
    val version: String,
    val contentId: String,
    val summary: LocalizedReleaseText,
    val entries: List<ReleaseEntry>,
    val fixture: Boolean,
)

/** Bundled APK asset contract. No network or arbitrary class names are accepted. */
internal object ReleaseCatalogParser {
    const val ASSET = "whats-new.json"

    fun parse(raw: String, installedVersion: String, allowFixture: Boolean): ReleaseHighlights? =
        runCatching {
            require(raw.length <= 65_536)
            val root = JSONObject(raw)
            require(root.getInt("schemaVersion") == 1)
            val fixture = root.optBoolean("fixture", false)
            require(!fixture || allowFixture)
            val version = root.getString("version")
            require(version == installedVersion)
            val contentId = root.getString("contentId")
            require(contentId.isNotBlank() && contentId.length <= 100)
            val entries = root.getJSONArray("entries")
            require(entries.length() in 1..20)
            val items = (0 until entries.length()).map { index ->
                val entry = entries.getJSONObject(index)
                val id = entry.getString("id")
                require(id.isNotBlank() && id.length <= 100)
                val destination = if (entry.isNull("destination")) null else
                    requireNotNull(FeatureDestination.parse(entry.getString("destination"))).also {
                        require(it != FeatureDestination.WHATS_NEW)
                    }
                ReleaseEntry(
                    id, ReleaseCategory.valueOf(entry.getString("category")),
                    entry.getJSONObject("title").localized(),
                    entry.getJSONObject("description").localized(), destination,
                )
            }
            require(items.map { it.id }.distinct().size == items.size)
            ReleaseHighlights(version, contentId, root.getJSONObject("summary").localized(), items, fixture)
        }.getOrNull()

    private fun JSONObject.localized(): LocalizedReleaseText {
        val en = getString("en")
        val id = getString("id")
        require(en.isNotBlank() && id.isNotBlank() && en.length <= 2_000 && id.length <= 2_000)
        return LocalizedReleaseText(en, id)
    }
}

/** The legacy fixed content ID intentionally does not suppress a new release. */
internal object WhatsNewEligibility {
    fun shouldShow(current: ReleaseIdentity, acknowledged: ReleaseIdentity?, upgraded: Boolean): Boolean =
        if (acknowledged == null) upgraded else
            current.code > acknowledged.code && current.version != acknowledged.version

    fun shouldRecordBaseline(acknowledged: ReleaseIdentity?, upgraded: Boolean): Boolean =
        acknowledged == null && !upgraded
}
