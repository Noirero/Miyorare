package org.koitharu.kotatsu.list.ui

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.ui.widgets.IconsView

enum class MangaCardStatus { NONE, LIBRARY, DOWNLOADED, LIBRARY_DOWNLOADED }

/** Cover indicators for the grid and detailed manga list; state is supplied by the existing mapper. */
class MangaIndicatorsView @JvmOverloads constructor(
	context: Context,
	attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

	private val statusIcons: IconsView
	private val ribbon: View
	private val libraryIcon: ImageView
	private val downloadIcon: ImageView
	var status: MangaCardStatus = MangaCardStatus.NONE
		private set

	init {
		orientation = HORIZONTAL
		gravity = Gravity.CENTER_VERTICAL
		inflate(context, R.layout.view_manga_indicators, this)
		statusIcons = findViewById(R.id.status_icons)
		ribbon = findViewById(R.id.status_ribbon)
		libraryIcon = findViewById(R.id.status_library)
		downloadIcon = findViewById(R.id.status_downloaded)
	}

	fun bind(isSaved: Boolean, isLocalSource: Boolean, isFavorite: Boolean) {
		statusIcons.clearIcons()
		if (isLocalSource) statusIcons.addIcon(R.drawable.ic_manga_source)
		statusIcons.isVisible = statusIcons.iconsCount > 0
		status = when {
			isSaved && isFavorite -> MangaCardStatus.LIBRARY_DOWNLOADED
			isSaved -> MangaCardStatus.DOWNLOADED
			isFavorite -> MangaCardStatus.LIBRARY
			else -> MangaCardStatus.NONE
		}
		ribbon.isVisible = status != MangaCardStatus.NONE
		libraryIcon.isVisible = isFavorite
		downloadIcon.isVisible = isSaved
		ribbon.contentDescription = listOfNotNull(
			context.getString(R.string.in_library).takeIf { isFavorite },
			context.getString(R.string.favourites_show_downloaded).takeIf { isSaved },
		).takeIf { it.isNotEmpty() }?.joinToString(", ")
		applySelectionPresentation(false)
		isVisible = statusIcons.isVisible || ribbon.isVisible
	}

	/** The existing selection decoration supplies this transient state before child drawing. */
	fun applySelectionPresentation(selected: Boolean) {
		ribbon.alpha = if (selected) 0f else 1f
		ribbon.importantForAccessibility = if (selected) IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
			else IMPORTANT_FOR_ACCESSIBILITY_YES
	}

	fun bindGrid(isSaved: Boolean, isLocalSource: Boolean, isFavorite: Boolean, counter: Int) {
		bind(isSaved, isLocalSource, isFavorite)
		updateLayoutParams<FrameLayout.LayoutParams> {
			gravity = if (status != MangaCardStatus.NONE) Gravity.TOP or Gravity.END else Gravity.TOP or Gravity.START
			marginEnd = if (status != MangaCardStatus.NONE) resources.getDimensionPixelSize(R.dimen.card_indicator_offset) else 0
			topMargin = when {
				status != MangaCardStatus.NONE -> resources.getDimensionPixelSize(R.dimen.card_indicator_offset)
				// Existing status-only offsets, preserving their pixel truncation.
				counter > 0 -> resources.getDimensionPixelOffset(R.dimen.card_indicator_size)
				else -> resources.getDimensionPixelOffset(R.dimen.margin_normal)
			}
		}
	}
}
