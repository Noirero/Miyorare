package org.koitharu.kotatsu.explore.ui.adapter

import android.content.Context
import android.view.View
import androidx.appcompat.widget.PopupMenu
import androidx.recyclerview.widget.RecyclerView
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.getLanguageCode
import org.koitharu.kotatsu.core.ui.BaseListAdapter
import org.koitharu.kotatsu.core.ui.list.OnListItemClickListener
import org.koitharu.kotatsu.core.ui.widgets.TipView
import org.koitharu.kotatsu.explore.ui.model.MangaSourceItem
import org.koitharu.kotatsu.extensions.runtime.getExternalExtensionLanguageDisplayName
import org.koitharu.kotatsu.extensions.runtime.getExternalExtensionLanguageFlag
import org.koitharu.kotatsu.list.ui.adapter.ListHeaderClickListener
import org.koitharu.kotatsu.list.ui.adapter.ListItemType
import org.koitharu.kotatsu.list.ui.adapter.emptyStateListAD
import org.koitharu.kotatsu.list.ui.adapter.loadingStateAD
import org.koitharu.kotatsu.list.ui.adapter.tipAD
import org.koitharu.kotatsu.list.ui.model.ListHeader
import org.koitharu.kotatsu.list.ui.model.ListModel
import org.koitharu.kotatsu.list.ui.model.TipModel
import org.koitharu.kotatsu.parsers.model.Manga
import java.util.Locale

enum class ExploreSourceSection {
	MIYORARE_PACKS,
	EXTENSION_STORE,
	PLUGINS,
}

data class ExploreSourceSectionHeaderPayload(
	val section: ExploreSourceSection,
	val expanded: Boolean,
	val sourceCount: Int,
)

data class ExplorePinnedHeaderPayload(
	val expanded: Boolean,
	val sourceCount: Int,
)

data class ExploreSourceOriginHeaderPayload(
	val origin: ExploreSourceOrigin,
	val expanded: Boolean,
	val sourceCount: Int,
)

data class ExploreSourceLanguageScope(
	val section: ExploreSourceSection,
	val origin: ExploreSourceOrigin?,
)

data class ExploreSourceLanguageGroup(
	val scope: ExploreSourceLanguageScope,
	val language: String,
)

data class ExploreSourceLanguageHeaderPayload(
	val group: ExploreSourceLanguageGroup,
	val expanded: Boolean,
	val sourceCount: Int,
)

