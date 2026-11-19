package org.koitharu.kotatsu.image.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

/** Checks Coil's actual copy path used when source headers are injected. */
class CoverBorderRequestTokenTest {

	@Test
	fun headerRewriteKeepsOriginalRequestToken() {
		val tracking = CoverBorderRequestToken(Any())
		val original = ImageRequest.Builder(ApplicationProvider.getApplicationContext<Context>())
			.data("https://example.invalid/manga-cover.jpg")
			.apply { extras[coverBorderRequestTokenKey] = tracking }
			.build()
		val updatedHeaders = original.httpHeaders.newBuilder()
			.set("X-Miyorare-Test", "source")
			.build()
		val rewritten = original.newBuilder().httpHeaders(updatedHeaders).build()
		assertNotSame(original, rewritten)
		assertSame(tracking, rewritten.extras[coverBorderRequestTokenKey])
		assertSame(tracking.bindingIdentity, rewritten.extras[coverBorderRequestTokenKey]?.bindingIdentity)
	}
}
