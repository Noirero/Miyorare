package org.koitharu.kotatsu.history.ui

import android.content.res.ColorStateList
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.isGone
import androidx.core.view.updateLayoutParams
import androidx.core.widget.ImageViewCompat
import com.google.android.material.color.MaterialColors
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.ShapeAppearanceModel
import com.hannesdorfmann.adapterdelegates4.dsl.adapterDelegateViewBinding
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.databinding.ItemHistoryAccordionBinding
import org.koitharu.kotatsu.list.ui.model.ListModel
import com.google.android.material.R as materialR

/** History-only delegate: no changes to the shared ListHeaderAD used by other screens. */
internal fun historyAccordionItemAD(onClick: (HistoryHeaderAction) -> Unit) =
	adapterDelegateViewBinding<HistoryAccordionItem, ListModel, ItemHistoryAccordionBinding>(
		{ inflater, parent -> ItemHistoryAccordionBinding.inflate(inflater, parent, false) },
	) {
		binding.root.setOnClickListener {
			onClick(HistoryHeaderAction(item.key, item.kind == HistoryAccordionItem.Kind.MORE))
		}

		bind {
			val row = item
			val isParent = row.kind == HistoryAccordionItem.Kind.DAYS_BUCKET ||
				row.kind == HistoryAccordionItem.Kind.OLDER_BUCKET
			val isMore = row.kind == HistoryAccordionItem.Kind.MORE
			val isChild = !isParent
			val density = context.resources.displayMetrics.density
			fun dp(value: Int) = (value * density + 0.5f).toInt()

			binding.root.updateLayoutParams<ViewGroup.MarginLayoutParams> {
				marginStart = dp(if (isChild) 24 else 8)
				marginEnd = dp(8)
				topMargin = dp(2)
				bottomMargin = dp(2)
			}
			binding.root.minimumHeight = dp(if (isMore) 48 else 56)

			// Semantic Material/Miyorare theme attributes; never use mockup colors.
			val surface = MaterialColors.getColor(binding.root, materialR.attr.colorSurface)
			val surfaceContainer = MaterialColors.getColor(binding.root, materialR.attr.colorSurfaceContainer)
			val surfaceContainerHigh = MaterialColors.getColor(binding.root, materialR.attr.colorSurfaceContainerHigh)
			val primaryContainer = MaterialColors.getColor(binding.root, materialR.attr.colorPrimaryContainer)
			val onPrimaryContainer = MaterialColors.getColor(binding.root, materialR.attr.colorOnPrimaryContainer)
			val onSurface = MaterialColors.getColor(binding.root, materialR.attr.colorOnSurface)
			val onSurfaceVariant = MaterialColors.getColor(binding.root, materialR.attr.colorOnSurfaceVariant)
			val accent = MaterialColors.getColor(binding.root, materialR.attr.colorPrimary)
			val outline = MaterialColors.getColor(binding.root, materialR.attr.colorOutlineVariant)

			val rowSurface = when {
				isParent && row.expanded -> primaryContainer
				isParent -> surfaceContainer
				row.expanded -> surfaceContainerHigh
				else -> surface
			}
			binding.root.background = MaterialShapeDrawable().apply {
				shapeAppearanceModel = ShapeAppearanceModel.builder().setAllCornerSizes(dp(12).toFloat()).build()
				fillColor = ColorStateList.valueOf(rowSurface)
			}

			val title = row.title(context)
			val subtitle = row.subtitle(context)
			binding.textViewTitle.text = title
			binding.textViewSubtitle.text = subtitle
			binding.textViewSubtitle.isGone = subtitle.isNullOrEmpty()
			binding.textViewTitle.setTextColor(when {
				isMore -> accent
				isParent && row.expanded -> onPrimaryContainer
				else -> onSurface
			})
			binding.textViewSubtitle.setTextColor(
				if (isParent && row.expanded) onPrimaryContainer else onSurfaceVariant,
			)
			binding.imageViewCalendar.isGone = isMore
			binding.indicator.isGone = !isChild || isMore
			binding.indicator.setBackgroundColor(if (row.expanded) accent else outline)
			binding.imageViewChevron.isGone = isMore
			binding.imageViewChevron.setImageResource(
				if (row.expanded) R.drawable.ic_expand_more else R.drawable.ic_chevron_right,
			)
			val iconColor = when {
				isParent && row.expanded -> onPrimaryContainer
				row.expanded -> accent
				else -> onSurfaceVariant
			}
			ImageViewCompat.setImageTintList(binding.imageViewCalendar, ColorStateList.valueOf(iconColor))
			ImageViewCompat.setImageTintList(binding.imageViewChevron, ColorStateList.valueOf(iconColor))

			binding.root.isFocusable = true
			binding.root.contentDescription = listOfNotNull(title, subtitle).joinToString(", ")
			ViewCompat.setStateDescription(binding.root, if (isMore) null else context.getString(
				if (row.expanded) R.string.collapse else R.string.expand,
			))
			ViewCompat.setAccessibilityHeading(binding.root, !isMore)
		}
	}
