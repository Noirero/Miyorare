package org.koitharu.kotatsu.search.ui.multi.adapter

import android.annotation.SuppressLint
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView.RecycledViewPool
import com.hannesdorfmann.adapterdelegates4.ListDelegationAdapter
import com.hannesdorfmann.adapterdelegates4.dsl.adapterDelegateViewBinding
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.UnknownMangaSource
import org.koitharu.kotatsu.core.ui.list.AdapterDelegateClickListenerAdapter
import org.koitharu.kotatsu.core.ui.list.OnListItemClickListener
import org.koitharu.kotatsu.core.ui.list.decor.SpacingItemDecoration
import org.koitharu.kotatsu.core.util.ext.getDisplayMessage
import org.koitharu.kotatsu.core.util.ext.textAndVisible
import org.koitharu.kotatsu.databinding.ItemListGroupBinding
import org.koitharu.kotatsu.list.ui.MangaSelectionDecoration
import org.koitharu.kotatsu.list.ui.adapter.MangaDetailsClickListener
import org.koitharu.kotatsu.list.ui.adapter.mangaGridItemAD
import org.koitharu.kotatsu.list.ui.model.ModernCoverContext
import org.koitharu.kotatsu.list.ui.model.ListModel
import org.koitharu.kotatsu.list.ui.model.MangaListModel
import org.koitharu.kotatsu.list.ui.size.ItemSizeResolver
import org.koitharu.kotatsu.search.ui.multi.GlobalSearchChrome
import org.koitharu.kotatsu.search.ui.multi.SearchResultsListModel
import org.koitharu.kotatsu.search.ui.multi.SearchSourceStatus

@SuppressLint("NotifyDataSetChanged")
fun searchResultsAD(
	sharedPool: RecycledViewPool,
	sizeResolver: ItemSizeResolver,
	selectionDecoration: MangaSelectionDecoration,
	listener: MangaDetailsClickListener,
	itemClickListener: OnListItemClickListener<SearchResultsListModel>,
) = adapterDelegateViewBinding<SearchResultsListModel, ListModel, ItemListGroupBinding>(
	{ layoutInflater, parent -> ItemListGroupBinding.inflate(layoutInflater, parent, false) },
) {

	binding.recyclerView.setRecycledViewPool(sharedPool)
	val adapter = ListDelegationAdapter(mangaGridItemAD(sizeResolver, listener, coverContext = ModernCoverContext.BROWSING))
	binding.recyclerView.addItemDecoration(selectionDecoration)
	binding.recyclerView.adapter = adapter
	val spacing = context.resources.getDimensionPixelOffset(R.dimen.grid_spacing_outer)
	binding.recyclerView.addItemDecoration(SpacingItemDecoration(spacing, withBottomPadding = true))
	val eventListener = AdapterDelegateClickListenerAdapter(this, itemClickListener)
	binding.buttonMore.setOnClickListener(eventListener)

	val chrome = GlobalSearchChrome(binding.root)
	binding.sourceHeader.background = chrome.headerBackground()
	binding.imageViewSourceIcon.background = chrome.iconBackground()
	binding.textViewTitle.setTextColor(chrome.onSurface)
	binding.textViewSourceLanguage.setTextColor(chrome.muted)
	binding.textViewSourceLanguage.background = chrome.languageBackground()
	chrome.styleMore(binding.buttonMore)
	var boundSourceKey: Pair<String, Int>? = null

	bind {
		binding.textViewTitle.text = item.getBaseTitle(context)
		val isSource = item.source !== UnknownMangaSource
		binding.buttonMore.isVisible = isSource
		val sourceKey = item.source.name to item.titleResId
		if (boundSourceKey != sourceKey) {
			// FaviconView normally uses its current drawable as a placeholder. Do not
			// show another source's logo when this holder is rebound.
			binding.imageViewSourceIcon.disposeImage()
			if (isSource) {
				binding.imageViewSourceIcon.setImageAsync(item.source)
			} else {
				binding.imageViewSourceIcon.setImageAsync(when (item.titleResId) {
					R.string.history -> R.drawable.ic_history
					R.string.favourites -> R.drawable.ic_heart
					else -> R.drawable.ic_manga_source
				})
			}
			boundSourceKey = sourceKey
		}
		binding.textViewSourceLanguage.isVisible = isSource
		binding.textViewSourceLanguage.text = item.getLanguageLabel() ?: "—"
		binding.textViewSourceLanguage.contentDescription = item.getLanguageLabel()
			?: context.getString(R.string.search_language_other)
		val status = item.headerStatus
		val statusLabel = context.getString(when (status) {
			SearchSourceStatus.LOADING -> R.string.loading_
			SearchSourceStatus.ERROR -> R.string.error
			SearchSourceStatus.COMPLETE -> R.string.global_search_source_complete
		})
		binding.textViewStatus.isVisible = isSource
		binding.textViewStatus.text = status.symbol
		binding.textViewStatus.contentDescription = statusLabel
		binding.textViewStatus.setTextColor(chrome.statusColor(status))
		binding.textViewStatus.background = chrome.statusBackground(status)
		binding.textViewResultCount.text = if (status == SearchSourceStatus.LOADING) statusLabel else
			context.resources.getQuantityString(R.plurals.global_search_result_count, item.list.size, item.list.size)
		val hasResults = item.list.isNotEmpty()
		binding.textViewResultCount.setTextColor(if (hasResults) chrome.primary else chrome.muted)
		binding.textViewResultCount.background = chrome.countBackground(hasResults)
		adapter.items = item.list
		adapter.notifyDataSetChanged()
		binding.recyclerView.isGone = item.list.isEmpty()
		binding.textViewError.textAndVisible = item.error?.getDisplayMessage(context.resources)
	}
}
