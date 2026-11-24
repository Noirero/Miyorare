package org.koitharu.kotatsu.core.network

import okhttp3.Request
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CurlLoggingInterceptorTest {

	@Test
	fun redactsAuthorizationHeadersWithoutRemovingOtherDiagnostics() {
		val request = Request.Builder()
			.url("https://example.invalid/users/@me")
			.header("authorization", "synthetic-discord-token")
			.header("PROXY-Authorization", "synthetic-proxy-credential")
			.header("Accept", "application/json")
			.build()

		val command = CurlLoggingInterceptor().formatCurlRequest(request)

		assertFalse(command.contains("synthetic-discord-token"))
		assertFalse(command.contains("synthetic-proxy-credential"))
		assertTrue(command.contains("authorization: <redacted>"))
		assertTrue(command.contains("PROXY-Authorization: <redacted>"))
		assertTrue(command.contains("Accept: application/json"))
	}
}
