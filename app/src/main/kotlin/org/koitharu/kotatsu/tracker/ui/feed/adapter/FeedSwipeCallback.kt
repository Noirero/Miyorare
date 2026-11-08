package org.koitharu.kotatsu.tracker.ui.feed.adapter

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.util.ext.getItem
import org.koitharu.kotatsu.core.util.ext.hapticFeedback
import org.koitharu.kotatsu.core.util.ext.HapticEffect
import org.koitharu.kotatsu.list.ui.adapter.ListItemType
import org.koitharu.kotatsu.tracker.ui.feed.model.FeedItem
import kotlin.math.abs

/**
 * Swipe right deletes the feed entry; swipe left toggles its notification Read/Unread state.
 * Left gestures never dismiss rows in ItemTouchHelper, because re-inserting a changed row during
 * recover animation can leave it offset. The action fires on release at the threshold instead.
 * The swipe-start state is retained while the row animates back, even if the feed observer rebinds.
 */
internal enum class FeedSwipeDecision { MARK_READ, MARK_UNREAD, DELETE }

internal fun resolveFeedSwipeDecision(isToggle: Boolean, isUnread: Boolean): FeedSwipeDecision = when {
	!isToggle -> FeedSwipeDecision.DELETE
	isUnread -> FeedSwipeDecision.MARK_READ
	else -> FeedSwipeDecision.MARK_UNREAD
}

internal fun shouldToggleFeedSwipe(dx: Float, width: Int): Boolean =
	width > 0 && dx < 0f && -dx >= width * 0.4f

