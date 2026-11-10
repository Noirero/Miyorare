package org.koitharu.kotatsu.history.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.plus
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.model.MangaHistory
import org.koitharu.kotatsu.core.model.MangaNovelSection
import org.koitharu.kotatsu.core.parser.MangaDataRepository
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.ListMode
import org.koitharu.kotatsu.core.prefs.observeAsFlow
import org.koitharu.kotatsu.core.prefs.observeAsStateFlow
import org.koitharu.kotatsu.core.ui.model.DateTimeAgo
import org.koitharu.kotatsu.core.ui.util.ReversibleAction
import org.koitharu.kotatsu.core.util.ext.calculateTimeAgo
import org.koitharu.kotatsu.core.util.ext.call
import org.koitharu.kotatsu.core.util.ext.flattenLatest
import org.koitharu.kotatsu.history.data.HistoryRepository
import org.koitharu.kotatsu.favourites.data.EXTRA_FAVOURITE_SPACE
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
import org.koitharu.kotatsu.history.domain.HistoryListQuickFilter
import org.koitharu.kotatsu.history.domain.MarkAsReadUseCase
import org.koitharu.kotatsu.history.domain.model.MangaWithHistory
import org.koitharu.kotatsu.list.domain.ListFilterOption
import org.koitharu.kotatsu.list.domain.ListSortOrder
import org.koitharu.kotatsu.list.domain.MangaListMapper
import org.koitharu.kotatsu.list.domain.QuickFilterListener
import org.koitharu.kotatsu.list.domain.ReadingProgress
import org.koitharu.kotatsu.list.ui.MangaListViewModel
import org.koitharu.kotatsu.list.ui.model.EmptyState
import org.koitharu.kotatsu.list.ui.model.InfoModel
import org.koitharu.kotatsu.list.ui.model.ListHeader
import org.koitharu.kotatsu.list.ui.model.ListModel
import org.koitharu.kotatsu.list.ui.model.LoadingState
import org.koitharu.kotatsu.list.ui.model.TIP_UI_SCALING
import org.koitharu.kotatsu.list.ui.model.uiScalingTip
import org.koitharu.kotatsu.list.ui.model.toErrorState
import org.koitharu.kotatsu.local.data.LocalStorageChanges
import org.koitharu.kotatsu.local.domain.model.LocalManga
import org.koitharu.kotatsu.parsers.model.Manga
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

private const val PAGE_SIZE = 16
private const val HISTORY_MAX_DAYS = 30

internal data class HistoryHeaderAction(val key: String, val loadMore: Boolean = false)

