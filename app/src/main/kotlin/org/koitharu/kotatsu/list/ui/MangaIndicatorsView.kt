package org.koitharu.kotatsu.list.ui

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.ui.widgets.IconsView

/** Cover indicators for the grid and detailed manga list; library state is supplied by the mapper. */
class MangaIndicatorsView @JvmOverloads constructor(
	context: Context,
	attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

	private val statusIcons: IconsView
	private val libraryHeart: ImageView
	private val libraryLabel: TextView

	init {
		orientation = HORIZONTAL
		gravity = Gravity.CENTER_VERTICAL
		inflate(context, R.layout.view_manga_indicators, this)
		statusIcons = findViewById(R.id.status_icons)
		libraryHeart = findViewById(R.id.library_heart)
		libraryLabel = findViewById(R.id.library_label)
	}

	fun bind(isSaved: Boolean, isLocalSource: Boolean, isFavorite: Boolean) {
		statusIcons.clearIcons()
		if (isSaved) statusIcons.addIcon(R.drawable.ic_storage)
		if (isLocalSource) statusIcons.addIcon(R.drawable.ic_manga_source)
		statusIcons.isVisible = statusIcons.iconsCount > 0
		libraryHeart.updateLayoutParams<LayoutParams> {
			marginStart = if (statusIcons.isVisible) resources.getDimensionPixelSize(R.dimen.library_indicator_spacing) else 0
		}
		libraryHeart.isVisible = isFavorite
		libraryLabel.isVisible = isFavorite
		isVisible = statusIcons.isVisible || isFavorite
	}
}
