package org.koitharu.kotatsu.list.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.text.StaticLayout
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.children
import androidx.core.view.isVisible
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.ui.widgets.BadgeView
import org.koitharu.kotatsu.list.ui.model.MangaGridModel
import kotlin.math.max
import kotlin.math.roundToInt

/** Normal Modern Favorites only. Measures inside the cover; never determines the card's size. */
class FavouriteCoverOverlayView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
	FrameLayout(context, attrs) {

	private val unread: FrameLayout
	private val number: BadgeView
	private val statuses: LinearLayout
	private val statusItems: List<ImageView>
	private val metadata: LinearLayout
	private val local: TextView
	private val language: TextView
	val titleView: TextView
	private val action: ImageView
	private var model: MangaGridModel? = null
	private var readClick: ((View) -> Unit)? = null
	private var styledMetrics: FavouriteCoverOverlayMetrics? = null
	private var styledWidth = -1
	private var styledActionSize = -1
	private val density get() = resources.displayMetrics.density

	init {
		inflate(context, R.layout.view_favourite_cover_overlays, this)
		unread = findViewById(R.id.cover_unread)
		number = findViewById(R.id.cover_unread_number)
		statuses = findViewById(R.id.cover_permanent_status)
		statusItems = listOf(R.id.cover_library, R.id.cover_downloaded, R.id.cover_pinned).map { findViewById<ImageView>(it) }
		metadata = findViewById(R.id.cover_metadata)
		local = findViewById(R.id.cover_local)
		language = findViewById(R.id.cover_language)
		titleView = findViewById(R.id.cover_title)
		action = findViewById(R.id.cover_continue)
		clipChildren = true
	}

	fun bind(value: MangaGridModel, onReadClick: (View) -> Unit) {
		model = value
		styledMetrics = null
		readClick = onReadClick.takeIf { value.showContinueReading }
		number.number = value.counter
		titleView.text = value.title
		language.text = value.languageLabel
		isSelected = false
		// Recycled views must not retain a listener, slot, selection transform or compacted visibility.
		(children + statuses.children + metadata.children + sequenceOf(number)).forEach {
			it.translationX = 0f
			it.translationY = 0f
			it.scaleX = 1f
			it.scaleY = 1f
			it.alpha = 1f
			it.contentDescription = null
		}
		action.contentDescription = context.getString(R.string.favourites_show_continue_reading)
		action.setOnLongClickListener { (parent?.parent as? View)?.performLongClick() == true }
		resetVisibility(value)
		requestLayout()
	}

	private fun resetVisibility(value: MangaGridModel) {
		listOf(value.isFavorite, value.isSaved, value.isPinned).forEachIndexed { index, active ->
			statusItems[index].isVisible = active
		}
		statuses.isVisible = statusItems.any { it.isVisible }
		unread.isVisible = value.counter > 0
		local.isVisible = value.isLocalSource
		language.isVisible = !value.languageLabel.isNullOrBlank()
		metadata.isVisible = local.isVisible || language.isVisible
		titleView.isVisible = value.isTitleOverCover && !value.isTitleHidden
		action.isVisible = value.showContinueReading
		action.setOnClickListener(if (value.showContinueReading) OnClickListener { readClick?.invoke(it) } else null)
		action.isClickable = value.showContinueReading
	}

	override fun setSelected(selected: Boolean) {
		if (isSelected == selected) return
		super.setSelected(selected)
		requestLayout()
	}

	override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
		val w = MeasureSpec.getSize(widthMeasureSpec)
		// A MATCH_PARENT overlay contributes zero height in the FrameLayout's first wrap-content pass.
		val h = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) 0 else MeasureSpec.getSize(heightMeasureSpec)
		setMeasuredDimension(w, h)
		if (w <= 0 || h <= 0) return
		val value = model ?: return
		val metrics = FavouriteCoverOverlayMetrics.resolve(w / density, h / density)
		fun px(dp: Float) = (dp * density).roundToInt()
		val inset = px(metrics.inset)
		val gap = px(metrics.gap)
		val padding = px(metrics.padding)
		val radius = px(metrics.radius).toFloat()
		val innerWidth = (w - inset * 2).coerceAtLeast(0)
		val bottom = h - px(FavouriteCoverOverlayMetrics.PROGRESS_RESERVED_DP) - gap
		val top = inset + if (isSelected) resources.getDimensionPixelSize(R.dimen.manga_status_ribbon_width) +
			resources.getDimensionPixelSize(R.dimen.library_indicator_spacing) else 0

		if (styledMetrics != metrics || styledWidth != innerWidth) {
			unread.background = shape(R.color.favourite_cover_protection, R.color.favourite_cover_unread_outline, radius)
			unread.setPadding(padding, padding, padding, padding)
			number.backgroundTintList = null
			number.background = shape(R.color.favourite_cover_unread, R.color.favourite_cover_unread_outline, px(metrics.unreadSize) / 2f)
			number.setTextColor(Color.WHITE)
			number.setTextSize(TypedValue.COMPLEX_UNIT_SP, metrics.badgeSp)
			number.includeFontPadding = false
			val diameter = maxOf(px(metrics.unreadSize), number.paint.measureText(number.text.toString()).roundToInt() + padding * 2,
				(number.paint.fontMetrics.descent - number.paint.fontMetrics.ascent).roundToInt() + padding * 2)
			number.layoutParams = LayoutParams(diameter, diameter)
			statuses.background = shape(R.color.favourite_cover_protection, R.color.favourite_cover_outline, radius)
			statuses.setPadding(padding, padding, padding, padding)
			val activeStatuses = statusItems.filter { it.isVisible }
			val statusSize = px(metrics.statusSize)
			statuses.orientation = if (activeStatuses.size * statusSize + max(0, activeStatuses.size - 1) * gap + padding * 2 <= innerWidth) {
				LinearLayout.HORIZONTAL
			} else LinearLayout.VERTICAL
			statusItems.forEachIndexed { index, icon ->
				icon.imageTintList = ColorStateList.valueOf(if (index == 2) color(R.color.favourite_cover_pin) else Color.WHITE)
				icon.background = shape(R.color.favourite_cover_item, R.color.favourite_cover_outline, radius * 0.7f)
				val iconPadding = px(3f * metrics.scale)
				icon.setPadding(iconPadding, iconPadding, iconPadding, iconPadding)
				icon.layoutParams = LinearLayout.LayoutParams(statusSize, statusSize).apply {
					if (icon.isVisible && icon !== activeStatuses.firstOrNull()) {
						if (statuses.orientation == LinearLayout.HORIZONTAL) leftMargin = gap else topMargin = gap
					}
				}
			}
			metadata.background = shape(R.color.favourite_cover_protection, R.color.favourite_cover_action_outline, radius)
			metadata.setPadding(padding, padding, padding, padding)
			listOf(local, language).forEach { badge ->
				badge.setTextColor(Color.WHITE)
				badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, metrics.metadataSp)
				badge.includeFontPadding = false
				badge.setPadding(padding * 2, padding, padding * 2, padding)
				badge.background = if (badge === local) shape(R.color.favourite_cover_local, R.color.favourite_cover_local_outline, radius * 0.7f)
					else shape(R.color.favourite_cover_item, R.color.favourite_cover_outline, radius * 0.7f)
				badge.layoutParams = LinearLayout.LayoutParams(-2, -2).apply {
					if (badge === language && local.isVisible) marginStart = gap
				}
			}
			titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, metrics.titleSp)
			titleView.setTextColor(Color.WHITE)
			titleView.setLineSpacing(0f, 0.96f)
			styledMetrics = metrics
			styledWidth = innerWidth
		}
		measureNatural(statuses, innerWidth)
		var showStatuses = statusItems.any { it.isVisible }
		var showTitle = value.isTitleOverCover && !value.isTitleHidden
		val naturalTitle = StaticLayout.Builder.obtain(value.title, 0, value.title.length, titleView.paint, innerWidth.coerceAtLeast(1))
			.setIncludePad(false).setLineSpacing(0f, 0.96f).setMaxLines(2).build()
		val titleLines = if (naturalTitle.height > bottom ||
			(showStatuses && top + statuses.measuredHeight + gap > bottom - naturalTitle.height)) 1 else 2
		if (titleView.maxLines != titleLines) titleView.maxLines = titleLines
		measureNatural(titleView, innerWidth)
		if (titleView.measuredHeight > bottom) showTitle = false
		val titleTop = bottom - if (showTitle) titleView.measuredHeight else 0
		place(titleView, inset, titleTop)
		val topLimit = titleTop - gap
		if (statuses.measuredHeight + top > topLimit || statuses.measuredWidth > innerWidth) showStatuses = false
		place(statuses, w - inset - statuses.measuredWidth, top)
		var usedTop = if (showStatuses) top + statuses.measuredHeight else inset
		measureNatural(unread, innerWidth)
		val unreadTop = if (showStatuses && unread.measuredWidth + statuses.measuredWidth + gap > innerWidth) usedTop + gap else inset
		val showUnread = value.counter > 0 && unread.measuredWidth <= innerWidth && unreadTop + unread.measuredHeight <= topLimit
		place(unread, inset, unreadTop)
		if (showUnread) usedTop = max(usedTop, unreadTop + unread.measuredHeight)

		// Metadata precedes the action in compaction priority. Measure even a previously GONE group.
		measureNatural(metadata, w)
		val showMetadata = (value.isLocalSource || !value.languageLabel.isNullOrBlank()) &&
			metadata.measuredWidth <= innerWidth && usedTop + gap <= titleTop - gap - metadata.measuredHeight
		place(metadata, inset, titleTop - gap - metadata.measuredHeight)
		val target = px(FavouriteCoverOverlayMetrics.ACTION_TOUCH_DP)
		if (styledActionSize != px(metrics.actionSize)) {
			val circleInset = ((target - px(metrics.actionSize)) / 2).coerceAtLeast(0)
			action.backgroundTintList = null
			action.background = InsetDrawable(shape(R.color.favourite_cover_action, R.color.favourite_cover_action_outline, target / 2f), circleInset)
			action.imageTintList = ColorStateList.valueOf(Color.WHITE)
			val actionPadding = circleInset + px(metrics.actionSize * 0.20f)
			action.setPadding(actionPadding, actionPadding, actionPadding, actionPadding)
			styledActionSize = px(metrics.actionSize)
		}
		action.measure(exact(target), exact(target))
		val showAction = value.showContinueReading && target <= innerWidth && usedTop + gap <= titleTop - gap - target &&
			(!showMetadata || metadata.measuredWidth + gap + target <= innerWidth)
		// Apply final visibility once; temporary visibility toggles during measure can cause layout loops.
		titleView.isVisible = showTitle
		statuses.isVisible = showStatuses
		unread.isVisible = showUnread
		metadata.isVisible = showMetadata
		action.isVisible = showAction
		action.setOnClickListener(if (showAction) OnClickListener { readClick?.invoke(it) } else null)
		action.isClickable = showAction
		place(action, w - inset - target, titleTop - gap - target)
		updateAccessibility(value)
	}

	private fun updateAccessibility(value: MangaGridModel) {
		val permanent = listOfNotNull(
			context.getString(R.string.in_library).takeIf { value.isFavorite },
			context.getString(R.string.favourites_show_downloaded).takeIf { value.isSaved },
			context.getString(R.string.pin).takeIf { value.isPinned },
		).joinToString(", ")
		statuses.contentDescription = permanent.ifEmpty { null }
		val unreadText = "${context.getString(R.string.unread)}: ${value.counter}"
		unread.contentDescription = unreadText
		val omitted = listOfNotNull(permanent.takeIf { !statuses.isVisible && it.isNotEmpty() },
			unreadText.takeIf { !unread.isVisible && value.counter > 0 },
			"LOCAL".takeIf { !metadata.isVisible && value.isLocalSource },
			value.languageLabel.takeIf { !metadata.isVisible || !language.isVisible })
		titleView.contentDescription = (listOf(value.title) + omitted).joinToString(", ")
		contentDescription = titleView.contentDescription.takeIf { !titleView.isVisible }
		importantForAccessibility = if (titleView.isVisible) IMPORTANT_FOR_ACCESSIBILITY_NO else IMPORTANT_FOR_ACCESSIBILITY_YES
	}

	private fun measureNatural(view: View, maxWidth: Int) {
		view.measure(MeasureSpec.makeMeasureSpec(maxWidth.coerceAtLeast(0), MeasureSpec.AT_MOST),
			MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
	}

	private fun place(view: View, left: Int, top: Int) {
		val x = left.coerceAtLeast(0)
		val y = top.coerceAtLeast(0)
		val old = view.layoutParams as LayoutParams
		if (old.width != view.measuredWidth || old.height != view.measuredHeight || old.leftMargin != x || old.topMargin != y) {
			view.layoutParams = LayoutParams(view.measuredWidth, view.measuredHeight).apply {
				leftMargin = x
				topMargin = y
			}
		}
	}

	override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
		children.forEach { child ->
			val params = child.layoutParams as LayoutParams
			val w = if (child.isVisible) child.measuredWidth else 0
			val h = if (child.isVisible) child.measuredHeight else 0
			child.layout(params.leftMargin, params.topMargin, params.leftMargin + w, params.topMargin + h)
		}
	}

	private fun exact(size: Int) = MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY)
	private fun color(id: Int) = ContextCompat.getColor(context, id)
	private fun shape(fill: Int, outline: Int, radius: Float) = GradientDrawable().apply {
		setColor(color(fill))
		cornerRadius = radius
		setStroke(max(1, density.roundToInt()), color(outline))
	}
}
