package org.koitharu.kotatsu.core.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.ColorUtils
import org.koitharu.kotatsu.R
import kotlin.math.roundToInt

/** Dedicated renderer for explicit Private Favourites themes. */
class MiyorarePrivateFavouritesHeaderDrawable(
	private val palette: MiyorareViewPalette,
	private val variant: MiyorareHeaderShapeDrawable.Variant,
	private val spec: PrivateFavouritesVisualSpec,
	private val density: Float,
) : Drawable() {

	private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
	private val artworkPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
		isDither = true
	}
	private val scrimPaint = Paint(Paint.ANTI_ALIAS_FLAG)
	private var drawableAlpha = 255
	private val artwork: Bitmap? = acquireArtwork(palette, spec.artworkRes)

	override fun draw(canvas: Canvas) {
		val b = bounds
		if (b.isEmpty) return
		val width = b.width().toFloat()
		val height = b.height().toFloat()
		if (width <= 0f || height <= 0f) return

		canvas.save()
		canvas.translate(b.left.toFloat(), b.top.toFloat())

		fillPaint.shader = LinearGradient(
			0f,
			0f,
			width,
			height,
			intArrayOf(
				withDrawableAlpha(palette.surfaceGradientStart),
				withDrawableAlpha(palette.surfaceGradientMiddle),
				withDrawableAlpha(palette.surfaceGradientEnd),
			),
			null,
			Shader.TileMode.CLAMP,
		)
		canvas.drawRect(0f, 0f, width, height, fillPaint)
		fillPaint.shader = null

		drawArtwork(canvas, width)
		drawReadabilityScrim(canvas, width, height)
		canvas.restore()
	}

	private fun drawArtwork(canvas: Canvas, width: Float) {
		val bitmap = artwork ?: return
		val baseScale = width / bitmap.width.toFloat()
		val scale = baseScale * spec.zoom
		if (scale <= 0f) return
		val drawnWidth = bitmap.width * scale
		val extraWidth = (drawnWidth - width).coerceAtLeast(0f)
		val x = -extraWidth * spec.focalX.coerceIn(0f, 1f)
		val y = width * spec.verticalShift - favouritesArtworkTopOffset()

		artworkPaint.alpha = drawableAlpha.coerceIn(0, 255)
		artworkPaint.colorFilter = null
		canvas.save()
		canvas.translate(x, y)
		canvas.scale(scale, scale)
		canvas.drawBitmap(bitmap, 0f, 0f, artworkPaint)
		canvas.restore()
	}

	private fun drawReadabilityScrim(canvas: Canvas, width: Float, height: Float) {
		val strong = withDrawableAlpha(spec.scrim)
		val soft = ColorUtils.setAlphaComponent(strong, (android.graphics.Color.alpha(strong) * 0.42f).roundToInt())
		scrimPaint.shader = LinearGradient(
			0f,
			0f,
			width,
			0f,
			intArrayOf(strong, soft, android.graphics.Color.TRANSPARENT),
			floatArrayOf(0f, 0.44f, 0.82f),
			Shader.TileMode.CLAMP,
		)
		canvas.drawRect(0f, 0f, width, height, scrimPaint)
		scrimPaint.shader = null
	}

	private fun favouritesArtworkTopOffset(): Float {
		if (variant != MiyorareHeaderShapeDrawable.Variant.FAVOURITES_BODY) return 0f
		val owner = callback as? View ?: return FALLBACK_TOP_HEIGHT_DP * density
		val appBar = owner.rootView.findViewById<View>(R.id.appbar)
			?: return FALLBACK_TOP_HEIGHT_DP * density
		val root = owner.rootView as? ViewGroup
		if (root != null) {
			val ownerRect = Rect()
			val appBarRect = Rect()
			owner.getDrawingRect(ownerRect)
			appBar.getDrawingRect(appBarRect)
			val split = runCatching {
				root.offsetDescendantRectToMyCoords(owner, ownerRect)
				root.offsetDescendantRectToMyCoords(appBar, appBarRect)
				ownerRect.top - appBarRect.top
			}.getOrNull()
			if (split != null && split > 0) return split.toFloat()
		}
		val measuredSplit = when {
			appBar.height > 0 -> appBar.height
			appBar.measuredHeight > 0 -> appBar.measuredHeight
			else -> 0
		}
		return if (measuredSplit > 0) measuredSplit.toFloat() else FALLBACK_TOP_HEIGHT_DP * density
	}

	private fun withDrawableAlpha(color: Int): Int {
		val alpha = (android.graphics.Color.alpha(color) * (drawableAlpha / 255f)).roundToInt().coerceIn(0, 255)
		return ColorUtils.setAlphaComponent(color, alpha)
	}

	override fun setAlpha(alpha: Int) {
		drawableAlpha = alpha.coerceIn(0, 255)
		invalidateSelf()
	}

	override fun setColorFilter(colorFilter: ColorFilter?) {
		fillPaint.colorFilter = colorFilter
		artworkPaint.colorFilter = colorFilter
		scrimPaint.colorFilter = colorFilter
		invalidateSelf()
	}

	@Deprecated("Deprecated in Android framework")
	override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

	private companion object {
		const val MASTER_WIDTH_PX = 1080
		const val MASTER_HEIGHT_PX = 835
		const val FALLBACK_TOP_HEIGHT_DP = 92f
		const val MAX_CACHE_ENTRIES = 2

		val artworkCache = object : LinkedHashMap<Int, Bitmap>(MAX_CACHE_ENTRIES, 0.75f, true) {
			override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Bitmap>?): Boolean =
				size > MAX_CACHE_ENTRIES
		}

		fun acquireArtwork(palette: MiyorareViewPalette, artworkRes: Int): Bitmap? {
			synchronized(artworkCache) {
				artworkCache[artworkRes]?.let { return it }
			}
			val bitmap = runCatching {
				val drawable = ResourcesCompat.getDrawable(palette.resources, artworkRes, null) ?: return@runCatching null
				Bitmap.createBitmap(MASTER_WIDTH_PX, MASTER_HEIGHT_PX, Bitmap.Config.ARGB_8888).also {
					drawable.setBounds(0, 0, it.width, it.height)
					drawable.draw(Canvas(it))
				}
			}.getOrNull() ?: return null
			synchronized(artworkCache) {
				artworkCache[artworkRes] = bitmap
			}
			return bitmap
		}
	}
}
