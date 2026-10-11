package org.koitharu.kotatsu.core.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticTimelineTest {

	@Test
	fun `same manga opened twice has independent timings and incomplete draw stays unknown`() {
		val timeline = DiagnosticTimeline()
		timeline.begin(DiagnosticTimeline.Session(1, 42, "catalog", 0))
		timeline.begin(DiagnosticTimeline.Session(2, 42, "favourites", 100_000_000))
		timeline.record(event(1, "navigation", 0))
		timeline.record(event(2, "navigation", 100_000_000))
		timeline.record(event(1, "publication_end", 45_000_000))
		timeline.record(event(2, "publication_end", 150_000_000))
		timeline.record(event(1, "first_chapter_draw", 900_000_000))
		val summaries = timeline.snapshot().second.associateBy { it["sessionId"] }
		assertEquals(45.0, summaries.getValue(1L)["stateReadyMs"])
		assertEquals(50.0, summaries.getValue(2L)["stateReadyMs"])
		assertEquals(900.0, summaries.getValue(1L)["firstChapterDrawMs"])
		assertEquals("unknown", summaries.getValue(2L)["firstChapterDrawMs"])
		assertEquals("unknown_draw_marker_is_not_display_presentation", summaries.getValue(1L)["firstUsefulPresentedFrame"])
	}

	@Test
	fun `consumer during publication is reported as overlap not fabricated positive latency`() {
		val timeline = DiagnosticTimeline()
		timeline.begin(DiagnosticTimeline.Session(1, 42, "catalog", 0))
		timeline.record(event(1, "navigation", 0))
		timeline.record(event(1, "publication_begin", 40_000_000))
		timeline.record(event(1, "ui_state_received", 43_000_000))
		timeline.record(event(1, "publication_end", 45_000_000))
		val summary = timeline.snapshot().second.single()
		assertEquals("overlap_or_earlier_emission", summary["stateToConsumerMs"])
		assertEquals(5.0, summary["publicationSetterMs"])
	}

	@Test
	fun `out of order thread arrival is exported in monotonic event order and first draw is once`() {
		val timeline = DiagnosticTimeline()
		timeline.begin(DiagnosticTimeline.Session(1, 42, "catalog", 0))
		timeline.record(event(1, "ui_state_received", 60_000_000))
		timeline.record(event(1, "publication_end", 45_000_000))
		assertTrue(timeline.record(event(1, "first_chapter_draw", 90_000_000), once = true))
		assertFalse(timeline.record(event(1, "first_chapter_draw", 95_000_000), once = true))
		assertEquals(listOf("publication_end", "ui_state_received", "first_chapter_draw"), timeline.snapshot().first.map { it.stage })
	}

	@Test
	fun `bounded export discloses dropped events and expired sessions cannot grow recorder`() {
		val timeline = DiagnosticTimeline(maxEvents = 2, maxSessions = 1)
		timeline.begin(DiagnosticTimeline.Session(1, 42, "catalog", 0))
		repeat(3) { timeline.record(event(1, "event$it", it.toLong())) }
		assertEquals(1L, timeline.droppedEvents)
		assertEquals(2, timeline.snapshot().first.size)
		timeline.begin(DiagnosticTimeline.Session(2, 43, "catalog", 10))
		assertFalse(timeline.record(event(1, "expired", 11)))
		assertEquals(2L, timeline.snapshot().second.single()["sessionId"])
	}

	@Test
	fun `DAO is measured separately and identified as the slow stage`() {
		val timeline = DiagnosticTimeline()
		timeline.begin(DiagnosticTimeline.Session(1, 42, "catalog", 0))
		timeline.record(event(1, "navigation", 0))
		timeline.record(event(1, "dao_find_start", 100_000_000))
		timeline.record(event(1, "dao_find_end", 1_600_000_000))
		timeline.record(event(1, "publication_end", 1_620_000_000))
		timeline.record(event(1, "ui_state_received", 1_630_000_000))
		val summary = timeline.snapshot().second.single()
		assertEquals(1500.0, summary["daoFindMs"])
		assertEquals("dao_find_start->dao_find_end", summary["slowestMeasuredStage"])
	}

	@Test
	fun `paused boundary samples cannot confirm inactivity when work changes between them`() {
		fun check(endGeneration: Long): Boolean {
			val timeline = DiagnosticTimeline()
			timeline.begin(DiagnosticTimeline.Session(1, 42, "catalog", 0))
			timeline.record(event(1, "workload_dao_start", 0).copy(fields = mapOf("pausedConfirmed" to true, "generation" to 10L)))
			timeline.record(event(1, "workload_dao_end", 100).copy(fields = mapOf("pausedConfirmed" to true, "generation" to endGeneration)))
			val checks = timeline.snapshot().second.single()["daoWorkloadChecks"] as List<*>
			return (checks.single() as Map<*, *>)["pausedThroughout"] as Boolean
		}
		assertTrue(check(10L))
		assertFalse(check(12L))
	}

	@Test
	fun `main cpu span is kept separate from wall time`() {
		val timeline = DiagnosticTimeline()
		timeline.begin(DiagnosticTimeline.Session(1, 42, "catalog", 0))
		timeline.record(event(1, "navigation", 0).copy(threadCpuNs = 5_000_000))
		timeline.record(event(1, "first_chapter_draw", 900_000_000).copy(threadCpuNs = 105_000_000))
		val summary = timeline.snapshot().second.single()
		assertEquals(900.0, summary["firstChapterDrawMs"])
		assertEquals(100.0, summary["mainCpuNavigationToDrawMs"])
	}

	private fun event(session: Long, stage: String, at: Long) = DiagnosticTimeline.Event(
		session = session, stage = stage, elapsedNs = at, monotonicNs = at, tid = 123, thread = "main", fields = emptyMap(),
	)
}
