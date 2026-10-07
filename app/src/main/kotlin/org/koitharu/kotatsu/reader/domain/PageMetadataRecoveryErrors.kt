package org.koitharu.kotatsu.reader.domain

import org.jsoup.HttpStatusException
import java.net.HttpURLConnection

internal fun Throwable.isPageNotFoundFailure(): Boolean {
	var current: Throwable? = this
	while (current != null) {
		if (current is HttpStatusException && current.statusCode == HttpURLConnection.HTTP_NOT_FOUND) {
			return true
		}
		current = current.cause
	}
	return false
}
