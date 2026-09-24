package org.koitharu.kotatsu.readerjourney.theme

import org.koitharu.kotatsu.readerjourney.domain.ReaderRank

/**
 * Stable internal identity for Reader Journey rank themes.
 *
 * [stableId] is persisted. [displayName] is presentation-only and may be translated/rebranded.
 * Rank ordinal is deliberately not used as persisted identity.
 */
enum class RankThemeId(
	val stableId: String,
	val displayName: String,
	val rank: ReaderRank,
	val version: Int = 1,
) {
	FIRST_PAGE("NEWCOMER_FIRST_PAGE", "First Page", ReaderRank.NEWCOMER),
	FIRST_LIGHT("READER_FIRST_LIGHT", "First Light", ReaderRank.READER),
	CYAN_CODEX("BOOKWORM_CYAN_CODEX", "Cyan Codex", ReaderRank.BOOKWORM),
	EMERALD_COMPASS("EXPLORER_EMERALD_COMPASS", "Emerald Compass", ReaderRank.EXPLORER),
	VIOLET_VAULT("COLLECTOR_VIOLET_VAULT", "Violet Vault", ReaderRank.COLLECTOR),
	ARCANE_SCHOLAR("SCHOLAR_ARCANE_SCHOLAR", "Arcane Scholar", ReaderRank.SCHOLAR),
	NEON_ARCHIVE("ARCHIVIST_NEON_ARCHIVE", "Neon Archive", ReaderRank.ARCHIVIST),
	CRIMSON_LIBRARY("BIBLIOPHILE_CRIMSON_LIBRARY", "Crimson Library", ReaderRank.BIBLIOPHILE),
	EMBER_VETERAN("VETERAN_EMBER_VETERAN", "Ember Veteran", ReaderRank.VETERAN_READER),
	GOLDEN_MANUSCRIPT("MASTER_GOLDEN_MANUSCRIPT", "Golden Manuscript", ReaderRank.MASTER_READER),
	IMPERIAL_AURORA("GRAND_IMPERIAL_AURORA", "Imperial Aurora", ReaderRank.GRAND_READER),
	ETERNAL_LIBRARY("LEGEND_ETERNAL_LIBRARY", "Eternal Library", ReaderRank.LEGEND);

	companion object {
		fun fromStableId(raw: String?): RankThemeId? =
			raw?.let { value -> entries.firstOrNull { it.stableId == value } }

		fun forRank(rank: ReaderRank): RankThemeId = entries.first { it.rank == rank }
	}
}

enum class RankThemeVariant {
	LIGHT,
	DARK,
	OLED,
}

enum class RankThemeSource {
	USER_CUSTOM,
	EXPLICIT_RANK,
	AUTO_RANK,
	DYNAMIC_COLOR,
	MIYORARE_DEFAULT,
}

data class RankThemeSourceRequest(
	val explicitCustomOverride: Boolean,
	val explicitRankThemeId: String?,
	val autoRankEnabled: Boolean,
	val currentRank: ReaderRank,
	val dynamicColorEnabled: Boolean,
)

data class RankThemeSourceResolution(
	val source: RankThemeSource,
	val theme: RankThemeId? = null,
)

/**
 * One precedence resolver for presentation source. Screens must not independently choose between
 * custom/rank/auto/dynamic/default sources.
 */
object RankThemeSourceResolver {
	fun resolve(request: RankThemeSourceRequest): RankThemeSourceResolution {
		if (request.explicitCustomOverride) {
			return RankThemeSourceResolution(RankThemeSource.USER_CUSTOM)
		}
		RankThemeId.fromStableId(request.explicitRankThemeId)?.let {
			return RankThemeSourceResolution(RankThemeSource.EXPLICIT_RANK, it)
		}
		if (request.autoRankEnabled) {
			return RankThemeSourceResolution(
				source = RankThemeSource.AUTO_RANK,
				theme = RankThemeId.forRank(request.currentRank),
			)
		}
		if (request.dynamicColorEnabled) {
			return RankThemeSourceResolution(RankThemeSource.DYNAMIC_COLOR)
		}
		return RankThemeSourceResolution(RankThemeSource.MIYORARE_DEFAULT)
	}
}

