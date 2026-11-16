package org.koitharu.kotatsu.core.logs

import org.junit.Assert.assertEquals
import org.junit.Test

class LegacyCbzDiagnosticSessionTest {
	@Test fun exportsActiveMarkerEvenWithoutResolverEvents() {
		val session = LegacyCbzDiagnosticSession()
		session.start()
		assertEquals("tag RECORDER_V2 active=true events=0 dropped=0\n", session.stop("tag"))
	}

	@Test fun onlyExportsBoundedEventsFromCurrentSession() {
		val session = LegacyCbzDiagnosticSession(capacity = 2)
		session.record("OUTSIDE_SESSION")
		session.start()
		session.record("FIRST")
		session.record("LOOKUP_BROWSE REJECTED OWNERSHIP_CONFLICT")
		session.record("READER_SELECTED_REMOTE")
		assertEquals(
			"tag RECORDER_V2 active=true events=2 dropped=1\n" +
				"tag LOOKUP_BROWSE REJECTED OWNERSHIP_CONFLICT\n" +
				"tag READER_SELECTED_REMOTE\n",
			session.stop("tag"),
		)
		session.record("OUTSIDE_SESSION")
		session.start()
		assertEquals("tag RECORDER_V2 active=true events=0 dropped=0\n", session.stop("tag"))
	}
}
