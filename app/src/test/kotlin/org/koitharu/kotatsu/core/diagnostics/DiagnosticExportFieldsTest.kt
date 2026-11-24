package org.koitharu.kotatsu.core.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticExportFieldsTest {
	@Test fun `only supported layout fields reach the export`() {
		val fields = DiagnosticExportFields.layout(
			"FavouritesLayout", "viewport_layout",
			"pager_w=600 url=https://example.test/title?token=fake cookie=session_fake " +
				"title=Synthetic_Title path=/storage/private.cbz adapter_count=Synthetic_Title orientation=portrait",
		)
		assertEquals("pager_w=600 orientation=portrait", fields)
		assertNull(DiagnosticExportFields.layout("Http", "response", "status=200"))
		assertNull(DiagnosticExportFields.layout("FavouritesLayout", "Synthetic_Title", "pager_w=600"))
	}

	@Test fun `raw log messages and owner names cannot appear in the user facing report`() {
		val session = DiagnosticsSession("synthetic", { 0L }) {}
		val privateTrace = session.navigation("normal", "Private_Title")
		session.event("Http", privateTrace, "Cookie=session_fake url=https://example.test/private")
		val source = session.begin("source", "Private_Title", privateTrace)
		session.end(source, "failure", "/storage/private.cbz")
		val sanitized = requireNotNull(DiagnosticExportFields.layout("FavouritesLayout", "viewport_layout", "pager_w=600"))
		session.captureDiagnostic("FavouritesLayout", "viewport_layout", null, sanitized)

		val report = session.exportSnapshot()
		assertTrue(report.contains("pager_w=600"))
		assertFalse(report.contains("Private_Title"))
		assertFalse(report.contains("session_fake"))
		assertFalse(report.contains("/storage/"))
		assertFalse(report.contains("example.test"))
	}
}
