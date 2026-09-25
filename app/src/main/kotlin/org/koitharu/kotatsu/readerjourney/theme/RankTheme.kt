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

/**
 * Centralized signature-effects contract for the two final Reader Journey ranks.
 *
 * These values describe presentation primitives only. Screens/components consume this shared
 * config instead of hardcoding Rank 90/100 colors, glow or motion timings independently.
 */
data class RankThemeSignatureProfile(
	val backgroundAuroraStops: List<Long>,
	val borderStops: List<Long>,
	val badgeStops: List<Long>,
	val profileRingStops: List<Long>,
	val selectedStops: List<Long>,
	val shimmerStops: List<Long>,
	val staticStarCount: Int,
	val signatureSparkleCount: Int,
	val borderShiftMs: Int,
	val badgeShimmerMs: Int,
	val auroraDriftMs: Int,
	val selectedSheenOnce: Boolean,
	val glowIntensity: Float,
)

object RankThemeSignatureRegistry {
	private val auroraPrism = RankThemeSignatureProfile(
		backgroundAuroraStops = listOf(
			0xFF22104BL, 0xFF5B2EE5L, 0xFF187EF4L, 0xFF38E7F2L, 0xFFE253D6L,
		),
		borderStops = listOf(0xFF8E52FFL, 0xFF407CFFL, 0xFF4FF3FFL, 0xFFE75BE0L),
		badgeStops = listOf(0xFF9F62FFL, 0xFF4DEEFFL, 0xFFE760D7L, 0xFFF8FBFFL),
		profileRingStops = listOf(0xFF3EDCF1L, 0xFF5C75FFL, 0xFFD957D6L),
		selectedStops = listOf(0xFF7E48FFL, 0xFF3DDEF1L, 0xFF4D72FFL),
		shimmerStops = listOf(0x00FFFFFFL, 0xAAFFFFFFL, 0x00FFFFFFL),
		staticStarCount = 12,
		signatureSparkleCount = 4,
		borderShiftMs = 12_000,
		badgeShimmerMs = 6_000,
		auroraDriftMs = 30_000,
		selectedSheenOnce = true,
		glowIntensity = 0.62f,
	)

	private val celestialPrism = RankThemeSignatureProfile(
		backgroundAuroraStops = listOf(
			0xFF11152DL, 0xFFF8FBFFL, 0xFF86F3FFL, 0xFF7B8CFFL, 0xFFD96CFFL,
			0xFFFF9ECBL, 0xFFFFE29AL, 0xFF9FFFD7L, 0xFFF8FBFFL,
		),
		borderStops = listOf(
			0xFFF8FBFFL, 0xFF86F3FFL, 0xFF7B8CFFL, 0xFFD96CFFL,
			0xFFFF9ECBL, 0xFFFFE29AL, 0xFF9FFFD7L, 0xFFF8FBFFL,
		),
		badgeStops = listOf(0xFFFFF7D6L, 0xFFF8FBFFL, 0xFF86F3FFL, 0xFFD96CFFL, 0xFFFFE29AL),
		profileRingStops = listOf(0xFFF8FBFFL, 0xFF86F3FFL, 0xFFD96CFFL, 0xFFFFE29AL),
		selectedStops = listOf(0xFFFFF8DCL, 0xFF8BF4FFL, 0xFFE18AFFL, 0xFFFFD89BL),
		shimmerStops = listOf(0x00FFFFFFL, 0xD8FFFFFFL, 0x55FFE29AL, 0x00FFFFFFL),
		staticStarCount = 18,
		signatureSparkleCount = 5,
		borderShiftMs = 15_000,
		badgeShimmerMs = 9_000,
		auroraDriftMs = 34_000,
		selectedSheenOnce = true,
		glowIntensity = 0.74f,
	)

	fun resolve(id: RankThemeId): RankThemeSignatureProfile? = when (id) {
		RankThemeId.IMPERIAL_AURORA -> auroraPrism
		RankThemeId.ETERNAL_LIBRARY -> celestialPrism
		else -> null
	}
}

