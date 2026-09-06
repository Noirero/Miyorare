package org.koitharu.kotatsu.core.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import androidx.core.graphics.ColorUtils
import kotlin.math.roundToInt

/**
 * Shared Modern-only shape language for Miyorare headers.
 *
 * This drawable is deliberately presentation-only: it never owns navigation, data, filtering,
 * download state, or content behavior. Callers create it only after Modern has been confirmed.
 */
class MiyorareHeaderShapeDrawable(
	private val palette: MiyorareViewPalette,
	private val variant: Variant,
	private val density: Float,
) : Drawable() {

	enum class Variant {
		FAVOURITES_TOP,
		FAVOURITES_BODY,
		DETAILS,
		EXPLORE,
	}

	private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
	private val shapePaint = Paint(Paint.ANTI_ALIAS_FLAG)
	private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
		style = Paint.Style.STROKE
		strokeCap = Paint.Cap.ROUND
		strokeJoin = Paint.Join.ROUND
	}
	private var drawableAlpha = 255

	override fun draw(canvas: Canvas) {
		val b = bounds
		if (b.isEmpty) return
		val width = b.width().toFloat()
		val height = b.height().toFloat()
		if (width <= 0f || height <= 0f) return

		canvas.save()
		canvas.translate(b.left.toFloat(), b.top.toFloat())
		val container = buildContainerPath(width, height)
		canvas.clipPath(container)

		fillPaint.shader = LinearGradient(
			0f,
			0f,
			if (variant == Variant.DETAILS) 0f else width,
			height,
			baseColors(),
			null,
			Shader.TileMode.CLAMP,
		)
		canvas.drawPath(container, fillPaint)
		fillPaint.shader = null

		when (variant) {
			Variant.FAVOURITES_TOP -> drawFavouritesTop(canvas, width, height)
			Variant.FAVOURITES_BODY -> drawFavouritesBody(canvas, width, height)
			Variant.DETAILS -> drawDetails(canvas, width, height)
			Variant.EXPLORE -> drawExplore(canvas, width, height)
		}

		if (variant == Variant.FAVOURITES_BODY || variant == Variant.EXPLORE) {
			strokePaint.strokeWidth = (0.9f * density).coerceAtLeast(1f)
			strokePaint.color = withDrawableAlpha(palette.borderHighlight, if (variant == Variant.FAVOURITES_BODY) 0.48f else 0.28f)
			canvas.drawPath(container, strokePaint)
		}

		canvas.restore()
	}

	private fun baseColors(): IntArray = when (variant) {
		Variant.FAVOURITES_TOP -> intArrayOf(
			withDrawableAlpha(ColorUtils.blendARGB(palette.surfaceGradientStart, palette.primary, 0.16f), 1f),
			withDrawableAlpha(ColorUtils.blendARGB(palette.surfaceGradientMiddle, palette.accent, 0.10f), 1f),
			withDrawableAlpha(palette.surfaceGradientStart, 1f),
		)
		Variant.FAVOURITES_BODY -> intArrayOf(
			withDrawableAlpha(ColorUtils.blendARGB(palette.surfaceGradientStart, palette.primary, 0.14f), 1f),
			withDrawableAlpha(ColorUtils.blendARGB(palette.surfaceGradientMiddle, palette.accent, 0.12f), 1f),
			withDrawableAlpha(ColorUtils.blendARGB(palette.surfaceGradientEnd, palette.surface, 0.18f), 1f),
		)
		Variant.DETAILS -> intArrayOf(
			withDrawableAlpha(ColorUtils.blendARGB(palette.surfaceGradientStart, palette.primary, 0.10f), 0.92f),
			withDrawableAlpha(ColorUtils.blendARGB(palette.surfaceGradientMiddle, palette.accent, 0.08f), 0.56f),
			Color.TRANSPARENT,
		)
		Variant.EXPLORE -> intArrayOf(
			withDrawableAlpha(ColorUtils.blendARGB(palette.background, palette.surfaceContainer, 0.68f), 1f),
			withDrawableAlpha(ColorUtils.blendARGB(palette.background, palette.primary, 0.045f), 1f),
			withDrawableAlpha(palette.background, 1f),
		)
	}

	private fun buildContainerPath(width: Float, height: Float): Path {
		val radius = when (variant) {
			Variant.FAVOURITES_BODY -> MiyorareVisualTokens.RADIUS_DIALOG_DP * density
			Variant.EXPLORE -> MiyorareVisualTokens.RADIUS_SURFACE_DP * density
			else -> 0f
		}
		return Path().apply {
			if (radius <= 0f) {
				addRect(0f, 0f, width, height, Path.Direction.CW)
			} else {
				addRoundRect(
					RectF(0f, 0f, width, height),
					floatArrayOf(0f, 0f, 0f, 0f, radius, radius, radius, radius),
					Path.Direction.CW,
				)
			}
		}
	}

	private fun drawFavouritesTop(canvas: Canvas, width: Float, height: Float) {
		// A cropped orbital form connects the shared search chrome to the decorative body below.
		shapePaint.color = withDrawableAlpha(palette.primary, 0.12f)
		canvas.drawOval(RectF(width * 0.62f, -height * 0.95f, width * 1.10f, height * 1.08f), shapePaint)

		strokePaint.strokeWidth = 1.35f * density
		strokePaint.color = withDrawableAlpha(palette.accent, 0.30f)
		canvas.drawArc(RectF(width * 0.68f, -height * 0.70f, width * 1.07f, height * 0.86f), 126f, 116f, false, strokePaint)

		strokePaint.strokeWidth = 0.9f * density
		strokePaint.color = withDrawableAlpha(palette.primary, 0.24f)
		canvas.drawArc(RectF(-width * 0.10f, -height * 0.68f, width * 0.46f, height * 1.34f), 210f, 94f, false, strokePaint)
	}

	private fun drawFavouritesBody(canvas: Canvas, width: Float, height: Float) {
		// Large off-canvas forms make the header visibly shaped without competing with controls.
		shapePaint.color = withDrawableAlpha(palette.primary, 0.13f)
		canvas.drawOval(RectF(width * 0.61f, -height * 0.23f, width * 1.16f, height * 0.70f), shapePaint)

		shapePaint.color = withDrawableAlpha(palette.accent, 0.085f)
		canvas.drawOval(RectF(-width * 0.16f, height * 0.54f, width * 0.42f, height * 1.22f), shapePaint)

		strokePaint.strokeWidth = 1.65f * density
		strokePaint.color = withDrawableAlpha(palette.accent, 0.34f)
		canvas.drawArc(RectF(width * 0.54f, -height * 0.18f, width * 1.08f, height * 0.76f), 132f, 154f, false, strokePaint)

		strokePaint.strokeWidth = 1.05f * density
		strokePaint.color = withDrawableAlpha(palette.primary, 0.30f)
		canvas.drawArc(RectF(-width * 0.18f, height * 0.42f, width * 0.46f, height * 1.16f), 198f, 126f, false, strokePaint)

		// Small leaf cluster echoes the reference artwork while remaining abstract/preset-neutral.
		drawLeaf(canvas, width * 0.84f, height * 0.17f, 18f * density, 7f * density, -24f, palette.accent, 0.26f)
		drawLeaf(canvas, width * 0.90f, height * 0.23f, 15f * density, 6f * density, 28f, palette.primary, 0.24f)
		drawLeaf(canvas, width * 0.79f, height * 0.26f, 12f * density, 5f * density, 52f, palette.accent, 0.20f)
	}

	private fun drawDetails(canvas: Canvas, width: Float, height: Float) {
		// Details stays semi-decorative: one restrained orbital silhouette plus two light leaves.
		shapePaint.color = withDrawableAlpha(palette.primary, 0.095f)
		canvas.drawOval(RectF(width * 0.66f, -height * 0.92f, width * 1.14f, height * 0.86f), shapePaint)

		strokePaint.strokeWidth = 1.15f * density
		strokePaint.color = withDrawableAlpha(palette.accent, 0.25f)
		canvas.drawArc(RectF(width * 0.64f, -height * 0.68f, width * 1.08f, height * 0.78f), 132f, 120f, false, strokePaint)

		drawLeaf(canvas, width * 0.86f, height * 0.34f, 12f * density, 4.5f * density, -18f, palette.accent, 0.18f)
		drawLeaf(canvas, width * 0.91f, height * 0.50f, 9f * density, 3.8f * density, 26f, palette.primary, 0.16f)
	}

	private fun drawExplore(canvas: Canvas, width: Float, height: Float) {
		// Explore keeps ~90% of its old structure: only a soft curved accent identifies Modern.
		shapePaint.color = withDrawableAlpha(palette.primary, 0.045f)
		canvas.drawOval(RectF(width * 0.68f, -height * 0.28f, width * 1.10f, height * 0.50f), shapePaint)

		strokePaint.strokeWidth = 0.9f * density
		strokePaint.color = withDrawableAlpha(palette.primary, 0.16f)
		canvas.drawArc(RectF(width * 0.62f, -height * 0.22f, width * 1.06f, height * 0.48f), 142f, 112f, false, strokePaint)

		drawLeaf(canvas, width * 0.88f, height * 0.13f, 9f * density, 3.5f * density, -22f, palette.accent, 0.11f)
	}

	private fun drawLeaf(
		canvas: Canvas,
		centerX: Float,
		centerY: Float,
		radiusX: Float,
		radiusY: Float,
		rotation: Float,
		color: Int,
		alpha: Float,
	) {
		val path = Path().apply {
			moveTo(centerX - radiusX, centerY)
			cubicTo(
				centerX - radiusX * 0.35f,
				centerY - radiusY,
				centerX + radiusX * 0.45f,
				centerY - radiusY,
				centerX + radiusX,
				centerY,
			)
			cubicTo(
				centerX + radiusX * 0.38f,
				centerY + radiusY,
				centerX - radiusX * 0.45f,
				centerY + radiusY,
				centerX - radiusX,
				centerY,
			)
			close()
		}
		shapePaint.color = withDrawableAlpha(color, alpha)
		canvas.save()
		canvas.rotate(rotation, centerX, centerY)
		canvas.drawPath(path, shapePaint)
		canvas.restore()
	}

	private fun withDrawableAlpha(color: Int, fraction: Float): Int {
		val sourceAlpha = Color.alpha(color)
		val scaled = (sourceAlpha * fraction * (drawableAlpha / 255f)).roundToInt().coerceIn(0, 255)
		return ColorUtils.setAlphaComponent(color, scaled)
	}

	override fun setAlpha(alpha: Int) {
		drawableAlpha = alpha.coerceIn(0, 255)
		invalidateSelf()
	}

	override fun setColorFilter(colorFilter: ColorFilter?) {
		fillPaint.colorFilter = colorFilter
		shapePaint.colorFilter = colorFilter
		strokePaint.colorFilter = colorFilter
		invalidateSelf()
	}

	@Deprecated("Deprecated in Android framework")
	override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
