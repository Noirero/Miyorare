package org.koitharu.kotatsu.core.logs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AppLoggerSessionFilesTest {

	@get:Rule val temporaryFolder = TemporaryFolder()

	@Test fun `explicit new session discards active and rolled log without touching exports`() {
		val dir = temporaryFolder.newFolder()
		val current = File(dir, "session.log").apply { writeText("previous active event\n") }
		val rolled = File(dir, "session.log.old").apply { writeText("previous rolled event\n") }
		val exported = File(dir, "saved.txt").apply { writeText("saved previous session") }

		openSessionLogWriter(current, rolled, resetExistingLogs = true).use { writer ->
			writer.write("new session event\n")
		}

		assertEquals("new session event\n", current.readText())
		assertFalse(rolled.exists())
		assertEquals("saved previous session", exported.readText())
	}

	@Test fun `automatic resumption preserves diagnostic history`() {
		val dir = temporaryFolder.newFolder()
		val current = File(dir, "session.log").apply { writeText("before restart\n") }
		val rolled = File(dir, "session.log.old").apply { writeText("earlier event\n") }

		openSessionLogWriter(current, rolled, resetExistingLogs = false).use { writer ->
			writer.write("after restart\n")
		}

		assertEquals("before restart\nafter restart\n", current.readText())
		assertTrue(rolled.exists())
		assertEquals("earlier event\n", rolled.readText())
	}
}
