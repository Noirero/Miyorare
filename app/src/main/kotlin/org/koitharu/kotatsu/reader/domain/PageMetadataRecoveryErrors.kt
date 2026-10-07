package org.koitharu.kotatsu.reader.domain

import eu.kanade.tachiyomi.network.HttpException
import org.jsoup.HttpStatusException
import java.net.HttpURLConnection

internal fun Throwable.isPageNotFoundFailure(): Boolean {
	var current: Throwable? = this
	while (current != null) {
		when (current) {
			is HttpStatusException -> if (current.statusCode == HttpURLConnection.HTTP_NOT_FOUND) return true
			is HttpException -> if (current.code == HttpURLConnection.HTTP_NOT_FOUND) return true
		}
		current = current.cause
	}
	return false
}
