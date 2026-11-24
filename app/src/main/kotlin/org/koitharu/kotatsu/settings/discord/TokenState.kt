package org.koitharu.kotatsu.settings.discord

import org.jsoup.HttpStatusException

enum class TokenState {

	EMPTY, REQUIRED, SAVED, INVALID, VALID, CHECKING, RATE_LIMITED, UNAVAILABLE;

	companion object {

		internal fun fromFailure(error: Throwable): TokenState = when ((error as? HttpStatusException)?.statusCode) {
			401 -> INVALID
			429 -> RATE_LIMITED
			else -> UNAVAILABLE
		}
	}
}