class ExploreAdapter(
	private val listener: ExploreListEventListener,
	clickListener: OnListItemClickListener<MangaSourceItem>,
	mangaClickListener: OnListItemClickListener<Manga>,
	onTipClose: (TipModel) -> Unit,
) : BaseListAdapter<ListModel>() {

	private var rawItems: List<ListModel> = emptyList()
	private var hostContext: Context? = null
	private var selectedOrigin = ExploreSourceOriginFilter.ALL
	private var selectedLanguage: String? = null
	private var pinnedSectionExpanded = true
	private val collapsedSourceSections = mutableSetOf<ExploreSourceSection>()
	private val collapsedPluginOrigins = mutableSetOf<ExploreSourceOrigin>()
	private val expandedLanguageGroups = mutableSetOf<ExploreSourceLanguageGroup>()
	private val initializedLanguageScopes = mutableSetOf<ExploreSourceLanguageScope>()
	private var stickyLanguageTitles: List<CharSequence?> = emptyList()

	private val headerClickListener = object : ListHeaderClickListener {
		override fun onListHeaderClick(item: ListHeader, view: View) {
			when (val payload = item.payload) {
				is ExplorePinnedHeaderPayload -> {
					pinnedSectionExpanded = !pinnedSectionExpanded
					refreshSourceSections()
				}

				is ExploreSourceSectionHeaderPayload -> {
					if (!collapsedSourceSections.add(payload.section)) {
						collapsedSourceSections.remove(payload.section)
					}
					refreshSourceSections()
				}

				is ExploreSourceOriginHeaderPayload -> {
					if (!collapsedPluginOrigins.add(payload.origin)) {
						collapsedPluginOrigins.remove(payload.origin)
					}
					refreshSourceSections()
				}

				is ExploreSourceLanguageHeaderPayload -> {
					if (!expandedLanguageGroups.add(payload.group)) {
						expandedLanguageGroups.remove(payload.group)
					}
					refreshSourceSections()
				}

				else -> listener.onListHeaderClick(item, view)
			}
		}
	}

	init {
		addDelegate(ListItemType.EXPLORE_BUTTONS, exploreButtonsAD(listener))
		addDelegate(
			ListItemType.EXPLORE_SUGGESTION,
			exploreRecommendationItemAD(mangaClickListener),
		)
		addDelegate(
			ListItemType.EXPLORE_SOURCE_FILTERS,
			exploreSourceFiltersAD(::showOriginFilterMenu, ::showLanguageFilterMenu),
		)
		addDelegate(ListItemType.HEADER, exploreListHeaderAD(headerClickListener))
		addDelegate(ListItemType.EXPLORE_SOURCE_LIST, exploreSourceListItemAD(clickListener))
		addDelegate(ListItemType.EXPLORE_SOURCE_GRID, exploreSourceGridItemAD(clickListener))
		addDelegate(ListItemType.STATE_EMPTY, emptyStateListAD(listener))
		addDelegate(ListItemType.STATE_LOADING, loadingStateAD())
		// Informational footer note — explains per-source language switching, dismissable.
		addDelegate(ListItemType.TIP, tipAD(NoopTipButtonListener, onTipClose))
	}

	override suspend fun emit(value: List<ListModel>?) {
		rawItems = value.orEmpty()
		val displayed = buildDisplayedItems(rawItems)
		super.emit(displayed)
		rebuildStickyLanguageTitles(displayed)
	}

	override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
		super.onAttachedToRecyclerView(recyclerView)
		hostContext = recyclerView.context
		refreshSourceSections()
	}

	override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
		if (hostContext === recyclerView.context) {
			hostContext = null
		}
		super.onDetachedFromRecyclerView(recyclerView)
	}

	/**
	 * Returns the language group that owns the first visible source row. Section/filter headers stop
	 * inheritance, and the real language header hides the sticky copy while it is itself visible.
	 */
	fun getStickyLanguageTitle(firstVisiblePosition: Int): CharSequence? =
		stickyLanguageTitles.getOrNull(firstVisiblePosition)

	private fun refreshSourceSections() {
		if (hostContext == null || rawItems.isEmpty()) return
		val displayed = buildDisplayedItems(rawItems)
		setItems(displayed)
		rebuildStickyLanguageTitles(displayed)
	}

	private fun rebuildStickyLanguageTitles(displayed: List<ListModel>) {
		val context = hostContext
		if (context == null || displayed.isEmpty()) {
			stickyLanguageTitles = emptyList()
			return
		}
		var currentTitle: CharSequence? = null
		stickyLanguageTitles = displayed.map { model ->
			if (model is ExploreSourceFiltersItem) {
				currentTitle = null
				return@map null
			}
			val header = model as? ListHeader
			when (header?.payload) {
				is ExploreSourceLanguageHeaderPayload -> {
					currentTitle = header.getText(context)
					null
				}
				is ExplorePinnedHeaderPayload,
				is ExploreSourceSectionHeaderPayload,
				is ExploreSourceOriginHeaderPayload,
				-> {
					currentTitle = null
					null
				}
				else -> currentTitle
			}
		}
	}

	private fun buildDisplayedItems(items: List<ListModel>): List<ListModel> {
		val context = hostContext ?: return items
		val allSources = items.filterIsInstance<MangaSourceItem>()
		if (allSources.isEmpty()) return items

		if (selectedOrigin != ExploreSourceOriginFilter.ALL &&
			allSources.none { it.exploreSourceOrigin().matches(selectedOrigin) }
		) {
			selectedOrigin = ExploreSourceOriginFilter.ALL
		}
		val originFiltered = allSources.filter { it.exploreSourceOrigin().matches(selectedOrigin) }
		val availableLanguages = originFiltered
			.map { item -> normalizeLanguageCode(item.source.mangaSource.getLanguageCode()) }
			.toSet()
		selectedLanguage?.let { language ->
			if (language !in availableLanguages) selectedLanguage = null
		}
		val filteredSources = selectedLanguage?.let { language ->
			originFiltered.filter { item ->
				normalizeLanguageCode(item.source.mangaSource.getLanguageCode()) == language
			}
		} ?: originFiltered
		val pinned = filteredSources.filter { it.source.isPinned }
		val sources = filteredSources.filterNot { it.source.isPinned }
		val miyorare = sources.filter { it.exploreSourceOrigin().family == ExploreSourceFamily.MIYORARE_PACKS }
		val store = sources.filter { it.exploreSourceOrigin().family == ExploreSourceFamily.EXTENSION_STORE }
		val plugins = sources.filter { it.exploreSourceOrigin().family == ExploreSourceFamily.PLUGINS }
		val trailingItems = items.filterNot { item -> item is MangaSourceItem || item is ListHeader }

		return buildList(items.size + 18) {
			add(ExploreSourceFiltersItem(selectedOrigin, selectedLanguage))
			appendPinnedSection(pinned)
			appendSourceSection(context, ExploreSourceSection.MIYORARE_PACKS, miyorare)
			appendSourceSection(context, ExploreSourceSection.EXTENSION_STORE, store)
			appendSourceSection(context, ExploreSourceSection.PLUGINS, plugins)
			addAll(trailingItems)
		}
	}

	private fun MutableList<ListModel>.appendPinnedSection(sources: List<MangaSourceItem>) {
		if (sources.isEmpty()) return
		add(
			ListHeader(
				textRes = R.string.pinned_sources,
				payload = ExplorePinnedHeaderPayload(
					expanded = pinnedSectionExpanded,
					sourceCount = sources.size,
				),
			),
		)
		if (pinnedSectionExpanded) addAll(sources)
	}

	private fun MutableList<ListModel>.appendSourceSection(
		context: Context,
		section: ExploreSourceSection,
		sources: List<MangaSourceItem>,
	) {
		if (sources.isEmpty()) return
		val expanded = section !in collapsedSourceSections
		val family = when (section) {
			ExploreSourceSection.MIYORARE_PACKS -> ExploreSourceFamily.MIYORARE_PACKS
			ExploreSourceSection.EXTENSION_STORE -> ExploreSourceFamily.EXTENSION_STORE
			ExploreSourceSection.PLUGINS -> ExploreSourceFamily.PLUGINS
		}
		add(
			ListHeader(
				textRes = family.titleRes(),
				payload = ExploreSourceSectionHeaderPayload(section, expanded, sources.size),
			),
		)
		if (!expanded) return

		if (section == ExploreSourceSection.PLUGINS) {
			appendPluginOrigins(context, sources)
		} else {
			appendLanguageGroups(context, ExploreSourceLanguageScope(section, null), sources)
		}
	}

	private fun MutableList<ListModel>.appendPluginOrigins(
		context: Context,
		sources: List<MangaSourceItem>,
	) {
		PLUGIN_ORIGIN_ORDER.forEach { origin ->
			val originSources = sources.filter { it.exploreSourceOrigin() == origin }
			if (originSources.isEmpty()) return@forEach
			val expanded = origin !in collapsedPluginOrigins
			add(
				ListHeader(
					textRes = origin.titleRes(),
					payload = ExploreSourceOriginHeaderPayload(origin, expanded, originSources.size),
				),
			)
			if (expanded) {
				appendLanguageGroups(
					context = context,
					scope = ExploreSourceLanguageScope(ExploreSourceSection.PLUGINS, origin),
					sources = originSources,
				)
			}
		}
	}

	private fun MutableList<ListModel>.appendLanguageGroups(
		context: Context,
		scope: ExploreSourceLanguageScope,
		sources: List<MangaSourceItem>,
	) {
		if (selectedLanguage != null) {
			addAll(sources)
			return
		}

		val preferredLanguage = normalizeLanguageCode(context.resources.configuration.locales[0].language)
		val languageGroups = sources
			.groupBy { item -> normalizeLanguageCode(item.source.mangaSource.getLanguageCode()) }
			.entries
			.sortedWith(
				compareBy<Map.Entry<String, List<MangaSourceItem>>>(
					{ if (it.key == preferredLanguage) 0 else 1 },
					{ getExternalExtensionLanguageDisplayName(it.key) },
				),
			)

		if (scope !in initializedLanguageScopes) {
			val initialLanguage = languageGroups.firstOrNull { it.key == preferredLanguage }?.key
				?: languageGroups.firstOrNull()?.key
			if (initialLanguage != null) {
				expandedLanguageGroups += ExploreSourceLanguageGroup(scope, initialLanguage)
			}
			initializedLanguageScopes += scope
		}

		languageGroups.forEach { (language, languageSources) ->
			val group = ExploreSourceLanguageGroup(scope, language)
			val languageExpanded = group in expandedLanguageGroups
			val flag = getExternalExtensionLanguageFlag(language)
			val languageTitle = buildString {
				if (flag.isNotBlank()) {
					append(flag)
					append(' ')
				}
				append(getExternalExtensionLanguageDisplayName(language))
			}
			add(
				ListHeader(
					text = languageTitle,
					payload = ExploreSourceLanguageHeaderPayload(
						group = group,
						expanded = languageExpanded,
						sourceCount = languageSources.size,
					),
				),
			)
			if (languageExpanded) addAll(languageSources)
		}
	}

	private fun showOriginFilterMenu(anchor: View) {
		val context = anchor.context
		val origins = rawItems
			.filterIsInstance<MangaSourceItem>()
			.mapTo(linkedSetOf()) { it.exploreSourceOrigin() }
		if (origins.isEmpty()) return

		PopupMenu(context, anchor).apply {
			fun addFilter(id: Int, order: Int, filter: ExploreSourceOriginFilter) {
				menu.add(ORIGIN_FILTER_MENU_GROUP_ID, id, order, context.getString(filter.titleRes())).apply {
					isCheckable = true
					isChecked = selectedOrigin == filter
				}
			}

			addFilter(ORIGIN_FILTER_ALL_ID, 0, ExploreSourceOriginFilter.ALL)
			if (origins.any { it.family == ExploreSourceFamily.MIYORARE_PACKS }) {
				addFilter(ORIGIN_FILTER_MIYORARE_ID, 1, ExploreSourceOriginFilter.MIYORARE_PACKS)
			}
			if (origins.any { it.family == ExploreSourceFamily.EXTENSION_STORE }) {
				addFilter(ORIGIN_FILTER_STORE_ID, 2, ExploreSourceOriginFilter.EXTENSION_STORE)
			}
			if (origins.any { it.family == ExploreSourceFamily.PLUGINS }) {
				addFilter(ORIGIN_FILTER_PLUGINS_ID, 3, ExploreSourceOriginFilter.PLUGINS)
				val pluginMenu = menu.addSubMenu(context.getString(R.string.explore_source_family_plugins))
				PLUGIN_ORIGIN_ORDER.forEachIndexed { index, origin ->
					if (origin !in origins) return@forEachIndexed
					val filter = origin.toOriginFilter()
					pluginMenu.add(
						ORIGIN_FILTER_MENU_GROUP_ID,
						ORIGIN_FILTER_PLUGIN_ID_BASE + index,
						index,
						context.getString(origin.titleRes()),
					).apply {
						isCheckable = true
						isChecked = selectedOrigin == filter
					}
				}
			}
			setOnMenuItemClickListener { menuItem ->
				val newFilter = when (menuItem.itemId) {
					ORIGIN_FILTER_ALL_ID -> ExploreSourceOriginFilter.ALL
					ORIGIN_FILTER_MIYORARE_ID -> ExploreSourceOriginFilter.MIYORARE_PACKS
					ORIGIN_FILTER_STORE_ID -> ExploreSourceOriginFilter.EXTENSION_STORE
					ORIGIN_FILTER_PLUGINS_ID -> ExploreSourceOriginFilter.PLUGINS
					in ORIGIN_FILTER_PLUGIN_ID_BASE until ORIGIN_FILTER_PLUGIN_ID_BASE + PLUGIN_ORIGIN_ORDER.size -> {
						PLUGIN_ORIGIN_ORDER[menuItem.itemId - ORIGIN_FILTER_PLUGIN_ID_BASE].toOriginFilter()
					}
					else -> selectedOrigin
				}
				if (newFilter != selectedOrigin) {
					selectedOrigin = newFilter
					collapsedSourceSections.clear()
					collapsedPluginOrigins.clear()
					initializedLanguageScopes.clear()
					expandedLanguageGroups.clear()
					refreshSourceSections()
				}
				true
			}
			show()
		}
	}

	private fun showLanguageFilterMenu(anchor: View) {
		val context = anchor.context
		val preferredLanguage = normalizeLanguageCode(context.resources.configuration.locales[0].language)
		val languages = rawItems
			.filterIsInstance<MangaSourceItem>()
			.filter { it.exploreSourceOrigin().matches(selectedOrigin) }
			.map { item -> normalizeLanguageCode(item.source.mangaSource.getLanguageCode()) }
			.distinct()
			.sortedWith(
				compareBy<String>(
					{ if (it == preferredLanguage) 0 else 1 },
					{ getExternalExtensionLanguageDisplayName(it) },
				),
			)
		if (languages.isEmpty()) return

		PopupMenu(context, anchor).apply {
			menu.add(
				LANGUAGE_FILTER_MENU_GROUP_ID,
				LANGUAGE_FILTER_ALL_ID,
				0,
				context.getString(R.string.explore_content_filter_all),
			).apply {
				isCheckable = true
				isChecked = selectedLanguage == null
			}
			languages.forEachIndexed { index, language ->
				menu.add(
					LANGUAGE_FILTER_MENU_GROUP_ID,
					LANGUAGE_FILTER_LANGUAGE_ID_BASE + index,
					index + 1,
					buildLanguageLabel(language),
				).apply {
					isCheckable = true
					isChecked = selectedLanguage == language
				}
			}
			menu.setGroupCheckable(LANGUAGE_FILTER_MENU_GROUP_ID, true, true)
			setOnMenuItemClickListener { menuItem ->
				val newLanguage = when (menuItem.itemId) {
					LANGUAGE_FILTER_ALL_ID -> null
					else -> languages.getOrNull(menuItem.itemId - LANGUAGE_FILTER_LANGUAGE_ID_BASE)
				}
				if (newLanguage != selectedLanguage) {
					selectedLanguage = newLanguage
					collapsedSourceSections.clear()
					collapsedPluginOrigins.clear()
					refreshSourceSections()
				}
				true
			}
			show()
		}
	}

	private fun ExploreSourceOrigin.toOriginFilter(): ExploreSourceOriginFilter = when (this) {
		ExploreSourceOrigin.MIYORARE_PACK -> ExploreSourceOriginFilter.MIYORARE_PACKS
		ExploreSourceOrigin.EXTENSION_STORE -> ExploreSourceOriginFilter.EXTENSION_STORE
		ExploreSourceOrigin.UMA -> ExploreSourceOriginFilter.UMA
		ExploreSourceOrigin.GEKKOUSHI -> ExploreSourceOriginFilter.GEKKOUSHI
		ExploreSourceOrigin.LNREADER -> ExploreSourceOriginFilter.LNREADER
		ExploreSourceOrigin.CUSTOM_PLUGIN -> ExploreSourceOriginFilter.CUSTOM_PLUGIN
	}

	private fun buildLanguageLabel(language: String): String {
		val flag = getExternalExtensionLanguageFlag(language)
		return buildString {
			if (flag.isNotBlank()) {
				append(flag)
				append(' ')
			}
			append(getExternalExtensionLanguageDisplayName(language))
		}
	}

	private fun normalizeLanguageCode(language: String?): String {
		return when (val normalized = language?.lowercase(Locale.ROOT).orEmpty()) {
			"in" -> "id"
			else -> normalized.ifBlank { "other" }
		}
	}

	private object NoopTipButtonListener : TipView.OnButtonClickListener {
		override fun onPrimaryButtonClick(tipView: TipView) = Unit
		override fun onSecondaryButtonClick(tipView: TipView) = Unit
	}

	private companion object {
		val PLUGIN_ORIGIN_ORDER = listOf(
			ExploreSourceOrigin.UMA,
			ExploreSourceOrigin.GEKKOUSHI,
			ExploreSourceOrigin.LNREADER,
			ExploreSourceOrigin.CUSTOM_PLUGIN,
		)

		const val ORIGIN_FILTER_MENU_GROUP_ID = 0x4D58
		const val ORIGIN_FILTER_ALL_ID = 10
		const val ORIGIN_FILTER_MIYORARE_ID = 11
		const val ORIGIN_FILTER_STORE_ID = 12
		const val ORIGIN_FILTER_PLUGINS_ID = 13
		const val ORIGIN_FILTER_PLUGIN_ID_BASE = 20
		const val LANGUAGE_FILTER_MENU_GROUP_ID = 0x4D59
		const val LANGUAGE_FILTER_ALL_ID = 1
		const val LANGUAGE_FILTER_LANGUAGE_ID_BASE = 100
	}
}
