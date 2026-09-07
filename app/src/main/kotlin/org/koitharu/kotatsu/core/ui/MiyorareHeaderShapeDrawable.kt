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
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Modern header artwork matched to the approved six-theme Favourites reference.
 *
 * The shared app bar intentionally stays visually quiet, like the reference screenshot. The
 * distinctive artwork lives in the formed header body; Details and Explore reuse the same motif
 * family at lower strength. Classic never creates this drawable.
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

		// The actual app bar in the approved reference is deliberately plain. Keeping this zero also
		// prevents decorative art from fighting the search bar and fixes the old "one giant shape"
		// look that made every preset feel alike.
		if (variant != Variant.FAVOURITES_TOP) {
			drawPresetMotif(canvas, width, height)
		}

		if (variant == Variant.FAVOURITES_BODY || variant == Variant.EXPLORE) {
			strokePaint.strokeWidth = (0.9f * density).coerceAtLeast(1f)
			strokePaint.color = withDrawableAlpha(
				palette.borderHighlight,
				if (variant == Variant.FAVOURITES_BODY) 0.48f else 0.24f,
			)
			canvas.drawPath(container, strokePaint)
		}

		canvas.restore()
	}

	private fun baseColors(): IntArray = when (variant) {
		Variant.FAVOURITES_TOP -> intArrayOf(
			withDrawableAlpha(ColorUtils.blendARGB(palette.surface, palette.primary, 0.035f), 1f),
			withDrawableAlpha(palette.surface, 1f),
			withDrawableAlpha(palette.surface, 1f),
		)
		Variant.FAVOURITES_BODY -> intArrayOf(
			withDrawableAlpha(ColorUtils.blendARGB(palette.surfaceGradientStart, palette.primary, 0.18f), 1f),
			withDrawableAlpha(ColorUtils.blendARGB(palette.surfaceGradientMiddle, palette.secondary, 0.10f), 1f),
			withDrawableAlpha(ColorUtils.blendARGB(palette.surfaceGradientEnd, palette.surface, 0.17f), 1f),
		)
		Variant.DETAILS -> intArrayOf(
			withDrawableAlpha(ColorUtils.blendARGB(palette.surfaceGradientStart, palette.primary, 0.09f), 0.90f),
			withDrawableAlpha(ColorUtils.blendARGB(palette.surfaceGradientMiddle, palette.secondary, 0.06f), 0.48f),
			Color.TRANSPARENT,
		)
		Variant.EXPLORE -> intArrayOf(
			withDrawableAlpha(ColorUtils.blendARGB(palette.background, palette.surfaceContainer, 0.68f), 1f),
			withDrawableAlpha(ColorUtils.blendARGB(palette.background, palette.primary, 0.035f), 1f),
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

	private fun motifStrength(): Float = when (variant) {
		Variant.FAVOURITES_BODY -> 1f
		Variant.FAVOURITES_TOP -> 0f
		Variant.DETAILS -> 0.44f
		Variant.EXPLORE -> 0.23f
	}

	private fun motifSize(body: Float, details: Float, explore: Float): Float = when (variant) {
		Variant.FAVOURITES_BODY -> body
		Variant.DETAILS -> details
		Variant.EXPLORE -> explore
		Variant.FAVOURITES_TOP -> 0f
	} * density

	private fun drawPresetMotif(canvas: Canvas, width: Float, height: Float) {
		when (palette.preset) {
			MiyorareThemePreset.MIYORARE,
			MiyorareThemePreset.CUSTOM -> drawMiyorare(canvas, width, height)
			MiyorareThemePreset.SAKURA -> drawSakura(canvas, width, height)
			MiyorareThemePreset.VIOLET -> drawViolet(canvas, width, height)
			MiyorareThemePreset.CYAN -> drawCyan(canvas, width, height)
			MiyorareThemePreset.EMERALD -> drawEmerald(canvas, width, height)
			MiyorareThemePreset.AMBER -> drawAmber(canvas, width, height)
		}
	}

	/**
	 * Reference: a sharp layered blue "plant/shard" burst at the right edge, plus a large translucent
	 * shard entering from the lower-left. It is deliberately not an orbit or a round flower.
	 */
	private fun drawMiyorare(canvas: Canvas, width: Float, height: Float) {
		val s = motifStrength()
		val baseX = width * 0.965f
		val baseY = height * 0.76f
		val blade = motifSize(58f, 34f, 22f)

		drawBlade(canvas, baseX, baseY, baseX - width * 0.22f, height * 0.06f, blade * 0.22f, palette.primary, 0.34f * s)
		drawBlade(canvas, baseX, baseY, baseX - width * 0.09f, -height * 0.04f, blade * 0.20f, palette.primary, 0.31f * s)
		drawBlade(canvas, baseX, baseY, width * 1.04f, height * 0.08f, blade * 0.20f, palette.secondary, 0.30f * s)
		drawBlade(canvas, baseX, baseY, width * 0.79f, height * 0.23f, blade * 0.18f, palette.secondary, 0.26f * s)
		drawBlade(canvas, baseX, baseY, width * 1.02f, height * 0.28f, blade * 0.16f, palette.accent, 0.23f * s)

		val leftShard = Path().apply {
			moveTo(-width * 0.03f, height * 0.46f)
			lineTo(width * 0.19f, height * 0.27f)
			lineTo(width * 0.28f, height * 0.64f)
			lineTo(width * 0.05f, height * 0.73f)
			close()
		}
		shapePaint.color = withDrawableAlpha(palette.primary, 0.12f * s)
		canvas.drawPath(leftShard, shapePaint)

		drawDiamond(canvas, width * 0.78f, height * 0.12f, blade * 0.09f, palette.secondary, 0.27f * s, 18f)
		drawDiamond(canvas, width * 0.91f, height * 0.08f, blade * 0.07f, palette.primary, 0.23f * s, -12f)
		drawParticle(canvas, width * 0.73f, height * 0.22f, blade * 0.035f, palette.secondary, 0.20f * s)
	}

	/** Reference: a thin branch entering from the right with several simple five-petal blossoms. */
	private fun drawSakura(canvas: Canvas, width: Float, height: Float) {
		val s = motifStrength()
		val flower = motifSize(24f, 14f, 10f)
		val branch = Path().apply {
			moveTo(width * 1.02f, height * 0.03f)
			cubicTo(width * 0.95f, height * 0.18f, width * 0.93f, height * 0.30f, width * 0.84f, height * 0.55f)
		}
		strokePaint.strokeWidth = (1.15f * density).coerceAtLeast(1f)
		strokePaint.color = withDrawableAlpha(palette.secondary, 0.30f * s)
		canvas.drawPath(branch, strokePaint)

		drawRoundFlower(canvas, width * 0.91f, height * 0.17f, flower, palette.primary, 0.46f * s, 5)
		drawRoundFlower(canvas, width * 0.78f, height * 0.25f, flower * 0.72f, palette.primary, 0.42f * s, 5)
		drawRoundFlower(canvas, width * 0.96f, height * 0.38f, flower * 0.78f, palette.secondary, 0.38f * s, 5)
		drawRoundFlower(canvas, width * 0.87f, height * 0.49f, flower * 0.54f, palette.primary, 0.29f * s, 5)

		drawPetal(canvas, width * 0.72f, height * 0.14f, flower * 0.24f, palette.primary, 0.20f * s, -28f)
		drawPetal(canvas, width * 0.76f, height * 0.43f, flower * 0.20f, palette.secondary, 0.18f * s, 36f)
		drawParticle(canvas, width * 0.68f, height * 0.34f, flower * 0.08f, palette.accent, 0.14f * s)
	}

	/** Reference: two prominent violet flowers and a smaller companion, with tiny diamond dust. */
	private fun drawViolet(canvas: Canvas, width: Float, height: Float) {
		val s = motifStrength()
		val flower = motifSize(25f, 14f, 10f)
		drawRoundFlower(canvas, width * 0.94f, height * 0.24f, flower, palette.primary, 0.42f * s, 5)
		drawRoundFlower(canvas, width * 0.81f, height * 0.20f, flower * 0.62f, palette.secondary, 0.35f * s, 5)
		drawRoundFlower(canvas, width * 0.77f, height * 0.38f, flower * 0.44f, palette.primary, 0.24f * s, 5)
		drawDiamond(canvas, width * 0.73f, height * 0.30f, flower * 0.11f, palette.accent, 0.22f * s, 45f)
		drawDiamond(canvas, width * 0.91f, height * 0.10f, flower * 0.08f, palette.secondary, 0.18f * s, 45f)
	}

	/** Reference: long cyan lanceolate leaves sweeping upward from the lower-right. */
	private fun drawCyan(canvas: Canvas, width: Float, height: Float) {
		val s = motifStrength()
		val leaf = motifSize(64f, 36f, 23f)
		val baseX = width * 0.965f
		val baseY = height * 0.78f
		drawBlade(canvas, baseX, baseY, width * 0.77f, height * 0.08f, leaf * 0.18f, palette.primary, 0.30f * s)
		drawBlade(canvas, baseX, baseY, width * 0.89f, height * 0.02f, leaf * 0.17f, palette.secondary, 0.31f * s)
		drawBlade(canvas, baseX, baseY, width * 1.03f, height * 0.07f, leaf * 0.16f, palette.primary, 0.27f * s)
		drawBlade(canvas, baseX, baseY, width * 0.84f, height * 0.31f, leaf * 0.13f, palette.secondary, 0.23f * s)
		drawParticle(canvas, width * 0.73f, height * 0.26f, leaf * 0.035f, palette.primary, 0.16f * s)
		drawParticle(canvas, width * 0.79f, height * 0.37f, leaf * 0.025f, palette.secondary, 0.13f * s)
	}

	/** Reference: one large leafy rosette on the right with a few detached leaves/diamond specks. */
	private fun drawEmerald(canvas: Canvas, width: Float, height: Float) {
		val s = motifStrength()
		val r = motifSize(27f, 16f, 11f)
		drawLeafRosette(canvas, width * 0.88f, height * 0.25f, r, palette.primary, 0.42f * s, 6)
		drawLeaf(canvas, width * 0.78f, height * 0.16f, r * 0.72f, r * 0.30f, -32f, palette.secondary, 0.26f * s)
		drawLeaf(canvas, width * 0.96f, height * 0.12f, r * 0.65f, r * 0.28f, 52f, palette.primary, 0.24f * s)
		drawDiamond(canvas, width * 0.72f, height * 0.13f, r * 0.12f, palette.secondary, 0.18f * s, 45f)
		drawDiamond(canvas, width * 0.77f, height * 0.34f, r * 0.09f, palette.primary, 0.16f * s, 45f)
	}

	/** Reference: a slim warm branch with four pointed leaves and a few floating warm particles. */
	private fun drawAmber(canvas: Canvas, width: Float, height: Float) {
		val s = motifStrength()
		val leaf = motifSize(24f, 14f, 10f)
		val branch = Path().apply {
			moveTo(width * 1.02f, height * 0.05f)
			cubicTo(width * 0.95f, height * 0.18f, width * 0.91f, height * 0.31f, width * 0.82f, height * 0.53f)
		}
		strokePaint.strokeWidth = (1.1f * density).coerceAtLeast(1f)
		strokePaint.color = withDrawableAlpha(palette.secondary, 0.28f * s)
		canvas.drawPath(branch, strokePaint)

		drawPointLeaf(canvas, width * 0.96f, height * 0.16f, leaf, palette.primary, 0.37f * s, -28f)
		drawPointLeaf(canvas, width * 0.88f, height * 0.24f, leaf * 0.92f, palette.secondary, 0.35f * s, 24f)
		drawPointLeaf(canvas, width * 0.94f, height * 0.35f, leaf * 0.78f, palette.primary, 0.30f * s, -20f)
		drawPointLeaf(canvas, width * 0.82f, height * 0.41f, leaf * 0.72f, palette.accent, 0.26f * s, 32f)

		drawParticle(canvas, width * 0.72f, height * 0.18f, leaf * 0.10f, palette.secondary, 0.16f * s)
		drawParticle(canvas, width * 0.76f, height * 0.39f, leaf * 0.07f, palette.primary, 0.14f * s)
	}

	private fun drawBlade(
		canvas: Canvas,
		baseX: Float,
		baseY: Float,
		tipX: Float,
		tipY: Float,
		halfWidth: Float,
		color: Int,
		alpha: Float,
	) {
		val dx = tipX - baseX
		val dy = tipY - baseY
		val length = kotlin.math.sqrt(dx * dx + dy * dy).coerceAtLeast(1f)
		val nx = -dy / length
		val ny = dx / length
		val path = Path().apply {
			moveTo(baseX, baseY)
			cubicTo(
				baseX + dx * 0.35f + nx * halfWidth,
				baseY + dy * 0.35f + ny * halfWidth,
				baseX + dx * 0.78f + nx * halfWidth * 0.35f,
				baseY + dy * 0.78f + ny * halfWidth * 0.35f,
				tipX,
				tipY,
			)
			cubicTo(
				baseX + dx * 0.78f - nx * halfWidth * 0.35f,
				baseY + dy * 0.78f - ny * halfWidth * 0.35f,
				baseX + dx * 0.35f - nx * halfWidth,
				baseY + dy * 0.35f - ny * halfWidth,
				baseX,
				baseY,
			)
			close()
		}
		shapePaint.color = withDrawableAlpha(color, alpha)
		canvas.drawPath(path, shapePaint)

		strokePaint.strokeWidth = (0.7f * density).coerceAtLeast(0.7f)
		strokePaint.color = withDrawableAlpha(Color.WHITE, alpha * 0.20f)
		canvas.drawLine(baseX, baseY, tipX, tipY, strokePaint)
	}

	private fun drawRoundFlower(
		canvas: Canvas,
		centerX: Float,
		centerY: Float,
		radius: Float,
		color: Int,
		alpha: Float,
		petalCount: Int,
	) {
		shapePaint.color = withDrawableAlpha(color, alpha)
		for (i in 0 until petalCount) {
			val angle = Math.toRadians((i * (360.0 / petalCount)) - 90.0)
			val px = centerX + cos(angle).toFloat() * radius * 0.48f
			val py = centerY + sin(angle).toFloat() * radius * 0.48f
			canvas.save()
			canvas.rotate((i * (360f / petalCount)), px, py)
			canvas.drawOval(
				RectF(
					px - radius * 0.34f,
					py - radius * 0.62f,
					px + radius * 0.34f,
					py + radius * 0.34f,
				),
				shapePaint,
			)
			canvas.restore()
		}
		shapePaint.color = withDrawableAlpha(palette.accent, alpha * 0.56f)
		canvas.drawCircle(centerX, centerY, radius * 0.13f, shapePaint)
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
				centerX - radiusX * 0.30f,
				centerY - radiusY,
				centerX + radiusX * 0.42f,
				centerY - radiusY,
				centerX + radiusX,
				centerY,
			)
			cubicTo(
				centerX + radiusX * 0.40f,
				centerY + radiusY,
				centerX - radiusX * 0.42f,
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

	private fun drawLeafRosette(
		canvas: Canvas,
		centerX: Float,
		centerY: Float,
		radius: Float,
		color: Int,
		alpha: Float,
		count: Int,
	) {
		for (i in 0 until count) {
			drawLeaf(
				canvas,
				centerX,
				centerY,
				radius,
				radius * 0.34f,
				i * (360f / count),
				if (i % 2 == 0) color else palette.secondary,
				alpha * if (i % 2 == 0) 1f else 0.84f,
			)
		}
		shapePaint.color = withDrawableAlpha(palette.accent, alpha * 0.55f)
		canvas.drawCircle(centerX, centerY, radius * 0.13f, shapePaint)
	}

	private fun drawPointLeaf(
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
			cubicTo(
				centerX + radius * 0.62f,
				centerY - radius * 0.22f,
				centerX + radius * 0.52f,
				centerY + radius * 0.38f,
				centerX,
				centerY + radius,
			)
			cubicTo(
				centerX - radius * 0.52f,
				centerY + radius * 0.38f,
				centerX - radius * 0.62f,
				centerY - radius * 0.22f,
				centerX,
				centerY - radius,
			)
			close()
		}
		shapePaint.color = withDrawableAlpha(color, alpha)
		canvas.save()
		canvas.rotate(rotation, centerX, centerY)
		canvas.drawPath(path, shapePaint)
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
			lineTo(centerX + radius * 0.70f, centerY)
			lineTo(centerX, centerY + radius)
			lineTo(centerX - radius * 0.70f, centerY)
			close()
		}
		shapePaint.color = withDrawableAlpha(color, alpha)
		canvas.save()
		canvas.rotate(rotation, centerX, centerY)
		canvas.drawPath(path, shapePaint)
		canvas.restore()
	}

	private fun drawParticle(
		canvas: Canvas,
		centerX: Float,
		centerY: Float,
		radius: Float,
		color: Int,
		alpha: Float,
	) {
		shapePaint.color = withDrawableAlpha(color, alpha)
		canvas.drawCircle(centerX, centerY, radius, shapePaint)
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
