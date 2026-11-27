package org.koitharu.kotatsu.core.prefs

import org.junit.Assert.assertEquals
import org.junit.Test

class NavigationTransitionPreferencesTest {
	@Test fun motionValuesRoundTripWithoutOrdinals() {
		for (motion in NavigationMotion.entries) {
			assertEquals(motion, NavigationMotion.parse(motion.storedValue))
		}
	}

	@Test fun unknownAndWrongTypeMotionFallBackToClassic() {
		for (value in listOf(null, "", "future_motion", 2, true)) {
			assertEquals(NavigationMotion.CLASSIC_GLIDE, NavigationMotion.parse(value))
		}
	}

	@Test fun transitionUsesIndependentStableDefault() {
		assertEquals(ScreenTransition.DIRECTIONAL_SLIDE, ScreenTransition.parse("directional_slide"))
		for (value in listOf(null, "", "fluid_lens", 1, false)) {
			assertEquals(ScreenTransition.DIRECTIONAL_SLIDE, ScreenTransition.parse(value))
		}
	}
}
