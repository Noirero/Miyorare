package org.koitharu.kotatsu.search.ui.multi.adapter

import android.content.Context
import android.text.Layout
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.TextView
import org.koitharu.kotatsu.R
import kotlin.math.ceil

/**
 * Keep the normal source header inline, but let the action and badges wrap onto extra
 * rows when their actual text no longer fits. The cover rail is not part of this layout.
 */
class AdaptiveSearchSourceHeader @JvmOverloads constructor(
	context: Context,
	attrs: AttributeSet? = null,
	defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

	private val identity by lazy { findViewById<LinearLayout>(R.id.source_identity) }
	private val metadata by lazy { findViewById<LinearLayout>(R.id.source_metadata) }
	private val badges by lazy { findViewById<LinearLayout>(R.id.source_badges) }
	private val icon by lazy { findViewById<View>(R.id.image_view_source_icon) }
	private val title by lazy { findViewById<TextView>(R.id.textView_title) }
	private val status by lazy { findViewById<TextView>(R.id.text_view_status) }
	private val language by lazy { findViewById<TextView>(R.id.text_view_source_language) }
	private val count by lazy { findViewById<TextView>(R.id.text_view_result_count) }
	private val more by lazy { findViewById<TextView>(R.id.button_more) }
	private val rowGap by lazy { resources.getDimensionPixelSize(R.dimen.grid_spacing) }
	private val badgeGap by lazy { resources.getDimensionPixelSize(R.dimen.grid_spacing_outer_double) }

	override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
		if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) {
			configureRows(stackAction = false, stackBadges = false)
			super.onMeasure(widthMeasureSpec, heightMeasureSpec)
			return
		}
		val available = (MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight).coerceAtLeast(0)
		val actionFits = more.fitsText(available, maxLines = 2)
		val actionWidth = if (more.visibility == GONE) 0 else more.measuredWidth
		val expandedMetadataWidth = (available - icon.layoutParams.width - metadata.horizontalMargins()).coerceAtLeast(0)
		val inlineMetadataWidth = (expandedMetadataWidth - actionWidth).coerceAtLeast(0)

		// Measure with the actual text paint, padding, icon and available pixels, not a
		// screen-width or font-scale cutoff. Keep ordinary two-line labels inline.
		val inlineBadgesFit = badgesFit(inlineMetadataWidth)
		val inlineFits = actionFits && inlineBadgesFit &&
			titleFits(inlineMetadataWidth, expandedMetadataWidth)
		val stackAction = more.visibility != GONE && !inlineFits
		val stackBadges = if (stackAction) !badgesFit(expandedMetadataWidth) else !inlineBadgesFit
		configureRows(stackAction, stackBadges)

		// The probes above measure individual TextViews directly. Invalidate the
		// intermediate containers so their cached dimensions cannot skip the final
		// measure of those children, including when the holder width is unchanged.
		identity.forceLayout()
		metadata.forceLayout()
		(title.parent as View).forceLayout()
		badges.forceLayout()
		super.onMeasure(widthMeasureSpec, heightMeasureSpec)
	}

	private fun badgesFit(width: Int): Boolean {
		if (!language.fitsText((width - language.horizontalMargins()).coerceAtLeast(0))) return false
		val used = if (language.visibility == GONE) 0 else language.measuredWidth + language.horizontalMargins()
		return count.fitsText((width - used - count.horizontalMargins()).coerceAtLeast(0), maxLines = 2)
	}

	private fun titleFits(width: Int, expandedWidth: Int): Boolean {
		if (!status.fitsText((width - status.horizontalMargins()).coerceAtLeast(0))) return false
		val used = if (status.visibility == GONE) 0 else status.measuredWidth + status.horizontalMargins()
		val titleWidth = (width - used - title.horizontalMargins()).coerceAtLeast(0)
		if (title.fitsText(titleWidth, maxLines = 2)) return true
		// Long source names may still use their original two-line ellipsis. Give a
		// squeezed short name more room if it would fit on the expanded row.
		val desired = ceil(Layout.getDesiredWidth(title.text, title.paint)).toInt() +
			title.compoundPaddingLeft + title.compoundPaddingRight
		val measured = title.layout ?: return false
		val hasReadablePrefix = measured.lineCount > 0 &&
			(measured.lineCount > 1 || measured.getEllipsisCount(0) == 0 ||
				measured.getEllipsisStart(0) > 0)
		return desired > expandedWidth - used - title.horizontalMargins() &&
			titleWidth > title.compoundPaddingLeft + title.compoundPaddingRight && hasReadablePrefix
	}

	private fun TextView.fitsText(width: Int, maxLines: Int = Int.MAX_VALUE): Boolean {
		if (visibility == GONE) return true
		if (width <= 0) return false
		// TextView's cached Layout can otherwise belong to a preceding measure pass.
		forceLayout()
		measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST),
			MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
		val textLayout = layout ?: return false
		return measuredWidth > compoundPaddingLeft + compoundPaddingRight &&
			textLayout.lineCount in 1..maxLines &&
			(0 until textLayout.lineCount).all { textLayout.getEllipsisCount(it) == 0 } &&
			textLayout.getLineEnd(textLayout.lineCount - 1) >= textLayout.text.length
	}

	private fun View.horizontalMargins(): Int {
		val params = layoutParams as LayoutParams
		return params.marginStart + params.marginEnd
	}

	private fun configureRows(stackAction: Boolean, stackBadges: Boolean) {
		orientation = if (stackAction) VERTICAL else HORIZONTAL
		(identity.layoutParams as LayoutParams).apply {
			width = if (stackAction) MATCH_PARENT else 0
			weight = if (stackAction) 0f else 1f
		}
		(more.layoutParams as LayoutParams).apply {
			width = if (stackAction) MATCH_PARENT else WRAP_CONTENT
			topMargin = if (stackAction) rowGap else 0
		}
		badges.orientation = if (stackBadges) VERTICAL else HORIZONTAL
		(count.layoutParams as LayoutParams).topMargin =
			if (stackBadges && language.visibility != GONE) badgeGap else 0
		val countLines = if (stackBadges) Int.MAX_VALUE else 2
		if (count.maxLines != countLines) count.maxLines = countLines
		val actionLines = if (stackAction) Int.MAX_VALUE else 2
		if (more.maxLines != actionLines) more.maxLines = actionLines
	}
}
