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
import org.koitharu.kotatsu.core.prefs.MiyorareThemePreset
import kotlin.math.roundToInt

/**
 * Reference-inspired Modern header artwork.
 *
 * The container hierarchy stays identical across presets, while each curated preset owns a
 * distinct motif family matching the approved reference direction:
 * Miyorare = futuristic orbit/geometry, Sakura = blossoms/petals, Violet = ribbon/crescent,
 * Cyan = clean waves/droplets, Emerald = leaves/vine, Amber = warm flame/feather forms.
 *
 * This drawable is presentation-only. Callers create it only after Modern has been confirmed,
 * so Classic never receives these shapes or any of their layout-independent decoration.
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
	private val shapePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
		style = Paint.Style.FILL
	}
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

		drawPresetMotif(canvas, width, height)

		if (variant == Variant.FAVOURITES_BODY || variant == Variant.EXPLORE) {
			strokePaint.strokeWidth = (0.9f * density).coerceAtLeast(1f)
			strokePaint.color = withDrawableAlpha(
				palette.borderHighlight,
				if (variant == Variant.FAVOURITES_BODY) 0.48f else 0.26f,
			)
			canvas.drawPath(container, strokePaint)
		}

		canvas.restore()
	}

	private fun baseColors(): IntArray = when (variant) {
		Variant.FAVOURITES_TOP -> intArrayOf(
			withDrawableAlpha(ColorUtils.blendARGB(palette.surfaceGradientStart, palette.primary, 0.18f), 1f),
			withDrawableAlpha(ColorUtils.blendARGB(palette.surfaceGradientMiddle, palette.accent, 0.11f), 1f),
			withDrawableAlpha(palette.surfaceGradientStart, 1f),
		)
		Variant.FAVOURITES_BODY -> intArrayOf(
			withDrawableAlpha(ColorUtils.blendARGB(palette.surfaceGradientStart, palette.primary, 0.16f), 1f),
			withDrawableAlpha(ColorUtils.blendARGB(palette.surfaceGradientMiddle, palette.accent, 0.13f), 1f),
			withDrawableAlpha(ColorUtils.blendARGB(palette.surfaceGradientEnd, palette.surface, 0.18f), 1f),
		)
		Variant.DETAILS -> intArrayOf(
			withDrawableAlpha(ColorUtils.blendARGB(palette.surfaceGradientStart, palette.primary, 0.10f), 0.92f),
			withDrawableAlpha(ColorUtils.blendARGB(palette.surfaceGradientMiddle, palette.accent, 0.08f), 0.54f),
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

	private fun drawPresetMotif(canvas: Canvas, width: Float, height: Float) {
		when (palette.preset) {
			MiyorareThemePreset.MIYORARE,
			MiyorareThemePreset.CUSTOM -> drawMiyorareMotif(canvas, width, height)
			MiyorareThemePreset.SAKURA -> drawSakuraMotif(canvas, width, height)
			MiyorareThemePreset.VIOLET -> drawVioletMotif(canvas, width, height)
			MiyorareThemePreset.CYAN -> drawCyanMotif(canvas, width, height)
			MiyorareThemePreset.EMERALD -> drawEmeraldMotif(canvas, width, height)
			MiyorareThemePreset.AMBER -> drawAmberMotif(canvas, width, height)
		}
	}

	private fun motifStrength(): Float = when (variant) {
		Variant.FAVOURITES_BODY -> 1f
		Variant.FAVOURITES_TOP -> 0.72f
		Variant.DETAILS -> 0.46f
		Variant.EXPLORE -> 0.28f
	}

	private fun drawMiyorareMotif(canvas: Canvas, width: Float, height: Float) {
		val s = motifStrength()
		val cx = width * 0.84f
		val cy = if (variant == Variant.FAVOURITES_TOP) height * 0.54f else height * 0.22f
		val rx = width * if (variant == Variant.FAVOURITES_BODY) 0.28f else 0.22f
		val ry = height * if (variant == Variant.FAVOURITES_BODY) 0.62f else 0.72f

		shapePaint.color = withDrawableAlpha(palette.primary, 0.09f * s)
		canvas.drawOval(RectF(cx - rx, cy - ry, cx + rx, cy + ry), shapePaint)

		strokePaint.strokeWidth = (1.7f * density).coerceAtLeast(1f)
		strokePaint.color = withDrawableAlpha(palette.secondary, 0.44f * s)
		canvas.drawArc(RectF(cx - rx * 1.05f, cy - ry * 0.82f, cx + rx * 1.05f, cy + ry * 0.82f), 132f, 154f, false, strokePaint)

		strokePaint.strokeWidth = (1.0f * density).coerceAtLeast(1f)
		strokePaint.color = withDrawableAlpha(palette.accent, 0.42f * s)
		canvas.drawArc(RectF(cx - rx * 0.72f, cy - ry, cx + rx * 0.72f, cy + ry), 302f, 122f, false, strokePaint)

		drawDiamond(canvas, width * 0.92f, height * 0.16f, 10f * density, palette.accent, 0.34f * s, 18f)
		drawDiamond(canvas, width * 0.78f, height * 0.29f, 6f * density, palette.secondary, 0.30f * s, -12f)
		if (variant == Variant.FAVOURITES_BODY) {
			drawDiamond(canvas, width * 0.88f, height * 0.42f, 4.5f * density, palette.primary, 0.28f, 34f)
		}
	}

	private fun drawSakuraMotif(canvas: Canvas, width: Float, height: Float) {
		val s = motifStrength()
		val mainX = width * 0.88f
		val mainY = if (variant == Variant.FAVOURITES_TOP) height * 0.62f else height * 0.18f
		val radius = when (variant) {
			Variant.FAVOURITES_BODY -> 20f
			Variant.FAVOURITES_TOP -> 13f
			Variant.DETAILS -> 12f
			Variant.EXPLORE -> 9f
		} * density

		drawBlossom(canvas, mainX, mainY, radius, palette.primary, 0.40f * s)
		drawBlossom(canvas, width * 0.80f, mainY + radius * 1.10f, radius * 0.72f, palette.accent, 0.34f * s)
		if (variant == Variant.FAVOURITES_BODY || variant == Variant.FAVOURITES_TOP) {
			drawBlossom(canvas, width * 0.95f, mainY + radius * 1.55f, radius * 0.55f, palette.secondary, 0.30f * s)
		}

		drawPetal(canvas, width * 0.72f, mainY + radius * 0.32f, radius * 0.54f, palette.accent, 0.28f * s, -30f)
		drawPetal(canvas, width * 0.92f, mainY + radius * 2.25f, radius * 0.40f, palette.primary, 0.24f * s, 28f)
		if (variant == Variant.FAVOURITES_BODY) {
			drawPetal(canvas, width * 0.69f, height * 0.42f, radius * 0.33f, palette.secondary, 0.20f, 58f)
		}
	}

	private fun drawVioletMotif(canvas: Canvas, width: Float, height: Float) {
		val s = motifStrength()
		val startX = width * 0.66f
		val startY = if (variant == Variant.FAVOURITES_TOP) height * 0.84f else height * 0.12f

		val ribbon = Path().apply {
			moveTo(startX, startY)
			cubicTo(width * 0.80f, startY - height * 0.24f, width * 0.92f, startY + height * 0.04f, width * 1.08f, startY - height * 0.20f)
		}
		strokePaint.strokeWidth = (8f * density * s).coerceAtLeast(1.2f * density)
		strokePaint.color = withDrawableAlpha(palette.primary, 0.13f * s)
		canvas.drawPath(ribbon, strokePaint)

		strokePaint.strokeWidth = (1.5f * density).coerceAtLeast(1f)
		strokePaint.color = withDrawableAlpha(palette.accent, 0.46f * s)
		canvas.drawPath(ribbon, strokePaint)

		val crescent = RectF(width * 0.74f, startY - height * 0.28f, width * 1.12f, startY + height * 0.34f)
		strokePaint.strokeWidth = (1.3f * density).coerceAtLeast(1f)
		strokePaint.color = withDrawableAlpha(palette.secondary, 0.40f * s)
		canvas.drawArc(crescent, 120f, 142f, false, strokePaint)

		drawFourPointStar(canvas, width * 0.88f, startY + height * 0.10f, 8f * density, palette.accent, 0.38f * s)
		drawFourPointStar(canvas, width * 0.76f, startY + height * 0.22f, 4.5f * density, palette.primary, 0.28f * s)
	}

	private fun drawCyanMotif(canvas: Canvas, width: Float, height: Float) {
		val s = motifStrength()
		val baseY = if (variant == Variant.FAVOURITES_TOP) height * 0.70f else height * 0.22f
		val wave1 = Path().apply {
			moveTo(width * 0.60f, baseY)
			cubicTo(width * 0.70f, baseY - height * 0.20f, width * 0.79f, baseY + height * 0.18f, width * 0.88f, baseY)
			cubicTo(width * 0.94f, baseY - height * 0.12f, width * 1.00f, baseY - height * 0.04f, width * 1.06f, baseY - height * 0.18f)
		}
		strokePaint.strokeWidth = (1.7f * density).coerceAtLeast(1f)
		strokePaint.color = withDrawableAlpha(palette.secondary, 0.46f * s)
		canvas.drawPath(wave1, strokePaint)

		val wave2 = Path().apply {
			moveTo(width * 0.69f, baseY + height * 0.18f)
			cubicTo(width * 0.80f, baseY + height * 0.03f, width * 0.88f, baseY + height * 0.29f, width * 1.05f, baseY + height * 0.08f)
		}
		strokePaint.strokeWidth = (5.5f * density * s).coerceAtLeast(1.3f * density)
		strokePaint.color = withDrawableAlpha(palette.primary, 0.085f * s)
		canvas.drawPath(wave2, strokePaint)

		drawDroplet(canvas, width * 0.90f, baseY - height * 0.12f, 9f * density, palette.primary, 0.30f * s, 22f)
		drawDroplet(canvas, width * 0.78f, baseY + height * 0.20f, 5.5f * density, palette.accent, 0.24f * s, -18f)
		shapePaint.color = withDrawableAlpha(palette.secondary, 0.22f * s)
		canvas.drawCircle(width * 0.96f, baseY + height * 0.18f, 3.5f * density, shapePaint)
	}

	private fun drawEmeraldMotif(canvas: Canvas, width: Float, height: Float) {
		val s = motifStrength()
		val startX = width * 0.69f
		val startY = if (variant == Variant.FAVOURITES_TOP) height * 0.94f else height * 0.36f
		val vine = Path().apply {
			moveTo(startX, startY)
			cubicTo(width * 0.77f, startY - height * 0.36f, width * 0.91f, startY - height * 0.16f, width * 1.05f, startY - height * 0.52f)
		}
		strokePaint.strokeWidth = (1.25f * density).coerceAtLeast(1f)
		strokePaint.color = withDrawableAlpha(palette.secondary, 0.38f * s)
		canvas.drawPath(vine, strokePaint)

		val leafSize = when (variant) {
			Variant.FAVOURITES_BODY -> 18f
			Variant.FAVOURITES_TOP -> 12f
			Variant.DETAILS -> 11f
			Variant.EXPLORE -> 8f
		} * density
		drawLeaf(canvas, width * 0.79f, startY - height * 0.22f, leafSize, leafSize * 0.42f, -42f, palette.primary, 0.34f * s)
		drawLeaf(canvas, width * 0.87f, startY - height * 0.27f, leafSize * 0.92f, leafSize * 0.40f, 38f, palette.accent, 0.31f * s)
		drawLeaf(canvas, width * 0.94f, startY - height * 0.40f, leafSize * 0.76f, leafSize * 0.36f, -18f, palette.secondary, 0.28f * s)
		if (variant == Variant.FAVOURITES_BODY) {
			drawLeaf(canvas, width * 0.72f, startY - height * 0.06f, leafSize * 0.66f, leafSize * 0.32f, 56f, palette.primary, 0.24f)
		}
	}

	private fun drawAmberMotif(canvas: Canvas, width: Float, height: Float) {
		val s = motifStrength()
		val anchorX = width * 0.90f
		val anchorY = if (variant == Variant.FAVOURITES_TOP) height * 0.72f else height * 0.16f
		val flameSize = when (variant) {
			Variant.FAVOURITES_BODY -> 28f
			Variant.FAVOURITES_TOP -> 18f
			Variant.DETAILS -> 16f
			Variant.EXPLORE -> 11f
		} * density

		drawFlame(canvas, anchorX, anchorY, flameSize, palette.primary, 0.34f * s, -28f)
		drawFlame(canvas, width * 0.82f, anchorY + flameSize * 0.82f, flameSize * 0.82f, palette.secondary, 0.30f * s, -8f)
		drawFlame(canvas, width * 0.96f, anchorY + flameSize * 1.18f, flameSize * 0.70f, palette.accent, 0.27f * s, 18f)

		val sweep = Path().apply {
			moveTo(width * 0.70f, anchorY + flameSize * 1.35f)
			cubicTo(width * 0.82f, anchorY + flameSize * 0.34f, width * 0.94f, anchorY + flameSize * 0.72f, width * 1.08f, anchorY - flameSize * 0.46f)
		}
		strokePaint.strokeWidth = (1.4f * density).coerceAtLeast(1f)
		strokePaint.color = withDrawableAlpha(palette.accent, 0.40f * s)
		canvas.drawPath(sweep, strokePaint)
	}

	private fun drawBlossom(
		canvas: Canvas,
		centerX: Float,
		centerY: Float,
		radius: Float,
		color: Int,
		alpha: Float,
	) {
		shapePaint.color = withDrawableAlpha(color, alpha)
		for (index in 0 until 5) {
			canvas.save()
			canvas.rotate(index * 72f, centerX, centerY)
			canvas.drawOval(
				RectF(
					centerX - radius * 0.34f,
					centerY - radius,
					centerX + radius * 0.34f,
					centerY + radius * 0.08f,
				),
				shapePaint,
			)
			canvas.restore()
		}
		shapePaint.color = withDrawableAlpha(palette.accent, alpha * 0.74f)
		canvas.drawCircle(centerX, centerY, radius * 0.16f, shapePaint)
	}

	private fun drawPetal(
		canvas: Canvas,
		centerX: Float,
		centerY: Float,
		radius: Float,
		color: Int,
		alpha: Float,
		rotation: Float,
	) {
		shapePaint.color = withDrawableAlpha(color, alpha)
		canvas.save()
		canvas.rotate(rotation, centerX, centerY)
		canvas.drawOval(
			RectF(centerX - radius * 0.30f, centerY - radius, centerX + radius * 0.30f, centerY + radius),
			shapePaint,
		)
		canvas.restore()
	}

	private fun drawDiamond(
		canvas: Canvas,
		centerX: Float,
		centerY: Float,
		radius: Float,
		color: Int,
		alpha: Float,
		rotation: Float,
	) {
		val path = Path().apply {
			moveTo(centerX, centerY - radius)
			lineTo(centerX + radius * 0.72f, centerY)
			lineTo(centerX, centerY + radius)
			lineTo(centerX - radius * 0.72f, centerY)
			close()
		}
		shapePaint.color = withDrawableAlpha(color, alpha)
		canvas.save()
		canvas.rotate(rotation, centerX, centerY)
		canvas.drawPath(path, shapePaint)
		canvas.restore()
	}

	private fun drawFourPointStar(
		canvas: Canvas,
		centerX: Float,
		centerY: Float,
		radius: Float,
		color: Int,
		alpha: Float,
	) {
		val path = Path().apply {
			moveTo(centerX, centerY - radius)
			lineTo(centerX + radius * 0.18f, centerY - radius * 0.18f)
			lineTo(centerX + radius, centerY)
			lineTo(centerX + radius * 0.18f, centerY + radius * 0.18f)
			lineTo(centerX, centerY + radius)
			lineTo(centerX - radius * 0.18f, centerY + radius * 0.18f)
			lineTo(centerX - radius, centerY)
			lineTo(centerX - radius * 0.18f, centerY - radius * 0.18f)
			close()
		}
		shapePaint.color = withDrawableAlpha(color, alpha)
		canvas.drawPath(path, shapePaint)
	}

	private fun drawDroplet(
		canvas: Canvas,
		centerX: Float,
		centerY: Float,
		radius: Float,
		color: Int,
		alpha: Float,
		rotation: Float,
	) {
		val path = Path().apply {
			moveTo(centerX, centerY - radius)
			cubicTo(centerX + radius * 0.68f, centerY - radius * 0.25f, centerX + radius * 0.70f, centerY + radius * 0.48f, centerX, centerY + radius)
			cubicTo(centerX - radius * 0.70f, centerY + radius * 0.48f, centerX - radius * 0.68f, centerY - radius * 0.25f, centerX, centerY - radius)
			close()
		}
		shapePaint.color = withDrawableAlpha(color, alpha)
		canvas.save()
		canvas.rotate(rotation, centerX, centerY)
		canvas.drawPath(path, shapePaint)
		canvas.restore()
	}

	private fun drawFlame(
		canvas: Canvas,
		centerX: Float,
		centerY: Float,
		radius: Float,
		color: Int,
		alpha: Float,
		rotation: Float,
	) {
		val path = Path().apply {
			moveTo(centerX, centerY - radius)
			cubicTo(centerX + radius * 0.78f, centerY - radius * 0.20f, centerX + radius * 0.56f, centerY + radius * 0.46f, centerX, centerY + radius)
			cubicTo(centerX - radius * 0.34f, centerY + radius * 0.34f, centerX - radius * 0.58f, centerY - radius * 0.10f, centerX - radius * 0.10f, centerY - radius * 0.54f)
			cubicTo(centerX - radius * 0.06f, centerY - radius * 0.18f, centerX + radius * 0.08f, centerY - radius * 0.24f, centerX, centerY - radius)
			close()
		}
		shapePaint.color = withDrawableAlpha(color, alpha)
		canvas.save()
		canvas.rotate(rotation, centerX, centerY)
		canvas.drawPath(path, shapePaint)
		canvas.restore()
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
		val scaled = (sourceAlpha * fraction.coerceIn(0f, 1f) * (drawableAlpha / 255f))
			.roundToInt()
			.coerceIn(0, 255)
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