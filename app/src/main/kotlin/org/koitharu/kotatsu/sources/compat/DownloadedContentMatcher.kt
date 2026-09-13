package org.koitharu.kotatsu.sources.compat

import org.koitharu.kotatsu.parsers.model.Manga
import java.net.URI
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

enum class DownloadedContentMatch {
	NONE,
	EXACT_ID,
	PUBLIC_URL,
	SOURCE_ALIAS_AND_CONTENT_URL,
}

/**
 * Non-destructive matcher for reconnecting a remote manga to an existing downloaded copy.
 *
 * This deliberately stops before title/chapter fuzzy matching. A title is presentation data, not
 * identity; title-based candidates belong in a manual reconnect UI and must never auto-adopt files.
 */
@Singleton
class DownloadedContentMatcher @Inject constructor(
	private val sourceAliasRegistry: SourceAliasRegistry,
) {

	suspend fun match(remote: Manga, downloaded: Manga): DownloadedContentMatch {
		val remoteSource = if (remote.id == downloaded.id ||
			normalizePublicUrl(remote.publicUrl)?.let { it == normalizePublicUrl(downloaded.publicUrl) } == true
		) {
			null
		} else {
			sourceAliasRegistry.canonicalId(remote.source.name)
		}
		val downloadedSource = if (remoteSource == null) null else sourceAliasRegistry.canonicalId(downloaded.source.name)
		return classify(
			remoteId = remote.id,
			downloadedId = downloaded.id,
			remotePublicUrl = remote.publicUrl,
			downloadedPublicUrl = downloaded.publicUrl,
			remoteCanonicalSource = remoteSource,
			downloadedCanonicalSource = downloadedSource,
			remoteContentUrl = remote.url,
			downloadedContentUrl = downloaded.url,
		)
	}

	companion object {
		internal fun classify(
			remoteId: Long,
			downloadedId: Long,
			remotePublicUrl: String?,
			downloadedPublicUrl: String?,
			remoteCanonicalSource: CanonicalSourceId?,
			downloadedCanonicalSource: CanonicalSourceId?,
			remoteContentUrl: String?,
			downloadedContentUrl: String?,
		): DownloadedContentMatch {
			if (remoteId == downloadedId) return DownloadedContentMatch.EXACT_ID

			val remotePublic = normalizePublicUrl(remotePublicUrl)
			val downloadedPublic = normalizePublicUrl(downloadedPublicUrl)
			if (remotePublic != null && remotePublic == downloadedPublic) {
				return DownloadedContentMatch.PUBLIC_URL
			}

			if (remoteCanonicalSource == null || remoteCanonicalSource != downloadedCanonicalSource) {
				return DownloadedContentMatch.NONE
			}
			val remoteContent = normalizeContentUrl(remoteContentUrl)
			val downloadedContent = normalizeContentUrl(downloadedContentUrl)
			return if (remoteContent != null && remoteContent == downloadedContent) {
				DownloadedContentMatch.SOURCE_ALIAS_AND_CONTENT_URL
			} else {
				DownloadedContentMatch.NONE
			}
		}

		internal fun normalizeContentUrl(value: String?): String? = value
			?.trim()
			?.takeIf(String::isNotEmpty)
			?.trimEnd('/')
			?.takeIf(String::isNotEmpty)

		/**
		 * Normalize only semantics that are safe for identity: HTTP(S) scheme/host case, default port,
		 * a trailing slash, and fragment. Query parameters are retained because some sources use them
		 * as the actual content key.
		 */
		internal fun normalizePublicUrl(value: String?): String? {
			val raw = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
			val uri = runCatching { URI(raw) }.getOrNull() ?: return null
			val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
			if (scheme != "http" && scheme != "https") return null
			val host = uri.host?.lowercase(Locale.ROOT) ?: return null
			val effectivePort = when {
				uri.port < 0 -> -1
				scheme == "http" && uri.port == 80 -> -1
				scheme == "https" && uri.port == 443 -> -1
				else -> uri.port
			}
			val path = uri.rawPath.orEmpty().let { path ->
				if (path.length > 1) path.trimEnd('/') else path
			}
			return URI(
				scheme,
				uri.rawUserInfo,
				host,
				effectivePort,
				path,
				uri.rawQuery,
				null,
			).toASCIIString()
		}
	}
}
