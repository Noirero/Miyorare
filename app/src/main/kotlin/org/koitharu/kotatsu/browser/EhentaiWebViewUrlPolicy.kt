package org.koitharu.kotatsu.browser

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * E-Hentai/ExHentai account profiles can hide galleries with custom language, uploader and tag
 * filters. Miyorare's source browser is intended to mirror the unfiltered source results instead of
 * silently inheriting those account-level exclusions.
 *
 * The three f_sf* flags are request-scoped; they do not mutate the user's remote uconfig/profile.
 */
internal object EhentaiWebViewUrlPolicy {

	private val hosts = setOf("e-hentai.org", "exhentai.org")

	fun withoutAccountFilters(url: String): String {
		val parsed = url.toHttpUrlOrNull() ?: return url
		if (parsed.host.lowercase() !in hosts || parsed.encodedPath != "/") return url

		val builder = parsed.newBuilder()
		var changed = false

		fun ensure(name: String, value: String) {
			if (parsed.queryParameter(name) != value) {
				builder.setQueryParameter(name, value)
				changed = true
			}
		}

		// These are the site's own Advanced Search switches for bypassing account-level filters.
		ensure("advsearch", "1")
		ensure("f_sfl", "on") // Disable custom Language filters.
		ensure("f_sfu", "on") // Disable custom Uploader filters.
		ensure("f_sft", "on") // Disable custom Tag filters.

		return if (changed) builder.build().toString() else url
	}
}
