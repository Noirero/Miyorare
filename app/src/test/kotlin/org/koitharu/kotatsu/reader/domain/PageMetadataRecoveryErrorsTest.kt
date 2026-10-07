package org.koitharu.kotatsu.reader.domain

import org.jsoup.HttpStatusException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PageMetadataRecoveryErrorsTest {

	@Test
	fun http404IsEligibleForMetadataRecovery() {
		val error = HttpStatusException("Not Found", 404, "https://example.test/page.jpg")

		assertTrue(error.isPageNotFoundFailure())
	}

	@Test
	fun non404HttpFailureIsNotEligible() {
		val error = HttpStatusException("Forbidden", 403, "https://example.test/page.jpg")

		assertFalse(error.isPageNotFoundFailure())
	}

	@Test
	fun wrapped404IsStillDetected() {
		val error = IllegalStateException(
			"wrapper",
			HttpStatusException("Not Found", 404, "https://example.test/page.jpg"),
		)

		assertTrue(error.isPageNotFoundFailure())
	}
}
