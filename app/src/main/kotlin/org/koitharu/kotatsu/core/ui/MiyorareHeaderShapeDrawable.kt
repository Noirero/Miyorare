package org.koitharu.kotatsu.core.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.util.Base64
import android.view.View
import android.view.ViewGroup
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.ColorUtils
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.prefs.MiyorareThemePreset
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Modern-only header renderer.
 *
 * Normal Favourites keeps the approved authored artwork untouched. Private Favourites deliberately
 * layers its own darker identity treatment and preset-specific motif over the same master geometry,
 * so TOP/BODY still meet perfectly while Private remains visually unmistakable from Normal.
 */
class MiyorareHeaderShapeDrawable(
	private val palette: MiyorareViewPalette,
	private val variant: Variant,
	private val density: Float,
	private val privateStyle: Boolean = false,
) : Drawable() {

	enum class Variant {
		FAVOURITES_TOP,
		FAVOURITES_BODY,
		DETAILS,
		EXPLORE,
	}

	private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
	private val motifPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
	private val privatePaint = Paint(Paint.ANTI_ALIAS_FLAG)
	private val artworkPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
		isDither = true
	}
	private var drawableAlpha = 255

	private val motif: Bitmap? by lazy(LazyThreadSafetyMode.NONE) {
		if (variant == Variant.DETAILS || variant == Variant.EXPLORE) {
			runCatching {
				palette.resources.assets.open(motifAssetPath()).use(BitmapFactory::decodeStream)
			}.getOrNull()
		} else {
			null
		}
	}

	private val favouritesArtwork: Bitmap? by lazy(LazyThreadSafetyMode.NONE) {
		if (variant == Variant.FAVOURITES_TOP || variant == Variant.FAVOURITES_BODY) {
			loadFavouritesArtwork()
		} else {
			null
		}
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

		if (variant == Variant.FAVOURITES_TOP || variant == Variant.FAVOURITES_BODY) {
			drawFavouritesArtwork(canvas, width)
			if (privateStyle) drawPrivateIdentity(canvas, width, height)
		} else {
			drawReferenceMotif(canvas, width, height)
		}
		canvas.restore()
	}

	private fun drawFavouritesArtwork(canvas: Canvas, width: Float) {
		val bitmap = favouritesArtwork ?: return
		val scale = width / bitmap.width.toFloat()
		if (scale <= 0f) return
		val topOffset = favouritesArtworkTopOffset()

		// TOP and BODY use the same source bitmap and the same scale. BODY only moves the shared master
		// upward by the real root-layout distance between the AppBar origin and the header-body origin.
		artworkPaint.alpha = drawableAlpha.coerceIn(0, 255)
		artworkPaint.colorFilter = null
		canvas.save()
		canvas.translate(0f, -topOffset)
		canvas.scale(scale, scale)
		canvas.drawBitmap(bitmap, 0f, 0f, artworkPaint)
		canvas.restore()
	}

	/**
	 * Private variants mirror the approved sample: dark hero artwork, bright preset edge accents and
	 * a different visual motif per preset. This is not a flat recolour pass; each preset gets its own
	 * geometry while preserving the exact Normal header dimensions and interaction layout.
	 */
	private fun drawPrivateIdentity(canvas: Canvas, width: Float, height: Float) {
		privatePaint.style = Paint.Style.FILL
		privatePaint.shader = LinearGradient(
			0f,
			0f,
			width,
			height,
			intArrayOf(
				ColorUtils.setAlphaComponent(Color.BLACK, 86),
				ColorUtils.setAlphaComponent(Color.BLACK, 118),
				ColorUtils.setAlphaComponent(palette.background, 178),
			),
			null,
			Shader.TileMode.CLAMP,
		)
		canvas.drawRect(0f, 0f, width, height, privatePaint)
		privatePaint.shader = null

		val alpha = (112f * (drawableAlpha / 255f)).roundToInt().coerceIn(0, 255)
		privatePaint.color = ColorUtils.setAlphaComponent(palette.primary, alpha)
		privatePaint.strokeWidth = (1.25f * density).coerceAtLeast(1f)
		privatePaint.style = Paint.Style.STROKE

		when (palette.preset) {
			MiyorareThemePreset.MIYORARE, MiyorareThemePreset.CUSTOM -> drawPrivateMiyorare(canvas, width, height)
			MiyorareThemePreset.SAKURA -> drawPrivateSakura(canvas, width, height)
			MiyorareThemePreset.VIOLET -> drawPrivateViolet(canvas, width, height)
			MiyorareThemePreset.CYAN -> drawPrivateCyan(canvas, width, height)
			MiyorareThemePreset.EMERALD -> drawPrivateEmerald(canvas, width, height)
			MiyorareThemePreset.AMBER -> drawPrivateAmber(canvas, width, height)
		}
	}

	private fun drawPrivateMiyorare(canvas: Canvas, width: Float, height: Float) {
		val step = width / 7f
		for (i in 0..7) {
			val x = i * step
			canvas.drawLine(x, 0f, (x + width * 0.24f).coerceAtMost(width), height, privatePaint)
		}
		canvas.drawCircle(width * 0.82f, height * 0.34f, width * 0.12f, privatePaint)
		canvas.drawCircle(width * 0.82f, height * 0.34f, width * 0.07f, privatePaint)
	}

	private fun drawPrivateSakura(canvas: Canvas, width: Float, height: Float) {
		privatePaint.style = Paint.Style.FILL
		privatePaint.color = ColorUtils.setAlphaComponent(palette.primary, 88)
		val points = arrayOf(
			0.18f to 0.24f, 0.38f to 0.46f, 0.66f to 0.20f, 0.78f to 0.58f, 0.90f to 0.34f,
		)
		for ((index, point) in points.withIndex()) {
			val cx = width * point.first
			val cy = height * point.second
			val rx = width * 0.018f
			val ry = width * 0.038f
			canvas.save()
			canvas.rotate((index * 31 - 28).toFloat(), cx, cy)
			canvas.drawOval(RectF(cx - rx, cy - ry, cx + rx, cy + ry), privatePaint)
			canvas.restore()
		}
		privatePaint.style = Paint.Style.STROKE
	}

	private fun drawPrivateViolet(canvas: Canvas, width: Float, height: Float) {
		for (i in 0..4) {
			val cx = width * (0.18f + i * 0.18f)
			val cy = height * if (i % 2 == 0) 0.28f else 0.54f
			val size = width * (0.055f + i * 0.004f)
			val path = Path().apply {
				moveTo(cx, cy - size)
				lineTo(cx + size * 0.72f, cy + size)
				lineTo(cx - size * 0.72f, cy + size)
				close()
			}
			canvas.drawPath(path, privatePaint)
		}
	}

	private fun drawPrivateCyan(canvas: Canvas, width: Float, height: Float) {
		val cx = width * 0.78f
		val cy = height * 0.36f
		for (i in 1..4) {
			canvas.drawCircle(cx, cy, width * (0.045f * i), privatePaint)
		}
		for (i in 1..3) {
			val y = height * (0.18f + i * 0.16f)
			canvas.drawLine(width * 0.06f, y, width * 0.48f, y, privatePaint)
		}
	}

	private fun drawPrivateEmerald(canvas: Canvas, width: Float, height: Float) {
		privatePaint.style = Paint.Style.STROKE
		val stem = Path().apply {
			moveTo(width * 0.60f, height * 0.92f)
			cubicTo(width * 0.64f, height * 0.64f, width * 0.74f, height * 0.46f, width * 0.90f, height * 0.14f)
		}
		canvas.drawPath(stem, privatePaint)
		privatePaint.style = Paint.Style.FILL
		privatePaint.color = ColorUtils.setAlphaComponent(palette.primary, 74)
		for (i in 0..4) {
			val cx = width * (0.64f + i * 0.055f)
			val cy = height * (0.70f - i * 0.10f)
			canvas.save()
			canvas.rotate(if (i % 2 == 0) -34f else 34f, cx, cy)
			canvas.drawOval(
				RectF(cx - width * 0.045f, cy - width * 0.016f, cx + width * 0.045f, cy + width * 0.016f),
				privatePaint,
			)
			canvas.restore()
		}
		privatePaint.style = Paint.Style.STROKE
	}

	private fun drawPrivateAmber(canvas: Canvas, width: Float, height: Float) {
		for (i in 0..5) {
			val cx = width * (0.12f + i * 0.16f)
			val cy = height * if (i % 2 == 0) 0.32f else 0.56f
			val size = width * 0.045f
			val path = Path().apply {
				moveTo(cx, cy - size)
				lineTo(cx + size * 0.42f, cy - size * 0.10f)
				lineTo(cx + size * 0.10f, cy + size)
				lineTo(cx - size * 0.48f, cy + size * 0.22f)
				close()
			}
			canvas.drawPath(path, privatePaint)
		}
		privatePaint.style = Paint.Style.FILL
		privatePaint.color = ColorUtils.setAlphaComponent(palette.accent, 96)
		for (i in 0..8) {
			canvas.drawCircle(width * (0.10f + i * 0.10f), height * (0.18f + (i % 3) * 0.13f), 1.7f * density, privatePaint)
		}
		privatePaint.style = Paint.Style.STROKE
	}

	private fun favouritesArtworkTopOffset(): Float {
		if (variant != Variant.FAVOURITES_BODY) return 0f

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

	private fun loadFavouritesArtwork(): Bitmap? {
		val cacheKey = favouritesArtworkCacheKey()
		synchronized(favouritesArtworkCache) {
			favouritesArtworkCache[cacheKey]?.let { return it }
		}

		val decoded = runCatching {
			if (usesMiyorareGoldenArtwork()) {
				val encoded = buildString {
					for (index in 0 until MIYORARE_GOLDEN_CHUNK_COUNT) {
						val chunk = index.toString().padStart(2, '0')
						append(
							palette.resources.assets
								.open("$FAVOURITES_ASSET_DIR/miyorare-hi/$chunk.b64")
								.bufferedReader()
								.use { it.readText() },
						)
					}
				}
				val bytes = Base64.decode(encoded, Base64.DEFAULT)
				BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
			} else {
				val vector = ResourcesCompat.getDrawable(
					palette.resources,
					favouritesArtworkDrawableRes(),
					null,
				) ?: return@runCatching null
				Bitmap.createBitmap(
					FAVOURITES_MASTER_WIDTH_PX,
					FAVOURITES_MASTER_HEIGHT_PX,
					Bitmap.Config.ARGB_8888,
				).also { bitmap ->
					vector.setBounds(0, 0, bitmap.width, bitmap.height)
					vector.draw(Canvas(bitmap))
				}
			}
		}.getOrNull() ?: return null

		if (decoded.width != FAVOURITES_MASTER_WIDTH_PX || decoded.height != FAVOURITES_MASTER_HEIGHT_PX) {
			return null
		}

		synchronized(favouritesArtworkCache) {
			favouritesArtworkCache[cacheKey] = decoded
		}
		return decoded
	}

	private fun usesMiyorareGoldenArtwork(): Boolean = when (palette.preset) {
		MiyorareThemePreset.MIYORARE, MiyorareThemePreset.CUSTOM -> true
		MiyorareThemePreset.SAKURA,
		MiyorareThemePreset.VIOLET,
		MiyorareThemePreset.CYAN,
		MiyorareThemePreset.EMERALD,
		MiyorareThemePreset.AMBER -> false
	}

	private fun favouritesArtworkDrawableRes(): Int = when (palette.preset) {
		MiyorareThemePreset.SAKURA -> R.drawable.miyorare_favourites_sakura
		MiyorareThemePreset.VIOLET -> R.drawable.miyorare_favourites_violet
		MiyorareThemePreset.CYAN -> R.drawable.miyorare_favourites_cyan
		MiyorareThemePreset.EMERALD -> R.drawable.miyorare_favourites_emerald
		MiyorareThemePreset.AMBER -> R.drawable.miyorare_favourites_amber
		MiyorareThemePreset.MIYORARE, MiyorareThemePreset.CUSTOM -> error("Miyorare uses its approved bitmap master")
	}

	private fun favouritesArtworkCacheKey(): String = if (usesMiyorareGoldenArtwork()) {
		"miyorare-golden-1080x835-approved-v2"
	} else {
		"${favouritesArtworkName()}-native-1080x835-final-v1"
	}

	private fun favouritesArtworkName(): String = when (palette.preset) {
		MiyorareThemePreset.MIYORARE, MiyorareThemePreset.CUSTOM -> "miyorare"
		MiyorareThemePreset.SAKURA -> "sakura"
		MiyorareThemePreset.VIOLET -> "violet"
		MiyorareThemePreset.CYAN -> "cyan"
		MiyorareThemePreset.EMERALD -> "emerald"
		MiyorareThemePreset.AMBER -> "amber"
	}

	private fun drawReferenceMotif(canvas: Canvas, width: Float, height: Float) {
		val bitmap = motif ?: return
		val widthFraction = when (variant) {
			Variant.DETAILS -> 0.30f
			Variant.EXPLORE -> 0.24f
			Variant.FAVOURITES_TOP, Variant.FAVOURITES_BODY -> return
		}
		val alphaFraction = when (variant) {
			Variant.DETAILS -> 0.46f
			Variant.EXPLORE -> 0.25f
			Variant.FAVOURITES_TOP, Variant.FAVOURITES_BODY -> return
		}
		val targetWidth = width * widthFraction
		val aspect = bitmap.height.toFloat() / bitmap.width.toFloat()
		val targetHeight = min(height * 0.62f, targetWidth * aspect)
		val right = width
		val top = when (variant) {
			Variant.DETAILS -> height * 0.035f
			Variant.EXPLORE -> height * 0.025f
			Variant.FAVOURITES_TOP, Variant.FAVOURITES_BODY -> return
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

	private fun motifAssetPath(): String = when (palette.preset) {
		MiyorareThemePreset.MIYORARE, MiyorareThemePreset.CUSTOM -> "miyorare/header-motifs/miyorare.png"
		MiyorareThemePreset.SAKURA -> "miyorare/header-motifs/sakura.png"
		MiyorareThemePreset.VIOLET -> "miyorare/header-motifs/violet.png"
		MiyorareThemePreset.CYAN -> "miyorare/header-motifs/cyan.png"
		MiyorareThemePreset.EMERALD -> "miyorare/header-motifs/emerald.png"
		MiyorareThemePreset.AMBER -> "miyorare/header-motifs/amber.png"
	}

	private fun baseColors(): IntArray = when (variant) {
		Variant.FAVOURITES_TOP -> intArrayOf(
			withDrawableAlpha(palette.surfaceGradientStart, 1f),
			withDrawableAlpha(ColorUtils.blendARGB(palette.surfaceGradientMiddle, palette.primary, if (privateStyle) 0.14f else 0.05f), 1f),
			withDrawableAlpha(palette.surfaceGradientStart, 1f),
		)
		Variant.FAVOURITES_BODY -> intArrayOf(
			withDrawableAlpha(ColorUtils.blendARGB(palette.surfaceGradientStart, palette.primary, if (privateStyle) 0.22f else 0.13f), 1f),
			withDrawableAlpha(ColorUtils.blendARGB(palette.surfaceGradientMiddle, palette.accent, if (privateStyle) 0.16f else 0.10f), 1f),
			withDrawableAlpha(ColorUtils.blendARGB(palette.surfaceGradientEnd, palette.surface, if (privateStyle) 0.30f else 0.17f), 1f),
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
		privatePaint.colorFilter = colorFilter
		invalidateSelf()
	}

	@Deprecated("Deprecated in Android framework")
	override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

	private companion object {
		const val FAVOURITES_ASSET_DIR = "miyorare/header-full/favourites"
		const val MIYORARE_GOLDEN_CHUNK_COUNT = 8
		const val FAVOURITES_MASTER_WIDTH_PX = 1080
		const val FAVOURITES_MASTER_HEIGHT_PX = 835
		const val FALLBACK_TOP_HEIGHT_DP = 92f
		val favouritesArtworkCache = HashMap<String, Bitmap>()
	}
}
