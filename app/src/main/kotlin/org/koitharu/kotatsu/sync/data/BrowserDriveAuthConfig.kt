package org.koitharu.kotatsu.sync.data

/**
 * Infrastructure-owned browser-auth endpoint.
 *
 * This origin must match the deployed HTTPS helper used by the Google OAuth Web Client callback.
 */
internal object BrowserDriveAuthConfig {
	const val BASE_URL = "https://miyorare-drive-auth.alive-felico.workers.dev"
}
