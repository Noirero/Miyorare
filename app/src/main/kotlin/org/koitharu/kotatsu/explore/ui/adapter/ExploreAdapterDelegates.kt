package org.koitharu.kotatsu.explore.ui.adapter

import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.text.bold
import androidx.core.text.buildSpannedString
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.carousel.CarouselLayoutManager
import com.google.android.material.carousel.MultiBrowseCarouselStrategy
import com.hannesdorfmann.adapterdelegates4.dsl.adapterDelegateViewBinding
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.getSummary
import org.koitharu.kotatsu.core.model.getTitle
import org.koitharu.kotatsu.core.model.isExternalSource
import org.koitharu.kotatsu.core.model.isNovelSource
import org.koitharu.kotatsu.core.model.unwrap
import org.koitharu.kotatsu.core.ui.BaseListAdapter
import org.koitharu.kotatsu.core.ui.dialog.buildAlertDialog
import org.koitharu.kotatsu.core.ui.list.AdapterDelegateClickListenerAdapter
import org.koitharu.kotatsu.core.ui.list.OnListItemClickListener
import org.koitharu.kotatsu.core.util.ext.drawableStart
import org.koitharu.kotatsu.core.util.ext.setTooltipCompat
import org.koitharu.kotatsu.databinding.ItemExploreButtonsBinding
import org.koitharu.kotatsu.databinding.ItemExploreSourceGridBinding
import org.koitharu.kotatsu.databinding.ItemExploreSourceListBinding
import org.koitharu.kotatsu.databinding.ItemExploreSuggestionsHeaderBinding
import org.koitharu.kotatsu.databinding.ItemMangaCarouselBinding
import org.koitharu.kotatsu.databinding.ItemRecommendationBinding
import org.koitharu.kotatsu.explore.ui.model.ExploreButtons
import org.koitharu.kotatsu.explore.ui.model.MangaSourceItem
import org.koitharu.kotatsu.explore.ui.model.RecommendationsItem
import org.koitharu.kotatsu.kotatsumigration.ui.KotatsuMigrationService
import org.koitharu.kotatsu.list.ui.adapter.ListHeaderClickListener
import org.koitharu.kotatsu.list.ui.adapter.ListItemType
import org.koitharu.kotatsu.list.ui.model.ListHeader
import org.koitharu.kotatsu.list.ui.model.ListModel
import org.koitharu.kotatsu.list.ui.model.MangaCompactListModel
import org.koitharu.kotatsu.mihon.model.MihonMangaSource
import org.koitharu.kotatsu.parsers.model.Manga

private const val PREF_EXPLORE_SUGGESTIONS_VISIBLE = "explore_suggestions_visible"

fun exploreButtonsAD(
	clickListener: View.OnClickListener,
) = adapterDelegateViewBinding<ExploreButtons, ListModel, ItemExploreButtonsBinding>(
	{ layoutInflater, parent -> ItemExploreButtonsBinding.inflate(layoutInflater, parent, false) },
) {

	binding.buttonDownloads.setOnClickListener(clickListener)
	binding.buttonLocal.setOnClickListener(clickListener)
	binding.buttonMigration.setOnClickListener {
		buildAlertDialog(context) {
			setTitle(R.string.migrate_from_kotatsu)
			setMessage(R.string.migrate_from_kotatsu_confirm)
			setNegativeButton(android.R.string.cancel, null)
			setPositiveButton(R.string.migrate_from_kotatsu) { _, _ ->
				if (KotatsuMigrationService.start(context)) {
					Toast.makeText(context, R.string.kotatsu_migration_running, Toast.LENGTH_SHORT).show()
				}
			}
		}.show()
	}
}

fun exploreListHeaderAD(
	listener: ListHeaderClickListener?,
) = adapterDelegateViewBinding<ListHeader, ListModel, ItemExploreSuggestionsHeaderBinding>(
	{ layoutInflater, parent -> ItemExploreSuggestionsHeaderBinding.inflate(layoutInflater, parent, false) },
) {
	val preferences = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)

	binding.buttonMore.setOnClickListener {
		listener?.onListHeaderClick(item, it)
	}
	binding.buttonVisibility.setOnClickListener {
		if (item.payload != R.id.nav_suggestions) return@setOnClickListener
		val currentlyVisible = preferences.getBoolean(PREF_EXPLORE_SUGGESTIONS_VISIBLE, true)
		preferences.edit().putBoolean(PREF_EXPLORE_SUGGESTIONS_VISIBLE, !currentlyVisible).apply()
		(itemView.parent as? RecyclerView)?.adapter?.notifyDataSetChanged()
	}

	bind {
		val currentItem = item
		binding.textViewTitle.text = currentItem.getText(context)
		val isSuggestions = currentItem.payload == R.id.nav_suggestions
		binding.buttonVisibility.isVisible = isSuggestions
		if (isSuggestions) {
			val isVisible = preferences.getBoolean(PREF_EXPLORE_SUGGESTIONS_VISIBLE, true)
			binding.buttonVisibility.setIconResource(if (isVisible) R.drawable.ic_eye else R.drawable.ic_eye_off)
			binding.buttonVisibility.contentDescription = context.getString(if (isVisible) R.string.hide else R.string.show)
			binding.buttonVisibility.setTooltipCompat(if (isVisible) R.string.hide else R.string.show)
		}
		binding.buttonMore.isVisible = currentItem.buttonTextRes != 0
		if (currentItem.buttonTextRes != 0) {
			binding.buttonMore.setText(currentItem.buttonTextRes)
			binding.buttonMore.contentDescription = context.getString(currentItem.buttonTextRes)
		}
	}
}

