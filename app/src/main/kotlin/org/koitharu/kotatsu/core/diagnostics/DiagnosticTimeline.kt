package org.koitharu.kotatsu.core.diagnostics

/** Bounded, value-only recorder. Never holds Activity, ViewModel, Flow, image or manga objects. */
internal class DiagnosticTimeline(private val maxEvents: Int = 4096, private val maxSessions: Int = 16) {

	data class Event(
		val session: Long,
		val stage: String,
		val elapsedNs: Long,
		val monotonicNs: Long,
		val tid: Int,
		val thread: String,
		val fields: Map<String, Any?>,
		val threadCpuNs: Long = -1L,
	)

	data class Session(val id: Long, val mangaId: Long, val route: String, val startNs: Long)

	private val events = ArrayDeque<Event>()
	private val sessions = LinkedHashMap<Long, Session>()
	private val first = HashMap<Pair<Long, String>, Long>()
	private val aggregates = HashMap<Long, MutableMap<String, Long>>()
	var droppedEvents: Long = 0
		private set

	@Synchronized
	fun begin(session: Session) {
		sessions[session.id] = session
		while (sessions.size > maxSessions) {
			val oldest = sessions.keys.first()
			sessions.remove(oldest)
			first.keys.removeAll { it.first == oldest }
			aggregates.remove(oldest)
			val removed = events.count { it.session == oldest }
			events.removeAll { it.session == oldest }
			droppedEvents += removed
		}
	}

	@Synchronized
	fun record(event: Event, once: Boolean = false): Boolean {
		if (event.session != 0L && event.session !in sessions) return false
		val key = event.session to event.stage
		if (once && key in first) return false
		first.putIfAbsent(key, event.elapsedNs)
		if (events.size == maxEvents) {
			events.removeFirst()
			droppedEvents++
		}
		events.addLast(event)
		return true
	}

	@Synchronized
	fun aggregate(session: Long, name: String, value: Long = 1L, maximum: Boolean = false) {
		if (session !in sessions) return
		val map = aggregates.getOrPut(session) { HashMap() }
		map[name] = if (maximum) maxOf(map[name] ?: 0L, value) else (map[name] ?: 0L) + value
	}

	@Synchronized
	fun has(session: Long, stage: String): Boolean = (session to stage) in first

	@Synchronized
	fun containsSession(session: Long): Boolean = session in sessions

	private data class Snapshot(
		val events: List<Event>, val sessions: List<Session>, val first: Map<Pair<Long, String>, Long>,
		val aggregates: Map<Long, Map<String, Long>>,
	)

	private fun daoWorkloadChecks(events: List<Event>): List<Map<String, Any?>> {
		val starts = ArrayDeque<Event>()
		val result = ArrayList<Map<String, Any?>>()
		for (event in events) {
			if (event.stage == "workload_dao_start") starts.addLast(event)
			if (event.stage != "workload_dao_end" || starts.isEmpty()) continue
			val start = starts.removeFirst()
			val confirmed = start.fields["pausedConfirmed"] == true && event.fields["pausedConfirmed"] == true &&
				start.fields["generation"] == event.fields["generation"]
			result += mapOf("startElapsedNs" to start.elapsedNs, "endElapsedNs" to event.elapsedNs,
				"pausedThroughout" to confirmed, "start" to start.fields, "end" to event.fields,
				"evidence" to "joined_list_producer_and_all_tracked_enrichment_jobs_unchanged_generation",
				"scope" to "tested_list_refresh_only_not_all_favourites_or_all_database_work")
		}
		return result
	}

	fun snapshot(): Pair<List<Event>, List<Map<String, Any?>>> {
		val copy = synchronized(this) {
			Snapshot(events.toList(), sessions.values.toList(), first.toMap(), aggregates.mapValues { it.value.toMap() })
		}
		// Sorting/summary construction never holds the lock needed by navigation/frame markers.
		val ordered = copy.events.sortedBy { it.elapsedNs }
		val summaries = copy.sessions.map { session ->
			fun at(stage: String) = copy.first[session.id to stage]
			fun delta(from: String, to: String): Any {
				val start = at(from) ?: return "unknown"
				val end = at(to) ?: return "unknown"
				return if (end < start) "overlap_or_earlier_emission" else (end - start) / 1_000_000.0
			}
			val chain = listOf("dao_find_start", "dao_find_end", "publication_end", "ui_state_received", "chapter_section_composition",
				"first_chapter_composition", "first_chapter_placement", "first_chapter_draw")
			val gaps = chain.zipWithNext().mapNotNull { (from, to) ->
				(delta(from, to) as? Double)?.let { "$from->$to" to it }
			}
			val snapshots = ordered.filter { it.session == session.id && it.stage.startsWith("memory_") }
			val cacheState = ordered.firstOrNull { it.session == session.id && it.stage == "cache_state" }?.fields
			val chapterCount = ordered.firstOrNull { it.session == session.id && it.stage == "publication_end" }
				?.fields?.get("chapterCount") ?: "unknown"
			val mainEvents = ordered.filter { it.session == session.id && it.thread == "main" && it.threadCpuNs >= 0 }
			val mainBegin = mainEvents.firstOrNull { it.stage == "navigation" }
			val mainDraw = mainEvents.firstOrNull { it.stage == "first_chapter_draw" }
			mapOf(
				"type" to "DETAILS_DIAG", "sessionId" to session.id,
				"mangaId" to (ordered.firstOrNull { it.session == session.id && it.stage == "publication_end" }?.fields?.get("mangaId") ?: session.mangaId),
				"route" to session.route, "chapterCount" to chapterCount,
				"cacheInitialized" to (cacheState?.get("initialized") ?: "unknown"),
				"cacheFresh" to (cacheState?.get("fresh") ?: "unknown"),
				"stateReadyMs" to delta("navigation", "publication_end"),
				"daoFindMs" to delta("dao_find_start", "dao_find_end"),
				"daoWorkloadChecks" to daoWorkloadChecks(ordered.filter { it.session == session.id }),
				"publicationBeginMs" to delta("navigation", "publication_begin"),
				"publicationSetterMs" to delta("publication_begin", "publication_end"),
				"stateToConsumerMs" to delta("publication_end", "ui_state_received"),
				"consumerToCompositionMs" to delta("ui_state_received", "chapter_section_composition"),
				"compositionToFirstChapterMs" to delta("chapter_section_composition", "first_chapter_composition"),
				"firstChapterDrawMs" to delta("navigation", "first_chapter_draw"),
				"modelReadyMs" to delta("navigation", "final_model_ready"),
				"totalNavigationMs" to delta("navigation", "details_stopped"),
				"mainCpuNavigationToDrawMs" to if (mainBegin != null && mainDraw != null && mainBegin.tid == mainDraw.tid)
					(mainDraw.threadCpuNs - mainBegin.threadCpuNs) / 1_000_000.0 else "unknown",
				"slowestMeasuredStage" to (gaps.maxByOrNull { it.second }?.first ?: "unknown"),
				"firstUsefulPresentedFrame" to "unknown_draw_marker_is_not_display_presentation",
				"memory" to snapshots.associate { it.stage to it.fields },
				"ownership" to ordered.filter { it.session == session.id && it.stage.startsWith("ownership_") }
					.associate { it.stage to it.fields },
				"counters" to copy.aggregates[session.id].orEmpty(),
				"markersElapsedNs" to copy.first.filterKeys { it.first == session.id }.mapKeys { it.key.second },
			)
		}
		return ordered to summaries
	}
}
