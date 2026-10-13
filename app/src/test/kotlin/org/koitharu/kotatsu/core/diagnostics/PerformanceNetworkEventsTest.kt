package org.koitharu.kotatsu.core.diagnostics

import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class PerformanceNetworkEventsTest {
	private var ns = 0L
	private val lines = ArrayList<String>()
	private val session = DiagnosticsSession("http", { ns }, lines::add)
	private val request = Request.Builder()
		.url("https://private.invalid/account?token=PRIVATE_SENTINEL")
		.header("Authorization", "Bearer PRIVATE_SENTINEL")
		.header("Cookie", "session=PRIVATE_SENTINEL")
		.post("PRIVATE_SENTINEL".toRequestBody())
		.build()
	private val call = OkHttpClient().newCall(request)
	private fun response(code: Int) = Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
		.code(code).message("PRIVATE_SENTINEL").header("Set-Cookie", "PRIVATE_SENTINEL").build()
	private fun listener() = PerformanceNetworkEvents(
		start = { session.begin("network", "http_call", null)?.let { PerformanceDiagnostics.Operation(session, it) } },
		finish = { operation, outcome, detail -> session.end(operation?.span, outcome, detail) },
	)

	@Test fun `cache success without network headers is successful and begins at call start`() {
		val listener = listener()
		ns += 1_000_000_000 // Creating a listener does not start the operation.
		listener.callStart(call)
		ns += 150_000_000
		listener.cacheHit(call, response(200))
		listener.callEnd(call)
		assertTrue(lines.single().contains("outcome=complete duration_ms=150"))
		assertTrue(lines.single().contains("route=cache status=200"))
		assertFalse((lines.joinToString() + session.stop()).contains("PRIVATE_SENTINEL"))
	}

	@Test fun `network status failure never formats credentials payload url or exception message`() {
		val listener = listener()
		listener.callStart(call)
		listener.cacheMiss(call)
		listener.responseHeadersEnd(call, response(503))
		ns += 300_000_000
		listener.callFailed(call, IOException("https://private.invalid/?token=PRIVATE_SENTINEL"))
		val output = lines.joinToString() + session.stop()
		assertTrue(output.contains("outcome=failure duration_ms=300"))
		assertTrue(output.contains("route=network status=503"))
		assertFalse(output.contains("PRIVATE_SENTINEL"))
		assertFalse(output.contains("private.invalid"))
		assertFalse(output.contains("Authorization"))
	}

	@Test fun `cancelled call is distinguished from failure`() {
		val listener = listener()
		listener.callStart(call)
		call.cancel()
		listener.callFailed(call, IOException("PRIVATE_SENTINEL"))
		assertTrue(session.stop().contains("network.http_call.cancel count=1 failures=0"))
	}

	@Test fun `disabled factory returns the original no-op listener`() {
		assertSame(EventListener.NONE, PerformanceNetworkListener().create(call))
	}
}