/**
 * Semantic tokens only. Reading content itself must never consume these values.
 *
 * ARGB values are stored as unsigned-looking Longs so this domain model has no Android/Compose
 * dependency and can be validated in plain JVM tests.
 */
data class RankThemeTokens(
	val background: Long,
	val surface: Long,
	val surfaceVariant: Long,
	val container: Long,
	val primaryAccent: Long,
	val secondaryAccent: Long,
	val onAccent: Long,
	val borderSubtle: Long,
	val borderEmphasis: Long,
	val glowColor: Long,
	val selectedStateColor: Long,
	val progressStart: Long,
	val progressEnd: Long,
	val iconAccent: Long,
	val achievementBorder: Long,
	val snackbarAccent: Long,
	// Reserved semantic status colors stay independent from rank identity.
	val errorColor: Long,
	val warningColor: Long,
	val successColor: Long,
	val destructiveColor: Long,
	val disabledColor: Long,
	val focusIndicatorColor: Long,
)

data class RankThemeDefinition(
	val id: RankThemeId,
	val light: RankThemeTokens,
	val dark: RankThemeTokens,
	val oled: RankThemeTokens,
) {
	fun tokens(variant: RankThemeVariant): RankThemeTokens = when (variant) {
		RankThemeVariant.LIGHT -> light
		RankThemeVariant.DARK -> dark
		RankThemeVariant.OLED -> oled
	}
}

private data class RankThemeSeed(
	val accent: Long,
	val accent2: Long,
	val lightBackground: Long,
	val lightSurface: Long,
	val darkBackground: Long,
	val darkSurface: Long,
)

private val STATUS_ERROR = 0xFFBA1A1AL
private val STATUS_WARNING = 0xFFF9A825L
private val STATUS_SUCCESS = 0xFF2E7D32L
private val STATUS_DESTRUCTIVE = 0xFFB3261EL
private val STATUS_DISABLED_LIGHT = 0xFF7A7A7AL
private val STATUS_DISABLED_DARK = 0xFF8E8E93L
private val STATUS_FOCUS = 0xFF0066CCL

private fun tokens(
	seed: RankThemeSeed,
	variant: RankThemeVariant,
): RankThemeTokens {
	val oled = variant == RankThemeVariant.OLED
	val light = variant == RankThemeVariant.LIGHT
	val background = when {
		oled -> 0xFF000000L
		light -> seed.lightBackground
		else -> seed.darkBackground
	}
	val surface = when {
		oled -> 0xFF080808L
		light -> seed.lightSurface
		else -> seed.darkSurface
	}
	val onAccent = if (light) 0xFFFFFFFFL else 0xFFFFFFFFL
	val borderSubtle = if (light) 0x335A5A5AL else 0x446F6F6FL
	val disabled = if (light) STATUS_DISABLED_LIGHT else STATUS_DISABLED_DARK
	return RankThemeTokens(
		background = background,
		surface = surface,
		surfaceVariant = surface,
		container = surface,
		primaryAccent = seed.accent,
		secondaryAccent = seed.accent2,
		onAccent = onAccent,
		borderSubtle = borderSubtle,
		borderEmphasis = seed.accent,
		glowColor = seed.accent,
		selectedStateColor = seed.accent,
		progressStart = seed.accent,
		progressEnd = seed.accent2,
		iconAccent = seed.accent,
		achievementBorder = seed.accent2,
		snackbarAccent = seed.accent,
		errorColor = STATUS_ERROR,
		warningColor = STATUS_WARNING,
		successColor = STATUS_SUCCESS,
		destructiveColor = STATUS_DESTRUCTIVE,
		disabledColor = disabled,
		focusIndicatorColor = STATUS_FOCUS,
	)
}

