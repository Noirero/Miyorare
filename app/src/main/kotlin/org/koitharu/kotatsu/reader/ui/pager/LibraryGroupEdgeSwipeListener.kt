package org.koitharu.kotatsu.reader.ui.pager

import android.content.Context
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs
import org.koitharu.kotatsu.reader.ui.LibraryGroupReaderNavigationController

/**
 * Observes a real swipe that starts on the first/last visible page of a chapter and hands the
 * gesture to Advanced Library Group navigation before the underlying pager can fall through to the
 * manga's native next/previous chapter. It never fabricates pages or changes chapter ownership.
 */
internal class LibraryGroupEdgeSwipeListener(
	context: Context,
	private val controller: LibraryGroupReaderNavigationController,
	private val orientation: Int,
	private val mapVisualDeltaToLogical: (Int) -> Int,
	private val isAtChapterBoundary: (Int) -> Boolean,
) : RecyclerView.SimpleOnItemTouchListener() {

	private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop * 2
	private var downX = 0f
	private var downY = 0f
	private var armed = false

	override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
		if (!controller.isGroupReader) return false
		when (e.actionMasked) {
			MotionEvent.ACTION_DOWN -> {
				downX = e.x
				downY = e.y
				armed = true
			}

			MotionEvent.ACTION_MOVE -> if (armed) {
				val displacement = if (orientation == RecyclerView.VERTICAL) {
					downY - e.y
				} else {
					downX - e.x
				}
				if (abs(displacement) >= touchSlop) {
					// Evaluate exactly once per gesture. This is important when a normal swipe starts
					// on the second-to-last page and settles on the last page: that gesture must not be
					// reinterpreted as a chapter transition after the pager updates its position.
					armed = false
					val visualDelta = if (displacement > 0f) 1 else -1
					val logicalDelta = mapVisualDeltaToLogical(visualDelta)
					if (
						isAtChapterBoundary(logicalDelta) &&
						controller.canSwitchChapterBy(logicalDelta) &&
						controller.switchChapterBy(logicalDelta)
					) {
						// Intercept the rest of this drag so the old pager cannot also slide into its
						// locally prefetched chapter while the Group transition is taking over.
						return true
					}
				}
			}

			MotionEvent.ACTION_UP,
			MotionEvent.ACTION_CANCEL -> armed = false
		}
		return false
	}

	override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) = Unit
}
