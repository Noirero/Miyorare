package org.koitharu.kotatsu.explore.ui.adapter

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
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
import com.google.android.material.button.MaterialButton
import com.google.android.material.carousel.CarouselLayoutManager
import com.google.android.material.carousel.MultiBrowseCarouselStrategy
import com.google.android.material.color.MaterialColors
import com.hannesdorfmann.adapterdelegates4.dsl.adapterDelegateViewBinding
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.getTitle
import org.koitharu.kotatsu.core.model.isExternalSource
import org.koitharu.kotatsu.core.model.isNovelSource
import org.koitharu.kotatsu.core.model.unwrap
import org.koitharu.kotatsu.core.ui.BaseListAdapter
import org.koitharu.kotatsu.core.ui.dialog.buildAlertDialog
import org.koitharu.kotatsu.core.ui.list.AdapterDelegateClickListenerAdapter
import org.koitharu.kotatsu.core.ui.list.OnListItemClickListener
import org.koitharu.kotatsu.core.ui.miyorareViewPaletteFromPreferences
import org.koitharu.kotatsu.core.util.ext.drawableStart
import org.koitharu.kotatsu.core.util.ext.getThemeColor
import org.koitharu.kotatsu.core.util.ext.setTooltipCompat
import org.koitharu.kotatsu.databinding.ItemExploreButtonsBinding
import org.koitharu.kotatsu.databinding.ItemExploreSourceFiltersBinding
import org.koitharu.kotatsu.databinding.ItemExploreSourceGridBinding
import org.koitharu.kotatsu.databinding.ItemExploreSourceListBinding
import org.koitharu.kotatsu.databinding.ItemExploreSuggestionsHeaderBinding
import org.koitharu.kotatsu.databinding.ItemMangaCarouselBinding
import org.koitharu.kotatsu.databinding.ItemRecommendationBinding
import org.koitharu.kotatsu.explore.ui.model.ExploreButtons
import org.koitharu.kotatsu.explore.ui.model.MangaSourceItem
import org.koitharu.kotatsu.explore.ui.model.RecommendationsItem
import org.koitharu.kotatsu.extensions.runtime.getExternalExtensionLanguageDisplayName
import org.koitharu.kotatsu.extensions.runtime.getExternalExtensionLanguageFlag
import org.koitharu.kotatsu.kotatsumigration.ui.KotatsuMigrationService
import org.koitharu.kotatsu.list.ui.adapter.ListHeaderClickListener
import org.koitharu.kotatsu.list.ui.adapter.ListItemType
import org.koitharu.kotatsu.list.ui.model.ListHeader
import org.koitharu.kotatsu.list.ui.model.ListModel
import org.koitharu.kotatsu.list.ui.model.MangaCompactListModel
import org.koitharu.kotatsu.mihon.model.MihonMangaSource
import org.koitharu.kotatsu.parsers.model.Manga
import com.google.android.material.R as materialR

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
			setTitle(R.string.explore_migration)
			setItems(arrayOf(context.getString(R.string.migrate_broken_sources), context.getString(R.string.migrate_from_kotatsu))) { _, which ->
				if (which == 0) {
					context.startActivity(android.content.Intent(context, org.koitharu.kotatsu.settings.SettingsActivity::class.java)
						.setAction(org.koitharu.kotatsu.core.nav.AppRouter.ACTION_MIGRATION))
				} else {
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
		}.show()
	}
	bind {
		binding.applyModernResolvedPalette()
	}
}

fun exploreSourceFiltersAD(
	onOriginClick: (View) -> Unit,
	onLanguageClick: (View) -> Unit,
) = adapterDelegateViewBinding<ExploreSourceFiltersItem, ListModel, ItemExploreSourceFiltersBinding>(
	{ layoutInflater, parent -> ItemExploreSourceFiltersBinding.inflate(layoutInflater, parent, false) },
) {
	binding.buttonOrigin.setOnClickListener { view -> onOriginClick(view) }
	binding.buttonLanguage.setOnClickListener { view -> onLanguageClick(view) }
	bind {
		val origin = context.getString(item.origin.titleRes())
		binding.buttonOrigin.text = "${context.getString(R.string.explore_source_origin_filter)}: $origin"
		val language = item.language?.let(::buildExploreLanguageLabel)
			?: context.getString(R.string.explore_content_filter_all)
		binding.buttonLanguage.text = "${context.getString(R.string.language)}: $language"
	}
}

