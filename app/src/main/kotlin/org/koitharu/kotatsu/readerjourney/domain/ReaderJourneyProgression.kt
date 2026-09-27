package org.koitharu.kotatsu.readerjourney.domain

import androidx.room.withTransaction
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.readerjourney.data.ReaderJourneyAward
import org.koitharu.kotatsu.readerjourney.data.ReaderJourneyProfileEntity
import org.koitharu.kotatsu.readerjourney.data.ReaderJourneyWeeklyStateEntity
import org.koitharu.kotatsu.readerjourney.data.ReaderJourneyXpEventEntity
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.TemporalAdjusters
import javax.inject.Inject

enum class ReaderJourneyXpSource {
	READING_COMPLETION,
	REREAD,
	EXPLORATION,
	WEEKLY_TASK,
	WEEKLY_BONUS,
	ACHIEVEMENT,
	RESTED,
	WELCOME_BACK,
	ACTIVE_DAYS,
	MIXED_FORMAT,
}

enum class ReaderJourneyTaskDifficulty { EASY, STANDARD, STRETCH }

enum class ReaderJourneyWeeklyMetric {
	CHAPTERS,
	ACTIVE_DAYS,
	TITLES,
	NOVEL_CHAPTERS,
	MANGA_CHAPTERS,
	NEW_TITLES,
}

enum class ReaderJourneyWeeklyTaskId(
	val metric: ReaderJourneyWeeklyMetric,
	val target: Int,
	val rewardXp: Int,
	val difficulty: ReaderJourneyTaskDifficulty,
) {
	READ_3_CHAPTERS(ReaderJourneyWeeklyMetric.CHAPTERS, 3, 25, ReaderJourneyTaskDifficulty.EASY),
	READ_2_DAYS(ReaderJourneyWeeklyMetric.ACTIVE_DAYS, 2, 20, ReaderJourneyTaskDifficulty.EASY),
	READ_2_TITLES(ReaderJourneyWeeklyMetric.TITLES, 2, 20, ReaderJourneyTaskDifficulty.STANDARD),
	READ_1_NOVEL(ReaderJourneyWeeklyMetric.NOVEL_CHAPTERS, 1, 20, ReaderJourneyTaskDifficulty.STANDARD),
	READ_5_CHAPTERS(ReaderJourneyWeeklyMetric.CHAPTERS, 5, 35, ReaderJourneyTaskDifficulty.STRETCH),
	TRY_NEW_TITLE(ReaderJourneyWeeklyMetric.NEW_TITLES, 1, 15, ReaderJourneyTaskDifficulty.EASY),
	READ_4_MANGA(ReaderJourneyWeeklyMetric.MANGA_CHAPTERS, 4, 25, ReaderJourneyTaskDifficulty.STANDARD),
	READ_2_NOVELS(ReaderJourneyWeeklyMetric.NOVEL_CHAPTERS, 2, 30, ReaderJourneyTaskDifficulty.STANDARD),
	READ_3_DAYS(ReaderJourneyWeeklyMetric.ACTIVE_DAYS, 3, 30, ReaderJourneyTaskDifficulty.STRETCH),
}

data class ReaderJourneyWeeklyTaskProgress(
	val id: ReaderJourneyWeeklyTaskId,
	val progress: Int,
	val awarded: Boolean,
) {
	val fraction: Float
		get() = (progress.toFloat() / id.target.coerceAtLeast(1)).coerceIn(0f, 1f)
	val isComplete: Boolean
		get() = progress >= id.target
}

data class ReaderJourneyWeeklySnapshot(
	val weekKey: String,
	val tasks: List<ReaderJourneyWeeklyTaskProgress>,
	val completedTaskCount: Int,
	val completionBonusAwarded: Boolean,
	val rerollsRemaining: Int,
)

data class ReaderJourneyXpHistoryItem(
	val source: ReaderJourneyXpSource,
	val xp: Int,
	val occurredAt: Long,
	val context: String? = null,
)

data class ReaderJourneyProgressionSnapshot(
	val weekly: ReaderJourneyWeeklySnapshot,
	val recentHistory: List<ReaderJourneyXpHistoryItem>,
	val preservedXp: Long = 0L,
)

