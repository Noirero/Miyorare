package org.koitharu.kotatsu.core.prefs

import org.junit.Assert.assertEquals
import org.junit.Test

class NavigationTransitionPreferencesTest {
	@Test fun motionValuesRoundTripWithoutOrdinals() {
		assertEquals(listOf("classic_glide", "elastic_glide", "fluid_lens", "refract_glass"),
			NavigationMotion.entries.map { it.storedValue })
		for (motion in NavigationMotion.entries) {
			assertEquals(motion, NavigationMotion.parse(motion.storedValue))
		}
	}

	@Test fun unknownAndWrongTypeMotionFallBackToClassic() {
		for (value in listOf(null, "", "future_motion", 2, true)) {
			assertEquals(NavigationMotion.CLASSIC_GLIDE, NavigationMotion.parse(value))
		}
	}

	@Test fun transitionValuesRoundTripWithoutOrdinals() {
		assertEquals(8, ScreenTransition.entries.size)
		assertEquals(8, ScreenTransition.entries.map { it.storedValue }.distinct().size)
		for (effect in ScreenTransition.entries) assertEquals(effect, ScreenTransition.parse(effect.storedValue))
	}

	@Test fun transitionUsesIndependentStableDefault() {
		assertEquals(ScreenTransition.DIRECTIONAL_SLIDE, ScreenTransition.parse("directional_slide"))
		for (value in listOf(null, "", "fluid_lens", 1, false)) {
			assertEquals(ScreenTransition.DIRECTIONAL_SLIDE, ScreenTransition.parse(value))
		}
	}
}