enum class RankThemeSource {
	USER_CUSTOM,
	EXPLICIT_RANK,
	AUTO_RANK,
	DYNAMIC_COLOR,
	MIYORARE_DEFAULT,
}

/**
 * Local-first emergency presentation switch. This affects cosmetic rendering only; Reader Journey
 * progression/ownership remains independent and intact.
 */
object RankThemePresentationSafety {
	const val ENABLED_BY_DEFAULT = true
}

data class RankThemeSourceRequest(
	val explicitCustomOverride: Boolean,
	val explicitRankThemeId: String?,
	val autoRankEnabled: Boolean,
	val currentRank: ReaderRank,
	val dynamicColorEnabled: Boolean,
	val presentationEnabled: Boolean = RankThemePresentationSafety.ENABLED_BY_DEFAULT,
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
		if (!request.presentationEnabled) {
			return RankThemeSourceResolution(RankThemeSource.MIYORARE_DEFAULT)
		}
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
	// Authored visual primitives. Final ranks fill these directly from the Rank 90/100 guide;
	// lower ranks may keep using the generic palette derivation until they are redesigned.
	val backgroundGradientStart: Long? = null,
	val backgroundGradientMiddle: Long? = null,
	val backgroundGradientEnd: Long? = null,
	val surfaceGradientStart: Long? = null,
	val surfaceGradientMiddle: Long? = null,
	val surfaceGradientEnd: Long? = null,
	val activeGradientStart: Long? = null,
	val activeGradientEnd: Long? = null,
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
)

private fun imperialAuroraTokens(variant: RankThemeVariant): RankThemeTokens {
	val light = variant == RankThemeVariant.LIGHT
	val oled = variant == RankThemeVariant.OLED
	return RankThemeTokens(
		background = when { oled -> 0xFF000000L; light -> 0xFFF6F3FFL; else -> 0xFF080A19L },
		surface = when { oled -> 0xFF06070DL; light -> 0xFFF0EBFFL; else -> 0xFF101429L },
		surfaceVariant = if (light) 0xFFE7E4F8L else 0xFF171D38L,
		container = if (light) 0xFFF1EDFFL else 0xFF0E1327L,
		primaryAccent = 0xFF8E52FFL,
		secondaryAccent = 0xFF4FF3FFL,
		onAccent = 0xFFFFFFFFL,
		borderSubtle = if (light) 0x557C64D9L else 0x665D68B8L,
		borderEmphasis = 0xFF6EE8F4L,
		glowColor = 0xFF8458F6L,
		selectedStateColor = 0xFF537BFFL,
		progressStart = 0xFF9F62FFL,
		progressEnd = 0xFF4DEEFFL,
		iconAccent = 0xFFE75BE0L,
		achievementBorder = 0xFFE760D7L,
		snackbarAccent = 0xFF55DCF1L,
		errorColor = STATUS_ERROR,
		warningColor = STATUS_WARNING,
		successColor = STATUS_SUCCESS,
		destructiveColor = STATUS_DESTRUCTIVE,
		disabledColor = if (light) STATUS_DISABLED_LIGHT else STATUS_DISABLED_DARK,
		focusIndicatorColor = 0xFF4FF3FFL,
		backgroundGradientStart = if (light) 0xFFF6F3FFL else 0xFF080A19L,
		backgroundGradientMiddle = if (light) 0xFFEDE6FFL else 0xFF22104BL,
		backgroundGradientEnd = if (light) 0xFFE8FBFFL else 0xFF102A43L,
		surfaceGradientStart = if (light) 0xFFF0EBFFL else 0xFF101429L,
		surfaceGradientMiddle = if (light) 0xFFEAF8FFL else 0xFF151D38L,
		surfaceGradientEnd = if (light) 0xFFF7E8F7L else 0xFF21132EL,
		activeGradientStart = 0xFF8E52FFL,
		activeGradientEnd = 0xFF4FF3FFL,
	)
}

