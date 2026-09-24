package org.koitharu.kotatsu.stats.data

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.db.entity.MangaWithTags
import org.koitharu.kotatsu.core.db.entity.toManga
import org.koitharu.kotatsu.core.model.isNovelContent
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.observeAsFlow
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.readerjourney.domain.ReaderAchievementRepository
import org.koitharu.kotatsu.stats.domain.ReadingStats
import org.koitharu.kotatsu.stats.domain.StatsBucket
import org.koitharu.kotatsu.stats.domain.StatsBucketUnit
import org.koitharu.kotatsu.stats.domain.StatsContentScope
import org.koitharu.kotatsu.stats.domain.StatsHeatmapDay
import org.koitharu.kotatsu.stats.domain.StatsInsight
import org.koitharu.kotatsu.stats.domain.StatsMatureMode
import org.koitharu.kotatsu.stats.domain.StatsPeriod
import org.koitharu.kotatsu.stats.domain.StatsRecord
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.time.temporal.WeekFields
import java.util.Locale
import java.util.NavigableMap
import java.util.TreeMap
import java.util.TreeSet
import javax.inject.Inject

class StatsRepository @Inject constructor(
	private val settings: AppSettings,
	private val db: MangaDatabase,
	private val achievementRepository: ReaderAchievementRepository,
) {

	/**
	 * Build the entire dashboard from one coherent set of local sessions.
	 *
	 * Mature PRIVATE mode deliberately filters only identifying metadata, never aggregate activity.
	 * That keeps XP, streaks, read time and heatmap truthful without leaking a title, cover or genre.
	 */
	suspend fun getStatsSnapshot(
		period: StatsPeriod,
		categories: Set<Long>,
		scope: StatsContentScope,
		matureMode: StatsMatureMode,
	): ReadingStats {
		val zone = ZoneId.systemDefault()
		val boundedStarts = if (period == StatsPeriod.ALL) null else bucketStarts(period, zone, 0L)
		val fromDate = boundedStarts?.second?.first() ?: 0L

		val periodRaw = db.getStatsDao().getSessions(fromDate, categories)
		val allRaw = if (fromDate == 0L) periodRaw else db.getStatsDao().getSessions(0L, categories)
		val ids = allRaw.mapTo(LinkedHashSet()) { it.mangaId }
		val metadata = if (ids.isEmpty()) {
			emptyMap()
		} else {
			val prefsById = db.getPreferencesDao().findByIds(ids).associateBy { it.mangaId }
			db.getMangaDao().findByIds(ids)
				.associate { stored ->
					val manga = stored.toManga()
					val detectedMature = stored.manga.isNsfw ||
						stored.manga.contentRating.equals("ADULT", ignoreCase = true) ||
						stored.tags.any(StatsTagClassifier::isMatureTag)
					val isMature = when (prefsById[stored.manga.id]?.contentRatingOverride?.uppercase(Locale.ROOT)) {
						"ADULT" -> true
						"SAFE" -> false
						else -> detectedMature
					}
					stored.manga.id to StatsTitleMeta(
						stored = stored,
						manga = manga,
						isNovel = manga.isNovelContent,
						isMature = isMature,
					)
				}
		}

		fun accepts(session: StatsEntity): Boolean {
			val meta = metadata[session.mangaId] ?: return false
			val contentMatches = when (scope) {
				StatsContentScope.OVERVIEW -> true
				StatsContentScope.MANGA -> !meta.isNovel
				StatsContentScope.NOVEL -> meta.isNovel
			}
			if (!contentMatches) return false
			return matureMode != StatsMatureMode.EXCLUDE || !meta.isMature
		}

		val sessions = periodRaw.filter(::accepts)
		val lifetimeSessions = allRaw.filter(::accepts)
		val (unit, starts) = boundedStarts
			?: bucketStarts(period, zone, sessions.firstOrNull()?.startedAt ?: System.currentTimeMillis())

		val durations = LongArray(starts.size)
		val activeDays = LinkedHashSet<LocalDate>()
		val perTitle = LinkedHashMap<Long, MangaAggregate>()
		val heatmap = LinkedHashMap<LocalDate, DayAggregate>()
		var totalDuration = 0L
		var chapterDuration = 0L
		var chapters = 0
		var pages = 0

		for (session in sessions) {
			totalDuration += session.duration
			chapters += session.chapters
			pages += session.pages
			if (session.chapters > 0) chapterDuration += session.duration
			val day = Instant.ofEpochMilli(session.startedAt).atZone(zone).toLocalDate()
			activeDays += day
			perTitle.getOrPut(session.mangaId) { MangaAggregate() }.add(session, day)
			heatmap.getOrPut(day) { DayAggregate() }.add(session)
			val index = starts.binarySearch(session.startedAt).let { if (it >= 0) it else -it - 2 }
			if (index in durations.indices) durations[index] += session.duration
		}

		val built = buildRecords(
			perTitle = perTitle,
			metadata = metadata,
			total = totalDuration,
			matureMode = matureMode,
			scope = scope,
		)
		val topGenres = buildGenreInsights(perTitle.keys, metadata, matureMode)
		val formatBreakdown = buildFormatInsights(perTitle.keys, metadata, matureMode)
		val revisited = built.directRecords
			.filter { it.sessionCount > 0 }
			.sortedWith(compareByDescending<StatsRecord> { it.sessionCount }.thenByDescending { it.duration })
			.take(MAX_REVISITED)

		val (currentStreak, longestStreak) = calculateStreaks(lifetimeSessions, zone)
		val journeyDao = db.getReaderJourneyDao()
		val journeyAwards = journeyDao.getAllChapterAwards()
		val lifetimeXp = journeyDao.getProfile()?.totalXp ?: 0L
		val journeyStartedDay = journeyAwards
			.asSequence()
			.map { it.firstCompletedAt }
			.filter { it > 0L }
			.minOrNull()
			?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
		val achievementStreak = if (journeyStartedDay == null) {
			0
		} else {
			val journeySessions = db.getStatsDao().getSessions(0L, emptySet()).filter { session ->
				!Instant.ofEpochMilli(session.startedAt).atZone(zone).toLocalDate().isBefore(journeyStartedDay)
			}
			calculateStreaks(journeySessions, zone).second
		}
		val achievements = achievementRepository.refresh(
			longestStreak = achievementStreak,
			allowUnlock = settings.isReaderJourneyEnabled,
		)

		return ReadingStats(
			period = period,
			scope = scope,
			matureMode = matureMode,
			records = built.records,
			otherRecords = built.otherRecords,
			revisited = revisited,
			topGenres = topGenres,
			formatBreakdown = formatBreakdown,
			heatmapDays = heatmap.entries.map { (day, value) ->
				StatsHeatmapDay(
					epochDay = day.toEpochDay(),
					duration = value.duration,
					sessions = value.sessions,
				)
			},
			buckets = starts.mapIndexed { index, start -> StatsBucket(start, durations[index]) },
			bucketUnit = unit,
			totalDuration = totalDuration,
			chapterDuration = chapterDuration,
			chapters = chapters,
			pages = pages,
			titleCount = perTitle.size,
			sessionCount = sessions.size,
			activeDays = activeDays.size,
			currentStreak = currentStreak,
			longestStreak = longestStreak,
			lifetimeXp = lifetimeXp,
			achievements = achievements,
			isJourneyEnabled = settings.isReaderJourneyEnabled,
			privateDuration = built.privateDuration,
			privateTitles = built.privateTitles,
		)
	}

	private fun buildRecords(
		perTitle: Map<Long, MangaAggregate>,
		metadata: Map<Long, StatsTitleMeta>,
		total: Long,
		matureMode: StatsMatureMode,
		scope: StatsContentScope,
	): RecordBuild {
		if (perTitle.isEmpty()) return RecordBuild()

		val direct = ArrayList<StatsRecord>(perTitle.size)
		var privateDuration = 0L
		var privatePages = 0
		var privateChapters = 0
		var privateSessions = 0
		var privateFirst = Long.MAX_VALUE
		var privateTitles = 0

		for ((id, aggregate) in perTitle) {
			val meta = metadata[id] ?: continue
			if (matureMode == StatsMatureMode.PRIVATE && meta.isMature) {
				privateTitles++
				privateDuration += aggregate.duration
				privatePages += aggregate.pages
				privateChapters += aggregate.chapters
				privateSessions += aggregate.sessions
				privateFirst = minOf(privateFirst, aggregate.firstReadAt)
				continue
			}
			direct += aggregate.toRecord(meta.manga, meta.isNovel)
		}

		if (privateTitles > 0) {
			direct += StatsRecord(
				manga = null,
				duration = privateDuration,
				pages = privatePages,
				chapters = privateChapters,
				daysRead = 0,
				sessionCount = privateSessions,
				isPrivate = true,
				isNovel = scope == StatsContentScope.NOVEL,
				firstReadAt = privateFirst.takeUnless { it == Long.MAX_VALUE } ?: 0L,
			)
		}

		direct.sortByDescending { it.duration }
		val records = ArrayList<StatsRecord>()
		val others = ArrayList<StatsRecord>()
		var otherDuration = 0L
		var otherPages = 0
		var otherChapters = 0
		var otherSessions = 0

		direct.forEach { record ->
			val isTooSmall = !record.isPrivate && total > 0L &&
				record.duration.toDouble() / total < OTHER_THRESHOLD
			if (!record.isPrivate && (isTooSmall || records.size >= MAX_TOP_RECORDS)) {
				others += record
				otherDuration += record.duration
				otherPages += record.pages
				otherChapters += record.chapters
				otherSessions += record.sessionCount
			} else {
				records += record
			}
		}
		if (otherDuration > 0L) {
			records += StatsRecord(
				manga = null,
				duration = otherDuration,
				pages = otherPages,
				chapters = otherChapters,
				sessionCount = otherSessions,
				firstReadAt = others.minOfOrNull { it.firstReadAt } ?: 0L,
			)
		}

		return RecordBuild(
			records = records,
			otherRecords = others,
			directRecords = direct,
			privateDuration = privateDuration,
			privateTitles = privateTitles,
		)
	}

	private fun buildGenreInsights(
		titleIds: Set<Long>,
		metadata: Map<Long, StatsTitleMeta>,
		matureMode: StatsMatureMode,
	): List<StatsInsight> {
		val counts = HashMap<String, Int>()
		for (id in titleIds) {
			val meta = metadata[id] ?: continue
			if (matureMode == StatsMatureMode.PRIVATE && meta.isMature) continue
			meta.stored.tags
				.asSequence()
				.mapNotNull(StatsTagClassifier::genreLabel)
				.distinctBy { it.lowercase(Locale.ROOT) }
				.forEach { label -> counts[label] = (counts[label] ?: 0) + 1 }
		}
		return counts.entries
			.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key.lowercase(Locale.ROOT) })
			.take(MAX_INSIGHTS)
			.map { StatsInsight(it.key, it.value) }
	}

	private fun buildFormatInsights(
		titleIds: Set<Long>,
		metadata: Map<Long, StatsTitleMeta>,
		matureMode: StatsMatureMode,
	): List<StatsInsight> {
		val counts = LinkedHashMap<String, Int>()
		for (id in titleIds) {
			val meta = metadata[id] ?: continue
			if (matureMode == StatsMatureMode.PRIVATE && meta.isMature) continue
			val label = StatsTagClassifier.formatLabel(meta.isNovel, meta.stored.tags)
			counts[label] = (counts[label] ?: 0) + 1
		}
		return counts.entries
			.sortedByDescending { it.value }
			.take(MAX_INSIGHTS)
			.map { StatsInsight(it.key, it.value) }
	}

	private fun bucketStarts(
		period: StatsPeriod,
		zone: ZoneId,
		firstSessionAt: Long,
	): Pair<StatsBucketUnit, LongArray> {
		val now = ZonedDateTime.now(zone)
		fun starts(count: Int, unit: StatsBucketUnit, anchor: ZonedDateTime, step: (ZonedDateTime, Long) -> ZonedDateTime) =
			unit to LongArray(count) { i -> step(anchor, -(count - 1L - i)).toInstant().toEpochMilli() }
		return when (period) {
			StatsPeriod.DAY -> starts(24, StatsBucketUnit.HOUR, now.truncatedTo(ChronoUnit.HOURS)) { a, d -> a.plusHours(d) }
			StatsPeriod.WEEK -> starts(7, StatsBucketUnit.DAY, now.toLocalDate().atStartOfDay(zone)) { a, d -> a.plusDays(d) }
			StatsPeriod.MONTH -> starts(30, StatsBucketUnit.DAY, now.toLocalDate().atStartOfDay(zone)) { a, d -> a.plusDays(d) }
			StatsPeriod.MONTHS_3 -> starts(
				13,
				StatsBucketUnit.WEEK,
				now.toLocalDate().with(WeekFields.of(Locale.getDefault()).dayOfWeek(), 1).atStartOfDay(zone),
			) { a, d -> a.plusWeeks(d) }
			StatsPeriod.YEAR -> starts(
				12,
				StatsBucketUnit.MONTH,
				now.toLocalDate().withDayOfMonth(1).atStartOfDay(zone),
			) { a, d -> a.plusMonths(d) }
			StatsPeriod.ALL -> {
				val firstMonth = Instant.ofEpochMilli(firstSessionAt)
					.atZone(zone).toLocalDate().withDayOfMonth(1)
				val thisMonth = now.toLocalDate().withDayOfMonth(1)
				val months = ChronoUnit.MONTHS.between(firstMonth, thisMonth).toInt() + 1
				starts(
					months.coerceAtLeast(1),
					StatsBucketUnit.MONTH,
					thisMonth.atStartOfDay(zone),
				) { a, d -> a.plusMonths(d) }
			}
		}
	}

	private fun calculateStreaks(sessions: List<StatsEntity>, zone: ZoneId): Pair<Int, Int> {
		val days = sessions
			.mapTo(TreeSet()) { Instant.ofEpochMilli(it.startedAt).atZone(zone).toLocalDate() }
		if (days.isEmpty()) return 0 to 0
		var longest = 0
		var run = 0
		var previous: LocalDate? = null
		for (day in days) {
			run = if (previous != null && previous.plusDays(1) == day) run + 1 else 1
			if (run > longest) longest = run
			previous = day
		}
		val today = LocalDate.now(zone)
		val current = if (days.last() == today || days.last() == today.minusDays(1)) run else 0
		return current to longest
	}

	suspend fun getChapterReadingStats(): ChapterReadingStats = db.withTransaction {
		val dao = db.getStatsDao()
		ChapterReadingStats(
			totalDuration = dao.getTotalReadDurationWithChapters(),
			chapters = dao.getTotalReadChapters(),
		)
	}

	suspend fun getTotalPagesRead(mangaId: Long): Int = db.getStatsDao().getReadPagesCount(mangaId)

	suspend fun getMangaTimeline(mangaId: Long): NavigableMap<Long, Int> {
		val entities = db.getStatsDao().findAll(mangaId)
		val map = TreeMap<Long, Int>()
		for (e in entities) map[e.startedAt] = e.pages
		return map
	}

	suspend fun clearStats() {
		db.getStatsDao().clear()
	}

	fun observeHasStats(mangaId: Long): Flow<Boolean> = settings.observeAsFlow(AppSettings.KEY_STATS_ENABLED) {
		isStatsEnabled
	}.flatMapLatest { isEnabled ->
		if (isEnabled) {
			db.getStatsDao().observeRowCount(mangaId).map { it > 0 }
		} else {
			flowOf(false)
		}
	}.distinctUntilChanged()
}