data class ReaderJourneyProgressionAward(
	val extraXp: Int,
	val totalXp: Long,
)

internal fun buildAdaptiveWeeklyPlan(
	profile: ReaderJourneyProfileEntity,
): List<ReaderJourneyWeeklyTaskId> {
	val manga = profile.mangaChapters
	val novel = profile.novelChapters
	val base = mutableListOf(
		ReaderJourneyWeeklyTaskId.READ_3_CHAPTERS,
		ReaderJourneyWeeklyTaskId.READ_2_DAYS,
		ReaderJourneyWeeklyTaskId.READ_2_TITLES,
		ReaderJourneyWeeklyTaskId.READ_1_NOVEL,
		ReaderJourneyWeeklyTaskId.READ_5_CHAPTERS,
		ReaderJourneyWeeklyTaskId.TRY_NEW_TITLE,
	)
	if (manga > 0L && (novel == 0L || (manga >= 10L && manga >= novel * 4L))) {
		base[3] = ReaderJourneyWeeklyTaskId.READ_4_MANGA
	} else if (novel > 0L && (manga == 0L || (novel >= 10L && novel >= manga * 4L))) {
		base[4] = ReaderJourneyWeeklyTaskId.READ_2_NOVELS
	}
	return base
}

internal fun selectAdaptiveRerollCandidates(
	profile: ReaderJourneyProfileEntity,
	candidates: List<ReaderJourneyWeeklyTaskId>,
): List<ReaderJourneyWeeklyTaskId> {
	val mangaHeavy = profile.mangaChapters > 0L && (
		profile.novelChapters == 0L ||
			(profile.mangaChapters >= 10L && profile.mangaChapters >= profile.novelChapters * 4L)
		)
	val novelHeavy = profile.novelChapters > 0L && (
		profile.mangaChapters == 0L ||
			(profile.novelChapters >= 10L && profile.novelChapters >= profile.mangaChapters * 4L)
		)
	val filtered = when {
		mangaHeavy -> candidates.filter { it.metric != ReaderJourneyWeeklyMetric.NOVEL_CHAPTERS }
		novelHeavy -> candidates.filter { it.metric != ReaderJourneyWeeklyMetric.MANGA_CHAPTERS }
		else -> candidates
	}
	return filtered.ifEmpty { candidates }
}

internal fun resolveWeeklyTaskId(
	configuredId: ReaderJourneyWeeklyTaskId,
	awardedEvent: ReaderJourneyXpEventEntity?,
	rerollEvent: ReaderJourneyXpEventEntity? = null,
): ReaderJourneyWeeklyTaskId {
	val event = awardedEvent?.takeIf { it.source == ReaderJourneyXpSource.WEEKLY_TASK.name }
		?: rerollEvent?.takeIf { it.source == "WEEKLY_REROLL" }
	return event?.context
		?.let { context -> ReaderJourneyWeeklyTaskId.entries.find { it.name == context } }
		?: configuredId
}

