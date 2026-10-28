package org.koitharu.kotatsu.tsuki.runtime

import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.koitharu.kotatsu.parsers.exception.TooManyRequestExceptions
import org.koitharu.kotatsu.tsuki.model.TsukiPluginDescriptor
import tsuki.MangaParser
import tsuki.model.MangaSource
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Narrow network resilience for the four ordinary sources in Miyorare Global.
 *
 * This runs after Tsuki routing has applied parser headers/interception, while Miyorare's normal
 * Cloudflare/rate-limit/base interceptors remain downstream. Only idempotent GET/HEAD requests can
 * be replayed. Automatic failover is limited to ConfigKey.Domain.presetValues and a mirror is only
 * promoted when the final response remains on that declared host.
 */
internal class OfficialGlobalSourceNetworkPolicyInterceptor(
	private val runtime: TsukiPluginRuntime,
	private val plugin: TsukiPluginDescriptor,
) : Interceptor {

	private val lastRequestAt = ConcurrentHashMap<String, Long>()

	override fun intercept(chain: Interceptor.Chain): Response {
		val request = chain.request()
		val rawSource = request.tag(MangaSource::class.java) ?: return chain.proceed(request)
		val parser = runtime.findCachedParserForPolicy(plugin, rawSource) ?: return chain.proceed(request)
		if (!OfficialGlobalSourceResilience.supports(plugin.provider, plugin.pluginId, parser.source.name)) {
			return chain.proceed(request)
		}
		val config = parser.config as? TsukiSourceConfig ?: return chain.proceed(request)
		val canReplay = request.method.equals("GET", ignoreCase = true) ||
			request.method.equals("HEAD", ignoreCase = true)
		if (!canReplay) return chain.proceed(request)

		val profile = config.requestProfile
		if (profile == OfficialGlobalRequestProfile.SLOWER) {
			pace(chain, parser.source.name, SLOWER_MIN_INTERVAL_MILLIS)
		}

		val maxRetries = when (profile) {
			OfficialGlobalRequestProfile.NORMAL -> 0
			OfficialGlobalRequestProfile.AUTO -> 1
			OfficialGlobalRequestProfile.SLOWER -> 2
		}
		var attempt = 0
		while (true) {
			try {
				val response = chain.proceed(request)
				if (!response.isTransientFailure() || attempt >= maxRetries) {
					if (!response.isTransientFailure()) return response
					return failoverTrustedMirror(chain, parser, config, request, response)
				}
				response.close()
				waitCancellable(chain, retryDelay(profile, attempt))
				attempt++
			} catch (e: TooManyRequestExceptions) {
				val serverDelay = e.getRetryDelay()
				if (attempt >= maxRetries || serverDelay < 0L || serverDelay > MAX_SERVER_RETRY_DELAY_MILLIS) {
					return failoverTrustedMirrorAfterFailure(chain, parser, config, request, e)
				}
				waitCancellable(chain, maxOf(serverDelay, retryDelay(profile, attempt)))
				attempt++
			} catch (e: IOException) {
				if (attempt >= maxRetries || chain.call().isCanceled()) {
					return failoverTrustedMirrorAfterFailure(chain, parser, config, request, e)
				}
				waitCancellable(chain, retryDelay(profile, attempt))
				attempt++
			}
		}
	}

	private fun failoverTrustedMirror(
		chain: Interceptor.Chain,
		parser: MangaParser,
		config: TsukiSourceConfig,
		request: Request,
		lastResponse: Response,
	): Response {
		if (!canFailover(parser, config, request)) return lastResponse
		lastResponse.close()
		return tryTrustedMirrors(chain, parser, config, request)
			?: throw IOException("All trusted mirrors failed for ${parser.source.name}")
	}

	private fun failoverTrustedMirrorAfterFailure(
		chain: Interceptor.Chain,
		parser: MangaParser,
		config: TsukiSourceConfig,
		request: Request,
		failure: Throwable,
	): Response {
		if (!canFailover(parser, config, request)) throw failure
		return tryTrustedMirrors(chain, parser, config, request) ?: throw failure
	}

	private fun canFailover(parser: MangaParser, config: TsukiSourceConfig, request: Request): Boolean {
		if (!config.autoMirrorEnabled) return false
		val presets = parser.configKeyDomain.presetValues.toList()
		if (presets.size < 2) return false
		val active = parser.domain
		if (presets.none { it.equals(active, ignoreCase = true) }) return false
		return requestTargetsDomain(request, active)
	}

	private fun tryTrustedMirrors(
		chain: Interceptor.Chain,
		parser: MangaParser,
		config: TsukiSourceConfig,
		request: Request,
	): Response? {
		val active = parser.domain
		val candidates = parser.configKeyDomain.presetValues.filterNot { it.equals(active, ignoreCase = true) }
		for ((index, candidate) in candidates.withIndex()) {
			val routed = rewriteTrustedDomain(request, candidate) ?: continue
			try {
				val response = chain.proceed(routed)
				val stayedOnDeclaredHost = requestTargetsDomain(response.request, candidate)
				if (response.code in 200..399 && stayedOnDeclaredHost) {
					config.setDomain(parser.configKeyDomain, candidate)
					return response
				}
				if (index == candidates.lastIndex) return response
				response.close()
			} catch (_: TooManyRequestExceptions) {
				// Try only the next source-declared trusted mirror.
			} catch (e: IOException) {
				if (chain.call().isCanceled()) throw e
			}
		}
		return null
	}

	private fun rewriteTrustedDomain(request: Request, domain: String): Request? {
		val base = runCatching { "https://$domain/".toHttpUrl() }.getOrNull() ?: return null
		val url = request.url.newBuilder()
			.host(base.host)
			.port(base.port)
			.build()
		return request.newBuilder()
			.url(url)
			.header("Referer", base.toString())
			.build()
	}

	private fun requestTargetsDomain(request: Request, domain: String): Boolean {
		val base = runCatching { "https://$domain/".toHttpUrl() }.getOrNull() ?: return false
		return request.url.host.equals(base.host, ignoreCase = true) && request.url.port == base.port
	}

	private fun pace(chain: Interceptor.Chain, sourceName: String, minIntervalMillis: Long) {
		val now = System.currentTimeMillis()
		val previous = lastRequestAt.put(sourceName, now) ?: return
		val remaining = minIntervalMillis - (now - previous)
		if (remaining > 0L) waitCancellable(chain, remaining)
		lastRequestAt[sourceName] = System.currentTimeMillis()
	}

	private fun retryDelay(profile: OfficialGlobalRequestProfile, attempt: Int): Long = when (profile) {
		OfficialGlobalRequestProfile.NORMAL -> 0L
		OfficialGlobalRequestProfile.AUTO -> AUTO_RETRY_DELAY_MILLIS * (attempt + 1L)
		OfficialGlobalRequestProfile.SLOWER -> SLOWER_RETRY_DELAY_MILLIS * (1L shl attempt.coerceIn(0, 2))
	}

	private fun waitCancellable(chain: Interceptor.Chain, millis: Long) {
		if (millis <= 0L) return
		val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis)
		while (true) {
			if (chain.call().isCanceled()) throw InterruptedIOException("Canceled")
			val remaining = deadline - System.nanoTime()
			if (remaining <= 0L) return
			val slice = TimeUnit.NANOSECONDS.toMillis(remaining).coerceIn(1L, CANCELLATION_POLL_MILLIS)
			try {
				Thread.sleep(slice)
			} catch (e: InterruptedException) {
				Thread.currentThread().interrupt()
				throw InterruptedIOException("Interrupted while waiting to retry").apply { initCause(e) }
			}
		}
	}

	private fun Response.isTransientFailure(): Boolean = code == 502 || code == 503 || code == 504

	private companion object {
		const val CANCELLATION_POLL_MILLIS = 100L
		const val AUTO_RETRY_DELAY_MILLIS = 350L
		const val SLOWER_MIN_INTERVAL_MILLIS = 600L
		const val SLOWER_RETRY_DELAY_MILLIS = 1_000L
		const val MAX_SERVER_RETRY_DELAY_MILLIS = 30_000L
	}
}