package org.koitharu.kotatsu.core.diagnostics

import org.junit.Assert.*
import org.junit.Test

class DiagnosticsSessionTest {
	private var ns = 0L
	private val lines = ArrayList<String>()
	private fun session(id: String = "test") = DiagnosticsSession(id, { ns }, lines::add)
	private fun advance(ms: Long) { ns += ms * 1_000_000 }

	@Test fun `navigation milestones use one trace and monotonic elapsed time`() {
		val session = session()
		val trace = session.navigation("Favourites", "Details")
		advance(12)
		session.milestone(trace, "manga_ready")
		advance(28)
		session.milestone(trace, "first_meaningful_content")
		session.milestone(trace, "first_meaningful_content")
		assertEquals(3, lines.size)
		assertTrue(lines.all { "trace=${trace.id}" in it })
		assertTrue(lines[1].contains("elapsed_ms=12"))
		assertTrue(lines[2].contains("elapsed_ms=40"))
		assertTrue(session.stop().contains("name=Details.first_meaningful_content"))
	}

	@Test fun `completion is exactly once and old sessions cannot contaminate new summaries`() {
		val old = session("old")
		val trace = old.navigation("Favourites", "Details")
		val span = old.begin("work", "load", trace)
		advance(31)
		old.end(span, "cancel")
		old.end(span, "complete")
		val summary = old.stop()
		advance(10)
		old.end(span, "failure")
		assertTrue(summary.contains("aggregate=work.load.cancel count=1"))
		assertFalse(summary.contains("work.load.complete"))
		val new = session("new")
		assertNull(new.begin("work", "load", trace))
		new.milestone(trace, "chapters_ready")
		assertFalse(new.stop().contains("old-t"))
	}

	@Test fun `images are aggregated and slow samples are bounded`() {
		val session = session()
		repeat(40) {
			val span = session.begin("image", "fetch", null)
			advance(1)
			session.end(span)
		}
		assertTrue(lines.isEmpty())
		repeat(7) {
			val span = session.begin("image", "decode", null)
			advance(100)
			session.end(span, "failure")
		}
		assertEquals(4, lines.size)
		val summary = session.stop()
		assertTrue(summary.contains("image.fetch.complete count=40"))
		assertTrue(summary.contains("image.decode.failure count=7 failures=7 total_ms=700 worst_ms=100"))
		assertTrue(summary.contains("suppressed=3"))
	}

	@Test fun `frame counters respect refresh budget and never log normal frames`() {
		val session = session()
		val trace = session.navigation("History", "Favourites")
		lines.clear()
		session.frame(trace, 8_000_000, 8_333_333, 0)
		session.frame(trace, 20_000_000, 8_333_333, 2)
		assertTrue(lines.isEmpty())
		session.flushFrames(trace)
		assertEquals(1, lines.size)
		assertTrue(lines.single().contains("frames=2 janky=1 worst_ms=20 callback_dropped=2"))
		assertTrue(session.stop().contains("frames=2 janky=1 worst_frame_ms=20"))
	}

	@Test fun `only significant stalls are logged and summaries retain every diagnostic category`() {
		val session = session()
		session.stall(null, 249)
		assertTrue(lines.isEmpty())
		session.stall(null, 400)
		repeat(20) {
			val span = session.begin("work", "producer", null)
			advance(1000)
			session.end(span)
		}
		val network = session.begin("network", "http_call", null)
		advance(800)
		session.end(network, "failure")
		val summary = session.stop()
		assertTrue(summary.contains("worst kind=stall"))
		assertTrue(summary.contains("worst kind=network"))
		assertEquals(3, summary.lineSequence().count { it.startsWith("worst kind=work") })
	}

	@Test fun `stop includes unfinished work and closes the session`() {
		val session = session()
		session.begin("repository", "resolve", null)
		advance(301)
		val summary = session.stop()
		assertTrue(summary.contains("repository.resolve.in_progress_at_stop count=1"))
		assertTrue(summary.contains("duration_ms=301"))
		advance(500)
		assertEquals(summary, session.stop())
		assertNull(session.begin("work", "later", null))
		val count = lines.size
		session.event("later", null, "ignored")
		assertEquals(count, lines.size)
	}

	@Test fun `draw reservation prevents repeated listeners and can retry hidden views`() {
		val session = session()
		val trace = session.navigation("Favourites", "Details")
		assertTrue(session.reserveDraw(trace, "first_draw"))
		assertFalse(session.reserveDraw(trace, "first_draw"))
		session.releaseDraw(trace, "first_draw")
		assertTrue(session.reserveDraw(trace, "first_draw"))
		session.milestone(trace, "first_draw")
		assertFalse(session.reserveDraw(trace, "first_draw"))
	}

	@Test fun `sink failures cannot break an operation and session output is bounded`() {
		val session = DiagnosticsSession("test", { ns }) { error("writer failed") }
		val span = session.begin("work", "load", null)
		session.end(span)
		repeat(3000) { session.event("lifecycle", null, "stage=resumed") }
		assertTrue(session.stop().contains("suppressed=502"))
	}

	@Test fun `existing local events aggregate safe reason and measured duration`() {
		val session = session()
		session.observed("image", "local_CACHE_HIT", null, 2_000_000)
		session.observed("image", "local_MISS_ABSENT", null, 0)
		session.observed("image", "local_GENERATION_FAILED", null, 120_000_000, "failure")
		assertEquals(1, lines.size)
		val summary = session.stop()
		assertTrue(summary.contains("image.local_CACHE_HIT.complete count=1"))
		assertTrue(summary.contains("image.local_MISS_ABSENT.complete count=1"))
		assertTrue(summary.contains("image.local_GENERATION_FAILED.failure count=1 failures=1 total_ms=120"))
	}

	@Test fun `concurrent repository completions preserve all counts`() {
		val session = session()
		val threads = List(4) {
			Thread {
				repeat(100) {
					val span = session.begin("repository", "load", null)
					session.end(span)
					session.end(span)
				}
			}
		}
		threads.forEach { it.start() }
		threads.forEach { it.join() }
		assertTrue(session.stop().contains("repository.load.complete count=400"))
	}

	@Test fun `disabled instrumentation preserves values exceptions and source flow identity`() {
		assertFalse(PerformanceDiagnostics.enabled)
		assertEquals(42, PerformanceDiagnostics.measure("repository", "test") { 42 })
		val error = IllegalStateException("private content must not be formatted")
		try {
			PerformanceDiagnostics.measure("repository", "test") { throw error }
			fail("Expected the original failure")
		} catch (caught: IllegalStateException) {
			assertSame(error, caught)
		}
		val source = kotlinx.coroutines.flow.flowOf(1)
		assertSame(source, PerformanceDiagnostics.firstResult(source, "test"))
	}
}
