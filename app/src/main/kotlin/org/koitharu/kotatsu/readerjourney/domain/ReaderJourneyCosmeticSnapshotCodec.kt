package org.koitharu.kotatsu.readerjourney.domain

import org.koitharu.kotatsu.readerjourney.theme.RankThemeId

/**
 * Small deterministic codec for the single persisted cosmetic snapshot.
 *
 * Values are internal enum/stable IDs rather than user text, so a compact key/value format keeps
 * this migration dependency-free. Unknown/corrupt values are sanitized to safe defaults.
 */
object ReaderJourneyCosmeticSnapshotCodec {

	private const val SEPARATOR = "|"
	private const val KEY_VALUE = "="
	private const val LIST_SEPARATOR = ","

	fun encode(loadout: ReaderJourneyCosmeticLoadout): String {
		val safe = sanitize(loadout)
		return listOf(
			"v" to safe.schemaVersion.toString(),
			"mode" to safe.mode.name,
			"theme" to safe.selectedThemeId.orEmpty(),
			"navigationTheme" to safe.navigationThemeId.orEmpty(),
			"accentTheme" to safe.accentThemeId.orEmpty(),
			"glowTheme" to safe.glowThemeId.orEmpty(),
			"badge" to safe.selectedBadgeId.orEmpty(),
			"wallpaper" to safe.selectedWallpaperId.orEmpty(),
			"frameId" to safe.selectedFrameId.orEmpty(),
			"nameplateId" to safe.selectedNameplateId.orEmpty(),
			"card" to safe.selectedReaderCardId.orEmpty(),
			"progressStyle" to safe.selectedProgressStyleId.orEmpty(),
			"frame" to safe.frame?.name.orEmpty(),
			"glow" to safe.glow?.name.orEmpty(),
			"background" to safe.background?.name.orEmpty(),
			"progress" to safe.progressBar?.name.orEmpty(),
			"favorites" to safe.favoriteThemeIds.sorted().joinToString(LIST_SEPARATOR),
			"autoEquip" to if (safe.autoEquipNewRankTheme) "1" else "0",
		).joinToString(SEPARATOR) { (key, value) -> "$key$KEY_VALUE$value" }
	}

	fun decode(raw: String?): ReaderJourneyCosmeticLoadout? {
		if (raw.isNullOrBlank()) return null
		val values = raw
			.split(SEPARATOR)
			.mapNotNull { entry ->
				val index = entry.indexOf(KEY_VALUE)
				.takeIf { it > 0 } ?: return@mapNotNull null
				entry.substring(0, index) to entry.substring(index + 1)
			}
			.toMap()

		val version = values["v"]?.toIntOrNull() ?: return null
		if (version !in 2..ReaderJourneyCosmeticLoadout.SCHEMA_VERSION) return null

		return sanitize(
			ReaderJourneyCosmeticLoadout(
				schemaVersion = ReaderJourneyCosmeticLoadout.SCHEMA_VERSION,
				mode = values["mode"]?.let { rawMode ->
					ReaderJourneyCosmeticMode.entries.firstOrNull { it.name == rawMode }
				} ?: ReaderJourneyCosmeticMode.AUTO,
				selectedThemeId = values["theme"].orEmpty().ifBlank { null },
				navigationThemeId = values["navigationTheme"].orEmpty().ifBlank { null },
				accentThemeId = values["accentTheme"].orEmpty().ifBlank { null },
				glowThemeId = values["glowTheme"].orEmpty().ifBlank { null },
				selectedBadgeId = values["badge"].orEmpty().ifBlank { null },
				selectedWallpaperId = values["wallpaper"].orEmpty().ifBlank { null },
				selectedFrameId = values["frameId"].orEmpty().ifBlank { null },
				selectedNameplateId = values["nameplateId"].orEmpty().ifBlank { null },
				selectedReaderCardId = values["card"].orEmpty().ifBlank { null },
				selectedProgressStyleId = values["progressStyle"].orEmpty().ifBlank { null },
				frame = parseRank(values["frame"]),
				glow = parseRank(values["glow"]),
				background = parseRank(values["background"]),
				progressBar = parseRank(values["progress"]),
				favoriteThemeIds = values["favorites"].orEmpty()
					.split(LIST_SEPARATOR)
					.filter(String::isNotBlank)
					.toSet(),
				autoEquipNewRankTheme = values["autoEquip"] == "1",
			),
		)
	}

	fun sanitize(loadout: ReaderJourneyCosmeticLoadout): ReaderJourneyCosmeticLoadout {
		val theme = RankThemeId.fromStableId(loadout.selectedThemeId)?.stableId
		val navigationTheme = RankThemeId.fromStableId(loadout.navigationThemeId)?.stableId
		val accentTheme = RankThemeId.fromStableId(loadout.accentThemeId)?.stableId
		val glowTheme = RankThemeId.fromStableId(loadout.glowThemeId)?.stableId
		val favorites = loadout.favoriteThemeIds
			.mapNotNull { RankThemeId.fromStableId(it)?.stableId }
			.toSet()
		return loadout.copy(
			schemaVersion = ReaderJourneyCosmeticLoadout.SCHEMA_VERSION,
			selectedThemeId = theme,
			navigationThemeId = navigationTheme,
			accentThemeId = accentTheme,
			glowThemeId = glowTheme,
			selectedBadgeId = loadout.selectedBadgeId.safeInternalId(),
			selectedWallpaperId = loadout.selectedWallpaperId.safeInternalId(),
			selectedFrameId = loadout.selectedFrameId.safeInternalId(),
			selectedNameplateId = loadout.selectedNameplateId.safeInternalId(),
			selectedReaderCardId = loadout.selectedReaderCardId.safeInternalId(),
			selectedProgressStyleId = loadout.selectedProgressStyleId.safeInternalId(),
			favoriteThemeIds = favorites,
		)
	}

	private fun parseRank(raw: String?): ReaderRank? =
		raw?.let { value -> ReaderRank.entries.firstOrNull { it.name == value } }

	private fun String?.safeInternalId(): String? {
		val value = this?.takeIf { it.isNotBlank() } ?: return null
		if (value.length > 96) return null
		if (value.any { !it.isLetterOrDigit() && it != '_' && it != '-' }) return null
		return value
	}
}
