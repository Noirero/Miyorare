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
import org.koitharu.kotatsu.list.ui.model.ModernCoverContext
import org.koitharu.kotatsu.list.ui.model.ModernCoverState
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
				cover.bind(model().copy(counter = count, unreadCount = count)) {}
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


	/**
	 * Real Android View measurement (not a mock or Compose preview).
	 * Each row records the actual visible overlay coverage, plus an apples-to-apples
	 * analytical before/after indicator-tile comparison using the prior metric curve.
	 * Tiny covers can never fit every label and the 48dp action; any omission must
	 * retain its spoken accessibility description.
	 */
	fun compactGridCoverageAndCollision(context: Context) {
		for (densityDpi in listOf(240, 420)) for (fontScale in listOf(1f, 1.5f)) {
			val config = Configuration(context.resources.configuration).apply {
				this.densityDpi = densityDpi
				this.fontScale = fontScale
			}
			val themed = ContextThemeWrapper(context.createConfigurationContext(config), R.style.Theme_Kotatsu)
			val density = themed.resources.displayMetrics.density
			for (columns in 3..5) for (gridSize in listOf(50, 55, 60, 69, 70, 100)) {
				// Same proportional card sizing as Modern Favourites, using a 396dp viewport.
				val widthDp = (396f / columns - 6f).roundToInt()
				val heightDp = (widthDp / org.koitharu.kotatsu.core.ui.MiyorareFavouritesVisualSpec.MANGA_CARD_ASPECT_RATIO *
					gridSize / 100f).roundToInt().coerceAtLeast(1)
				val metrics = FavouriteCoverOverlayMetrics.resolve(widthDp.toFloat(), heightDp.toFloat())
				val oldScale = minOf(widthDp / 128f, heightDp / 152f).coerceIn(0.8f, 1.4f)
				val oldStatus = (22f * oldScale).coerceIn(18f, 30f)
				val oldUnread = (24f * oldScale).coerceIn(22f, 34f)
				val modeledBefore = (3 * oldStatus * oldStatus + oldUnread * oldUnread) / (widthDp * heightDp) * 100f
				val modeledAfter = (3 * metrics.statusSize * metrics.statusSize +
					metrics.unreadSize * metrics.unreadSize) / (widthDp * heightDp) * 100f
				assertTrue("Indicator-area budget must not grow at grid=$gridSize, columns=$columns",
					modeledAfter <= modeledBefore + 0.01f)
				for (unreadCount in listOf(7, 31, 311)) {
					val cover = FavouriteCoverOverlayView(themed)
					val current = model().copy(isFavorite = true, isSaved = true, isPinned = true,
						isLocalSource = true, languageLabel = "EN", showContinueReading = true,
						isTitleOverCover = true, unreadCount = unreadCount)
					cover.bind(current) {}
					layout(cover, widthDp, heightDp)
					val children = cover.children.filter { it.isVisible }.toList()
					val limit = cover.height - (18 * density).roundToInt()
					for (child in children) {
						assertTrue("outside grid=$gridSize columns=$columns font=$fontScale: ${child.id}",
							child.left >= 0 && child.top >= 0 && child.right <= cover.width &&
								child.bottom <= limit)
					}
					for (i in children.indices) for (j in i + 1 until children.size) {
						val first = children[i]
						val next = children[j]
						assertFalse("collision grid=$gridSize columns=$columns unread=$unreadCount: ${first.id} / ${next.id}",
							Rect.intersects(Rect(first.left, first.top, first.right, first.bottom),
								Rect(next.left, next.top, next.right, next.bottom)))
					}
					val visibleArea = children.sumOf { it.width.toLong() * it.height }
					val measuredCoverage = visibleArea.toDouble() * 100 / (cover.width.toLong() * cover.height)
					val readable = cover.titleView.contentDescription?.toString().orEmpty() +
						cover.contentDescription?.toString().orEmpty()
					if (!cover.findViewById<View>(R.id.cover_permanent_status).isVisible) {
						assertTrue("Omitted status must remain accessible", readable.contains(themed.getString(R.string.in_library)))
					}
					if (!cover.findViewById<View>(R.id.cover_unread).isVisible) {
						assertTrue("Omitted unread must remain accessible", readable.contains(themed.getString(R.string.unread)))
					}
					android.util.Log.i("MiyorareCoverAudit",
						"grid=$gridSize cols=$columns unread=$unreadCount density=$densityDpi font=$fontScale " +
							"cover=${widthDp}x${heightDp}dp modeledBefore=${"%.1f".format(java.util.Locale.US, modeledBefore)}% " +
							"modeledAfter=${"%.1f".format(java.util.Locale.US, modeledAfter)}% " +
							"measuredAfter=${"%.1f".format(java.util.Locale.US, measuredCoverage)}%")
					if (columns == 5 && unreadCount == 31 && fontScale == 1f &&
						densityDpi == 420 && gridSize in listOf(50, 69, 100)) {
						captureOverlayScreenshot(cover, gridSize)
					}
				}
			}
		}
	}

	private fun captureOverlayScreenshot(cover: FavouriteCoverOverlayView, gridSize: Int) {
		val bitmap = android.graphics.Bitmap.createBitmap(cover.width, cover.height,
			android.graphics.Bitmap.Config.ARGB_8888)
		val canvas = android.graphics.Canvas(bitmap)
		canvas.drawColor(android.graphics.Color.rgb(32, 49, 62))
		cover.draw(canvas) // actual Android-rendered overlay on a neutral fixture background
		val targetWidth = 112
		val thumb = android.graphics.Bitmap.createScaledBitmap(bitmap, targetWidth,
			(cover.height * targetWidth.toFloat() / cover.width).roundToInt().coerceAtLeast(1), true)
		val data = java.io.ByteArrayOutputStream()
		thumb.compress(android.graphics.Bitmap.CompressFormat.JPEG, 55, data)
		val encoded = android.util.Base64.encodeToString(data.toByteArray(), android.util.Base64.NO_WRAP)
		val chunks = encoded.chunked(850)
		for ((part, chunk) in chunks.withIndex()) {
			// JUnit system-out is retained in the Android test evidence artifact; no uploads to web services.
			System.out.println("MIYORARE_COVER_SNAPSHOT|after|grid=$gridSize|part=${part + 1}|total=${chunks.size}|$chunk")
		}
		thumb.recycle()
		bitmap.recycle()
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

	private fun FavouriteCoverOverlayView.bind(model: MangaGridModel, onReadClick: (View) -> Unit) =
		bind(ModernCoverState.from(model, ModernCoverContext.COLLECTION), onReadClick = onReadClick)

	private fun model() = MangaGridModel(SampleData.manga.copy(title = "A long manga title that must wrap and ellipsize safely"),
		null, 115, null, false, false, unreadCount = 115)

	private fun layout(view: View, widthDp: Int, heightDp: Int) {
		val density = view.resources.displayMetrics.density
		val width = (widthDp * density).roundToInt()
		val height = (heightDp * density).roundToInt()
		view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
			View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
		view.layout(0, 0, width, height)
	}
}
