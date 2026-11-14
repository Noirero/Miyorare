package org.koitharu.kotatsu.core.ui.widgets

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.ScrollView
import androidx.compose.ui.geometry.Rect
import com.google.android.material.navigationrail.NavigationRailView
import org.koitharu.kotatsu.core.util.ext.getThemeColor

/** Preserves the Material rail, including header, labels, expand/collapse and accessibility. */
class DragNavigationRailView @JvmOverloads constructor(
	context: Context,
	attrs: AttributeSet? = null,
) : NavigationRailView(context, attrs) {

	private var restoreNativeIndicator = false
	private val drag = NavigationTabDrag(
		host = this,
		vertical = true,
		visibleSlots = ::visibleTabSlots,
		selectedId = { selectedItemId },
		accent = { context.getThemeColor(androidx.appcompat.R.attr.colorPrimary) },
		onIndicatorChanged = { moving ->
			if (moving) {
				restoreNativeIndicator = isItemActiveIndicatorEnabled
				if (restoreNativeIndicator) setItemActiveIndicatorEnabled(false)
			} else if (restoreNativeIndicator) {
				setItemActiveIndicatorEnabled(true)
				restoreNativeIndicator = false
			}
		},
		canStart = { x, rect ->
			// If the native rail overflows, keep its side gutters available to scroll.
			val scroll = getChildAt(0) as? ScrollView
			val scrollable = scroll?.let { it.canScrollVertically(-1) || it.canScrollVertically(1) } == true
			!scrollable || kotlin.math.abs(x - rect.center.x) <= rect.width * 0.30f
		},
	)

	internal fun setTabDragListeners(preview: (Int?) -> Boolean, commit: (Int) -> Unit) {
		drag.preview = preview
		drag.commit = commit
	}

	internal fun cancelTabDrag() = drag.cancel()

	override fun dispatchTouchEvent(event: MotionEvent): Boolean =
		drag.dispatch(event) { super.dispatchTouchEvent(it) }

	override fun dispatchDraw(canvas: Canvas) {
		super.dispatchDraw(canvas)
		drag.draw(canvas)
	}

	override fun onDetachedFromWindow() {
		drag.reset()
		super.onDetachedFromWindow()
	}

	private fun visibleTabSlots(): List<Pair<Int, Rect>> {
		val clipped = android.graphics.Rect()
		val position = IntArray(2)
		return (0 until menu.size()).mapNotNull { index ->
			val item = menu.getItem(index)
			if (!item.isVisible || !item.isEnabled) return@mapNotNull null
			val child = findViewById<View>(item.itemId) ?: return@mapNotNull null
			if (!child.isShown || !child.getLocalVisibleRect(clipped)) return@mapNotNull null
			child.getLocationInWindow(position)
			item.itemId to Rect(
				(position[0] + clipped.left).toFloat(), (position[1] + clipped.top).toFloat(),
				(position[0] + clipped.right).toFloat(), (position[1] + clipped.bottom).toFloat(),
			)
		}
	}
}
