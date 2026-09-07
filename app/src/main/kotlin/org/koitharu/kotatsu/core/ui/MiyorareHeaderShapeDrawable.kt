package org.koitharu.kotatsu.core.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import androidx.core.graphics.ColorUtils
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.prefs.MiyorareThemePreset
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Modern-only header renderer backed by motifs traced directly from the approved reference image.
 * The motif silhouette is no longer procedurally invented; each curated preset owns a dedicated
 * transparent asset extracted from the reference and tinted with its active semantic palette.
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
	private val motifPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
	private var drawableAlpha = 255
	private val motif: Bitmap? by lazy(LazyThreadSafetyMode.NONE) {
		if (variant == Variant.FAVOURITES_TOP) null else BitmapFactory.decodeResource(palette.resources, motifResId())
	}

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
			if (variant == Variant.DETAILS) 0f else width,
			height,
			baseColors(),
			null,
			Shader.TileMode.CLAMP,
		)
		canvas.drawRect(0f, 0f, width, height, fillPaint)
		fillPaint.shader = null

		drawReferenceMotif(canvas, width, height)
		canvas.restore()
	}

	private fun drawReferenceMotif(canvas: Canvas, width: Float, height: Float) {
		val bitmap = motif ?: return
		val widthFraction = when (variant) {
			Variant.FAVOURITES_BODY -> 0.36f
			Variant.DETAILS -> 0.30f
			Variant.EXPLORE -> 0.24f
			Variant.FAVOURITES_TOP -> return
		}
		val alphaFraction = when (variant) {
			Variant.FAVOURITES_BODY -> 0.92f
			Variant.DETAILS -> 0.46f
			Variant.EXPLORE -> 0.25f
			Variant.FAVOURITES_TOP -> 0f
		}
		val targetWidth = width * widthFraction
		val aspect = bitmap.height.toFloat() / bitmap.width.toFloat()
		val targetHeight = min(height * 0.62f, targetWidth * aspect)
		val right = width
		val top = when (variant) {
			Variant.FAVOURITES_BODY -> 0f
			Variant.DETAILS -> height * 0.035f
			Variant.EXPLORE -> height * 0.025f
			Variant.FAVOURITES_TOP -> 0f
		}
		val dst = RectF(right - targetWidth, top, right, top + targetHeight)

		motifPaint.alpha = (255f * alphaFraction * (drawableAlpha / 255f)).roundToInt().coerceIn(0, 255)
		motifPaint.colorFilter = PorterDuffColorFilter(motifTint(), PorterDuff.Mode.SRC_IN)
		canvas.drawBitmap(bitmap, null, dst, motifPaint)
	}

	private fun motifTint(): Int = when (palette.preset) {
		MiyorareThemePreset.MIYORARE, MiyorareThemePreset.CUSTOM ->
			ColorUtils.blendARGB(palette.primary, palette.secondary, 0.28f)
		MiyorareThemePreset.SAKURA -> palette.primary
		MiyorareThemePreset.VIOLET -> palette.primary
		MiyorareThemePreset.CYAN -> palette.primary
		MiyorareThemePreset.EMERALD -> palette.primary
		MiyorareThemePreset.AMBER -> palette.primary
	}

	private fun motifResId(): Int = when (palette.preset) {
		MiyorareThemePreset.MIYORARE, MiyorareThemePreset.CUSTOM -> R.drawable.miyorare_header_motif_miyorare
		MiyorareThemePreset.SAKURA -> R.drawable.miyorare_header_motif_sakura
		MiyorareThemePreset.VIOLET -> R.drawable.miyorare_header_motif_violet
		MiyorareThemePreset.CYAN -> R.drawable.miyorare_header_motif_cyan
		MiyorareThemePreset.EMERALD -> R.drawable.miyorare_header_motif_emerald
		MiyorareThemePreset.AMBER -> R.drawable.miyorare_header_motif_amber
	}

	private fun baseColors(): IntArray = when (variant) {
		Variant.FAVOURITES_TOP -> intArrayOf(
			withDrawableAlpha(palette.surfaceGradientStart, 1f),
			withDrawableAlpha(ColorUtils.blendARGB(palette.surfaceGradientMiddle, palette.primary, 0.05f), 1f),
			withDrawableAlpha(palette.surfaceGradientStart, 1f),
		)
		Variant.FAVOURITES_BODY -> intArrayOf(
			withDrawableAlpha(ColorUtils.blendARGB(palette.surfaceGradientStart, palette.primary, 0.13f), 1f),
			withDrawableAlpha(ColorUtils.blendARGB(palette.surfaceGradientMiddle, palette.accent, 0.10f), 1f),
			withDrawableAlpha(ColorUtils.blendARGB(palette.surfaceGradientEnd, palette.surface, 0.17f), 1f),
		)
		Variant.DETAILS -> intArrayOf(
			withDrawableAlpha(ColorUtils.blendARGB(palette.surfaceGradientStart, palette.primary, 0.08f), 0.90f),
			withDrawableAlpha(ColorUtils.blendARGB(palette.surfaceGradientMiddle, palette.accent, 0.06f), 0.48f),
			Color.TRANSPARENT,
		)
		Variant.EXPLORE -> intArrayOf(
			withDrawableAlpha(ColorUtils.blendARGB(palette.background, palette.surfaceContainer, 0.70f), 1f),
			withDrawableAlpha(ColorUtils.blendARGB(palette.background, palette.primary, 0.035f), 1f),
			withDrawableAlpha(palette.background, 1f),
		)
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
		motifPaint.colorFilter = colorFilter
		invalidateSelf()
	}

	@Deprecated("Deprecated in Android framework")
	override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
