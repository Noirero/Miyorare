package org.koitharu.kotatsu.core.ui.widgets

import org.koitharu.kotatsu.core.prefs.NavigationMotion
import org.junit.Assert.*
import org.junit.Test

class EnhancedNavigationMotionGeometryTest {
	private fun shape(motion: NavigationMotion, x: Float = 150f, active: Float = 1f,
		stretch: Float = 0f, vertical: Boolean = false) = checkNotNull(
		EnhancedNavigationMotionGeometry.calculate(motion, x, 43f, 42f, 60f,
			360f, 86f, 18f, 290f, 70f, active, stretch, vertical)
	)

	@Test fun elasticAndFluidHaveClearlyLargerActiveSilhouettesThanTheirRestShape() {
		for (motion in listOf(NavigationMotion.ELASTIC_GLIDE, NavigationMotion.FLUID_LENS)) {
			val rest = shape(motion, active = 0f)
			val active = shape(motion)
			assertTrue(active.right - active.left >= (rest.right - rest.left) * 1.7f)
			assertTrue(active.bottom - active.top >= (rest.bottom - rest.top) * 1.5f)
		}
	}

	@Test fun elasticRespondsToMotionDirectionAndFluidHasDifferentShapeAndLift() {
		val forward = shape(NavigationMotion.ELASTIC_GLIDE, stretch = 1f)
		val backward = shape(NavigationMotion.ELASTIC_GLIDE, stretch = -1f)
		val held = shape(NavigationMotion.ELASTIC_GLIDE)
		val fluid = shape(NavigationMotion.FLUID_LENS)
		assertTrue(forward.right - forward.left > held.right - held.left)
		assertTrue(forward.left < backward.left)
		assertTrue(fluid.radius < held.radius)
		assertTrue(fluid.top < held.top)
	}

	@Test fun edgesKeepFullShapeInsideTabStripAndHostNotContinueButton() {
		for (motion in listOf(NavigationMotion.ELASTIC_GLIDE, NavigationMotion.FLUID_LENS)) {
			val left = shape(motion, x = 19f)
			val right = shape(motion, x = 289f)
			assertTrue(left.left >= 20f)
			assertTrue(right.right <= 288f)
			assertEquals(left.right - left.left, right.right - right.left, 0.01f)
			assertTrue(left.top >= 2f && left.bottom <= 84f)
		}
	}
}
