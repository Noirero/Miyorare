package org.koitharu.kotatsu.list.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Rect
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.view.children
import androidx.core.view.isVisible
import org.junit.Assert.*
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.SampleData
import org.koitharu.kotatsu.list.ui.model.MangaGridModel
import kotlin.math.roundToInt

/** Invoked by the existing Android-runtime suite, without changing its workflow or chapter tests. */
internal object FavouriteCoverOverlayRuntimeChecks {
	fun compactStatusesAndRecycle(context: Context) {
		for (night in listOf(Configuration.UI_MODE_NIGHT_NO, Configuration.UI_MODE_NIGHT_YES)) {
			val config = Configuration(context.resources.configuration).apply { uiMode = night }
			val themed = ContextThemeWrapper(context.createConfigurationContext(config), R.style.Theme_Kotatsu)
			val cover = FavouriteCoverOverlayView(themed)
			val density = themed.resources.displayMetrics.density
			val group = cover.findViewById<ViewGroup>(R.id.cover_permanent_status)
			val metadata = cover.findViewById<ViewGroup>(R.id.cover_metadata)
			val action = cover.findViewById<View>(R.id.cover_continue)
			var clicks = 0
			for (mask in (7 downTo 0).toList() + listOf(7, 0)) {
				val model = model().copy(isFavorite = mask and 1 != 0, isSaved = mask and 2 != 0,
					isPinned = mask and 4 != 0, isLocalSource = mask == 7,
					languageLabel = if (mask == 7) "JA" else null, showContinueReading = mask != 0)
				cover.bind(model) { clicks++ }
				layout(cover, 194, 344)
				val active = group.children.filter { it.isVisible }.toList()
				assertEquals(Integer.bitCount(mask), active.size)
				assertEquals(mask != 0, group.isVisible)
				if (active.isNotEmpty()) {
					assertEquals(group.paddingLeft, active.first().left)
					assertEquals(group.width - group.paddingRight, active.last().right)
					for (pair in active.zipWithNext()) assertTrue(pair.first.right < pair.second.left)
				}
				assertEquals(mask == 7, metadata.isVisible)
				assertEquals(mask != 0, action.isVisible)
				val before = clicks
				action.performClick()
				assertEquals(before + if (mask != 0) 1 else 0, clicks)
				for (view in group.children) {
					assertEquals(0f, view.translationX)
					assertEquals(0f, view.translationY)
					assertEquals(1f, view.alpha)
				}
			}
			cover.bind(model().copy(isLocalSource = true, languageLabel = null)) {}
			layout(cover, 128, 170)
			assertTrue(metadata.isVisible)
			assertEquals(listOf(R.id.cover_local), metadata.children.filter { it.isVisible }.map { it.id }.toList())
			cover.bind(model().copy(languageLabel = "EN")) {}
			layout(cover, 128, 170)
			assertEquals(listOf(R.id.cover_language), metadata.children.filter { it.isVisible }.map { it.id }.toList())
			assertEquals(metadata.paddingLeft, cover.findViewById<View>(R.id.cover_language).left)
			val number = cover.findViewById<android.widget.TextView>(R.id.cover_unread_number)
			for ((count, label) in listOf(999 to "999", 1000 to "99+")) {
				cover.bind(model().copy(counter = count)) {}
				layout(cover, 128, 170)
				assertEquals(label, number.text.toString())
				assertTrue(number.paint.measureText(label) <= number.width)
			}
			assertTrue(action.measuredWidth >= (48 * density).roundToInt())
			progressBoundsStayLocked(themed)
		}
	}