private val seeds = mapOf(
	RankThemeId.FIRST_PAGE to RankThemeSeed(
		0xFF70757AL, 0xFFB0B5BAL, 0xFFF8F9FAL, 0xFFF0F1F2L, 0xFF151719L, 0xFF202326L,
	),
	RankThemeId.FIRST_LIGHT to RankThemeSeed(
		0xFF3978F6L, 0xFF65A7FFL, 0xFFF7F9FFL, 0xFFEEF3FFL, 0xFF0C1424L, 0xFF121D33L,
	),
	RankThemeId.CYAN_CODEX to RankThemeSeed(
		0xFF00AFC8L, 0xFF5BE7F2L, 0xFFF3FCFDL, 0xFFE7F8FAL, 0xFF071B20L, 0xFF0C2930L,
	),
	RankThemeId.EMERALD_COMPASS to RankThemeSeed(
		0xFF138A69L, 0xFF4CC9A6L, 0xFFF3FBF7L, 0xFFE8F6EFL, 0xFF071A14L, 0xFF0C2A21L,
	),
	RankThemeId.VIOLET_VAULT to RankThemeSeed(
		0xFF7B4DDBL, 0xFFA987FFL, 0xFFFAF7FFL, 0xFFF1EBFFL, 0xFF171024L, 0xFF241934L,
	),
	RankThemeId.ARCANE_SCHOLAR to RankThemeSeed(
		0xFF7950C7L, 0xFFC2A8FFL, 0xFFFAF7FFL, 0xFFF1ECFAL, 0xFF181123L, 0xFF271B36L,
	),
	RankThemeId.NEON_ARCHIVE to RankThemeSeed(
		0xFFE144A9L, 0xFFFF79C6L, 0xFFFFF7FBL, 0xFFFFEAF5L, 0xFF1B0C18L, 0xFF2A1023L,
	),
	RankThemeId.CRIMSON_LIBRARY to RankThemeSeed(
		0xFFB62D45L, 0xFFE26A79L, 0xFFFFF7F7L, 0xFFFFECEEL, 0xFF1E0C10L, 0xFF2D1218L,
	),
	RankThemeId.EMBER_VETERAN to RankThemeSeed(
		0xFFC86720L, 0xFFF2A24CL, 0xFFFFF9F3L, 0xFFFFF0E1L, 0xFF1E1209L, 0xFF302014L,
	),
	RankThemeId.GOLDEN_MANUSCRIPT to RankThemeSeed(
		0xFF9A7318L, 0xFFD5B45CL, 0xFFFFFBF1L, 0xFFFFF2CCL, 0xFF191409L, 0xFF2B2311L,
	),
	RankThemeId.IMPERIAL_AURORA to RankThemeSeed(
		0xFF6A51C7L, 0xFFD4AA45L, 0xFFFAF8FFL, 0xFFF0ECFFL, 0xFF141021L, 0xFF211A33L,
	),
	RankThemeId.ETERNAL_LIBRARY to RankThemeSeed(
		0xFF8067C7L, 0xFFC7C2D8L, 0xFFFAFAFCL, 0xFFF0EFF4L, 0xFF09090CL, 0xFF121116L,
	),
)

/**
 * Registry is complete for all 12 rank identities from day one. Heavy visual assets can still be
 * added later; semantic palette completeness is validated independently.
 */
object RankThemeRegistry {
	val definitions: List<RankThemeDefinition> = RankThemeId.entries.map { id ->
		val seed = checkNotNull(seeds[id]) { "Missing rank theme seed for ${id.stableId}" }
		RankThemeDefinition(
			id = id,
			light = tokens(seed, RankThemeVariant.LIGHT),
			dark = tokens(seed, RankThemeVariant.DARK),
			oled = tokens(seed, RankThemeVariant.OLED),
		)
	}

	private val byStableId = definitions.associateBy { it.id.stableId }

	fun resolve(stableId: String?): RankThemeDefinition? = stableId?.let(byStableId::get)

	fun resolveOrDefault(stableId: String?): RankThemeDefinition =
		resolve(stableId) ?: checkNotNull(byStableId[RankThemeId.FIRST_PAGE.stableId])

	fun validate(): List<String> {
		val errors = mutableListOf<String>()
		val ids = definitions.map { it.id.stableId }
		if (ids.distinct().size != ids.size) errors += "duplicate stable theme id"
		if (definitions.size != ReaderRank.entries.size) errors += "rank/theme count mismatch"
		ReaderRank.entries.forEach { rank ->
			if (definitions.none { it.id.rank == rank }) errors += "missing rank mapping: ${rank.name}"
		}
		definitions.forEach { definition ->
			if (definition.id.stableId.isBlank()) errors += "blank stable id: ${definition.id.name}"
			if (definition.id.version <= 0) errors += "invalid version: ${definition.id.stableId}"
		}
		return errors
	}
}
