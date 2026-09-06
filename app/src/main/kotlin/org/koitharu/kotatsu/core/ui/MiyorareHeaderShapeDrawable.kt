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
 * Each curated preset owns a distinct motif family matching the approved reference:
 * Miyorare = futuristic faceted petals/shards + sparkles,
 * Sakura = blossoms/petals,
 * Violet = violet flower clusters,
 * Cyan = cyan flower + long botanical stems/leaves,
 * Emerald = leafy vine + botanical cluster,
 * Amber = warm autumn branch/leaves.
 *
 * This drawable is presentation-only. Callers create it only after Modern has been confirmed,
 * so Classic never receives these shapes or any of their decoration.
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
		Variant.FAVOURITES_TOP -> 0.78f
		Variant.DETAILS -> 0.48f
		Variant.EXPLORE -> 0.30f
	}

	private fun motifSize(body: Float, top: Float, details: Float, explore: Float): Float = when (variant) {
		Variant.FAVOURITES_BODY -> body
		Variant.FAVOURITES_TOP -> top
		Variant.DETAILS -> details
		Variant.EXPLORE -> explore
	} * density

	/** Miyorare reference: broad faceted/petal-like shards concentrated on the right edge. */
	private fun drawMiyorareMotif(canvas: Canvas, width: Float, height: Float) {
		val s = motifStrength()
		val anchorX = width * 0.91f
		val anchorY = if (variant == Variant.FAVOURITES_TOP) height * 0.54f else height * 0.23f
		val size = motifSize(46f, 30f, 27f, 18f)

		// Stronger than v2 so the motif remains visible after returning to Miyorare.
		drawFacetPetal(canvas, anchorX, anchorY, size, size * 0.42f, -62f, palette.primary, 0.34f * s)
		drawFacetPetal(canvas, anchorX + size * 0.18f, anchorY + size * 0.18f, size * 0.92f, size * 0.38f, -20f, palette.secondary, 0.31f * s)
		drawFacetPetal(canvas, anchorX - size * 0.26f, anchorY + size * 0.30f, size * 0.76f, size * 0.34f, 34f, palette.accent, 0.29f * s)
		drawFacetPetal(canvas, anchorX + size * 0.22f, anchorY - size * 0.28f, size * 0.62f, size * 0.28f, 58f, palette.primary, 0.24f * s)

		if (variant == Variant.FAVOURITES_BODY) {
			drawFacetPetal(canvas, width * 0.82f, height * 0.40f, size * 0.68f, size * 0.27f, -42f, palette.secondary, 0.22f)
		}

		drawFourPointStar(canvas, width * 0.76f, height * 0.13f, size * 0.15f, palette.secondary, 0.32f * s)
		drawFourPointStar(canvas, width * 0.95f, height * 0.08f, size * 0.11f, palette.accent, 0.30f * s)
		drawDiamond(canvas, width * 0.82f, height * 0.28f, size * 0.10f, palette.primary, 0.24f * s, 18f)
	}

	/** Sakura is intentionally unchanged because the user approved this motif. */
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

	/** Violet reference: two/three violet blossoms on the right, not ribbons or crescents. */
	private fun drawVioletMotif(canvas: Canvas, width: Float, height: Float) {
		val s = motifStrength()
		val x = width * 0.89f
		val y = if (variant == Variant.FAVOURITES_TOP) height * 0.62f else height * 0.19f
		val radius = motifSize(22f, 14f, 12f, 9f)

		drawBlossom(canvas, x, y, radius, palette.primary, 0.40f * s)
		drawBlossom(canvas, width * 0.79f, y + radius * 0.85f, radius * 0.63f, palette.secondary, 0.31f * s)
		drawBlossom(canvas, width * 0.96f, y + radius * 1.10f, radius * 0.75f, palette.accent, 0.34f * s)
		if (variant == Variant.FAVOURITES_BODY) {
			drawBlossom(canvas, width * 0.72f, height * 0.34f, radius * 0.40f, palette.primary, 0.20f)
		}
		drawFourPointStar(canvas, width * 0.74f, height * 0.11f, radius * 0.20f, palette.accent, 0.22f * s)
	}

	/** Cyan reference: a large flower at the upper right plus long narrow stems/leaves. */
	private fun drawCyanMotif(canvas: Canvas, width: Float, height: Float) {
		val s = motifStrength()
		val flowerRadius = motifSize(21f, 14f, 12f, 9f)
		val flowerX = width * 0.94f
		val flowerY = if (variant == Variant.FAVOURITES_TOP) height * 0.34f else height * 0.10f

		drawBlossom(canvas, flowerX, flowerY, flowerRadius, palette.primary, 0.36f * s)

		val stem = Path().apply {
			moveTo(width * 1.01f, height * 0.04f)
			cubicTo(width * 0.95f, height * 0.22f, width * 0.90f, height * 0.34f, width * 0.86f, height * 0.58f)
		}
		strokePaint.strokeWidth = (1.2f * density).coerceAtLeast(1f)
		strokePaint.color = withDrawableAlpha(palette.secondary, 0.34f * s)
		canvas.drawPath(stem, strokePaint)

		val leaf = motifSize(19f, 12f, 11f, 8f)
		drawLeaf(canvas, width * 0.91f, height * 0.27f, leaf, leaf * 0.25f, -58f, palette.secondary, 0.27f * s)
		drawLeaf(canvas, width * 0.88f, height * 0.39f, leaf * 0.88f, leaf * 0.23f, 52f, palette.primary, 0.25f * s)
		drawLeaf(canvas, width * 0.84f, height * 0.50f, leaf * 0.75f, leaf * 0.21f, -50f, palette.accent, 0.20f * s)
		if (variant == Variant.FAVOURITES_BODY) {
			drawLeaf(canvas, width * 0.80f, height * 0.62f, leaf * 0.62f, leaf * 0.20f, 48f, palette.secondary, 0.17f)
		}
	}

	/** Emerald reference: thin vine with paired leaves plus a fuller botanical cluster. */
	private fun drawEmeraldMotif(canvas: Canvas, width: Float, height: Float) {
		val s = motifStrength()
		val stem = Path().apply {
			moveTo(width * 1.02f, height * 0.02f)
			cubicTo(width * 0.95f, height * 0.18f, width * 0.93f, height * 0.35f, width * 0.84f, height * 0.60f)
		}
		strokePaint.strokeWidth = (1.25f * density).coerceAtLeast(1f)
		strokePaint.color = withDrawableAlpha(palette.secondary, 0.38f * s)
		canvas.drawPath(stem, strokePaint)

		val leaf = motifSize(20f, 13f, 11f, 8f)
		drawLeaf(canvas, width * 0.96f, height * 0.16f, leaf, leaf * 0.34f, -55f, palette.primary, 0.33f * s)
		drawLeaf(canvas, width * 0.91f, height * 0.25f, leaf * 0.92f, leaf * 0.32f, 42f, palette.secondary, 0.31f * s)
		drawLeaf(canvas, width * 0.90f, height * 0.37f, leaf * 0.80f, leaf * 0.30f, -48f, palette.accent, 0.28f * s)

		val clusterX = width * 0.88f
		val clusterY = if (variant == Variant.FAVOURITES_TOP) height * 0.63f else height * 0.24f
		drawLeafRosette(canvas, clusterX, clusterY, leaf * 0.86f, palette.primary, 0.29f * s)
		if (variant == Variant.FAVOURITES_BODY) {
			drawLeaf(canvas, width * 0.76f, height * 0.34f, leaf * 0.64f, leaf * 0.25f, 58f, palette.secondary, 0.18f)
		}
	}

	/** Amber reference: a slim branch carrying warm autumn leaves. */
	private fun drawAmberMotif(canvas: Canvas, width: Float, height: Float) {
		val s = motifStrength()
		val branch = Path().apply {
			moveTo(width * 1.03f, height * 0.02f)
			cubicTo(width * 0.96f, height * 0.16f, width * 0.91f, height * 0.32f, width * 0.80f, height * 0.57f)
		}
		strokePaint.strokeWidth = (1.25f * density).coerceAtLeast(1f)
		strokePaint.color = withDrawableAlpha(palette.secondary, 0.37f * s)
		canvas.drawPath(branch, strokePaint)

		val leaf = motifSize(21f, 14f, 12f, 9f)
		drawAutumnLeaf(canvas, width * 0.96f, height * 0.16f, leaf, palette.primary, 0.35f * s, -28f)
		drawAutumnLeaf(canvas, width * 0.89f, height * 0.27f, leaf * 0.90f, palette.secondary, 0.32f * s, 20f)
		drawAutumnLeaf(canvas, width * 0.84f, height * 0.38f, leaf * 0.82f, palette.accent, 0.28f * s, -18f)
		if (variant == Variant.FAVOURITES_BODY || variant == Variant.FAVOURITES_TOP) {
			drawAutumnLeaf(canvas, width * 0.94f, height * 0.45f, leaf * 0.66f, palette.primary, 0.22f * s, 36f)
		}
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

	private fun drawFacetPetal(
		canvas: Canvas,
		centerX: Float,
		centerY: Float,
		length: Float,
		width: Float,
		rotation: Float,
		color: Int,
		alpha: Float,
	) {
		val path = Path().apply {
			moveTo(centerX - length, centerY)
			lineTo(centerX - length * 0.12f, centerY - width)
			lineTo(centerX + length, centerY)
			lineTo(centerX + length * 0.10f, centerY + width)
			lineTo(centerX - length * 0.30f, centerY + width * 0.36f)
			close()
		}
		shapePaint.color = withDrawableAlpha(color, alpha)
		canvas.save()
		canvas.rotate(rotation, centerX, centerY)
		canvas.drawPath(path, shapePaint)
		canvas.restore()
	}

	private fun drawLeafRosette(
		canvas: Canvas,
		centerX: Float,
		centerY: Float,
		radius: Float,
		color: Int,
		alpha: Float,
	) {
		for (index in 0 until 6) {
			drawLeaf(
				canvas,
				centerX,
				centerY,
				radius,
				radius * 0.30f,
				index * 60f,
				if (index % 2 == 0) color else palette.secondary,
				alpha * if (index % 2 == 0) 1f else 0.82f,
			)
		}
		shapePaint.color = withDrawableAlpha(palette.accent, alpha * 0.68f)
		canvas.drawCircle(centerX, centerY, radius * 0.16f, shapePaint)
	}

	private fun drawAutumnLeaf(
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
			lineTo(centerX + radius * 0.28f, centerY - radius * 0.38f)
			lineTo(centerX + radius * 0.72f, centerY - radius * 0.45f)
			lineTo(centerX + radius * 0.46f, centerY - radius * 0.05f)
			lineTo(centerX + radius * 0.78f, centerY + radius * 0.22f)
			lineTo(centerX + radius * 0.28f, centerY + radius * 0.30f)
			lineTo(centerX, centerY + radius)
			lineTo(centerX - radius * 0.24f, centerY + radius * 0.28f)
			lineTo(centerX - radius * 0.72f, centerY + radius * 0.18f)
			lineTo(centerX - radius * 0.42f, centerY - radius * 0.04f)
			lineTo(centerX - radius * 0.68f, centerY - radius * 0.42f)
			lineTo(centerX - radius * 0.24f, centerY - radius * 0.36f)
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
