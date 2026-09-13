package org.koitharu.kotatsu.explore.ui.adapter

import android.content.Context
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.getSummary
import org.koitharu.kotatsu.core.ui.BaseListAdapter
import org.koitharu.kotatsu.core.ui.list.OnListItemClickListener
import org.koitharu.kotatsu.core.ui.widgets.TipView
import org.koitharu.kotatsu.explore.ui.model.MangaSourceItem
import org.koitharu.kotatsu.list.ui.adapter.ListHeaderClickListener
import org.koitharu.kotatsu.list.ui.adapter.ListItemType
import org.koitharu.kotatsu.list.ui.adapter.emptyStateListAD
import org.koitharu.kotatsu.list.ui.adapter.loadingStateAD
import org.koitharu.kotatsu.list.ui.adapter.tipAD
import org.koitharu.kotatsu.list.ui.model.ListHeader
import org.koitharu.kotatsu.list.ui.model.ListModel
import org.koitharu.kotatsu.list.ui.model.TipModel
import org.koitharu.kotatsu.parsers.model.Manga

enum class ExploreSourceSection {
	MIYORARE,
	THIRD_PARTY,
}

data class ExploreSourceSectionHeaderPayload(
	val section: ExploreSourceSection,
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

	private val headerClickListener = object : ListHeaderClickListener {
		override fun onListHeaderClick(item: ListHeader, view: View) {
			val section = (item.payload as? ExploreSourceSectionHeaderPayload)?.section
			if (section == null) {
				listener.onListHeaderClick(item, view)
				return
			}
			if (!collapsedSourceSections.add(section)) {
				collapsedSourceSections.remove(section)
			}
			refreshSourceSections()
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

		return buildList(items.size + 2) {
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
		if (expanded) addAll(sources)
	}

	private object NoopTipButtonListener : TipView.OnButtonClickListener {
		override fun onPrimaryButtonClick(tipView: TipView) = Unit
		override fun onSecondaryButtonClick(tipView: TipView) = Unit
	}

	private companion object {
		const val MIYORARE_MARKER = "miyorare"
	}
}
