package org.koitharu.kotatsu.mihon.compat

import android.content.Context
import okhttp3.Interceptor
import okhttp3.Response
import org.koitharu.kotatsu.core.prefs.MihonExtensionNetworkSettings
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

/**
 * Applies Miyorare's user-selected Mihon-extension network profile without rebuilding the shared
 * OkHttp client. A policy snapshot is taken once per request; changing profiles therefore affects
 * the next request while an in-flight request is allowed to finish with the policy it started with.
 *
 * Extra retries are deliberately limited to GET/HEAD and transient transport/server failures.
 * POST/PUT/etc. are never replayed here, and permanent HTTP failures such as 403/404 are returned
 * untouched. This keeps extension behavior predictable and avoids duplicate side effects.
 */
internal class MihonExtensionNetworkPolicyInterceptor(
	context: Context,
) : Interceptor {

	private val appContext = context.applicationContext
	private val hostStates = ConcurrentHashMap<String, HostState>()

	override fun intercept(chain: Interceptor.Chain): Response {
		val request = chain.request()
		val host = request.url.host.lowercase()
		val policy = MihonExtensionNetworkSettings.resolvePolicy(appContext, host)
		val requestChain = chain.withConnectTimeout(policy.connectTimeoutSeconds, TimeUnit.SECONDS)
		val canReplay = request.method.equals("GET", ignoreCase = true) ||
			request.method.equals("HEAD", ignoreCase = true)

		if (!canReplay) {
			return requestChain.proceed(request)
		}

		if (policy.adaptive) {
			sleepCancellable(chain, currentAdaptiveWaitMillis(host))
		}

		var attempt = 0
		while (true) {
			try {
				val response = requestChain.proceed(request)
				if (!response.isTransientFailure()) {
					if (policy.adaptive) recordSuccess(host)
					return response
				}

				val adaptiveDelay = if (policy.adaptive) {
					recordTransientFailure(host, policy.maxBackoffMillis)
				} else {
					0L
				}
				if (attempt >= policy.retryCount) {
					return response
				}

				val waitMillis = retryDelayMillis(
					policy = policy,
					attempt = attempt,
					retryAfterMillis = response.retryAfterMillis(),
					adaptiveDelayMillis = adaptiveDelay,
				)
				response.close()
				sleepCancellable(chain, waitMillis)
				attempt++
			} catch (e: IOException) {
				val adaptiveDelay = if (policy.adaptive) {
					recordTransientFailure(host, policy.maxBackoffMillis)
				} else {
					0L
				}
				if (attempt >= policy.retryCount || chain.call().isCanceled()) {
					throw e
				}
				val waitMillis = retryDelayMillis(
					policy = policy,
					attempt = attempt,
					retryAfterMillis = 0L,
					adaptiveDelayMillis = adaptiveDelay,
				)
				sleepCancellable(chain, waitMillis)
				attempt++
			}
		}
	}

	private fun retryDelayMillis(
		policy: MihonExtensionNetworkSettings.Policy,
		attempt: Int,
		retryAfterMillis: Long,
		adaptiveDelayMillis: Long,
	): Long {
		val baseDelay = if (policy.backoffEnabled) {
			val multiplier = 1L shl attempt.coerceIn(0, 6)
			policy.retryDelayMillis * multiplier
		} else {
			policy.retryDelayMillis
		}
		val requested = max(baseDelay, max(retryAfterMillis, adaptiveDelayMillis))
		return if (policy.backoffEnabled) {
			min(requested, policy.maxBackoffMillis)
		} else {
			requested
		}
	}

	private fun currentAdaptiveWaitMillis(host: String): Long {
		val state = hostStates[host] ?: return 0L
		return synchronized(state) {
			(state.backoffUntilMillis - System.currentTimeMillis()).coerceAtLeast(0L)
		}
	}

	private fun recordTransientFailure(host: String, maxBackoffMillis: Long): Long {
		val state = hostStates.getOrPut(host) { HostState() }
		return synchronized(state) {
			state.failureLevel = (state.failureLevel + 1).coerceAtMost(6)
			val exponential = 1_000L * (1L shl (state.failureLevel - 1))
			val delay = min(exponential, maxBackoffMillis.coerceAtLeast(1_000L))
			state.backoffUntilMillis = max(
				state.backoffUntilMillis,
				System.currentTimeMillis() + delay,
			)
			delay
		}
	}

	private fun recordSuccess(host: String) {
		val state = hostStates[host] ?: return
		val recovered = synchronized(state) {
			state.failureLevel = (state.failureLevel - 1).coerceAtLeast(0)
			if (state.failureLevel == 0) {
				state.backoffUntilMillis = 0L
				true
			} else {
				false
			}
		}
		if (recovered) {
			hostStates.remove(host, state)
		}
	}

	private fun sleepCancellable(chain: Interceptor.Chain, millis: Long) {
		if (millis <= 0L) return
		if (chain.call().isCanceled()) throw InterruptedIOException("Canceled")
		try {
			Thread.sleep(millis)
		} catch (e: InterruptedException) {
			Thread.currentThread().interrupt()
			throw InterruptedIOException("Interrupted while waiting to retry").apply { initCause(e) }
		}
		if (chain.call().isCanceled()) throw InterruptedIOException("Canceled")
	}

	private fun Response.isTransientFailure(): Boolean = code == 429 || code == 502 || code == 503 || code == 504

	private fun Response.retryAfterMillis(): Long {
		val seconds = header("Retry-After")?.trim()?.toLongOrNull() ?: return 0L
		return seconds.coerceAtLeast(0L) * 1_000L
	}

	private class HostState(
		var failureLevel: Int = 0,
		var backoffUntilMillis: Long = 0L,
	)
}