@HiltViewModel
class HistoryListViewModel @Inject constructor(
	private val repository: HistoryRepository,
	private val settings: AppSettings,
	private val mangaListMapper: MangaListMapper,
	private val markAsReadUseCase: MarkAsReadUseCase,
	private val quickFilter: HistoryListQuickFilter,
	private val database: MangaDatabase,
	private val savedStateHandle: SavedStateHandle,
	mangaDataRepository: MangaDataRepository,
	@LocalStorageChanges localStorageChanges: SharedFlow<LocalManga?>,
) : MangaListViewModel(settings, mangaDataRepository, localStorageChanges), QuickFilterListener by quickFilter {

	private val favouriteSpace = FavouriteSpace.fromArgument(
		savedStateHandle[EXTRA_FAVOURITE_SPACE] ?: FavouriteSpace.NORMAL.dbValue,
	)

	private val sortOrder: StateFlow<ListSortOrder> = settings.observeAsStateFlow(
		scope = viewModelScope + Dispatchers.IO,
		key = AppSettings.KEY_HISTORY_ORDER,
		valueProducer = { historySortOrder },
	)

	override val listMode = settings.observeAsStateFlow(
		scope = viewModelScope + Dispatchers.Default,
		key = AppSettings.KEY_LIST_MODE_HISTORY,
		valueProducer = { historyListMode },
	)

	private val isGroupingEnabled = settings.observeAsFlow(
		key = AppSettings.KEY_HISTORY_GROUPING,
		valueProducer = { isHistoryGroupingEnabled },
	).combine(sortOrder) { g, s ->
		g && s.isGroupingSupported()
	}

	/**
	 * Re-evaluates the calendar date exactly at the next local midnight. History grouping is based on
	 * calendar days, not elapsed 24-hour windows, so "Today" becomes "Yesterday" without requiring a
	 * database write, screen reopen, or a wasteful minute-by-minute timer.
	 */
	private val currentDate = flow {
		while (true) {
			val today = LocalDate.now()
			emit(today)
			val zone = ZoneId.systemDefault()
			val nextMidnight = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
			delay((nextMidnight - System.currentTimeMillis()).coerceAtLeast(1_000L))
		}
	}.stateIn(
		scope = viewModelScope + Dispatchers.Default,
		started = SharingStarted.Eagerly,
		initialValue = LocalDate.now(),
	)

	/**
	 * local_index is part of the SQL predicate for History's Downloaded filter. Observe the table
	 * directly so adding/removing on-device files re-runs the query instead of only remapping old rows.
	 * Details observes targeted storage events itself, so this must not clear unrelated snapshots.
	 */
	private val localIndexChanges = database.invalidationTracker.createFlow(
		"local_index",
		emitInitialState = true,
	)

	private val limit = MutableStateFlow(PAGE_SIZE)

	/** Expand/collapse is isolated per Manga/Novel tab and survives navigation/config restoration. */
	private val expandedGroups = MutableStateFlow(
		savedStateHandle.get<ArrayList<String>>(KEY_HISTORY_EXPANDED)?.toSet().orEmpty(),
	)
	private val groupPageLimits = MutableStateFlow<Map<String, Int>>(emptyMap())
	@Volatile private var availableRecentCount: Int? = null
	private val selectedSectionState = MutableStateFlow(
		MangaNovelSection.fromSaved(savedStateHandle[KEY_HISTORY_SECTION]),
	)
	val selectedSection: StateFlow<MangaNovelSection> = selectedSectionState

	fun selectSection(section: MangaNovelSection) {
		if (selectedSectionState.value == section) return
		limit.value = PAGE_SIZE
		savedStateHandle[KEY_HISTORY_SECTION] = section.name
		selectedSectionState.value = section
	}
	private val isPaginationReady = AtomicBoolean(false)

	val isStatsEnabled = settings.observeAsStateFlow(
		scope = viewModelScope + Dispatchers.Default,
		key = AppSettings.KEY_STATS_ENABLED,
		valueProducer = { isStatsEnabled },
	)

	override val content = combine(
		observeHistory(),
		isGroupingEnabled,
		observeListModeWithTriggers(),
		// Paired to stay inside combine's five-flow overload.
		combine(
			settings.observeAsFlow(AppSettings.KEY_INCOGNITO_MODE) { isIncognitoModeEnabled },
			settings.observeAsFlow(AppSettings.KEY_TIPS_CLOSED) { isTipEnabled(TIP_UI_SCALING) },
		) { incognito, isScalingTipVisible -> incognito to isScalingTipVisible },
	) { list, grouped, mode, (incognito, isScalingTipVisible) ->
		// Filters are read here rather than combined in: observeHistory() already re-queries on every
		// filter change, and a second input would render the chips one frame ahead of their results.
		mapList(list, grouped, mode, quickFilter.appliedOptions.value, incognito, isScalingTipVisible)
	}.distinctUntilChanged().onEach {
		isPaginationReady.set(true)
	}.catch { e ->
		emit(listOf(e.toErrorState(canRetry = false)))
	}.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, listOf(LoadingState))

	override fun onRefresh() = Unit

	override fun onRetry() = Unit

	/** Shared with Favourites: dismissing it on either screen dismisses it on both. */
	fun dismissScalingTip() {
		settings.closeTip(TIP_UI_SCALING)
	}

	fun clearHistory(minDate: Instant?) {
		launchJob(Dispatchers.Default) {
			val stringRes = if (minDate == null) {
				repository.clear()
				R.string.history_cleared
			} else {
				repository.deleteAfter(minDate.toEpochMilli())
				R.string.removed_from_history
			}
			onActionDone.call(ReversibleAction(stringRes, null))
		}
	}

	fun removeNotFavorite() {
		launchJob(Dispatchers.Default) {
			repository.deleteNotFavorite()
			onActionDone.call(ReversibleAction(R.string.removed_from_history, null))
		}
	}

	fun removeFromHistory(ids: Set<Long>) {
		if (ids.isEmpty()) {
			return
		}
		launchJob(Dispatchers.Default) {
			val handle = repository.delete(ids)
			onActionDone.call(ReversibleAction(R.string.removed_from_history, handle))
		}
	}

	fun markAsRead(items: Set<Manga>) {
		launchLoadingJob(Dispatchers.Default) {
			markAsReadUseCase(items)
		}
	}


	fun toggleHistoryGroup(key: String) {
		val scoped = "${selectedSectionState.value.name}:$key"
		val next = if (scoped in expandedGroups.value) expandedGroups.value - scoped
		else expandedGroups.value + scoped
		expandedGroups.value = next
		savedStateHandle[KEY_HISTORY_EXPANDED] = ArrayList(next)
	}

	fun requestMoreHistoryGroup(key: String) {
		val scoped = "${selectedSectionState.value.name}:$key"
		groupPageLimits.value = groupPageLimits.value + (
			scoped to ((groupPageLimits.value[scoped] ?: PAGE_SIZE) + PAGE_SIZE)
		)
	}

	fun requestMoreItems() {
		if (availableRecentCount?.let { limit.value >= it } == true) return
		if (isPaginationReady.compareAndSet(true, false)) {
			limit.value += PAGE_SIZE
		}
	}

	private data class HistoryRequest(
		val order: ListSortOrder,
		val filters: Set<ListFilterOption>,
		val section: MangaNovelSection,
		val limit: Int,
		val today: LocalDate,
		val grouped: Boolean,
		val expanded: Set<String>,
		val pageLimits: Map<String, Int>,
	)

	private sealed interface HistorySnapshot {
		data class Plain(val items: List<MangaWithHistory>) : HistorySnapshot
		data class Grouped(
			val recent: List<MangaWithHistory>,
			val overview: HistoryDateOverview,
			val groups: Map<String, List<MangaWithHistory>>,
			val expanded: Set<String>,
			val pageLimits: Map<String, Int>,
			val section: MangaNovelSection,
			val today: LocalDate,
		) : HistorySnapshot
	}

	/** Parent buckets come from lightweight timestamps; only expanded children fetch actual cards. */
	private fun observeHistory() = combine(
		sortOrder,
		combine(quickFilter.appliedOptions.combineWithSettings(), selectedSectionState) { filters, section ->
			filters to section
		},
		limit,
		currentDate,
		combine(localIndexChanges, isGroupingEnabled, expandedGroups, groupPageLimits) { _, grouped, expanded, pages ->
			Triple(grouped, expanded, pages)
		},
	) { order, (filters, section), itemLimit, today, (grouped, expanded, pages) ->
		isPaginationReady.set(false)
		HistoryRequest(order, filters, section, itemLimit, today, grouped, expanded, pages)
	}.map { request ->
		if (!request.grouped || request.order != ListSortOrder.LAST_READ) {
			repository.observeAllWithHistory(request.order, request.filters, request.limit,
				space = favouriteSpace, section = request.section).map { HistorySnapshot.Plain(it) }
		} else {
			val zone = ZoneId.systemDefault()
			val recentStart = request.today.minusDays(7).atStartOfDay(zone).toInstant().toEpochMilli()
			repository.observeHistoryTimestamps(request.filters, favouriteSpace, request.section).map { timestamps ->
				val overview = historyDateOverview(timestamps, request.today, zone)
				val prefix = "${request.section.name}:"
				val expanded = request.expanded.asSequence().filter { it.startsWith(prefix) }
					.map { it.removePrefix(prefix) }.toSet()
				val dayKeys = if ("bucket:middle" in expanded) overview.days.keys
					.sortedDescending().map { "day:$it" } else emptyList()
				val monthKeys = if ("bucket:older" in expanded) overview.months.keys
					.sortedDescending().map { "month:$it" } else emptyList()
				val openKeys = (dayKeys + monthKeys).filter { it in expanded }
				val pages = listOf(
					repository.observeAllWithHistory(request.order, request.filters, request.limit,
						minUpdatedAt = recentStart, space = favouriteSpace, section = request.section),
				) + openKeys.map { key ->
					val window = checkNotNull(historyGroupWindow(key, request.today, zone))
					repository.observeAllWithHistory(request.order, request.filters,
						request.pageLimits[prefix + key] ?: PAGE_SIZE,
						minUpdatedAt = window.start, space = favouriteSpace,
						section = request.section, maxUpdatedAtExclusive = window.endExclusive)
				}
				combine(pages) { results ->
					HistorySnapshot.Grouped(results[0], overview, openKeys.zip(results.drop(1)).toMap(),
						expanded, request.pageLimits, request.section, request.today)
				}
			}.flattenLatest()
		}
	}.flattenLatest().onEach { snapshot ->
		availableRecentCount = if (snapshot is HistorySnapshot.Grouped) snapshot.overview.recentCount else null
	}

	private suspend fun mapGroupedHistory(
		snapshot: HistorySnapshot.Grouped,
		mode: ListMode,
		filters: Set<ListFilterOption>,
		isIncognito: Boolean,
		isScalingTipVisible: Boolean,
	): List<ListModel> {
		if (snapshot.overview.isEmpty) {
			return listOfNotNull(quickFilter.filterItem(filters),
				getEmptyState(hasFilters = filters.isNotEmpty()))
		}
		val result = ArrayList<ListModel>()
		if (isScalingTipVisible) result += uiScalingTip
		quickFilter.filterItem(filters)?.let(result::add)
		if (isIncognito) result += InfoModel(
			key = AppSettings.KEY_INCOGNITO_MODE,
			title = R.string.incognito_mode,
			text = R.string.incognito_mode_hint,
			icon = R.drawable.ic_incognito,
		)
		var previous: ListHeader? = null
		for ((manga, history) in snapshot.recent) {
			val header = readDateHeader(history.updatedAt, snapshot.today) ?: ListHeader(R.string.unknown)
			if (header != previous) result += header
			previous = header
			result += mangaListMapper.toListModel(manga, mode)
		}
		if (snapshot.overview.days.isNotEmpty()) {
			val open = "bucket:middle" in snapshot.expanded
			result += ListHeader(R.string.history_days_8_30,
				buttonTextRes = if (open) R.string.collapse else R.string.expand,
				payload = HistoryHeaderAction("bucket:middle"))
			if (open) for ((day, count) in snapshot.overview.days.toSortedMap(reverseOrder())) {
				val key = "day:$day"
				val childOpen = key in snapshot.expanded
				val age = ChronoUnit.DAYS.between(day, snapshot.today).toInt()
				result += ListHeader(DateTimeAgo.DaysAgo(age),
					buttonTextRes = if (childOpen) R.string.collapse else R.string.expand,
					payload = HistoryHeaderAction(key))
				if (childOpen) {
					val rows = snapshot.groups[key].orEmpty()
					for ((manga, _) in rows) result += mangaListMapper.toListModel(manga, mode)
					if (rows.size < count) result += ListHeader(R.string.more,
						buttonTextRes = R.string.more, payload = HistoryHeaderAction(key, loadMore = true),
						badge = (count - rows.size).toString(), itemKey = "history-more:$key")
				}
			}
		}
		if (snapshot.overview.months.isNotEmpty()) {
			val open = "bucket:older" in snapshot.expanded
			result += ListHeader(R.string.history_older_than_30,
				buttonTextRes = if (open) R.string.collapse else R.string.expand,
				payload = HistoryHeaderAction("bucket:older"))
			if (open) for ((month, count) in snapshot.overview.months.toSortedMap(reverseOrder())) {
				val key = "month:$month"
				val childOpen = key in snapshot.expanded
				result += ListHeader(month.format(DateTimeFormatter.ofPattern("LLLL yyyy", Locale.getDefault())),
					buttonTextRes = if (childOpen) R.string.collapse else R.string.expand,
					payload = HistoryHeaderAction(key))
				if (childOpen) {
					val rows = snapshot.groups[key].orEmpty()
					for ((manga, _) in rows) result += mangaListMapper.toListModel(manga, mode)
					if (rows.size < count) result += ListHeader(R.string.more,
						buttonTextRes = R.string.more, payload = HistoryHeaderAction(key, loadMore = true),
						badge = (count - rows.size).toString(), itemKey = "history-more:$key")
				}
			}
		}
		return result
	}

	private suspend fun mapList(
		snapshot: HistorySnapshot,
		grouped: Boolean,
		mode: ListMode,
		filters: Set<ListFilterOption>,
		isIncognito: Boolean,
		isScalingTipVisible: Boolean,
	): List<ListModel> {
		if (snapshot is HistorySnapshot.Grouped) {
			return mapGroupedHistory(snapshot, mode, filters, isIncognito, isScalingTipVisible)
		}
		val list = (snapshot as HistorySnapshot.Plain).items
		if (list.isEmpty()) {
			return if (filters.isEmpty()) {
				listOf(getEmptyState(hasFilters = false))
			} else {
				listOfNotNull(quickFilter.filterItem(filters), getEmptyState(hasFilters = true))
			}
		}
		val result = ArrayList<ListModel>((if (grouped) (list.size * 1.4).toInt() else list.size) + 3)
		if (isScalingTipVisible) {
			result += uiScalingTip
		}
		quickFilter.filterItem(filters)?.let(result::add)
		if (isIncognito) {
			result += InfoModel(
				key = AppSettings.KEY_INCOGNITO_MODE,
				title = R.string.incognito_mode,
				text = R.string.incognito_mode_hint,
				icon = R.drawable.ic_incognito,
			)
		}
		val order = sortOrder.value
		val today = currentDate.value
		var prevHeader: ListHeader? = null
		var isEmpty = true
		for ((manga, history) in list) {
			isEmpty = false
			if (grouped) {
				val header = history.header(order, today)
				if (header != prevHeader) {
					if (header != null) {
						result += header
					}
					prevHeader = header
				}
			}
			result += mangaListMapper.toListModel(manga, mode)
		}
		if (filters.isNotEmpty() && isEmpty) {
			result += getEmptyState(hasFilters = true)
		}
		return result
	}

	private fun MangaHistory.header(order: ListSortOrder, today: LocalDate): ListHeader? = when (order.type) {
		ListSortOrder.Type.LAST_READ -> readDateHeader(updatedAt, today) ?: ListHeader(R.string.unknown)

		ListSortOrder.Type.DATE_ADDED -> calculateTimeAgo(createdAt)?.let {
			ListHeader(it)
		} ?: ListHeader(R.string.unknown)

		ListSortOrder.Type.PROGRESS -> ListHeader(
			when {
				ReadingProgress.isCompleted(percent) -> R.string.status_completed
				percent in 0f..0.01f -> R.string.status_planned
				percent in 0f..1f -> R.string.status_reading
				else -> R.string.unknown
			},
		)

		else -> null
	}

	private fun readDateHeader(instant: Instant, today: LocalDate): ListHeader? {
		val readDate = instant.atZone(ZoneId.systemDefault()).toLocalDate()
		val daysAgo = ChronoUnit.DAYS.between(readDate, today)
		return when {
			daysAgo < 0L -> null
			daysAgo == 0L -> ListHeader(R.string.today)
			daysAgo == 1L -> ListHeader(R.string.yesterday)
			daysAgo == 2L -> ListHeader(R.string.day_before_yesterday)
			daysAgo in 3L..HISTORY_MAX_DAYS.toLong() -> ListHeader(DateTimeAgo.DaysAgo(daysAgo.toInt()))
			else -> null
		}
	}

	private fun getEmptyState(hasFilters: Boolean) = if (hasFilters) {
		EmptyState(
			icon = R.drawable.ic_empty_history,
			textPrimary = R.string.nothing_found,
			textSecondary = R.string.text_empty_holder_secondary_filtered,
			actionStringRes = R.string.reset_filter,
		)
	} else {
		EmptyState(
			icon = R.drawable.ic_empty_history,
			textPrimary = R.string.text_history_holder_primary,
			textSecondary = R.string.text_history_holder_secondary,
			actionStringRes = 0,
		)
	}
}

private const val KEY_HISTORY_SECTION = "history_manga_novel_section"
private const val KEY_HISTORY_EXPANDED = "history_expanded_groups"