class FeedSwipeCallback(
	context: Context,
	private val onAction: (item: FeedItem, isRead: Boolean) -> Unit,
) : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {

	private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)

	private val deleteIcon = ContextCompat.getDrawable(context, R.drawable.ic_delete)?.mutate()
	private val readIcon = ContextCompat.getDrawable(context, R.drawable.ic_eye_check)?.mutate()
	private val unreadIcon = ContextCompat.getDrawable(context, R.drawable.ic_eye_off)?.mutate()

	// fixed colors on purpose: the reveal should look identical in every theme, light or dark
	private val deleteBg = 0xFFD32F2F.toInt()
	private val readBg = 0xFF1976D2.toInt()
	private val unreadBg = 0xFF995C00.toInt()

	private var hapticFired = false
	private var lastDx = 0f
	private var isCurrentItemRead = false
	private var currentItem: FeedItem? = null
	private var pendingToggleRead = false

	override fun getMovementFlags(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int {
		// only feed rows are swipeable, not the date headers
		if (viewHolder.itemViewType != ListItemType.FEED.ordinal) {
			return 0
		}
		return super.getMovementFlags(recyclerView, viewHolder)
	}

	override fun onMove(
		recyclerView: RecyclerView,
		viewHolder: RecyclerView.ViewHolder,
		target: RecyclerView.ViewHolder,
	): Boolean = false

	override fun getSwipeThreshold(viewHolder: RecyclerView.ViewHolder): Float {
		// A leftward read/unread toggle never commits — the action fires on release instead,
		// letting the row settle back with the standard recover animation.
		return if (lastDx < 0f) 3f else 0.4f
	}

	// a fling can commit a swipe via escape velocity even below the distance threshold,
	// so block it for leftward read/unread swipes too
	override fun getSwipeEscapeVelocity(defaultValue: Float): Float =
		if (lastDx < 0f) Float.MAX_VALUE else super.getSwipeEscapeVelocity(defaultValue)

	override fun getSwipeVelocityThreshold(defaultValue: Float): Float =
		if (lastDx < 0f) Float.MAX_VALUE else super.getSwipeVelocityThreshold(defaultValue)

	override fun getAnimationDuration(
		recyclerView: RecyclerView,
		animationType: Int,
		animateDx: Float,
		animateDy: Float,
	): Long = if (animationType == SWIPE_SUCCESS_ANIMATION_TYPE) {
		DELETE_ANIMATION_DURATION_MS
	} else {
		super.getAnimationDuration(recyclerView, animationType, animateDx, animateDy)
	}

	override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
		super.onSelectedChanged(viewHolder, actionState)
		if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE && viewHolder != null) {
			currentItem = viewHolder.getItem(FeedItem::class.java)
			// A read chapter may still have an unread feed notification. Use the log state.
			isCurrentItemRead = currentItem?.isUnread == false
		} else if (actionState == ItemTouchHelper.ACTION_STATE_IDLE) {
			// Fire the read/unread toggle on release, not on a destructive swipe commit.
			if (pendingToggleRead) {
				currentItem?.let { onAction(it, true) }
			}
			pendingToggleRead = false
		}
	}

	override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
		// Only delete commits. Read/unread toggles fire on release in onSelectedChanged.
		if (direction != ItemTouchHelper.LEFT) {
			viewHolder.getItem(FeedItem::class.java)?.let { onAction(it, false) }
		}
	}

	override fun onChildDraw(
		c: Canvas,
		recyclerView: RecyclerView,
		viewHolder: RecyclerView.ViewHolder,
		dX: Float,
		dY: Float,
		actionState: Int,
		isCurrentlyActive: Boolean,
	) {
		val view = viewHolder.itemView
		// Eligibility is recomputed even at exactly zero displacement. Returning to the
		// starting position cancels a previously crossed threshold on the same gesture.
		if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE && isCurrentlyActive) {
			pendingToggleRead = shouldToggleFeedSwipe(dX, view.width)
		}
		if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE && dX != 0f) {
			lastDx = dX
			val swipeLeft = dX < 0f
			// Snapshot the swipe-start state. Rebinding during the settle animation must not
			// flip the revealed icon from Mark read to Mark unread (or vice versa).
			val markUnread = swipeLeft && isCurrentItemRead
			bgPaint.color = when {
				markUnread -> unreadBg
				swipeLeft -> readBg
				else -> deleteBg
			}
			val top = view.top.toFloat()
			val bottom = view.bottom.toFloat()
			val left = if (swipeLeft) view.right + dX else view.left.toFloat()
			val right = if (swipeLeft) view.right.toFloat() else view.left + dX
			if (right > left) {
				val radius = (bottom - top) / 2f
				c.drawRoundRect(left, top, right, bottom, radius, radius, bgPaint)
				val icon = when {
					markUnread -> unreadIcon
					swipeLeft -> readIcon
					else -> deleteIcon
				}
				if (icon != null && right - left >= icon.intrinsicWidth) {
					val progress = (abs(dX) / view.width).coerceIn(0f, 1f)
					icon.alpha = ((progress * 3f).coerceAtMost(1f) * 255).toInt()
					icon.setTint(Color.WHITE)
					val cx = ((left + right) / 2f).toInt()
					val cy = ((top + bottom) / 2f).toInt()
					icon.setBounds(
						cx - icon.intrinsicWidth / 2,
						cy - icon.intrinsicHeight / 2,
						cx + icon.intrinsicWidth / 2,
						cy + icon.intrinsicHeight / 2,
					)
					icon.draw(c)
				}
			}
			val passedThreshold = abs(dX) >= 0.4f * view.width
			if (isCurrentlyActive) {
				if (passedThreshold && !hapticFired) {
					view.hapticFeedback(HapticEffect.CONFIRM)
					hapticFired = true
				} else if (!passedThreshold) {
					hapticFired = false
				}
			}
		}
		super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
	}

	override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
		super.clearView(recyclerView, viewHolder)
		hapticFired = false
		lastDx = 0f
		isCurrentItemRead = false
		// currentItem/pendingToggleRead are reset in onSelectedChanged(IDLE) at release; resetting
		// them here too would clobber a new gesture that starts while this row is still settling
	}

	private companion object {
		const val SWIPE_SUCCESS_ANIMATION_TYPE = 2
		const val DELETE_ANIMATION_DURATION_MS = 0L
	}
}
