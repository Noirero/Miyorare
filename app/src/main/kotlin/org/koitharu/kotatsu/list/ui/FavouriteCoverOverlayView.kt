package org.koitharu.kotatsu.list.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.text.StaticLayout
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
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
import org.koitharu.kotatsu.list.ui.model.ModernCoverState
import kotlin.math.max
import kotlin.math.roundToInt

/** Shared Modern cover presentation. The historic name is retained to avoid resource churn. */
class FavouriteCoverOverlayView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
	FrameLayout(context, attrs) {

	private val unread: FrameLayout
	private val number: BadgeView
	private val legacyCounter: BadgeView
	private val statuses: LinearLayout
	private val statusItems: List<ImageView>
	private val metadata: LinearLayout
	private val local: TextView
	private val language: TextView
	val titleView: TextView
	private val action: ImageView
	private var model: ModernCoverState? = null
	private var preservedProgress: View? = null
	private var readClick: ((View) -> Unit)? = null
	private var styledMetrics: FavouriteCoverOverlayMetrics? = null
	private var styledWidth = -1
	private var styledActionSize = -1
	private val density get() = resources.displayMetrics.density

	init {
		inflate(context, R.layout.view_favourite_cover_overlays, this)
		unread = findViewById(R.id.cover_unread)
		number = findViewById(R.id.cover_unread_number)
		legacyCounter = findViewById(R.id.cover_legacy_counter)
		statuses = findViewById(R.id.cover_permanent_status)
		statusItems = listOf(R.id.cover_library, R.id.cover_downloaded, R.id.cover_pinned).map { findViewById<ImageView>(it) }
		metadata = findViewById(R.id.cover_metadata)
		local = findViewById(R.id.cover_local)
		language = findViewById(R.id.cover_language)
		titleView = findViewById(R.id.cover_title)
		action = findViewById(R.id.cover_continue)
		clipChildren = true
	}

	fun bind(value: ModernCoverState, preservedProgress: View? = null, onReadClick: (View) -> Unit) {
		model = value
		this.preservedProgress = preservedProgress
		styledMetrics = null
		styledActionSize = -1
		contentDescription = null
		readClick = onReadClick.takeIf { value.showContinueReading }
		number.number = value.unreadCount ?: 0
		legacyCounter.number = value.legacyCounter
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

	private fun resetVisibility(value: ModernCoverState) {
		listOf(value.isFavorite, value.isSaved, value.isPinned).forEachIndexed { index, active ->
			statusItems[index].isVisible = active
		}
		statuses.isVisible = statusItems.any { it.isVisible }
		unread.isVisible = (value.unreadCount ?: 0) > 0
		legacyCounter.isVisible = value.legacyCounter > 0
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
		// Only the cover parent's final exact pass owns height; neither wrap-content pass may grow it.
		val h = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) MeasureSpec.getSize(heightMeasureSpec) else 0
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
		// Protect the host's existing progress bounds without moving or restyling that view.
		val progress = preservedProgress?.takeIf { it.isVisible && it.measuredHeight > 0 }
		val progressParams = progress?.layoutParams as? LayoutParams
		val progressGravity = progressParams?.gravity?.and(Gravity.VERTICAL_GRAVITY_MASK)
		val progressHeight = progress?.measuredHeight ?: 0
		val bottomReserve = if (preservedProgress == null) px(FavouriteCoverOverlayMetrics.PROGRESS_RESERVED_DP)
			else if (progressGravity == Gravity.BOTTOM) progressHeight + (progressParams?.bottomMargin ?: 0) else 0
		val contentTop = inset + if (progressGravity == Gravity.TOP) progressHeight + (progressParams?.topMargin ?: 0) else 0
		val bottom = h - bottomReserve - gap
		val selectionSize = resources.getDimensionPixelSize(R.dimen.manga_status_ribbon_width)
		val top = contentTop + if (isSelected) selectionSize +
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
			(number.background as GradientDrawable).cornerRadius = diameter / 2f
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
			(preservedProgress != null && naturalTitle.height > bottom - contentTop) ||
			(showStatuses && top + statuses.measuredHeight + gap > bottom - naturalTitle.height)) 1 else 2
		if (titleView.maxLines != titleLines) titleView.maxLines = titleLines
		measureNatural(titleView, innerWidth)
		if (titleView.measuredHeight > bottom ||
			(preservedProgress != null && titleView.measuredHeight > bottom - contentTop)) showTitle = false
		var titleTop = bottom - if (showTitle) titleView.measuredHeight else 0
		val topLimit = titleTop - gap
		if (statuses.measuredHeight + top > topLimit || statuses.measuredWidth > innerWidth) showStatuses = false
		place(statuses, w - inset - statuses.measuredWidth, top)
		var usedTop = if (showStatuses) top + statuses.measuredHeight else if (isSelected) top else contentTop
		measureNatural(unread, w)
		val unreadTop = when {
			showStatuses && unread.measuredWidth + statuses.measuredWidth + gap > innerWidth -> usedTop + gap
			isSelected && unread.measuredWidth + selectionSize + gap > innerWidth -> top
			else -> contentTop
		}
		val showUnread = (value.unreadCount ?: 0) > 0 && unread.measuredWidth <= innerWidth && unreadTop + unread.measuredHeight <= topLimit
		place(unread, inset, unreadTop)
		if (showUnread) usedTop = max(usedTop, unreadTop + unread.measuredHeight)
		measureNatural(legacyCounter, w)
		val legacyTop = when {
			showUnread -> usedTop + gap
			showStatuses && legacyCounter.measuredWidth + statuses.measuredWidth + gap > innerWidth -> usedTop + gap
			isSelected && legacyCounter.measuredWidth + selectionSize + gap > innerWidth -> top
			else -> contentTop
		}
		val showLegacyCounter = value.legacyCounter > 0 && legacyCounter.measuredWidth <= innerWidth &&
			legacyTop + legacyCounter.measuredHeight <= topLimit
		place(legacyCounter, inset, legacyTop)
		if (showLegacyCounter) usedTop = max(usedTop, legacyTop + legacyCounter.measuredHeight)

		// Metadata precedes the action in compaction priority. Measure even a previously GONE group.
		measureNatural(metadata, w)
		val showMetadata = (value.isLocalSource || !value.languageLabel.isNullOrBlank()) &&
			metadata.measuredWidth <= innerWidth && usedTop + gap <= titleTop - gap - metadata.measuredHeight
		val target = px(FavouriteCoverOverlayMetrics.ACTION_TOUCH_DP)
		if (styledActionSize != px(metrics.actionSize)) {
			val circleInset = ((target - px(metrics.actionSize)) / 2).coerceAtLeast(0)
			action.backgroundTintList = null
			action.background = InsetDrawable(shape(R.color.favourite_cover_action, R.color.favourite_cover_action_outline, target / 2f), circleInset)
			action.imageTintList = ColorStateList.valueOf(Color.WHITE)
			// ic_play already contains optical insets; the glyph follows the blue circle's size.
			action.setPadding(circleInset, circleInset, circleInset, circleInset)
			styledActionSize = px(metrics.actionSize)
		}
		action.measure(exact(target), exact(target))
		val textWidthBesideAction = innerWidth - target - gap
		var showAction = value.showContinueReading && target <= innerWidth && usedTop + gap <= bottom - target &&
			(!showTitle || textWidthBesideAction >= px(FavouriteCoverOverlayMetrics.MIN_ACTION_TEXT_WIDTH_DP)) &&
			(!showMetadata || metadata.measuredWidth <= textWidthBesideAction)
		if (showAction && showTitle) {
			measureNatural(titleView, textWidthBesideAction)
			val candidateTop = bottom - titleView.measuredHeight
			val contentTop = candidateTop - if (showMetadata) gap + metadata.measuredHeight else 0
			if (usedTop + gap > contentTop) {
				showAction = false
				measureNatural(titleView, innerWidth)
			} else titleTop = candidateTop
		}
		// Apply final visibility once; temporary visibility toggles during measure can cause layout loops.
		titleView.isVisible = showTitle
		statuses.isVisible = showStatuses
		unread.isVisible = showUnread
		legacyCounter.isVisible = showLegacyCounter
		metadata.isVisible = showMetadata
		action.isVisible = showAction
		action.setOnClickListener(if (showAction) OnClickListener { readClick?.invoke(it) } else null)
		action.isClickable = showAction
		place(titleView, inset, titleTop)
		place(metadata, inset, titleTop - gap - metadata.measuredHeight)
		place(action, w - inset - target, bottom - target)
		updateAccessibility(value)
	}

	private fun updateAccessibility(value: ModernCoverState) {
		val permanent = listOfNotNull(
			context.getString(R.string.in_library).takeIf { value.isFavorite },
			context.getString(R.string.favourites_show_downloaded).takeIf { value.isSaved },
			context.getString(R.string.pin).takeIf { value.isPinned },
		).joinToString(", ")
		statuses.contentDescription = permanent.ifEmpty { null }
		val unreadText = "${context.getString(R.string.unread)}: ${value.unreadCount ?: 0}"
		unread.contentDescription = unreadText
		val omitted = listOfNotNull(permanent.takeIf { !statuses.isVisible && it.isNotEmpty() },
			unreadText.takeIf { !unread.isVisible && (value.unreadCount ?: 0) > 0 },
			value.legacyCounter.toString().takeIf { !legacyCounter.isVisible && value.legacyCounter > 0 },
			"LOCAL".takeIf { !metadata.isVisible && value.isLocalSource },
			value.languageLabel.takeIf { !metadata.isVisible || !language.isVisible })
		titleView.contentDescription = (listOf(value.title) + omitted).joinToString(", ")
		contentDescription = titleView.contentDescription.takeIf { !titleView.isVisible }
		importantForAccessibility = if (titleView.isVisible) IMPORTANT_FOR_ACCESSIBILITY_NO else IMPORTANT_FOR_ACCESSIBILITY_YES
	}

	/** Selection retains its fill/outline even when its extra marker cannot fit above the title. */
	fun canShowSelectionMarker(): Boolean {
		if (isLayoutRequested) return false
		val markerBottom = resources.getDimensionPixelSize(R.dimen.card_indicator_offset) +
			resources.getDimensionPixelSize(R.dimen.manga_status_ribbon_width)
		val progress = preservedProgress
		val params = progress?.layoutParams as? LayoutParams
		if (progress?.isVisible == true && progress.measuredHeight > 0 &&
			params?.gravity?.and(Gravity.VERTICAL_GRAVITY_MASK) == Gravity.TOP && params.topMargin < markerBottom) return false
		return markerBottom <= height - (FavouriteCoverOverlayMetrics.PROGRESS_RESERVED_DP * density).roundToInt() &&
			(!titleView.isVisible || markerBottom < titleView.top)
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
