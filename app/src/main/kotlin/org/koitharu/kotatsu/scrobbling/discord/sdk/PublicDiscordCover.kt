package org.koitharu.kotatsu.scrobbling.discord.sdk

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.koitharu.kotatsu.parsers.util.await
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import java.net.InetAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PublicDiscordCover @Inject constructor() {

	// A source's normal client may attach cookies or parser headers. Test the exact URL anonymously.
	private val client = OkHttpClient.Builder()
		.proxy(Proxy.NO_PROXY)
		.followRedirects(false)
		.followSslRedirects(false)
		.callTimeout(5, TimeUnit.SECONDS)
		.dns(::resolvePublicDiscordHost)
		.build()

	internal suspend fun resolvesPublicly(url: HttpUrl): Boolean = withContext(Dispatchers.IO) {
		runCatchingCancellable { resolvePublicDiscordHost(url.host).isNotEmpty() }.getOrDefault(false)
	}

	internal suspend fun canLoad(url: HttpUrl): Boolean = runCatchingCancellable {
		val request = Request.Builder().url(url).build()
		client.newCall(request).await().use { response ->
			response.isSuccessful && response.body.contentType()?.type == "image" &&
				response.body.source().request(1)
		}
	}.getOrDefault(false)
}

private fun resolvePublicDiscordHost(host: String): List<InetAddress> = Dns.SYSTEM.lookup(host).also { addresses ->
	if (addresses.isEmpty() || addresses.any { !isPublicDiscordAddress(it) }) throw UnknownHostException(host)
}

// Send stable HTTPS URLs only. Query strings and userinfo may contain access credentials.
internal fun discordPublicUrl(value: String?): HttpUrl? = value?.toHttpUrlOrNull()?.takeIf { url ->
	url.isHttps && url.encodedUsername.isEmpty() && url.encodedPassword.isEmpty() &&
		url.query == null && url.fragment == null && '.' in url.host &&
		':' !in url.host && url.host.any { it in 'a'..'z' } && url.port == 443
}

internal fun isPublicDiscordAddress(address: InetAddress): Boolean {
	if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
		address.isSiteLocalAddress || address.isMulticastAddress
	) return false
	val bytes = address.address
	val first = bytes[0].toInt() and 0xff
	val second = bytes[1].toInt() and 0xff
	val third = bytes[2].toInt() and 0xff
	return if (bytes.size == 4) {
		first in 1..223 && !(first == 100 && second in 64..127) &&
			!(first == 192 && (second == 0 && (third == 0 || third == 2) || second == 88 && third == 99)) &&
			!(first == 198 && (second in 18..19 || second == 51 && third == 100)) &&
			!(first == 203 && second == 0 && third == 113)
	} else {
		first in 0x20..0x3f && !(first == 0x20 && second == 1 && third == 0x0d && bytes[3] == 0xb8.toByte())
	}
}
