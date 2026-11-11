package org.koitharu.kotatsu.core.logs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AppLoggerCommandTest {
	@Test
	fun `logcat follows the live tail without clearing system buffers`() {
		val command = liveLogcatCommand().toList()
		assertEquals(listOf("logcat", "-v", "threadtime", "-T", "1"), command)
		assertFalse(command.contains("-c"))
		assertFalse(command.contains("-d"))
	}
}
