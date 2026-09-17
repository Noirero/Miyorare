package org.koitharu.kotatsu.explore.ui.adapter

import android.content.Context
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.getLanguageCode
import org.koitharu.kotatsu.core.model.getSummary
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
	MIYORARE,
	THIRD_PARTY,
}

data class ExploreSourceSectionHeaderPayload(
	val section: ExploreSourceSection,
	val expanded: Boolean,
)

data class ExploreSourceLanguageGroup(
	val section: ExploreSourceSection,
	val language: String,
)

data class ExploreSourceLanguageHeaderPayload(
	val group: ExploreSourceLanguageGroup,
	val expanded: Boolean,
)

class ExploreAdapter(
	private val listener: ExploreListEventListener,
	clickListener: OnListItemClickListener<MangaSourceItem>,
	mangaClickListener: OnListItemClickListener<Manga>,
	onTipClose: (TipModel) -> Unit,
) : BaseListAdapter<ListModel>() {

	private var rawItems: List<ListModel> = emptyList()
	private var hostContext: Context? = null
	private val collapsedSourceSections = mutableSetOf<ExploreSourceSection>()
	private val expandedLanguageGroups = mutableSetOf<ExploreSourceLanguageGroup>()
	private val initializedLanguageSections = mutableSetOf<ExploreSourceSection>()

	private val headerClickListener = object : ListHeaderClickListener {
		override fun onListHeaderClick(item: ListHeader, view: View) {
			when (val payload = item.payload) {
				is ExploreSourceSectionHeaderPayload -> {
					if (!collapsedSourceSections.add(payload.section)) {
						collapsedSourceSections.remove(payload.section)
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
		super.emit(buildDisplayedItems(rawItems))
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

	private fun refreshSourceSections() {
		if (hostContext == null || rawItems.isEmpty()) return
		setItems(buildDisplayedItems(rawItems))
	}

	private fun buildDisplayedItems(items: List<ListModel>): List<ListModel> {
		val context = hostContext ?: return items
		val sources = items.filterIsInstance<MangaSourceItem>()
		if (sources.isEmpty()) return items

		val (miyorare, thirdParty) = sources.partition { source ->
			source.source.getSummary(context)?.contains(MIYORARE_MARKER, ignoreCase = true) == true
		}
		val trailingItems = items.filterNot { item -> item is MangaSourceItem || item is ListHeader }

		return buildList(items.size + 8) {
			appendSourceSection(context, ExploreSourceSection.MIYORARE, miyorare)
			appendSourceSection(context, ExploreSourceSection.THIRD_PARTY, thirdParty)
			addAll(trailingItems)
		}
	}

	private fun MutableList<ListModel>.appendSourceSection(
		context: Context,
		section: ExploreSourceSection,
		sources: List<MangaSourceItem>,
	) {
		if (sources.isEmpty()) return
		val expanded = section !in collapsedSourceSections
		val title = when (section) {
			ExploreSourceSection.MIYORARE -> "MIYORARE"
			ExploreSourceSection.THIRD_PARTY -> {
				val locale = context.resources.configuration.locales[0]
				if (locale.language.equals("id", ignoreCase = true) || locale.language.equals("in", ignoreCase = true)) {
					"PIHAK KETIGA"
				} else {
					context.getString(R.string.external_source)
				}
			}
		}
		add(
			ListHeader(
				text = title,
				payload = ExploreSourceSectionHeaderPayload(section, expanded),
			),
		)
		if (!expanded) return

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

		if (section !in initializedLanguageSections) {
			val initialLanguage = languageGroups.firstOrNull { it.key == preferredLanguage }?.key
				?: languageGroups.firstOrNull()?.key
			if (initialLanguage != null) {
				expandedLanguageGroups += ExploreSourceLanguageGroup(section, initialLanguage)
			}
			initializedLanguageSections += section
		}

		languageGroups.forEach { (language, languageSources) ->
			val group = ExploreSourceLanguageGroup(section, language)
			val languageExpanded = group in expandedLanguageGroups
			val flag = getExternalExtensionLanguageFlag(language)
			val languageTitle = buildString {
				append(getExternalExtensionLanguageDisplayName(language))
				if (flag.isNotBlank()) {
					append(' ')
					append(flag)
				}
				append(" · ")
				append(languageSources.size)
			}
			add(
				ListHeader(
					text = languageTitle,
					payload = ExploreSourceLanguageHeaderPayload(group, languageExpanded),
				),
			)
			if (languageExpanded) addAll(languageSources)
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
		const val MIYORARE_MARKER = "miyorare"
	}
}
