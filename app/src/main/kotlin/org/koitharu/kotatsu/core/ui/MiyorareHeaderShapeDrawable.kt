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
	private val extendFavouritesArtwork: Boolean = false,
) : Drawable() {

	enum class Variant {
		FAVOURITES_TOP,
		FAVOURITES_BODY,
		DETAILS,
		EXPLORE,
		APP_BACKGROUND,
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
		if (
			variant == Variant.FAVOURITES_TOP ||
			variant == Variant.FAVOURITES_BODY ||
			variant == Variant.APP_BACKGROUND
		) {
			loadFavouritesArtwork()
		} else {
			null
		}
	}

	private val blurredFavouritesArtwork: Bitmap? by lazy(LazyThreadSafetyMode.NONE) {
		if (variant == Variant.APP_BACKGROUND) {
			favouritesArtwork?.let(::loadBlurredFavouritesArtwork)
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

		if (variant == Variant.APP_BACKGROUND) {
			drawAppBackground(canvas, width, height)
			canvas.restore()
			return
		}

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
			drawFavouritesArtwork(canvas, width, height)
			if (privateStyle) drawPrivateIdentity(canvas, width, height)
		} else {
			drawReferenceMotif(canvas, width, height)
		}
		canvas.restore()
	}

	/**
	 * Shared blurred wallpaper used outside Normal Favourites.
	 *
	 * The source is the exact authored 1080x2408 Favourites portrait for the active preset. Blur is
	 * precomputed once on a small bitmap and cached, so RecyclerView scrolling never runs a live blur
	 * or allocates per frame. A palette-background wash keeps text readable while preserving the
	 * wallpaper identity in both light and dark themes.
	 */
	private fun drawAppBackground(canvas: Canvas, width: Float, height: Float) {
		val lightBackground = ColorUtils.calculateLuminance(palette.background) >= 0.5
		val baseColor = if (lightBackground) {
			ColorUtils.blendARGB(palette.background, Color.WHITE, LIGHT_BACKGROUND_WHITE_BASE_MIX)
		} else {
			palette.background
		}
		fillPaint.shader = null
		fillPaint.color = withDrawableAlpha(baseColor, 1f)
		canvas.drawRect(0f, 0f, width, height, fillPaint)

		val bitmap = blurredFavouritesArtwork
		if (bitmap != null && bitmap.width > 0 && bitmap.height > 0) {
			val destinationAspect = width / height
			val sourceAspect = bitmap.width.toFloat() / bitmap.height.toFloat()
			val sourceRect = if (destinationAspect > sourceAspect) {
				val cropHeight = (bitmap.width / destinationAspect)
					.roundToInt()
					.coerceIn(1, bitmap.height)
				val top = (bitmap.height - cropHeight) / 2
				Rect(0, top, bitmap.width, top + cropHeight)
			} else {
				val cropWidth = (bitmap.height * destinationAspect)
					.roundToInt()
					.coerceIn(1, bitmap.width)
				val left = (bitmap.width - cropWidth) / 2
				Rect(left, 0, left + cropWidth, bitmap.height)
			}
			artworkPaint.alpha = if (lightBackground) {
				(drawableAlpha * LIGHT_BACKGROUND_ARTWORK_ALPHA).roundToInt().coerceIn(0, 255)
			} else {
				drawableAlpha.coerceIn(0, 255)
			}
			artworkPaint.colorFilter = null
			canvas.drawBitmap(bitmap, sourceRect, RectF(0f, 0f, width, height), artworkPaint)
		}

		// Light mode intentionally behaves like diffused ambient colour on white glass: the authored
		// wallpaper is still recognizable as a theme tint, but its large dark geometry no longer
		// competes with text/cards. Dark mode keeps the existing treatment unchanged.
		val topAlpha = if (lightBackground) LIGHT_BACKGROUND_WASH_TOP_ALPHA else 0.46f
		val middleAlpha = if (lightBackground) LIGHT_BACKGROUND_WASH_MIDDLE_ALPHA else 0.38f
		val bottomAlpha = if (lightBackground) LIGHT_BACKGROUND_WASH_BOTTOM_ALPHA else 0.50f
		val washColor = if (lightBackground) Color.WHITE else palette.background
		fillPaint.shader = LinearGradient(
			0f,
			0f,
			0f,
			height,
			intArrayOf(
				withDrawableAlpha(washColor, topAlpha),
				withDrawableAlpha(washColor, middleAlpha),
				withDrawableAlpha(washColor, bottomAlpha),
			),
			null,
			Shader.TileMode.CLAMP,
		)
		canvas.drawRect(0f, 0f, width, height, fillPaint)
		fillPaint.shader = null
	}

	private fun loadBlurredFavouritesArtwork(source: Bitmap): Bitmap {
		val cacheKey = "${favouritesArtworkCacheKey()}-blur-v1"
		synchronized(blurredFavouritesArtworkCache) {
			blurredFavouritesArtworkCache[cacheKey]?.let { return it }
		}

		val scaled = Bitmap.createScaledBitmap(
			source,
			APP_BACKGROUND_BLUR_WIDTH_PX,
			APP_BACKGROUND_BLUR_HEIGHT_PX,
			true,
		)
		val width = scaled.width
		val height = scaled.height
		val input = IntArray(width * height)
		val temp = IntArray(input.size)
		scaled.getPixels(input, 0, width, 0, 0, width, height)

		repeat(APP_BACKGROUND_BLUR_PASSES) {
			boxBlurHorizontal(input, temp, width, height, APP_BACKGROUND_BLUR_RADIUS_PX)
			boxBlurVertical(temp, input, width, height, APP_BACKGROUND_BLUR_RADIUS_PX)
		}

		val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
		result.setPixels(input, 0, width, 0, 0, width, height)
		synchronized(blurredFavouritesArtworkCache) {
			blurredFavouritesArtworkCache[cacheKey] = result
		}
		return result
	}

	private fun boxBlurHorizontal(
		input: IntArray,
		output: IntArray,
		width: Int,
		height: Int,
		radius: Int,
	) {
		val diameter = radius * 2 + 1
		for (y in 0 until height) {
			val row = y * width
			var alpha = 0
			var red = 0
			var green = 0
			var blue = 0
			for (offset in -radius..radius) {
				val pixel = input[row + offset.coerceIn(0, width - 1)]
				alpha += Color.alpha(pixel)
				red += Color.red(pixel)
				green += Color.green(pixel)
				blue += Color.blue(pixel)
			}
			for (x in 0 until width) {
				output[row + x] = Color.argb(
					alpha / diameter,
					red / diameter,
					green / diameter,
					blue / diameter,
				)
				val removePixel = input[row + (x - radius).coerceIn(0, width - 1)]
				val addPixel = input[row + (x + radius + 1).coerceIn(0, width - 1)]
				alpha += Color.alpha(addPixel) - Color.alpha(removePixel)
				red += Color.red(addPixel) - Color.red(removePixel)
				green += Color.green(addPixel) - Color.green(removePixel)
				blue += Color.blue(addPixel) - Color.blue(removePixel)
			}
		}
	}

	private fun boxBlurVertical(
		input: IntArray,
		output: IntArray,
		width: Int,
		height: Int,
		radius: Int,
	) {
		val diameter = radius * 2 + 1
		for (x in 0 until width) {
			var alpha = 0
			var red = 0
			var green = 0
			var blue = 0
			for (offset in -radius..radius) {
				val pixel = input[offset.coerceIn(0, height - 1) * width + x]
				alpha += Color.alpha(pixel)
				red += Color.red(pixel)
				green += Color.green(pixel)
				blue += Color.blue(pixel)
			}
			for (y in 0 until height) {
				output[y * width + x] = Color.argb(
					alpha / diameter,
					red / diameter,
					green / diameter,
					blue / diameter,
				)
				val removePixel = input[(y - radius).coerceIn(0, height - 1) * width + x]
				val addPixel = input[(y + radius + 1).coerceIn(0, height - 1) * width + x]
				alpha += Color.alpha(addPixel) - Color.alpha(removePixel)
				red += Color.red(addPixel) - Color.red(removePixel)
				green += Color.green(addPixel) - Color.green(removePixel)
				blue += Color.blue(addPixel) - Color.blue(removePixel)
			}
		}
	}

	private fun drawFavouritesArtwork(canvas: Canvas, width: Float, height: Float) {
		val bitmap = favouritesArtwork ?: return
		if (usesFullPortraitArtwork()) {
			drawFullPortraitBackground(canvas, bitmap, width)
			return
		}

		val scale = width / bitmap.width.toFloat()
		if (scale <= 0f) return
		val topOffset = favouritesArtworkTopOffset()

		// Non-Miyorare preset artwork remains finite and keeps its established renderer.
		artworkPaint.alpha = drawableAlpha.coerceIn(0, 255)
		artworkPaint.colorFilter = null
		canvas.save()
		canvas.translate(0f, -topOffset)
		canvas.scale(scale, scale)
		canvas.drawBitmap(bitmap, 0f, 0f, artworkPaint)
		canvas.restore()

		if (!extendFavouritesArtwork || variant != Variant.FAVOURITES_BODY) return
		val masterRemainder = bitmap.height * scale - topOffset
		if (masterRemainder >= height) return
		drawFavouritesArtworkContinuation(
			canvas = canvas,
			bitmap = bitmap,
			width = width,
			height = height,
			destinationTop = masterRemainder.coerceAtLeast(0f),
		)
	}

	/**
	 * Draw the exact user-supplied 1080x2408 portrait wallpaper as one aligned image.
	 *
	 * Every Normal Favourites theme now provides its own final portrait composition. Do not crop,
	 * mirror, repeat, stretch, or independently reframe it inside TOP/BODY containers. Both owners
	 * use the same width-derived scale; BODY only subtracts its real root offset so the artwork stays
	 * continuous from the status-bar edge through the list.
	 */
	private fun drawFullPortraitBackground(canvas: Canvas, bitmap: Bitmap, width: Float) {
		if (width <= 0f || bitmap.width <= 0 || bitmap.height <= 0) return
		val scale = width / bitmap.width.toFloat()
		if (scale <= 0f) return
		val localTop = -favouritesArtworkTopOffset()

		artworkPaint.alpha = drawableAlpha.coerceIn(0, 255)
		artworkPaint.colorFilter = null
		canvas.save()
		canvas.translate(0f, localTop)
		canvas.scale(scale, scale)
		canvas.drawBitmap(bitmap, 0f, 0f, artworkPaint)
		canvas.restore()
	}

	/**
	 * Extends the finite Favourites hero without repeating rectangular tiles.
	 *
	 * The old implementation looped the same lower 52% crop down the screen. Strong geometry on the
	 * right edge therefore repeated at a fixed interval and exposed obvious horizontal block seams.
	 * A single mirrored continuation keeps the first row mathematically continuous with the master's
	 * bottom edge, stretches only once to the remaining viewport, and is gently muted toward the
	 * bottom so the reflection does not become a second focal point. This remains static bitmap work:
	 * no blur, shader animation, per-item rendering, or scrolling-time allocation is introduced.
	 */
	private fun drawFavouritesArtworkContinuation(
		canvas: Canvas,
		bitmap: Bitmap,
		width: Float,
		height: Float,
		destinationTop: Float,
	) {
		if (destinationTop >= height) return
		val continuationHeight = height - destinationTop
		if (continuationHeight <= 0f) return

		val sourceTop = (bitmap.height * FAVOURITES_CONTINUATION_SOURCE_TOP_FRACTION)
			.roundToInt()
			.coerceIn(0, bitmap.height - 1)
		val previousAlpha = artworkPaint.alpha
		artworkPaint.alpha = (previousAlpha * FAVOURITES_CONTINUATION_ALPHA)
			.roundToInt()
			.coerceIn(0, 255)

		// Flip one lower-artwork crop vertically. Source-bottom lands exactly on destinationTop, so the
		// transition begins from the same edge pixels as the finite master instead of a new tile.
		canvas.save()
		canvas.translate(0f, height)
		canvas.scale(1f, -1f)
		canvas.drawBitmap(
			bitmap,
			Rect(0, sourceTop, bitmap.width, bitmap.height),
			RectF(0f, 0f, width, continuationHeight),
			artworkPaint,
		)
		canvas.restore()
		artworkPaint.alpha = previousAlpha

		// Let the authored base gradient gradually regain weight lower in the viewport. This softens
		// the reflected continuation without creating a visible horizontal boundary.
		val fadeColor = ColorUtils.setAlphaComponent(
			ColorUtils.blendARGB(palette.surfaceGradientEnd, palette.background, 0.55f),
			(drawableAlpha * FAVOURITES_CONTINUATION_FADE_ALPHA).roundToInt().coerceIn(0, 255),
		)
		fillPaint.shader = LinearGradient(
			0f,
			destinationTop,
			0f,
			height,
			intArrayOf(Color.TRANSPARENT, fadeColor),
			null,
			Shader.TileMode.CLAMP,
		)
		canvas.drawRect(0f, destinationTop, width, height, fillPaint)
		fillPaint.shader = null
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
				val fullBackground = usesFullPortraitArtwork()
				val assetSubdir = if (fullBackground) "miyorare-full" else "miyorare-hi"
				val chunkCount = if (fullBackground) MIYORARE_BACKGROUND_CHUNK_COUNT else MIYORARE_GOLDEN_CHUNK_COUNT
				val encoded = buildString {
					for (index in 0 until chunkCount) {
						val chunk = index.toString().padStart(2, '0')
						append(
							palette.resources.assets
								.open("$FAVOURITES_ASSET_DIR/$assetSubdir/$chunk.b64")
								.bufferedReader()
								.use { it.readText() },
						)
					}
				}
				val bytes = Base64.decode(encoded, Base64.DEFAULT)
				BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
			} else if (usesFullPortraitArtwork()) {
				palette.resources.assets.open(fullPortraitArtworkAssetPath()).use(BitmapFactory::decodeStream)
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

		val expectedWidth = if (usesFullPortraitArtwork()) FAVOURITES_PORTRAIT_WIDTH_PX else FAVOURITES_MASTER_WIDTH_PX
		val expectedHeight = if (usesFullPortraitArtwork()) FAVOURITES_PORTRAIT_HEIGHT_PX else FAVOURITES_MASTER_HEIGHT_PX
		if (decoded.width != expectedWidth || decoded.height != expectedHeight) {
			return null
		}

		synchronized(favouritesArtworkCache) {
			favouritesArtworkCache[cacheKey] = decoded
		}
		return decoded
	}

	private fun usesFullPortraitArtwork(): Boolean = !privateStyle

	private fun fullPortraitArtworkAssetPath(): String = when (palette.preset) {
		MiyorareThemePreset.SAKURA -> "$FAVOURITES_ASSET_DIR/theme-full/miyorare_favourites_sakura.webp"
		MiyorareThemePreset.VIOLET -> "$FAVOURITES_ASSET_DIR/theme-full/miyorare_favourites_violet.webp"
		MiyorareThemePreset.CYAN -> "$FAVOURITES_ASSET_DIR/theme-full/miyorare_favourites_cyan.webp"
		MiyorareThemePreset.EMERALD -> "$FAVOURITES_ASSET_DIR/theme-full/miyorare_favourites_emerald.webp"
		MiyorareThemePreset.AMBER -> "$FAVOURITES_ASSET_DIR/theme-full/miyorare_favourites_amber.webp"
		MiyorareThemePreset.MIYORARE, MiyorareThemePreset.CUSTOM ->
			error("Miyorare portrait background is stored in the approved chunked asset")
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

	private fun favouritesArtworkCacheKey(): String = when {
		usesFullPortraitArtwork() -> "${favouritesArtworkName()}-full-1080x2408-source-v1"
		usesMiyorareGoldenArtwork() -> "miyorare-golden-1080x835-approved-v2"
		else -> "${favouritesArtworkName()}-native-1080x835-final-v1"
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
			Variant.FAVOURITES_TOP, Variant.FAVOURITES_BODY, Variant.APP_BACKGROUND -> return
		}
		val alphaFraction = when (variant) {
			Variant.DETAILS -> 0.46f
			Variant.EXPLORE -> 0.25f
			Variant.FAVOURITES_TOP, Variant.FAVOURITES_BODY, Variant.APP_BACKGROUND -> return
		}
		val targetWidth = width * widthFraction
		val aspect = bitmap.height.toFloat() / bitmap.width.toFloat()
		val targetHeight = min(height * 0.62f, targetWidth * aspect)
		val right = width
		val top = when (variant) {
			Variant.DETAILS -> height * 0.035f
			Variant.EXPLORE -> height * 0.025f
			Variant.FAVOURITES_TOP, Variant.FAVOURITES_BODY, Variant.APP_BACKGROUND -> return
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
			withDrawableAlpha(palette.surfaceGradientStart, if (privateStyle) 1f else 0.72f),
			withDrawableAlpha(
				ColorUtils.blendARGB(palette.surfaceGradientMiddle, palette.primary, if (privateStyle) 0.14f else 0.05f),
				if (privateStyle) 1f else 0.62f,
			),
			withDrawableAlpha(palette.surfaceGradientStart, if (privateStyle) 1f else 0.70f),
		)
		Variant.FAVOURITES_BODY -> intArrayOf(
			withDrawableAlpha(
				ColorUtils.blendARGB(palette.surfaceGradientStart, palette.primary, if (privateStyle) 0.22f else 0.13f),
				if (privateStyle) 1f else 0.62f,
			),
			withDrawableAlpha(
				ColorUtils.blendARGB(palette.surfaceGradientMiddle, palette.accent, if (privateStyle) 0.16f else 0.10f),
				if (privateStyle) 1f else 0.50f,
			),
			withDrawableAlpha(
				ColorUtils.blendARGB(palette.surfaceGradientEnd, palette.surface, if (privateStyle) 0.30f else 0.17f),
				if (privateStyle) 1f else 0.58f,
			),
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
		Variant.APP_BACKGROUND -> intArrayOf(
			withDrawableAlpha(palette.background, 1f),
			withDrawableAlpha(palette.background, 1f),
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
		const val MIYORARE_BACKGROUND_CHUNK_COUNT = 42
		const val MIYORARE_GOLDEN_CHUNK_COUNT = 8
		const val FAVOURITES_PORTRAIT_WIDTH_PX = 1080
		const val FAVOURITES_PORTRAIT_HEIGHT_PX = 2408
		const val FAVOURITES_MASTER_WIDTH_PX = 1080
		const val FAVOURITES_MASTER_HEIGHT_PX = 835
		const val FALLBACK_TOP_HEIGHT_DP = 92f
		const val FAVOURITES_CONTINUATION_SOURCE_TOP_FRACTION = 0.48f
		const val FAVOURITES_CONTINUATION_ALPHA = 0.80f
		const val FAVOURITES_CONTINUATION_FADE_ALPHA = 0.22f
		const val APP_BACKGROUND_BLUR_WIDTH_PX = 135
		const val APP_BACKGROUND_BLUR_HEIGHT_PX = 301
		const val APP_BACKGROUND_BLUR_RADIUS_PX = 7
		const val APP_BACKGROUND_BLUR_PASSES = 2
		const val LIGHT_BACKGROUND_WHITE_BASE_MIX = 0.82f
		const val LIGHT_BACKGROUND_ARTWORK_ALPHA = 0.34f
		const val LIGHT_BACKGROUND_WASH_TOP_ALPHA = 0.78f
		const val LIGHT_BACKGROUND_WASH_MIDDLE_ALPHA = 0.68f
		const val LIGHT_BACKGROUND_WASH_BOTTOM_ALPHA = 0.74f
		val favouritesArtworkCache = HashMap<String, Bitmap>()
		val blurredFavouritesArtworkCache = HashMap<String, Bitmap>()
	}
}