	fun sizeChangesStayInsideCover(context: Context) {
		for (fontScale in listOf(1f, 1.5f)) {
			val config = Configuration(context.resources.configuration).apply { this.fontScale = fontScale }
			val themed = ContextThemeWrapper(context.createConfigurationContext(config), R.style.Theme_Kotatsu)
			val density = themed.resources.displayMetrics.density
			val cover = FavouriteCoverOverlayView(themed)
			val rich = model().copy(isFavorite = true, isSaved = true, isPinned = true,
				isLocalSource = true, languageLabel = "ZH-HANT", showContinueReading = true)
			cover.bind(rich) {}
			for ((w, h) in listOf(194 to 344, 59 to 35, 55 to 33, 55 to 98, 59 to 105, 128 to 170, 194 to 344)) {
				for (selected in listOf(false, true)) {
					cover.isSelected = selected
					layout(cover, w, h)
					val visible = cover.children.filter { it.isVisible }.toList()
					for (view in visible) {
						assertTrue("inside $w x $h: ${view.id}", view.left >= 0 && view.top >= 0 && view.right <= cover.width &&
							view.bottom <= cover.height - (18 * density).roundToInt())
					}
					for (a in visible.indices) for (b in a + 1 until visible.size) {
						val x = visible[a]
						val y = visible[b]
						assertFalse("collision $w x $h: ${x.id}, ${y.id}", Rect.intersects(Rect(x.left, x.top, x.right, x.bottom), Rect(y.left, y.top, y.right, y.bottom)))
					}
					if (selected && cover.canShowSelectionMarker()) {
						val markerSize = themed.resources.getDimensionPixelSize(R.dimen.manga_status_ribbon_width)
						val markerInset = themed.resources.getDimensionPixelSize(R.dimen.card_indicator_offset)
						val marker = Rect(cover.width - markerInset - markerSize, markerInset, cover.width - markerInset, markerInset + markerSize)
						for (view in visible) assertFalse("Selection marker collision $w x $h", Rect.intersects(marker, Rect(view.left, view.top, view.right, view.bottom)))
					}
					if (h <= 35) assertFalse(cover.canShowSelectionMarker())
					if (w <= 59) assertFalse(cover.findViewById<View>(R.id.cover_continue).isVisible)
				}
			}
			cover.isSelected = false
			layout(cover, 194, 344)
			assertEquals(3, cover.findViewById<ViewGroup>(R.id.cover_permanent_status).children.count { it.isVisible })
			assertTrue(cover.findViewById<View>(R.id.cover_permanent_status).isVisible)
			// A wrap-content host takes its height from the cover, never from chrome intrinsic size.
			for (mode in listOf(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.AT_MOST)) {
				(cover.parent as? ViewGroup)?.removeView(cover)
				val host = MeasureHost(themed)
				val image = View(themed)
				host.addView(image, FrameLayout.LayoutParams(-1, (35 * density).roundToInt()))
				host.addView(cover, FrameLayout.LayoutParams(-1, -1))
				host.measure(View.MeasureSpec.makeMeasureSpec((59 * density).roundToInt(), View.MeasureSpec.EXACTLY),
					View.MeasureSpec.makeMeasureSpec((700 * density).roundToInt(), mode))
				assertEquals((35 * density).roundToInt(), host.measuredHeight)
				host.layout(0, 0, host.measuredWidth, host.measuredHeight)
				host.requests = 0
				cover.forceLayout()
				layout(cover, 59, 35)
				assertEquals("Stable repeated measurement must not schedule another parent layout", 0, host.requests)
			}
		}
	}

	private fun progressBoundsStayLocked(context: Context) {
		val density = context.resources.displayMetrics.density
		val root = FrameLayout(context)
		val legacy = android.widget.LinearLayout(context).apply { id = R.id.layout_indicators }
		val progress = org.koitharu.kotatsu.history.ui.util.ReadingProgressView(context).apply {
			id = R.id.progressView
			setProgress(org.koitharu.kotatsu.list.domain.ReadingProgress(0.5f, 20,
				org.koitharu.kotatsu.core.prefs.ProgressIndicatorMode.PERCENT_READ), false)
		}
		legacy.addView(progress)
		root.addView(legacy)
		val oldLocal = android.widget.TextView(context).apply { id = R.id.textView_local }
		root.addView(oldLocal)
		val overlays = FavouriteCoverOverlayView(context).apply { id = R.id.favourite_cover_overlays }
		overlays.bind(model().copy(isFavorite = true, isPinned = true)) {}
		root.addView(overlays, FrameLayout.LayoutParams(-1, -1))
		val old = MangaIndicatorsView(context)
		root.addView(old)
		old.bindGrid(true, true, true, 115, true)
		layout(root, 194, 344)
		assertFalse(old.isVisible)
		assertFalse(oldLocal.isVisible)
		assertFalse(legacy.isVisible)
		assertSame(root, progress.parent)
		assertEquals((8 * density).toInt(), progress.left)
		assertEquals(root.width - (8 * density).toInt(), progress.right)
		assertEquals(root.height - (4 * density).toInt(), progress.bottom)
		assertEquals((14 * density).toInt(), progress.height)
		assertTrue(overlays.titleView.bottom < progress.top)
	}

	private class MeasureHost(context: Context) : FrameLayout(context) {
		var requests = 0
		override fun requestLayout() {
			super.requestLayout()
			requests++
		}
	}

	private fun model() = MangaGridModel(SampleData.manga.copy(title = "A long manga title that must wrap and ellipsize safely"),
		null, 115, null, false, false)

	private fun layout(view: View, widthDp: Int, heightDp: Int) {
		val density = view.resources.displayMetrics.density
		val width = (widthDp * density).roundToInt()
		val height = (heightDp * density).roundToInt()
		view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
			View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
		view.layout(0, 0, width, height)
	}
}
