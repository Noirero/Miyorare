package org.koitharu.kotatsu.list.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.ui.widgets.IconsView
import org.koitharu.kotatsu.core.util.ext.getThemeColor
import org.koitharu.kotatsu.history.ui.util.ReadingProgressView
import com.google.android.material.R as materialR

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
	private val legacyLayoutParams = mutableMapOf<Int, LinearLayout.LayoutParams>()
	private var modernGridPresentation = false
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
		if (modernGridPresentation) {
			val root = parent as? FrameLayout
			val pin = root?.findViewById<View>(R.id.imageView_pin)
			pin?.translationY = if (selected) {
				resources.getDimension(R.dimen.manga_status_ribbon_width) +
					resources.getDimension(R.dimen.library_indicator_spacing)
			} else 0f
		}
	}

	fun bindGrid(isSaved: Boolean, isLocalSource: Boolean, isFavorite: Boolean, counter: Int, useModernFavouritesPresentation: Boolean = false) {
		bind(isSaved, isLocalSource, isFavorite)
		modernGridPresentation = useModernFavouritesPresentation
		if (modernGridPresentation) {
			applyModernGridPresentation(isLocalSource)
			updateLayoutParams<FrameLayout.LayoutParams> {
				gravity = Gravity.TOP or Gravity.END
				marginEnd = resources.getDimensionPixelSize(R.dimen.card_indicator_offset)
				topMargin = resources.getDimensionPixelSize(R.dimen.card_indicator_offset)
			}
		} else {
			restoreLegacyGridPresentation()
			updateLayoutParams<FrameLayout.LayoutParams> {
				gravity = if (status != MangaCardStatus.NONE) Gravity.TOP or Gravity.END else Gravity.TOP or Gravity.START
				marginEnd = if (status != MangaCardStatus.NONE) resources.getDimensionPixelSize(R.dimen.card_indicator_offset) else 0
				topMargin = when {
					status != MangaCardStatus.NONE -> resources.getDimensionPixelSize(R.dimen.card_indicator_offset)
					counter > 0 -> resources.getDimensionPixelOffset(R.dimen.card_indicator_size)
					else -> resources.getDimensionPixelOffset(R.dimen.margin_normal)
				}
			}
		}
	}

	private fun applyModernGridPresentation(isLocalSource: Boolean) {
		val root = parent as? FrameLayout ?: return
		val legacy = root.findViewById<LinearLayout>(R.id.layout_indicators) ?: return
		val language = root.findViewById<TextView>(R.id.textView_language)
		val pin = root.findViewById<ImageView>(R.id.imageView_pin)
		val progress = root.findViewById<ReadingProgressView>(R.id.progressView)
		val continueView = root.findViewById<ImageView>(R.id.imageView_continue)
		val local = root.findViewById<TextView>(R.id.textView_local)

		statusIcons.isVisible = false
		isVisible = ribbon.isVisible
		local?.isVisible = isLocalSource
		local?.backgroundTintList = ColorStateList.valueOf(context.getThemeColor(materialR.attr.colorTertiary, Color.DKGRAY))
		local?.setTextColor(context.getThemeColor(materialR.attr.colorOnTertiary, Color.WHITE))

		reparent(root, legacy, language, modernLayout(Gravity.BOTTOM or Gravity.START, start = if (isLocalSource) 58 else 8, bottom = 30))
		reparent(root, legacy, pin, modernLayout(Gravity.TOP or Gravity.END, end = statusRibbonWidthDp() + 10, top = 8))
		reparent(root, legacy, progress, modernLayout(Gravity.BOTTOM or Gravity.START, start = 8, end = 8, bottom = 4, matchWidth = true))
		reparent(root, legacy, continueView, modernLayout(Gravity.BOTTOM or Gravity.END, end = 8, bottom = 30))
		progress?.setModernBottomEdge(true)
		legacy.isVisible = false
	}

	private fun restoreLegacyGridPresentation() {
		val root = parent as? FrameLayout ?: return
		val legacy = root.findViewById<LinearLayout>(R.id.layout_indicators) ?: return
		val local = root.findViewById<TextView>(R.id.textView_local)
		local?.isVisible = false
		val ordered = listOf(
			root.findViewById<View>(R.id.textView_language),
			root.findViewById<View>(R.id.imageView_pin),
			root.findViewById<View>(R.id.progressView),
			root.findViewById<View>(R.id.imageView_continue),
		)
		ordered.forEach { child ->
			if (child != null && child.parent !== legacy) {
				(child.parent as? ViewGroup)?.removeView(child)
				val original = legacyLayoutParams[child.id]
				if (original != null) {
					legacy.addView(child, LinearLayout.LayoutParams(original))
				} else {
					legacy.addView(child)
				}
			}
			child?.translationX = 0f
			child?.translationY = 0f
			child?.scaleX = 1f
			child?.scaleY = 1f
		}
		root.findViewById<ReadingProgressView>(R.id.progressView)?.setModernBottomEdge(false)
		legacy.isVisible = true
	}

	private fun reparent(
		root: FrameLayout,
		legacy: LinearLayout,
		child: View?,
		params: FrameLayout.LayoutParams,
	) {
		if (child == null) return
		if (child.parent === legacy && child.id != View.NO_ID && child.id !in legacyLayoutParams) {
			(child.layoutParams as? LinearLayout.LayoutParams)?.let {
				legacyLayoutParams[child.id] = LinearLayout.LayoutParams(it)
			}
		}
		if (child.parent !== root) {
			(child.parent as? ViewGroup)?.removeView(child)
			root.addView(child, params)
		} else {
			child.layoutParams = params
		}
	}

	private fun modernLayout(
		gravity: Int,
		start: Int = 0,
		end: Int = 0,
		top: Int = 0,
		bottom: Int = 0,
		matchWidth: Boolean = false,
	): FrameLayout.LayoutParams {
		val density = resources.displayMetrics.density
		return FrameLayout.LayoutParams(
			if (matchWidth) FrameLayout.LayoutParams.MATCH_PARENT else FrameLayout.LayoutParams.WRAP_CONTENT,
			FrameLayout.LayoutParams.WRAP_CONTENT,
			gravity,
		).apply {
			marginStart = (start * density).toInt()
			marginEnd = (end * density).toInt()
			topMargin = (top * density).toInt()
			bottomMargin = (bottom * density).toInt()
		}
	}

	private fun statusRibbonWidthDp(): Int {
		val width = resources.getDimension(R.dimen.manga_status_ribbon_width)
		val density = resources.displayMetrics.density
		return ((width / density) * when (status) {
			MangaCardStatus.LIBRARY_DOWNLOADED -> 2
			MangaCardStatus.LIBRARY, MangaCardStatus.DOWNLOADED -> 1
			MangaCardStatus.NONE -> 0
		}).toInt()
	}
}
