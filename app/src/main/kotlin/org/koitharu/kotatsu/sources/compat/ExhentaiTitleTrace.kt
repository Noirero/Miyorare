package org.koitharu.kotatsu.sources.compat

import android.util.Log
import java.security.MessageDigest

/** Narrow owner-validation probe. Never log HTML, URLs, credentials, or title text. */
internal object ExhentaiTitleTrace {
	const val TAG = "ExhentaiTitleTrace"
	private const val VALIDATION_PACKAGE = "org.noirero.miyorare.debug.globalvalidation"
	private const val TARGET_GALLERY = 4323075L

	fun enabled(packageName: String): Boolean = packageName == VALIDATION_PACKAGE

	fun matches(source: String, vararg urls: String?): Boolean =
		EhentaiSourceFamily.isOfficialSource(source) && EhentaiSourceFamily.galleryId(*urls) == TARGET_GALLERY

	fun fingerprint(value: String?): String {
		val title = value?.takeIf { it.isNotBlank() } ?: return "absent"
		val hash = MessageDigest.getInstance("SHA-256").digest(title.toByteArray())
			.take(8).joinToString("") { "%02x".format(it) }
		return "${title.length}/${title.count { it == '[' }}/$hash"
	}

	fun record(stage: String, fields: String) {
		Log.i(TAG, "gallery=$TARGET_GALLERY $stage $fields")
	}
}
