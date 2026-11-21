package org.koitharu.kotatsu.core.logs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream

class AppLoggerSessionFilesTest {
	@get:Rule val temporaryFolder = TemporaryFolder()

	@Test fun `a new recording leaves the last completed export intact until it completes`() {
		val store = VerboseLogStore(temporaryFolder.newFolder())
		store.prepare(resume = false)
		store.checkpoint("previous active")
		store.finish("previous final", partial = false)

		store.prepare(resume = false)
		store.checkpoint("new active")
		assertEquals("capture_status=complete\nprevious final", export(store))
		store.finish("new final", partial = false)
		assertEquals("capture_status=complete\nnew final", export(store))
		assertFalse(store.hasInterrupted)
	}

	@Test fun `automatic resumption preserves the previous checkpoint in a partial report`() {
		val directory = temporaryFolder.newFolder()
		VerboseLogStore(directory).checkpoint("before restart")
		val store = VerboseLogStore(directory)
		assertTrue(store.hasInterrupted)
		store.prepare(resume = true)
		store.checkpoint("after restart checkpoint")
		assertTrue(store.finish("after restart final", partial = false))

		val saved = export(VerboseLogStore(directory))
		assertTrue(saved.startsWith("capture_status=partial"))
		assertTrue(saved.contains("before restart"))
		assertTrue(saved.contains("after restart final"))
		assertFalse(saved.contains("after restart checkpoint"))
	}

	@Test fun `cancelled picker null stream and failed write allow retry of the same recording`() {
		val directory = temporaryFolder.newFolder()
		val store = VerboseLogStore(directory)
		store.finish("keep this recording", partial = false)
		// A cancelled picker never opens a destination. The app-owned file survives recreation.
		val reopened = VerboseLogStore(directory)
		assertFalse(reopened.export { null })
		assertFalse(reopened.export {
			object : OutputStream() {
				override fun write(b: Int) { throw IOException("destination failed") }
			}
		})
		assertEquals("capture_status=complete\nkeep this recording", export(reopened))
		assertEquals(export(store), export(reopened))
		reopened.deleteSaved()
		assertFalse(store.hasSaved)
	}

	@Test fun `capture failure is persisted and unreadable internal storage can use a partial snapshot`() {
		val store = VerboseLogStore(temporaryFolder.newFolder())
		assertTrue(store.finish("checkpoint write failed", partial = true))
		assertTrue(store.savedIsPartial())
		val output = ByteArrayOutputStream()
		assertTrue(store.export(recovered = "from memory") { output })
		assertEquals("capture_status=partial\nfrom memory", output.toString(Charsets.UTF_8.name()))
		assertEquals("capture_status=partial\ncheckpoint write failed", export(store))
	}

	private fun export(store: VerboseLogStore): String {
		val output = ByteArrayOutputStream()
		assertTrue(store.export { output })
		return output.toString(Charsets.UTF_8.name())
	}
}