private fun eternalLibraryTokens(variant: RankThemeVariant): RankThemeTokens {
	val light = variant == RankThemeVariant.LIGHT
	val oled = variant == RankThemeVariant.OLED
	return RankThemeTokens(
		background = when { oled -> 0xFF000000L; light -> 0xFFFBFAFFL; else -> 0xFF060812L },
		surface = when { oled -> 0xFF050506L; light -> 0xFFF5F3FFL; else -> 0xFF0E1222L },
		surfaceVariant = if (light) 0xFFEDEAF7L else 0xFF171B2DL,
		container = if (light) 0xFFF7F4FCL else 0xFF0C1020L,
		primaryAccent = 0xFFF8FBFFL,
		secondaryAccent = 0xFF86F3FFL,
		onAccent = 0xFF090B12L,
		borderSubtle = if (light) 0x667C7EA6L else 0x6677789CL,
		borderEmphasis = 0xFFFFE29AL,
		glowColor = 0xFFB5F5F7L,
		selectedStateColor = 0xFF9FFFD7L,
		progressStart = 0xFF86F3FFL,
		progressEnd = 0xFFFFE29AL,
		iconAccent = 0xFFD96CFFL,
		achievementBorder = 0xFFFF9ECBL,
		snackbarAccent = 0xFFFFE29AL,
		errorColor = STATUS_ERROR,
		warningColor = STATUS_WARNING,
		successColor = STATUS_SUCCESS,
		destructiveColor = STATUS_DESTRUCTIVE,
		disabledColor = if (light) STATUS_DISABLED_LIGHT else STATUS_DISABLED_DARK,
		focusIndicatorColor = 0xFF86F3FFL,
		backgroundGradientStart = if (light) 0xFFFBFAFFL else 0xFF060812L,
		backgroundGradientMiddle = if (light) 0xFFF0F8FFL else 0xFF11152DL,
		backgroundGradientEnd = if (light) 0xFFFFF8E8L else 0xFF16101FL,
		surfaceGradientStart = if (light) 0xFFF5F3FFL else 0xFF0E1222L,
		surfaceGradientMiddle = if (light) 0xFFEFFFFFL else 0xFF101B2AL,
		surfaceGradientEnd = if (light) 0xFFFFF3E1L else 0xFF21182AL,
		activeGradientStart = 0xFFF8FBFFL,
		activeGradientEnd = 0xFFFFE29AL,
	)
}

private fun finalRankDefinition(id: RankThemeId): RankThemeDefinition = when (id) {
	RankThemeId.IMPERIAL_AURORA -> RankThemeDefinition(
		id = id,
		light = imperialAuroraTokens(RankThemeVariant.LIGHT),
		dark = imperialAuroraTokens(RankThemeVariant.DARK),
		oled = imperialAuroraTokens(RankThemeVariant.OLED),
	)
	RankThemeId.ETERNAL_LIBRARY -> RankThemeDefinition(
		id = id,
		light = eternalLibraryTokens(RankThemeVariant.LIGHT),
		dark = eternalLibraryTokens(RankThemeVariant.DARK),
		oled = eternalLibraryTokens(RankThemeVariant.OLED),
	)
	else -> error("Not a final-rank theme: $id")
}

/**
 * Registry is complete for all 12 rank identities from day one. Heavy visual assets can still be
 * added later; semantic palette completeness is validated independently.
 */
object RankThemeRegistry {
	val definitions: List<RankThemeDefinition> = RankThemeId.entries.map { id ->
		if (id == RankThemeId.IMPERIAL_AURORA || id == RankThemeId.ETERNAL_LIBRARY) {
			finalRankDefinition(id)
		} else {
			val seed = checkNotNull(seeds[id]) { "Missing rank theme seed for ${id.stableId}" }
			RankThemeDefinition(
				id = id,
				light = tokens(seed, RankThemeVariant.LIGHT),
				dark = tokens(seed, RankThemeVariant.DARK),
				oled = tokens(seed, RankThemeVariant.OLED),
			)
		}
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
