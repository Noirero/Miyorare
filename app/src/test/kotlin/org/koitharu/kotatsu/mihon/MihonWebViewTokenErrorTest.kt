package org.koitharu.kotatsu.mihon

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MihonWebViewTokenErrorTest {

	@Test
	fun `recognizes extension request to refresh token in webview`() {
		assertTrue(isWebViewTokenRefreshRequested("Open webview to refresh token"))
		assertTrue(isWebViewTokenRefreshRequested("Please open WebView to renew the access token"))
	}

	@Test
	fun `does not turn ordinary token and webview failures into interactive action`() {
		assertFalse(isWebViewTokenRefreshRequested("Invalid token"))
		assertFalse(isWebViewTokenRefreshRequested("WebView is unavailable"))
		assertFalse(isWebViewTokenRefreshRequested("HTTP 500"))
	}
}
