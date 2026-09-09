package org.koitharu.kotatsu.main.ui.protect

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenshotPolicyHelperTest {
	@Test fun `private is secure by default`() = assertTrue(
		shouldSecureWindow(false, true, false, false, false),
	)
	@Test fun `private permission ignores general screenshot block`() = assertFalse(
		shouldSecureWindow(true, true, true, true, false),
	)
	@Test fun `app protection still secures private`() = assertTrue(
		shouldSecureWindow(false, true, false, true, true),
	)
	@Test fun `normal follows general screenshot policy`() = assertTrue(
		shouldSecureWindow(true, false, false, true, false),
	)
	@Test fun `unknown sensitive classification is fail closed`() = assertTrue(
		shouldSecureWindow(false, false, true, true, false),
	)
}
