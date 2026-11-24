package org.koitharu.kotatsu.scrobbling.discord.sdk

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.Executors
import kotlin.reflect.KClass

class PublicDiscordCoverTest {

	@Test
	fun readsAndClosesCoverBodyOffCallerThread() = runBlocking {
		val callerThread = Thread.currentThread()
		var readThread: Thread? = null
		var closeThread: Thread? = null
		val source = object : Source {
			override fun read(sink: Buffer, byteCount: Long): Long {
				readThread = Thread.currentThread()
				sink.writeByte(0xff)
				return 1L
			}

			override fun timeout() = Timeout.NONE

			override fun close() {
				closeThread = Thread.currentThread()
			}
		}.buffer()
		val body = object : ResponseBody() {
			override fun contentType() = "image/jpeg".toMediaType()
			override fun contentLength() = 1L
			override fun source() = source
		}
		Executors.newSingleThreadExecutor().use { worker ->
			val client = Call.Factory { request ->
				val response = Response.Builder()
					.request(request)
					.protocol(Protocol.HTTP_1_1)
					.code(200)
					.message("OK")
					.body(body)
					.build()
				object : Call {
					override fun enqueue(responseCallback: Callback) {
						worker.execute { responseCallback.onResponse(this, response) }
					}
					override fun request(): Request = request
					override fun execute(): Response = error("Expected an asynchronous call")
					override fun cancel() = Unit
					override fun isExecuted() = true
					override fun isCanceled() = false
					override fun timeout() = Timeout.NONE
					override fun clone(): Call = error("Unexpected call clone")
					override fun <T : Any> tag(type: KClass<T>): T? = request.tag(type)
					override fun <T> tag(type: Class<out T>): T? = request.tag(type)
					override fun <T : Any> tag(type: KClass<T>, computeIfAbsent: () -> T): T = computeIfAbsent()
					override fun <T : Any> tag(type: Class<T>, computeIfAbsent: () -> T): T = computeIfAbsent()
				}
			}
			withTimeout(5_000) {
				assertTrue(PublicDiscordCover(client).canLoad("https://cdn.example.com/cover.jpg".toHttpUrl()))
			}
		}
		assertNotNull(readThread)
		assertNotNull(closeThread)
		assertNotSame(callerThread, readThread)
		assertNotSame(callerThread, closeThread)
	}

	@Test
	fun acceptsStableHttpsUrlsOnly() {
		assertNotNull(discordPublicUrl("https://cdn.example.com/cover.jpg"))
		listOf(
			"http://cdn.example.com/cover.jpg",
			"https://cdn.example.com:8443/cover.jpg",
			"https://user:password@cdn.example.com/cover.jpg",
			"https://cdn.example.com/cover.jpg?access_token=sample",
			"https://cdn.example.com/cover.jpg#private",
			"https://127.0.0.1/cover.jpg",
			"https://[::1]/cover.jpg",
			"file:///sdcard/cover.jpg",
		).forEach { assertNull(it, discordPublicUrl(it)) }
	}

	@Test
	fun rejectsLocalAndSpecialUseAddresses() {
		listOf("127.0.0.1", "10.0.0.2", "169.254.169.254", "100.64.0.2", "192.168.1.1", "198.18.0.1",
			"192.0.2.1", "::1", "fd00::1", "fe80::1", "2001:db8::1").forEach {
			assertFalse(it, isPublicDiscordAddress(InetAddress.getByName(it)))
		}
		assertTrue(isPublicDiscordAddress(InetAddress.getByName("1.1.1.1")))
		assertTrue(isPublicDiscordAddress(InetAddress.getByName("2606:4700:4700::1111")))
	}
}
