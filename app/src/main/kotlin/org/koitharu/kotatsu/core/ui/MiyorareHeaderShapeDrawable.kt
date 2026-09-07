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
 * Favourites uses six distinct final artworks at a native 1080x835 master size. TOP and BODY draw
 * different windows from the exact same authored master, so the split cannot introduce a second
 * crop, upscale, recolour or sharpening pass. Miyorare keeps its previously approved high-resolution
 * master; Sakura, Violet, Cyan, Emerald and Amber use their own final native-resolution artwork.
 *
 * Final Favourites artwork is never palette-tinted at runtime. Details and Explore retain their
 * lighter motif overlays and existing Semi Decorative treatment.
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

		// Final assets are authored at the master size. Reject accidental legacy/low-res replacements
		// instead of silently cropping, sharpening or upscaling them at runtime.
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
		// Final Favourites artwork is intentionally rendered as-authored with no runtime tint/filter.
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
