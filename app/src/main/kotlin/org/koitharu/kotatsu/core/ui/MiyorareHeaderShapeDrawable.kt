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
import androidx.core.graphics.ColorUtils
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.prefs.MiyorareThemePreset
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Modern-only header renderer.
 *
 * Favourites keeps six distinct authored artworks. Each preset is converted into one cached
 * 1080x835 master bitmap, then TOP and BODY render different windows from that exact same master.
 * Miyorare keeps its approved native high-resolution artwork untouched; the other five presets keep
 * their existing authored shapes while being cropped to the currently visible composition,
 * sharpened lightly, and promoted to the same high-resolution master size.
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

		// Both variants use the exact same source bitmap and scale. BODY only moves the shared master
		// upward by the real root-layout distance between the AppBar origin and the header-body origin.
		// This restores the previously approved Miyorare placement without relying on window coordinates.
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
			val encoded = if (usesMiyorareGoldenArtwork()) {
				buildString {
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
			} else {
				palette.resources.assets
					.open("$FAVOURITES_ASSET_DIR/${favouritesArtworkName()}.b64")
					.bufferedReader()
					.use { it.readText() }
			}
			val bytes = Base64.decode(encoded, Base64.DEFAULT)
			BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
		}.getOrNull() ?: return null

		val master = if (usesMiyorareGoldenArtwork()) {
			// Preserve the approved 1080x835 Miyorare source exactly as authored.
			decoded
		} else {
			promoteLegacyFavouritesArtwork(decoded)
		}
		synchronized(favouritesArtworkCache) {
			favouritesArtworkCache[cacheKey] = master
		}
		return master
	}

	/**
	 * The original Sakura/Violet/Cyan/Emerald/Amber assets are 340px-wide authored panels. Their
	 * visible composition is the upper portion currently shown by the app. Crop only that same visible
	 * aspect, sharpen it before enlargement, and promote it to the Miyorare golden-master dimensions.
	 * This deliberately preserves each preset's existing motif/shape instead of recolouring one theme.
	 */
	private fun promoteLegacyFavouritesArtwork(source: Bitmap): Bitmap {
		val targetAspectHeight = source.width * FAVOURITES_MASTER_HEIGHT_PX.toFloat() /
			FAVOURITES_MASTER_WIDTH_PX.toFloat()
		val cropHeight = targetAspectHeight.roundToInt()
			.coerceAtLeast(1)
			.coerceAtMost(source.height)
		val cropped = if (cropHeight == source.height) {
			source
		} else {
			Bitmap.createBitmap(source, 0, 0, source.width, cropHeight)
		}
		val sharpened = sharpenFavouritesArtwork(cropped)
		return if (
			sharpened.width == FAVOURITES_MASTER_WIDTH_PX &&
			sharpened.height == FAVOURITES_MASTER_HEIGHT_PX
		) {
			sharpened
		} else {
			Bitmap.createScaledBitmap(
				sharpened,
				FAVOURITES_MASTER_WIDTH_PX,
				FAVOURITES_MASTER_HEIGHT_PX,
				true,
			)
		}
	}

	/** Lightweight 4-neighbour unsharp pass at the small source size, before high-resolution scaling. */
	private fun sharpenFavouritesArtwork(source: Bitmap): Bitmap {
		if (source.width < 3 || source.height < 3) return source
		val width = source.width
		val height = source.height
		val input = IntArray(width * height)
		source.getPixels(input, 0, width, 0, 0, width, height)
		val output = input.copyOf()
		for (y in 1 until height - 1) {
			val row = y * width
			for (x in 1 until width - 1) {
				val index = row + x
				val center = input[index]
				val left = input[index - 1]
				val right = input[index + 1]
				val up = input[index - width]
				val down = input[index + width]
				val alpha = (center ushr 24) and 0xFF
				val red = sharpenChannel(
					(center ushr 16) and 0xFF,
					((left ushr 16) and 0xFF) + ((right ushr 16) and 0xFF) +
						((up ushr 16) and 0xFF) + ((down ushr 16) and 0xFF),
				)
				val green = sharpenChannel(
					(center ushr 8) and 0xFF,
					((left ushr 8) and 0xFF) + ((right ushr 8) and 0xFF) +
						((up ushr 8) and 0xFF) + ((down ushr 8) and 0xFF),
				)
				val blue = sharpenChannel(
					center and 0xFF,
					(left and 0xFF) + (right and 0xFF) + (up and 0xFF) + (down and 0xFF),
				)
				output[index] = (alpha shl 24) or (red shl 16) or (green shl 8) or blue
			}
		}
		return Bitmap.createBitmap(output, width, height, Bitmap.Config.ARGB_8888)
	}

	private fun sharpenChannel(center: Int, neighbourSum: Int): Int {
		val detail = center * 4 - neighbourSum
		return (center + detail * LEGACY_SHARPEN_AMOUNT)
			.roundToInt()
			.coerceIn(0, 255)
	}

	private fun usesMiyorareGoldenArtwork(): Boolean = when (palette.preset) {
		MiyorareThemePreset.MIYORARE, MiyorareThemePreset.CUSTOM -> true
		MiyorareThemePreset.SAKURA,
		MiyorareThemePreset.VIOLET,
		MiyorareThemePreset.CYAN,
		MiyorareThemePreset.EMERALD,
		MiyorareThemePreset.AMBER -> false
	}

	private fun favouritesArtworkCacheKey(): String = if (usesMiyorareGoldenArtwork()) {
		"miyorare-golden-1080x835-approved-v2"
	} else {
		"${favouritesArtworkName()}-master-1080x835-sharp-v1"
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
		const val LEGACY_SHARPEN_AMOUNT = 0.34f
		const val FALLBACK_TOP_HEIGHT_DP = 92f
		val favouritesArtworkCache = HashMap<String, Bitmap>()
	}
}
