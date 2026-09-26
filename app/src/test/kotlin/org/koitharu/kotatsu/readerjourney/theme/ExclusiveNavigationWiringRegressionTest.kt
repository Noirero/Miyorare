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
	fun `production exclusive routing uses the reactive palette as its only Modern source`() {
		val navigation = source("kotlin/org/koitharu/kotatsu/main/ui/nav/FloatingNavBar.kt")
			.replace(Regex("\\s+"), "")

		assertTrue(navigation.contains("valisMiyorareModern=palette.isModern"))
		assertTrue(
			navigation.contains(
				"valuseExclusiveRenderer=isMiyorareModern&&exclusiveNavigation!=null&&exclusiveNavigationSpec!=null",
			),
		)
		assertTrue(navigation.contains("if(useExclusiveRenderer){"))
		assertFalse(
			"Production routing must not re-read a stale design-style preference inside FloatingNavBar",
			navigation.contains("PreferenceManager.getDefaultSharedPreferences(context).getEnumValue("),
		)
		assertFalse(
			"Exclusive renderer reachability must not depend on the Favourites emphasis flag",
			navigation.contains("isMiyorareModern&&emphasizeFavourites&&exclusiveNavigation!=null"),
		)
	}

	@Test
	fun `motion policy keeps selection while reduce motion and battery saver stop ambient loops`() {
		val renderer = source("kotlin/org/koitharu/kotatsu/main/ui/nav/ExclusiveBottomNavigation.kt")
			.replace(Regex("\\s+"), "")

		assertTrue(renderer.contains("KEY_RANK_THEME_REDUCE_MOTION"))
		assertTrue(renderer.contains("valpowerSaveMode=rememberPowerSaveMode()"))
		assertTrue(renderer.contains("vallifecycleResumed=rememberAppLifecycleResumed()"))
		assertTrue(
			renderer.contains(
				"valambientEnabled=!reduceMotion&&!powerSaveMode&&lifecycleResumed&&spec.ambientCycleMs!=null",
			),
		)
		assertTrue(renderer.contains("valeffectiveSelectionDuration=if(reduceMotion)140elsespec.selectionDurationMs"))
		assertTrue(renderer.contains("valauthoredScale=if(reduceMotion){"))
		assertTrue(renderer.contains(".97f+.03f*selectionProgress"))
		assertTrue(renderer.contains("valauthoredLift=if(reduceMotion){0f"))
		assertTrue(renderer.contains("oneShotAccentEvent.snapTo(1f)"))
		assertTrue(renderer.contains("sweepEvent.snapTo(1f)"))
		assertTrue(
			"Reduce Glow may lower intensity but must not stop timelines",
			renderer.contains("valreduceGlowbyrememberBooleanPref(AppSettings.KEY_RANK_THEME_REDUCE_GLOW,false)"),
		)
	}

	@Test
	fun `long selection sweeps are independent from short one shot accents`() {
		val renderer = source("kotlin/org/koitharu/kotatsu/main/ui/nav/ExclusiveBottomNavigation.kt")
			.replace(Regex("\\s+"), "")
		val registry = source("kotlin/org/koitharu/kotatsu/readerjourney/theme/ExclusiveBottomNavigationSpec.kt")
			.replace(Regex("\\s+"), "")

		assertTrue(registry.contains("valselectionSweepDurationMs:Int?=null"))
		assertTrue(renderer.contains("valoneShotAccentEvent=remember(spec.stableId){Animatable(1f)}"))
		assertTrue(renderer.contains("valsweepEvent=remember(spec.stableId){Animatable(1f)}"))
		assertTrue(renderer.contains("valduration=spec.selectionSweepDurationMs"))
		assertTrue(renderer.contains("valhasSelectionSweep=spec.selectionSweepDurationMs!=null"))
		assertTrue(renderer.contains("valsweepWave=sin(PI*sweepEventPhase)"))
	}

	@Test
	fun `press feedback remains independent and one shot belongs only to new selected item`() {
		val renderer = source("kotlin/org/koitharu/kotatsu/main/ui/nav/ExclusiveBottomNavigation.kt")
			.replace(Regex("\\s+"), "")

		assertTrue(renderer.contains("valpressedbyinteractionSource.collectIsPressedAsState()"))
		assertTrue(renderer.contains("targetValue=if(pressed).97felse1f"))
		assertTrue(renderer.contains("animationSpec=tween(if(pressed)90else120)"))
		assertTrue(
			renderer.contains(
				"selectionEventPhase=if(item.id==selectedId)oneShotAccentEvent.valueelse1f",
			),
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
