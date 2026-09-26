package org.koitharu.kotatsu.readerjourney.theme

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source-wiring guard for the failure mode where Exclusive navigation looked like one recoloured
 * capsule even though 12 presets existed. These assertions keep identity, preview and runtime on
 * the same production renderer.
 */
class ExclusiveNavigationWiringRegressionTest {

	@Test
	fun `custom navigation identity reaches the geometry resolver`() {
		val mixer = source("kotlin/org/koitharu/kotatsu/readerjourney/theme/ExclusiveThemeMixer.kt")
			.replace(Regex("\\s+"), "")
		val colors = source("kotlin/org/koitharu/kotatsu/core/ui/MiyorareColorScheme.kt")
			.replace(Regex("\\s+"), "")
		val navigation = source("kotlin/org/koitharu/kotatsu/main/ui/nav/FloatingNavBar.kt")
			.replace(Regex("\\s+"), "")

		assertTrue(mixer.contains("navigationId=navigation.id"))
		assertTrue(colors.contains("navigationStableId=navigationId.stableId"))
		assertTrue(
			navigation.contains(
				"ExclusiveBottomNavigationRegistry.resolve(palette.exclusiveTheme?.navigationStableId)",
			),
		)
		assertFalse(
			"Geometry must never resolve from the foundation stableId after a custom nav override",
			navigation.contains(
				"ExclusiveBottomNavigationRegistry.resolve(palette.exclusiveTheme?.stableId)",
			),
		)
	}

	@Test
	fun `exclusive navigation bypasses legacy recolour renderer`() {
		val host = source("kotlin/org/koitharu/kotatsu/core/ui/widgets/FloatingBottomNavigationView.kt")
			.replace(Regex("\\s+"), "")

		assertTrue(host.contains("valhasExclusiveNavigation=visualPalette.isModern&&visualPalette.exclusiveTheme?.navigation!=null"))
		assertTrue(host.contains("if(useLegacy&&!hasExclusiveNavigation){LegacyGlowNavBar("))
		assertTrue(host.contains("}else{FloatingNavBar("))
	}

	@Test
	fun `customizer preview reuses production renderer instead of a generic capsule`() {
		val customizer = source("kotlin/org/koitharu/kotatsu/stats/ui/ReaderJourneyExclusiveCollection.kt")
			.replace(Regex("\\s+"), "")
		val renderer = source("kotlin/org/koitharu/kotatsu/main/ui/nav/ExclusiveBottomNavigation.kt")
			.replace(Regex("\\s+"), "")

		assertTrue(customizer.contains("ExclusiveBottomNavigationPreview(themeId=themeId"))
		assertTrue(renderer.contains("internalfunExclusiveBottomNavigationPreview("))
		assertTrue(renderer.contains("varselectedIdbyremember(themeId.stableId){mutableStateOf(items.first().id)}"))
		assertTrue(renderer.contains("onItemSelected={selectedId=it}"))
		assertTrue(renderer.contains("ExclusiveBottomNavigationBar(items=items"))
		assertFalse(
			"Customizer must not keep the old non-interactive preview callbacks",
			renderer.contains("selectedId=items.first().id,showLabels=true,spec=spec,palette=palette,onItemSelected={}"),
		)
		assertFalse(
			"Customizer must not keep the old fixed RoundedCornerShape(22.dp) navigation mock",
			customizer.contains("valshape=RoundedCornerShape(22.dp)"),
		)
	}

	@Test
	fun `exclusive navigation animation is controlled by theme specs not global motion guards`() {
		val renderer = source("kotlin/org/koitharu/kotatsu/main/ui/nav/ExclusiveBottomNavigation.kt")
			.replace(Regex("\\s+"), "")

		assertTrue(renderer.contains("valambientEnabled=spec.ambientCycleMs!=null"))
		assertFalse(renderer.contains("KEY_RANK_THEME_REDUCE_MOTION"))
		assertFalse(renderer.contains("KEY_RANK_THEME_MINIMAL_COSMETICS"))
		assertFalse(renderer.contains("isPowerSaveMode"))
		assertFalse(renderer.contains("rememberPowerSaveMode"))
		assertFalse(renderer.contains("reduceMotion"))
		assertFalse(renderer.contains("minimalCosmetics"))
		assertTrue(
			"Reduce Glow may lower intensity but must not gate the animation timeline",
			renderer.contains("valreduceGlowbyrememberBooleanPref(AppSettings.KEY_RANK_THEME_REDUCE_GLOW,false)"),
		)
	}

	@Test
	fun `selected state uses explicit enter animation instead of starting at target`() {
		val renderer = source("kotlin/org/koitharu/kotatsu/main/ui/nav/ExclusiveBottomNavigation.kt")
			.replace(Regex("\\s+"), "")

		assertTrue(renderer.contains("valselection=remember(spec.stableId,item.id){Animatable(0f)}"))
		assertTrue(renderer.contains("selection.animateTo(targetValue=1f"))
		assertTrue(renderer.contains("valselectionProgress=selection.value"))
		assertFalse(
			"animateFloatAsState starts at the selected target on first composition and hides theme-entry motion",
			renderer.contains("label=\"exclusiveNavSelection\""),
		)
	}

	@Test
	fun `body silhouette is real path geometry not ornament-only recolouring`() {
		val renderer = source("kotlin/org/koitharu/kotatsu/main/ui/nav/ExclusiveBottomNavigation.kt")
			.replace(Regex("\\s+"), "")

		assertTrue(renderer.contains("privatefunDrawScope.exclusiveBodyPath("))
		assertTrue(renderer.contains("ExclusiveNavigationSilhouette.NOTCHED->{"))
		assertTrue(renderer.contains("ExclusiveNavigationSilhouette.AGGRESSIVE->{"))
		assertTrue(renderer.contains("ExclusiveNavigationSilhouette.BEVELED->{"))
		assertTrue(renderer.contains("ExclusiveNavigationSilhouette.ORNAMENTAL->{"))
		assertTrue(renderer.contains("ExclusiveNavigationSilhouette.PRISM->{"))
		assertTrue(renderer.contains("drawPath(path=path,brush=brush"))
	}

	private fun source(relativePath: String): String {
		return sequenceOf(
			File("src/main", relativePath),
			File("app/src/main", relativePath),
		)
			.firstOrNull(File::isFile)
			?.readText()
			?: error("Cannot find production source: $relativePath")
	}
}
