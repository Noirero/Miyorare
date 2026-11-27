package org.koitharu.kotatsu.core.ui.widgets

import org.koitharu.kotatsu.core.prefs.NavigationMotion
import org.junit.Assert.*
import org.junit.Test

class EnhancedNavigationMotionGeometryTest {
	private fun shape(motion: NavigationMotion, x: Float = 150f, active: Float = 1f,
		stretch: Float = 0f, vertical: Boolean = false, edgePull: Float = 0f) = checkNotNull(
		EnhancedNavigationMotionGeometry.calculate(motion, x, 43f, 42f, 60f,
			360f, 86f, 18f, 290f, 70f, active, stretch, vertical, edgePull)
	)

	@Test fun elasticAndFluidHaveClearlyLargerActiveSilhouettesThanTheirRestShape() {
		for (motion in listOf(NavigationMotion.ELASTIC_GLIDE, NavigationMotion.FLUID_LENS)) {
			val rest = shape(motion, active = 0f)
			val active = shape(motion)
			assertTrue(active.right - active.left >= (rest.right - rest.left) * 1.7f)
			val heightRatio = if (motion == NavigationMotion.ELASTIC_GLIDE) 1.5f else 1.4f
			assertTrue(active.bottom - active.top >= (rest.bottom - rest.top) * heightRatio)
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
		// A full lens must cross both strip edges within the host, rather than only lifting.
		assertTrue(fluid.top < 8f && fluid.bottom > 78f)
		assertTrue((fluid.top + fluid.bottom) / 2f <= 43f)
		assertEquals(43f, (held.top + held.bottom) / 2f, 0.01f)
	}

	@Test fun edgesAllowBoundedOverflowWithoutClippingHostOrContinueGap() {
		for (motion in listOf(NavigationMotion.ELASTIC_GLIDE, NavigationMotion.FLUID_LENS)) {
			val left = shape(motion, x = 19f)
			val right = shape(motion, x = 289f)
			assertTrue(left.left >= 14f && left.left < 18f)
			assertTrue(right.right <= 294f && right.right > 290f)
			assertEquals(left.right - left.left, right.right - right.left, 0.01f)
			assertTrue(left.top >= 2f && left.bottom <= 84f)
		}
	}

	@Test fun elasticEdgePullIsResistedAndReversible() {
		val near = EnhancedNavigationMotionGeometry.edgePull(305f, 40f, 289f)
		val far = EnhancedNavigationMotionGeometry.edgePull(353f, 40f, 289f)
		assertTrue(near > 0f && far > near && far < 1f)
		assertEquals(-far, EnhancedNavigationMotionGeometry.edgePull(-24f, 40f, 289f), 0.001f)
		assertEquals(0f, EnhancedNavigationMotionGeometry.edgePull(150f, 40f, 289f), 0f)
		fun width(pull: Float): Float = shape(NavigationMotion.ELASTIC_GLIDE, x = 289f, edgePull = pull).let { it.right - it.left }
		assertTrue(width(far) > width(near) && width(near) > width(0f))
		val rest = shape(NavigationMotion.ELASTIC_GLIDE, x = 289f, active = 0f)
		val settled = shape(NavigationMotion.ELASTIC_GLIDE, x = 289f, active = 0f, edgePull = far)
		assertEquals(rest.right - rest.left, settled.right - settled.left, 0.001f)
	}
}