private data class StatsTitleMeta(
	val stored: MangaWithTags,
	val manga: Manga,
	val isNovel: Boolean,
	val isMature: Boolean,
)

private class MangaAggregate {
	var duration: Long = 0L
		private set
	var pages: Int = 0
		private set
	var chapters: Int = 0
		private set
	var sessions: Int = 0
		private set
	var firstReadAt: Long = Long.MAX_VALUE
		private set
	val days = HashSet<LocalDate>()

	fun add(session: StatsEntity, day: LocalDate) {
		duration += session.duration
		pages += session.pages
		chapters += session.chapters
		sessions++
		firstReadAt = minOf(firstReadAt, session.startedAt)
		days += day
	}

	fun toRecord(manga: Manga, isNovel: Boolean) = StatsRecord(
		manga = manga,
		duration = duration,
		pages = pages,
		chapters = chapters,
		daysRead = days.size,
		sessionCount = sessions,
		isNovel = isNovel,
		firstReadAt = firstReadAt.takeUnless { it == Long.MAX_VALUE } ?: 0L,
	)
}

private class DayAggregate {
	var duration: Long = 0L
		private set
	var sessions: Int = 0
		private set

	fun add(session: StatsEntity) {
		duration += session.duration
		sessions++
	}
}

private data class RecordBuild(
	val records: List<StatsRecord> = emptyList(),
	val otherRecords: List<StatsRecord> = emptyList(),
	val directRecords: List<StatsRecord> = emptyList(),
	val privateDuration: Long = 0L,
	val privateTitles: Int = 0,
)

private const val OTHER_THRESHOLD = 0.01
private const val MAX_TOP_RECORDS = 10
private const val MAX_REVISITED = 5
private const val MAX_INSIGHTS = 4

data class ChapterReadingStats(
	val totalDuration: Long,
	val chapters: Int,
)