fun exploreRecommendationItemAD(
	itemClickListener: OnListItemClickListener<Manga>,
) = adapterDelegateViewBinding<RecommendationsItem, ListModel, ItemRecommendationBinding>(
	{ layoutInflater, parent -> ItemRecommendationBinding.inflate(layoutInflater, parent, false) },
) {

	val preferences = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
	val expandedHeight = binding.root.layoutParams.height
	val adapter = BaseListAdapter<MangaCompactListModel>()
		.addDelegate(ListItemType.MANGA_CAROUSEL, recommendationCarouselItemAD(itemClickListener))
	with(binding.recyclerView) {
		this.adapter = adapter
		layoutManager = CarouselLayoutManager(MultiBrowseCarouselStrategy())
		isNestedScrollingEnabled = false
		clipChildren = false
		clipToPadding = false
	}

	bind {
		val isSuggestionsVisible = preferences.getBoolean(PREF_EXPLORE_SUGGESTIONS_VISIBLE, true)
		binding.root.updateLayoutParams<ViewGroup.LayoutParams> {
			height = if (isSuggestionsVisible) expandedHeight else 0
		}
		binding.root.isVisible = isSuggestionsVisible
		adapter.items = if (isSuggestionsVisible) item.manga else emptyList()
	}
}

fun recommendationCarouselItemAD(
	itemClickListener: OnListItemClickListener<Manga>,
) = adapterDelegateViewBinding<MangaCompactListModel, MangaCompactListModel, ItemMangaCarouselBinding>(
	{ layoutInflater, parent -> ItemMangaCarouselBinding.inflate(layoutInflater, parent, false) },
) {

	binding.root.setOnClickListener { v ->
		itemClickListener.onItemClick(item.manga, v)
	}
	binding.progressView.isVisible = false
	binding.iconsView.isVisible = false
	binding.badge.isVisible = false

	bind {
		binding.textViewTitle.text = item.manga.title
		binding.imageViewCover.setImageAsync(item.manga.coverUrl, item.manga.source)
	}
}


fun exploreSourceListItemAD(
	listener: OnListItemClickListener<MangaSourceItem>,
) = adapterDelegateViewBinding<MangaSourceItem, ListModel, ItemExploreSourceListBinding>(
	{ layoutInflater, parent ->
		ItemExploreSourceListBinding.inflate(
			layoutInflater,
			parent,
			false,
		)
	},
	on = { item, _, _ -> item is MangaSourceItem && !item.isGrid },
) {

	AdapterDelegateClickListenerAdapter(this, listener).attach(itemView)
	val iconPinned = ContextCompat.getDrawable(context, R.drawable.ic_pin_small)

	bind {
		binding.textViewTitle.text = item.source.getTitle(context)
		binding.textViewTitle.drawableStart = if (item.source.isPinned) iconPinned else null
		binding.textViewSubtitle.text = item.source.getSummary(context)
		binding.imageViewIcon.applyExternalSourceStyle(item.source.mangaSource.isExternalSource())
		val inset = sourceIconInsetPx(
			binding.imageViewIcon.layoutParams.width,
			item.source.mangaSource.isNovelSource,
		)
		binding.imageViewIcon.setPadding(inset, inset, inset, inset)
		binding.imageViewIcon.setImageAsync(item.source)
	}
}

fun exploreSourceGridItemAD(
	listener: OnListItemClickListener<MangaSourceItem>,
) = adapterDelegateViewBinding<MangaSourceItem, ListModel, ItemExploreSourceGridBinding>(
	{ layoutInflater, parent ->
		ItemExploreSourceGridBinding.inflate(
			layoutInflater,
			parent,
			false,
		)
	},
	on = { item, _, _ -> item is MangaSourceItem && item.isGrid },
) {

	AdapterDelegateClickListenerAdapter(this, listener).attach(itemView)
	val iconPinned = ContextCompat.getDrawable(context, R.drawable.ic_pin_small)

	bind {
		val baseTitle = item.source.getTitle(context)
		val mihonSource = item.source.mangaSource.unwrap() as? MihonMangaSource
		val title = if (mihonSource?.hasLanguageSuffix == true) {
			"$baseTitle (${mihonSource.languageDisplayName})"
		} else {
			baseTitle
		}
		itemView.setTooltipCompat(
			buildSpannedString {
				bold {
					append(title)
				}
				appendLine()
				append(item.source.getSummary(context))
			},
		)
		binding.textViewTitle.text = title
		binding.textViewTitle.drawableStart = if (item.source.isPinned) iconPinned else null
		binding.imageViewIcon.applyExternalSourceStyle(item.source.mangaSource.isExternalSource())
		val inset = sourceIconInsetPx(
			binding.imageViewIcon.layoutParams.width,
			item.source.mangaSource.isNovelSource,
		)
		binding.imageViewIcon.setPadding(inset, inset, inset, inset)
		binding.imageViewIcon.setImageAsync(item.source)
	}
}

internal fun sourceIconInsetPx(iconSizePx: Int, isNovel: Boolean): Int =
	if (isNovel) iconSizePx / 10 else 0