fun exploreListHeaderAD(
	listener: ListHeaderClickListener?,
) = adapterDelegateViewBinding<ListHeader, ListModel, ItemExploreSuggestionsHeaderBinding>(
	{ layoutInflater, parent -> ItemExploreSuggestionsHeaderBinding.inflate(layoutInflater, parent, false) },
) {
	val preferences = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
	val defaultHeaderTitleColors = binding.textViewTitle.textColors

	binding.buttonMore.setOnClickListener {
		listener?.onListHeaderClick(item, it)
	}
	binding.buttonVisibility.setOnClickListener {
		when (item.payload) {
			R.id.nav_suggestions -> {
				val currentlyVisible = preferences.getBoolean(PREF_EXPLORE_SUGGESTIONS_VISIBLE, true)
				preferences.edit().putBoolean(PREF_EXPLORE_SUGGESTIONS_VISIBLE, !currentlyVisible).apply()
				(itemView.parent as? RecyclerView)?.adapter?.notifyDataSetChanged()
			}

			is ExplorePinnedHeaderPayload -> listener?.onListHeaderClick(item, it)
			is ExploreSourceSectionHeaderPayload -> listener?.onListHeaderClick(item, it)
			is ExploreSourceOriginHeaderPayload -> listener?.onListHeaderClick(item, it)
			is ExploreSourceLanguageHeaderPayload -> listener?.onListHeaderClick(item, it)
		}
	}

	bind {
		val currentItem = item
		val pinnedSection = currentItem.payload as? ExplorePinnedHeaderPayload
		val sourceSection = currentItem.payload as? ExploreSourceSectionHeaderPayload
		val sourceOrigin = currentItem.payload as? ExploreSourceOriginHeaderPayload
		val sourceLanguage = currentItem.payload as? ExploreSourceLanguageHeaderPayload
		binding.textViewTitle.text = currentItem.getText(context)
		binding.textViewCount.isVisible =
			pinnedSection != null || sourceSection != null || sourceOrigin != null || sourceLanguage != null
		binding.textViewCount.text = when {
			pinnedSection != null -> pinnedSection.sourceCount.toString()
			sourceSection != null -> sourceSection.sourceCount.toString()
			sourceOrigin != null -> sourceOrigin.sourceCount.toString()
			sourceLanguage != null -> sourceLanguage.sourceCount.toString()
			else -> ""
		}
		val isSourceAccordion =
			pinnedSection != null || sourceSection != null || sourceOrigin != null || sourceLanguage != null
		itemView.setOnClickListener(
			if (isSourceAccordion) {
				View.OnClickListener { listener?.onListHeaderClick(currentItem, it) }
			} else {
				null
			},
		)
		binding.root.background = if (sourceLanguage != null) {
			ContextCompat.getDrawable(context, R.drawable.bg_explore_language_header)
		} else {
			null
		}
		binding.textViewTitle.drawableStart = if (pinnedSection != null) {
			ContextCompat.getDrawable(context, R.drawable.ic_pin_small)?.mutate()?.also { icon ->
				icon.setTint(
					MaterialColors.getColor(
						binding.textViewTitle,
						materialR.attr.colorSecondary,
					),
				)
			}
		} else {
			null
		}
		binding.textViewTitle.compoundDrawablePadding = if (pinnedSection != null) {
			(8 * context.resources.displayMetrics.density).toInt()
		} else {
			0
		}
		if (sourceSection?.section == ExploreSourceSection.MIYORARE_PACKS) {
			binding.textViewTitle.setTextColor(
				MaterialColors.getColor(binding.textViewTitle, androidx.appcompat.R.attr.colorPrimary),
			)
		} else {
			binding.textViewTitle.setTextColor(defaultHeaderTitleColors)
		}

		val isSuggestions = currentItem.payload == R.id.nav_suggestions
		val expanded = pinnedSection?.expanded ?: sourceSection?.expanded ?: sourceOrigin?.expanded ?: sourceLanguage?.expanded
		binding.buttonVisibility.isVisible = isSuggestions || expanded != null
		when {
			isSuggestions -> {
				val isVisible = preferences.getBoolean(PREF_EXPLORE_SUGGESTIONS_VISIBLE, true)
				binding.buttonVisibility.rotation = 0f
				binding.buttonVisibility.setIconResource(if (isVisible) R.drawable.ic_eye else R.drawable.ic_eye_off)
				binding.buttonVisibility.contentDescription = context.getString(if (isVisible) R.string.hide else R.string.show)
				binding.buttonVisibility.setTooltipCompat(if (isVisible) R.string.hide else R.string.show)
			}

			expanded != null -> {
				val description = if (expanded) R.string.hide else R.string.show
				binding.buttonVisibility.setIconResource(R.drawable.ic_expand_more)
				binding.buttonVisibility.rotation = if (expanded) 180f else 0f
				binding.buttonVisibility.contentDescription = context.getString(description)
				binding.buttonVisibility.setTooltipCompat(description)
			}

			else -> binding.buttonVisibility.rotation = 0f
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
		val origin = item.exploreSourceOrigin()
		val compactSummary = item.summary.toCompactExploreSourceSummary()
		val isExternal = item.source.mangaSource.isExternalSource()
		binding.textViewTitle.text = item.source.getTitle(context)
		binding.textViewTitle.drawableStart = if (item.source.isPinned) iconPinned else null
		binding.textViewSubtitle.text = formatExploreSourceListSummary(origin, item.summary, compactSummary)
		binding.textViewOrigin.bindExploreSourceOriginBadge(origin)
		binding.imageViewIcon.applyExternalSourceStyle(isExternal)
		binding.applyModernResolvedPalette(isExternal)
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
		val origin = item.exploreSourceOrigin()
		val originLabel = context.getString(origin.titleRes())
		val compactSummary = item.summary.toCompactExploreSourceSummary()
		val detailSummary = formatExploreSourceListSummary(origin, item.summary, compactSummary)
		itemView.setTooltipCompat(
			buildSpannedString {
				bold {
					append(title)
				}
				detailSummary?.let { details ->
					appendLine()
					append(details)
				}
				appendLine()
				append(originLabel)
			},
		)
		binding.textViewTitle.text = title
		binding.textViewTitle.drawableStart = if (item.source.isPinned) iconPinned else null
		binding.textViewOrigin.bindExploreSourceOriginBadge(origin)
		binding.imageViewIcon.applyExternalSourceStyle(item.source.mangaSource.isExternalSource())
		val inset = sourceIconInsetPx(
			binding.imageViewIcon.layoutParams.width,
			item.source.mangaSource.isNovelSource,
		)
		binding.imageViewIcon.setPadding(inset, inset, inset, inset)
		binding.imageViewIcon.setImageAsync(item.source)
	}
}

private fun ItemExploreButtonsBinding.applyModernResolvedPalette() {
	val context = root.context
	if (context.miyorareViewPaletteFromPreferences() == null) return
	val container = context.getThemeColor(materialR.attr.colorSurfaceContainerHigh)
	val content = context.getThemeColor(materialR.attr.colorOnSurfaceVariant)
	for (button in listOf(buttonLocal, buttonDownloads, buttonMigration)) {
		button.applyResolvedExploreButtonPalette(container, content)
	}
}

private fun MaterialButton.applyResolvedExploreButtonPalette(container: Int, content: Int) {
	backgroundTintList = ColorStateList.valueOf(container)
	setTextColor(content)
	iconTint = ColorStateList.valueOf(content)
}

private fun ItemExploreSourceListBinding.applyModernResolvedPalette(isExternalSource: Boolean) {
	val context = root.context
	if (context.miyorareViewPaletteFromPreferences() == null) return
	val surfaceLow = context.getThemeColor(materialR.attr.colorSurfaceContainerLow)
	val surfaceHigh = context.getThemeColor(materialR.attr.colorSurfaceContainerHigh)
	val surface = context.getThemeColor(materialR.attr.colorSurfaceContainer)
	val surfaceHighest = context.getThemeColor(materialR.attr.colorSurfaceContainerHighest)
	val outline = context.getThemeColor(materialR.attr.colorOutline)
	val outlineVariant = context.getThemeColor(materialR.attr.colorOutlineVariant)
	val onSurface = context.getThemeColor(materialR.attr.colorOnSurface)
	val onSurfaceVariant = context.getThemeColor(materialR.attr.colorOnSurfaceVariant)

	root.background = createExploreSourceRowBackground(
		context = context,
		normalColor = surfaceLow,
		pressedColor = surfaceHigh,
		outlineColor = outlineVariant,
	)
	textViewTitle.setTextColor(onSurface)
	textViewSubtitle.setTextColor(onSurfaceVariant)
	textViewOrigin.setTextColor(onSurfaceVariant)
	textViewOrigin.background = GradientDrawable().apply {
		shape = GradientDrawable.RECTANGLE
		setColor(surfaceHighest)
		cornerRadius = 999f * context.resources.displayMetrics.density
	}
	if (!isExternalSource) {
		imageViewIcon.setBackgroundColor(surface)
		imageViewIcon.strokeColor = ColorStateList.valueOf(outline)
	}
}

private fun createExploreSourceRowBackground(
	context: Context,
	normalColor: Int,
	pressedColor: Int,
	outlineColor: Int,
): RippleDrawable {
	val density = context.resources.displayMetrics.density
	val radius = 12f * density
	val strokeWidth = density.toInt().coerceAtLeast(1)
	fun shape(color: Int, stroke: Boolean): GradientDrawable = GradientDrawable().apply {
		shape = GradientDrawable.RECTANGLE
		setColor(color)
		cornerRadius = radius
		if (stroke) setStroke(strokeWidth, outlineColor)
	}
	val states = StateListDrawable().apply {
		addState(intArrayOf(android.R.attr.state_pressed), shape(pressedColor, false))
		addState(intArrayOf(), shape(normalColor, true))
	}
	val ripple = context.getThemeColor(materialR.attr.colorPrimary, alphaFactor = 0.12f)
	return RippleDrawable(ColorStateList.valueOf(ripple), states, null)
}

private fun String?.toCompactExploreSourceSummary(): String? =
	this?.takeIf { it.isNotBlank() }?.substringBefore(" • ")?.trim()?.takeIf { it.isNotBlank() }

private fun buildExploreLanguageLabel(language: String): String {
	val flag = getExternalExtensionLanguageFlag(language)
	return buildString {
		if (flag.isNotBlank()) {
			append(flag)
			append(' ')
		}
		append(getExternalExtensionLanguageDisplayName(language))
	}
}

internal fun sourceIconInsetPx(iconSizePx: Int, isNovel: Boolean): Int =
	if (isNovel) iconSizePx / 10 else 0
