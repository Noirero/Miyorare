package org.koitharu.kotatsu.readerjourney.theme

/**
 * Authoring contract for one UI consumer family.
 *
 * New Exclusive Themes may override only the roles they need. Every omitted role is resolved
 * centrally from [RankThemeTokens], so screens never invent fallback colours on their own.
 */
data class ExclusiveThemeComponentAuthoring(
	val containerStops: List<Long> = emptyList(),
	val borderStops: List<Long> = emptyList(),
	val cardBorderStops: List<Long> = emptyList(),
	val selectedStops: List<Long> = emptyList(),
	val glowStops: List<Long> = emptyList(),
	val iconStops: List<Long> = emptyList(),
	val content: Long? = null,
	val mutedContent: Long? = null,
	val interactiveText: Long? = null,
	val containerMix: Float? = null,
	val selectedMix: Float? = null,
	val iconMix: Float? = null,
)

/**
 * Theme-authoring surface. Adding or redesigning a rank should happen here / in [RankThemeDefinition],
 * not inside Navbar, Favourites, Settings, Details or other screens.
 */
data class ExclusiveThemeAuthoringContract(
	val shared: ExclusiveThemeComponentAuthoring = ExclusiveThemeComponentAuthoring(),
	val navigation: ExclusiveThemeComponentAuthoring = ExclusiveThemeComponentAuthoring(),
	val favourites: ExclusiveThemeComponentAuthoring = ExclusiveThemeComponentAuthoring(),
	val settings: ExclusiveThemeComponentAuthoring = ExclusiveThemeComponentAuthoring(),
	val details: ExclusiveThemeComponentAuthoring = ExclusiveThemeComponentAuthoring(),
)

data class ResolvedExclusiveThemeComponent(
	val containerStops: List<Long>,
	val borderStops: List<Long>,
	val cardBorderStops: List<Long>,
	val selectedStops: List<Long>,
	val glowStops: List<Long>,
	val iconStops: List<Long>,
	val content: Long,
	val mutedContent: Long,
	val interactiveText: Long,
	val containerMix: Float,
	val selectedMix: Float,
	val iconMix: Float,
)

/**
 * Atomic output of the Exclusive Theme engine.
 *
 * Identity, base tokens and every consumer-specific role are resolved together so Compose and
 * Android Views cannot observe a theme id from one resolution and colours from another.
 */
data class ResolvedExclusiveTheme(
	val id: RankThemeId,
	/**
	 * Effective navigation identity. This may differ from [id] for CUSTOM loadouts and must be
	 * carried beside the navigation palette so geometry never falls back to the foundation theme.
	 */
	val navigationId: RankThemeId,
	val tokens: RankThemeTokens,
	val shared: ResolvedExclusiveThemeComponent,
	val navigation: ResolvedExclusiveThemeComponent,
	val favourites: ResolvedExclusiveThemeComponent,
	val settings: ResolvedExclusiveThemeComponent,
	val details: ResolvedExclusiveThemeComponent,
)

/**
 * Single fallback owner for Exclusive Theme presentation roles.
 *
 * Consumer code must read the resolved roles instead of blending primary/secondary again.
 */
object ExclusiveThemeContractResolver {

	fun validateAuthoring(
		id: RankThemeId,
		contract: ExclusiveThemeAuthoringContract,
	): List<String> {
		val errors = mutableListOf<String>()
		val components = listOf(
			"shared" to contract.shared,
			"navigation" to contract.navigation,
			"favourites" to contract.favourites,
			"settings" to contract.settings,
			"details" to contract.details,
		)
		for ((name, component) in components) {
			fun validateStops(role: String, stops: List<Long>) {
				if (stops.size == 1) {
					errors += "${id.stableId}.$name.$role requires at least 2 stops when authored"
				}
			}
			validateStops("containerStops", component.containerStops)
			validateStops("borderStops", component.borderStops)
			validateStops("cardBorderStops", component.cardBorderStops)
			validateStops("selectedStops", component.selectedStops)
			validateStops("glowStops", component.glowStops)
			validateStops("iconStops", component.iconStops)
			for ((role, value) in listOf(
				"containerMix" to component.containerMix,
				"selectedMix" to component.selectedMix,
				"iconMix" to component.iconMix,
			)) {
				if (value != null && value !in 0f..1f) {
					errors += "${id.stableId}.$name.$role must be within 0..1"
				}
			}
		}
		return errors
	}

	fun resolve(
		definition: RankThemeDefinition,
		variant: RankThemeVariant,
	): ResolvedExclusiveTheme {
		val tokens = definition.tokens(variant)
		val signature = RankThemeSignatureRegistry.resolve(definition.id)

		val baseContainer = listOfNotNull(
			tokens.surfaceGradientStart,
			tokens.surfaceGradientMiddle,
			tokens.surfaceGradientEnd,
		).ifEmpty {
			listOf(tokens.surface, tokens.container, tokens.surfaceVariant)
		}
		val baseBorder = signature?.borderStops
			?.takeIf { it.size >= 2 }
			?: listOf(tokens.borderEmphasis, tokens.borderEmphasis)
		val baseSelected = signature?.selectedStops
			?.takeIf { it.size >= 2 }
			?: listOf(
				tokens.activeGradientStart ?: tokens.primaryAccent,
				tokens.activeGradientEnd ?: tokens.secondaryAccent,
			)
		val baseGlow = listOf(tokens.glowColor, tokens.secondaryAccent, tokens.iconAccent)
		val baseIcons = listOf(tokens.iconAccent, tokens.secondaryAccent)
		val baseContent = tokens.onAccent
		val baseMutedContent = tokens.onAccent
		val baseInteractive = tokens.secondaryAccent

		fun resolveComponent(authoring: ExclusiveThemeComponentAuthoring): ResolvedExclusiveThemeComponent =
			ResolvedExclusiveThemeComponent(
				containerStops = authoring.containerStops.takeIf { it.size >= 2 } ?: baseContainer,
				borderStops = authoring.borderStops.takeIf { it.size >= 2 } ?: baseBorder,
				cardBorderStops = authoring.cardBorderStops.takeIf { it.size >= 2 }
					?: authoring.borderStops.takeIf { it.size >= 2 }
					?: baseBorder,
				selectedStops = authoring.selectedStops.takeIf { it.size >= 2 } ?: baseSelected,
				glowStops = authoring.glowStops.takeIf { it.size >= 2 } ?: baseGlow,
				iconStops = authoring.iconStops.takeIf { it.size >= 2 } ?: baseIcons,
				content = authoring.content ?: baseContent,
				mutedContent = authoring.mutedContent ?: baseMutedContent,
				interactiveText = authoring.interactiveText ?: baseInteractive,
				containerMix = authoring.containerMix?.coerceIn(0f, 1f) ?: 0.18f,
				selectedMix = authoring.selectedMix?.coerceIn(0f, 1f) ?: 0.64f,
				iconMix = authoring.iconMix?.coerceIn(0f, 1f) ?: 0.48f,
			)

		return ResolvedExclusiveTheme(
			id = definition.id,
			navigationId = definition.id,
			tokens = tokens,
			shared = resolveComponent(definition.authoring.shared),
			navigation = resolveComponent(definition.authoring.navigation),
			favourites = resolveComponent(definition.authoring.favourites),
			settings = resolveComponent(definition.authoring.settings),
			details = resolveComponent(definition.authoring.details),
		)
	}
}
