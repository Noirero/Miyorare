package org.koitharu.kotatsu.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DownloadDestinationAvailabilityTest {

	@get:Rule val folder = TemporaryFolder()

	@Test
	fun writableRootDoesNotCreateDownloadsDirectoryWhileProbing() {
		val root = folder.newFolder("custom")
		assertEquals(DownloadDestinationAvailability.AVAILABLE, downloadDestinationAvailability(root))
		assertFalse(File(root, "downloads").exists())
	}

	@Test
	fun missingDestinationAndFileBlockingDownloadsAreUnavailable() {
		assertEquals(DownloadDestinationAvailability.UNAVAILABLE, downloadDestinationAvailability(null))
		val root = folder.newFolder("custom")
		assertEquals(DownloadDestinationAvailability.UNAVAILABLE, downloadDestinationAvailability(File(root, "missing")))
		File(root, "downloads").writeText("not a directory")
		assertEquals(DownloadDestinationAvailability.UNAVAILABLE, downloadDestinationAvailability(root))
	}

	@Test
	fun readOnlyRootStaysReadOnlyInsteadOfSelectingAnotherLocation() {
		val root = object : File("/read-only-test") {
			override fun isDirectory() = true
			override fun canRead() = true
			override fun canWrite() = false
		}
		assertEquals(DownloadDestinationAvailability.READ_ONLY, downloadDestinationAvailability(root))
	}
}