class ReaderJourneyProgressionRepository @Inject constructor(
	private val db: MangaDatabase,
) {

	private val dao
		get() = db.getReaderJourneyDao()

	suspend fun effectiveReadingXp(baseXp: Int, at: Long): Int {
		if (baseXp <= 0) return 0
		val readingXpToday = dao.sumReadingXpSince(startOfDayMillis(at))
		return ReaderJourneyRules.applySoftDailyReadingReturn(baseXp, readingXpToday)
	}

	suspend fun onVerifiedCompletion(
		award: ReaderJourneyAward,
		mangaId: Long,
		chapterId: Long,
		completedAt: Long,
	): ReaderJourneyProgressionAward {
		if (award.xp <= 0) return ReaderJourneyProgressionAward(0, award.totalXp)

		val readingSource = if (award.isFirstCompletion) {
			ReaderJourneyXpSource.READING_COMPLETION
		} else {
			ReaderJourneyXpSource.REREAD
		}
		dao.insertXpEvent(
			ReaderJourneyXpEventEntity(
				eventKey = "reading:" + mangaId + ":" + chapterId + ":" + award.completionCount,
				source = readingSource.name,
				xp = award.xp,
				occurredAt = completedAt,
				mangaId = mangaId,
				chapterId = chapterId,
				profileDelta = false,
			),
		)

		if (!award.isFirstCompletion) {
			val weeklyXp = processWeeklyJourneyWithGrace(completedAt)
			return ReaderJourneyProgressionAward(
				extraXp = weeklyXp,
				totalXp = dao.getProfile()?.totalXp ?: (award.totalXp + weeklyXp),
			)
		}

		var extraXp = 0
		val gapMs = if (award.previousUpdatedAt > 0L) {
			(completedAt - award.previousUpdatedAt).coerceAtLeast(0L)
		} else {
			0L
		}

		if (gapMs >= ReaderJourneyRules.RESTED_AFTER_MS) {
			dao.insertXpEvent(
				ReaderJourneyXpEventEntity(
					eventKey = "rested-window:" + award.previousUpdatedAt,
					source = INTERNAL_RESTED_WINDOW,
					xp = 0,
					occurredAt = completedAt,
					context = award.previousUpdatedAt.toString(),
					profileDelta = false,
				),
			)
		}
		if (gapMs >= ReaderJourneyRules.WELCOME_BACK_AFTER_MS) {
			dao.insertXpEvent(
				ReaderJourneyXpEventEntity(
					eventKey = "welcome-window:" + award.previousUpdatedAt,
					source = INTERNAL_WELCOME_WINDOW,
					xp = 0,
					occurredAt = completedAt,
					context = award.previousUpdatedAt.toString(),
					profileDelta = false,
				),
			)
		}

		if (dao.countCompletedChaptersForTitle(mangaId) == 1L) {
			extraXp += awardBonus(
				eventKey = "exploration:first-title:" + mangaId,
				source = ReaderJourneyXpSource.EXPLORATION,
				xp = ReaderJourneyRules.NEW_TITLE_EXPLORATION_XP,
				at = completedAt,
				mangaId = mangaId,
				chapterId = chapterId,
				context = "NEW_TITLE",
			)
		}

		val restedWindow = dao.latestXpEventBySource(INTERNAL_RESTED_WINDOW)
		if (
			restedWindow != null &&
			completedAt - restedWindow.occurredAt in 0L..ReaderJourneyRules.RESTED_WINDOW_MS
		) {
			val windowId = restedWindow.context ?: restedWindow.occurredAt.toString()
			val restedSlot = dao.countXpEventsByKeyPrefix("rested:" + windowId + ":slot:")
			if (restedSlot < ReaderJourneyRules.RESTED_MAX_COMPLETIONS) {
				extraXp += awardBonus(
					eventKey = "rested:" + windowId + ":slot:" + restedSlot,
					source = ReaderJourneyXpSource.RESTED,
					xp = ReaderJourneyRules.percentageBonus(award.xp, ReaderJourneyRules.RESTED_BONUS_PERCENT),
					at = completedAt,
					mangaId = mangaId,
					chapterId = chapterId,
					context = windowId,
				)
			}
		}

		val welcomeWindow = dao.latestXpEventBySource(INTERNAL_WELCOME_WINDOW)
		if (
			welcomeWindow != null &&
			completedAt - welcomeWindow.occurredAt in 0L..ReaderJourneyRules.WELCOME_BACK_WINDOW_MS
		) {
			val windowId = welcomeWindow.context ?: welcomeWindow.occurredAt.toString()
			val welcomeSlot = dao.countXpEventsByKeyPrefix("welcome:" + windowId + ":slot:")
			if (welcomeSlot < ReaderJourneyRules.WELCOME_BACK_MAX_COMPLETIONS) {
				extraXp += awardBonus(
					eventKey = "welcome:" + windowId + ":slot:" + welcomeSlot,
					source = ReaderJourneyXpSource.WELCOME_BACK,
					xp = ReaderJourneyRules.percentageBonus(award.xp, ReaderJourneyRules.WELCOME_BACK_BONUS_PERCENT),
					at = completedAt,
					mangaId = mangaId,
					chapterId = chapterId,
					context = windowId,
				)
			}
		}

		extraXp += processWeeklyJourneyWithGrace(completedAt)
		return ReaderJourneyProgressionAward(
			extraXp = extraXp,
			totalXp = dao.getProfile()?.totalXp ?: (award.totalXp + extraXp),
		)
	}

	suspend fun reconcile(at: Long = System.currentTimeMillis()) {
		processWeeklyJourneyWithGrace(at)
	}

	private suspend fun processWeeklyJourneyWithGrace(at: Long): Int {
		var awardedXp = processWeeklyJourney(periodAt = at, creditedAt = at)
		val currentWeek = weekBounds(at)
		if (at - currentWeek.start <= ReaderJourneyRules.WEEKLY_GRACE_MS) {
			awardedXp += processWeeklyJourney(
				periodAt = currentWeek.start - 1L,
				creditedAt = at,
			)
		}
		return awardedXp
	}

	suspend fun snapshot(at: Long = System.currentTimeMillis()): ReaderJourneyProgressionSnapshot =
		ReaderJourneyProgressionSnapshot(
			weekly = weeklySnapshot(at),
			recentHistory = dao.getRecentXpEvents(HISTORY_LIMIT).mapNotNull { event ->
				if (event.xp <= 0) return@mapNotNull null
				val source = runCatching { ReaderJourneyXpSource.valueOf(event.source) }.getOrNull()
					?: return@mapNotNull null
				ReaderJourneyXpHistoryItem(source, event.xp, event.occurredAt, event.context)
			},
			preservedXp = dao.getProfile()?.xpFloorAdjustment ?: 0L,
		)

	suspend fun rerollWeeklyTask(
		taskId: ReaderJourneyWeeklyTaskId,
		at: Long = System.currentTimeMillis(),
	): Boolean = db.withTransaction {
		val bounds = weekBounds(at)
		val profile = dao.getProfile() ?: ReaderJourneyProfileEntity()
		val state = ensureWeeklyState(bounds, profile)
		val snapshot = weeklySnapshot(at)
		val index = snapshot.tasks.indexOfFirst { it.id == taskId && !it.awarded && !it.isComplete }
		if (index < 0) return@withTransaction false

		val rerollPrefix = weeklyRerollEventPrefix(bounds.key)
		val rerollsUsed = dao.countXpEventsByKeyPrefix(rerollPrefix)
		if (rerollsUsed >= ReaderJourneyRules.WEEKLY_REROLL_LIMIT) return@withTransaction false
		val rerollKey = weeklyRerollEventKey(bounds.key, index)
		if (dao.hasXpEvent(rerollKey)) return@withTransaction false

		val tasks = snapshot.tasks.map { it.id }.toMutableList()
		val candidates = ReaderJourneyWeeklyTaskId.entries.filter { it !in tasks }
		if (candidates.isEmpty()) return@withTransaction false
		val formatSafeCandidates = selectAdaptiveRerollCandidates(profile, candidates)
		val preferred = formatSafeCandidates.filter {
			it.difficulty == taskId.difficulty || it.difficulty == ReaderJourneyTaskDifficulty.EASY
		}.ifEmpty { formatSafeCandidates }
		val replacement = preferred[Math.floorMod(bounds.key.hashCode() + rerollsUsed, preferred.size)]
		tasks[index] = replacement

		val inserted = dao.insertXpEvent(
			ReaderJourneyXpEventEntity(
				eventKey = rerollKey,
				source = INTERNAL_WEEKLY_REROLL,
				xp = 0,
				occurredAt = at,
				context = replacement.name,
				profileDelta = false,
			),
		)
		if (inserted == -1L) return@withTransaction false

		dao.upsertWeeklyState(
			state.copy(
				taskIds = tasks.joinToString(",") { it.name },
				rerollsUsed = (rerollsUsed + 1).coerceAtMost(ReaderJourneyRules.WEEKLY_REROLL_LIMIT),
				updatedAt = at,
			),
		)
		true
	}

	private suspend fun processWeeklyJourney(
		periodAt: Long,
		creditedAt: Long = periodAt,
	): Int {
		val snapshot = weeklySnapshot(periodAt)
		var awardedXp = 0
		for ((slotIndex, task) in snapshot.tasks.withIndex()) {
			if (!task.isComplete || task.awarded) continue
			awardedXp += awardBonus(
				eventKey = weeklyTaskEventKey(snapshot.weekKey, slotIndex),
				source = ReaderJourneyXpSource.WEEKLY_TASK,
				xp = task.id.rewardXp,
				at = creditedAt,
				context = task.id.name,
			)
		}

		val refreshed = weeklySnapshot(periodAt)
		if (refreshed.completedTaskCount >= ReaderJourneyRules.WEEKLY_TASKS_FOR_BONUS) {
			awardedXp += awardBonus(
				eventKey = "weekly-bonus:" + refreshed.weekKey,
				source = ReaderJourneyXpSource.WEEKLY_BONUS,
				xp = ReaderJourneyRules.WEEKLY_COMPLETION_BONUS_XP,
				at = creditedAt,
				context = refreshed.weekKey,
			)
		}

		val metrics = weeklyMetrics(weekBounds(periodAt))
		if (metrics.activeDays >= ReaderJourneyRules.ACTIVE_READING_DAYS_TARGET) {
			awardedXp += awardBonus(
				eventKey = "active-days:" + refreshed.weekKey,
				source = ReaderJourneyXpSource.ACTIVE_DAYS,
				xp = ReaderJourneyRules.ACTIVE_READING_DAYS_XP,
				at = creditedAt,
				context = refreshed.weekKey,
			)
		}
		if (metrics.mangaChapters > 0 && metrics.novelChapters > 0) {
			awardedXp += awardBonus(
				eventKey = "mixed-format:" + refreshed.weekKey,
				source = ReaderJourneyXpSource.MIXED_FORMAT,
				xp = ReaderJourneyRules.MIXED_FORMAT_XP,
				at = creditedAt,
				context = refreshed.weekKey,
			)
		}
		if (metrics.chapters >= 5 && metrics.titles >= 2) {
			awardedXp += awardBonus(
				eventKey = "exploration:diverse-five:" + refreshed.weekKey,
				source = ReaderJourneyXpSource.EXPLORATION,
				xp = ReaderJourneyRules.DIVERSE_READING_XP,
				at = creditedAt,
				context = "DIVERSE_5",
			)
		}
		return awardedXp
	}

	private suspend fun weeklySnapshot(at: Long): ReaderJourneyWeeklySnapshot {
		val bounds = weekBounds(at)
		val profile = dao.getProfile() ?: ReaderJourneyProfileEntity()
		val state = ensureWeeklyState(bounds, profile)
		val metrics = weeklyMetrics(bounds)
		val tasks = decodeTaskIds(state.taskIds).mapIndexed { slotIndex, configuredId ->
			val awardEvent = dao.getXpEvent(weeklyTaskEventKey(bounds.key, slotIndex))
			val rerollEvent = dao.getXpEvent(weeklyRerollEventKey(bounds.key, slotIndex))
			val id = resolveWeeklyTaskId(
				configuredId = configuredId,
				awardedEvent = awardEvent,
				rerollEvent = rerollEvent,
			)
			val progress = when (id.metric) {
				ReaderJourneyWeeklyMetric.CHAPTERS -> metrics.chapters
				ReaderJourneyWeeklyMetric.ACTIVE_DAYS -> metrics.activeDays
				ReaderJourneyWeeklyMetric.TITLES -> metrics.titles
				ReaderJourneyWeeklyMetric.NOVEL_CHAPTERS -> metrics.novelChapters
				ReaderJourneyWeeklyMetric.MANGA_CHAPTERS -> metrics.mangaChapters
				ReaderJourneyWeeklyMetric.NEW_TITLES -> metrics.newTitles
			}
			ReaderJourneyWeeklyTaskProgress(
				id = id,
				progress = progress.coerceAtMost(id.target),
				awarded = awardEvent != null,
			)
		}
		return ReaderJourneyWeeklySnapshot(
			weekKey = bounds.key,
			tasks = tasks,
			completedTaskCount = tasks.count { it.awarded || it.isComplete },
			completionBonusAwarded = dao.hasXpEvent("weekly-bonus:" + bounds.key),
			rerollsRemaining = (
				ReaderJourneyRules.WEEKLY_REROLL_LIMIT -
					dao.countXpEventsByKeyPrefix(weeklyRerollEventPrefix(bounds.key))
				).coerceAtLeast(0),
		)
	}

	private suspend fun ensureWeeklyState(
		bounds: WeekBounds,
		profile: ReaderJourneyProfileEntity,
	): ReaderJourneyWeeklyStateEntity {
		dao.getWeeklyState(bounds.key)?.let { existing ->
			if (decodeTaskIds(existing.taskIds).size == ReaderJourneyRules.WEEKLY_TASK_COUNT) return existing
		}
		val state = ReaderJourneyWeeklyStateEntity(
			weekKey = bounds.key,
			taskIds = buildAdaptiveWeeklyPlan(profile).joinToString(",") { it.name },
			updatedAt = bounds.start,
		)
		dao.upsertWeeklyState(state)
		return state
	}


	private suspend fun weeklyMetrics(bounds: WeekBounds): WeeklyMetrics {
		val completions = dao.getFirstCompletionsBetween(bounds.start, bounds.end)
		val readingEvents = dao.getReadingXpEventsBetween(bounds.start, bounds.end)
		val zone = JOURNEY_ECONOMY_ZONE
		val activeDates = LinkedHashSet<java.time.LocalDate>()
		completions.forEach { completion ->
			activeDates += Instant.ofEpochMilli(completion.firstCompletedAt).atZone(zone).toLocalDate()
		}
		readingEvents.forEach { event ->
			activeDates += Instant.ofEpochMilli(event.occurredAt).atZone(zone).toLocalDate()
		}
		return WeeklyMetrics(
			chapters = completions.size,
			activeDays = activeDates.size,
			titles = completions.map { it.mangaId }.distinct().size,
			novelChapters = completions.count { it.isNovel },
			mangaChapters = completions.count { !it.isNovel },
			newTitles = dao.countTitlesFirstCompletedBetween(bounds.start, bounds.end).toInt(),
		)
	}

	private suspend fun awardBonus(
		eventKey: String,
		source: ReaderJourneyXpSource,
		xp: Int,
		at: Long,
		mangaId: Long? = null,
		chapterId: Long? = null,
		context: String? = null,
	): Int {
		if (xp <= 0) return 0
		return dao.awardBonusEvent(
			ReaderJourneyXpEventEntity(
				eventKey = eventKey,
				source = source.name,
				xp = xp,
				occurredAt = at,
				mangaId = mangaId,
				chapterId = chapterId,
				context = context,
				profileDelta = true,
			),
		).xp
	}

	private fun weeklyTaskEventKey(weekKey: String, slotIndex: Int): String =
		"weekly:" + weekKey + ":slot:" + slotIndex

	private fun weeklyRerollEventPrefix(weekKey: String): String =
		"weekly-reroll:" + weekKey + ":slot:"

	private fun weeklyRerollEventKey(weekKey: String, slotIndex: Int): String =
		weeklyRerollEventPrefix(weekKey) + slotIndex

	private fun decodeTaskIds(value: String): List<ReaderJourneyWeeklyTaskId> =
		value.split(',')
			.mapNotNull { raw -> ReaderJourneyWeeklyTaskId.entries.find { it.name == raw } }
			.distinct()
			.take(ReaderJourneyRules.WEEKLY_TASK_COUNT)

	private fun startOfDayMillis(at: Long): Long {
		val zone = ZoneId.systemDefault()
		return Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
			.atStartOfDay(zone).toInstant().toEpochMilli()
	}

	private fun weekBounds(at: Long): WeekBounds {
		val zone = JOURNEY_ECONOMY_ZONE
		val date = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
		val monday = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
		return WeekBounds(
			key = monday.toString(),
			start = monday.atStartOfDay(zone).toInstant().toEpochMilli(),
			end = monday.plusDays(7).atStartOfDay(zone).toInstant().toEpochMilli(),
		)
	}

	private data class WeekBounds(val key: String, val start: Long, val end: Long)
	private data class WeeklyMetrics(
		val chapters: Int,
		val activeDays: Int,
		val titles: Int,
		val novelChapters: Int,
		val mangaChapters: Int,
		val newTitles: Int,
	)

	private companion object {
		const val INTERNAL_RESTED_WINDOW = "RESTED_WINDOW"
		const val INTERNAL_WELCOME_WINDOW = "WELCOME_BACK_WINDOW"
		const val INTERNAL_WEEKLY_REROLL = "WEEKLY_REROLL"
		const val HISTORY_LIMIT = 12
		val JOURNEY_ECONOMY_ZONE: ZoneId = ZoneOffset.UTC
	}
}
