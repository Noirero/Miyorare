package org.koitharu.kotatsu.tsuki.runtime

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.koitharu.kotatsu.tsuki.model.TsukiMangaSource
import org.koitharu.kotatsu.tsuki.model.TsukiPluginProvider
import java.net.IDN

/**
 * Miyorare-owned resilience policy for the four ordinary sources in Miyorare Global.
 *
 * ExHentai is deliberately excluded: it owns a separate E-Hentai/ExHentai domain and session
 * contract and must not inherit generic mirror/custom-domain behavior.
 */
internal object OfficialGlobalSourceResilience {
	const val GLOBAL_PLUGIN_ID = "miyorare-global"

	val supportedSourceNames: Set<String> = setOf(
		"COMICASURA",
		"GELBOORU",
		"RAWDEX",
		"NEWXTOON",
	)

	fun supports(source: TsukiMangaSource): Boolean =
		source.plugin.provider == TsukiPluginProvider.MIYORARE &&
			source.pluginId == GLOBAL_PLUGIN_ID &&
			source.descriptor.name in supportedSourceNames

	fun supports(provider: TsukiPluginProvider, pluginId: String, sourceName: String): Boolean =
		provider == TsukiPluginProvider.MIYORARE &&
			pluginId == GLOBAL_PLUGIN_ID &&
			sourceName in supportedSourceNames

	/**
	 * ConfigKey.Domain stores only host[:port]. Accept a friendly https URL or bare host, but reject
	 * credentials, query/fragment and non-root paths so a value is never silently truncated into a
	 * different site than the user typed.
	 */
	fun normalizeDomainInput(input: String): String? {
		val raw = input.trim()
		if (raw.isEmpty()) return null
		val url = (if (raw.contains("://")) raw else "https://$raw").toHttpUrlOrNull() ?: return null
		if (!url.isHttps) return null
		if (url.username.isNotEmpty() || url.password.isNotEmpty()) return null
		if (url.encodedPath != "/" || url.query != null || url.fragment != null) return null
		val host = runCatching { IDN.toASCII(url.host) }.getOrNull()?.lowercase()?.takeIf { it.isNotBlank() }
			?: return null
		return if (url.port == 443) host else "$host:${url.port}"
	}

	fun domainMode(activeDomain: String, presetDomains: List<String>): OfficialGlobalDomainMode = when {
		presetDomains.isNotEmpty() && activeDomain.equals(presetDomains.first(), ignoreCase = true) ->
			OfficialGlobalDomainMode.DEFAULT
		presetDomains.any { it.equals(activeDomain, ignoreCase = true) } ->
			OfficialGlobalDomainMode.TRUSTED_MIRROR
		else -> OfficialGlobalDomainMode.CUSTOM
	}
}

internal enum class OfficialGlobalDomainMode {
	DEFAULT,
	TRUSTED_MIRROR,
	CUSTOM,
}

internal enum class OfficialGlobalRequestProfile {
	/** No fixed pacing while healthy; one bounded adaptive retry for transient failures. */
	AUTO,

	/** Preserve ordinary Miyorare request behavior without an extra resilience retry. */
	NORMAL,

	/** Add modest request spacing plus bounded retry/backoff for sensitive sites. */
	SLOWER,
}

internal enum class OfficialGlobalDiagnosticStatus {
	UNTESTED,
	HEALTHY,
	INVALID_URL,
	NETWORK_ERROR,
	HTTP_ERROR,
	CLOUDFLARE,
	RATE_LIMITED,
	PARSER_MISMATCH,
	AUTH_REQUIRED,
	SOURCE_UNAVAILABLE,
}

internal enum class OfficialGlobalDiagnosticStage {
	NONE,
	CATALOGUE,
	DETAILS,
	CHAPTERS,
	READER,
}

internal data class OfficialGlobalDiagnostic(
	val domain: String,
	val status: OfficialGlobalDiagnosticStatus,
	val stage: OfficialGlobalDiagnosticStage = OfficialGlobalDiagnosticStage.NONE,
	val httpStatus: Int? = null,
	val testedAt: Long = System.currentTimeMillis(),
	val message: String? = null,
) {
	val isHealthy: Boolean
		get() = status == OfficialGlobalDiagnosticStatus.HEALTHY
}

internal data class OfficialGlobalSourceSettingsSnapshot(
	val activeDomain: String,
	val presetDomains: List<String>,
	val domainMode: OfficialGlobalDomainMode,
	val autoMirrorEnabled: Boolean,
	val requestProfile: OfficialGlobalRequestProfile,
	val diagnostic: OfficialGlobalDiagnostic?,
)
