package org.koitharu.kotatsu.widget.common

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.util.Log
import androidx.annotation.Px
import coil3.ImageLoader
import coil3.asDrawable
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.request.bitmapConfig
import org.koitharu.kotatsu.core.util.ext.mangaSourceExtra
import org.koitharu.kotatsu.core.util.ext.toBitmapOrNull
import org.koitharu.kotatsu.parsers.model.Manga

object WidgetCoverLoader {

	private const val TAG = "WidgetCoverLoader"

	suspend fun load(
		context: Context,
		loader: ImageLoader,
		manga: Manga,
		@Px targetWidth: Int,
		@Px targetHeight: Int,
		@Px cornerRadiusPx: Float = 24f,
	): Bitmap? {
		val w = targetWidth.coerceAtLeast(1)
		val h = targetHeight.coerceAtLeast(1)
		val urlCandidates = buildList {
			manga.coverUrl?.takeIf { it.isNotBlank() }?.let { add(it) }
			manga.largeCoverUrl?.takeIf { it.isNotBlank() }?.let { add(it) }
		}
		if (urlCandidates.isEmpty()) {
			Log.w(TAG, "No cover URL is available")
			return null
		}

		for (url in urlCandidates) {
			val raw = tryLoad(context, loader, manga, url) ?: continue
			val rounded = runCatching { raw.roundedCenterCrop(w, h, cornerRadiusPx) }
				.onFailure { Log.w(TAG, "Rounded crop failed; using scaled raw bitmap", it) }
				.getOrNull()
			if (rounded != null) return rounded
			val scaled = runCatching {
				Bitmap.createScaledBitmap(raw, w, h, true)
			}.getOrNull()
			return scaled ?: raw
		}
		return null
	}

	/**
	 * Mirrors the proven path in `TrackerNotificationHelper`: just data + source-extra (for the
	 * header interceptor) + an explicit ARGB_8888 config so we never end up with a hardware
	 * bitmap that can't be drawn into a Canvas. `allowHardware(false)` belt-and-braces.
	 */
	private suspend fun tryLoad(
		context: Context,
		loader: ImageLoader,
		manga: Manga,
		url: String,
	): Bitmap? {
		val result = runCatching {
			val request = ImageRequest.Builder(context)
				.data(url)
				.mangaSourceExtra(manga.source)
				.bitmapConfig(Bitmap.Config.ARGB_8888)
				.allowHardware(false)
				.build()
			loader.execute(request)
		}.onFailure { Log.w(TAG, "Cover request failed: ${it.javaClass.simpleName}") }.getOrNull()

		return when (val r = result) {
			null -> null
			is SuccessResult -> {
				val direct = r.toBitmapOrNull()
				if (direct != null) return direct
				// Fall back: rasterize the drawable onto a software bitmap. Handles cases
				// where the underlying image is HW-config or a vector/animated source.
				Log.w(TAG, "Cover bitmap conversion failed; rasterizing drawable")
				rasterizeDrawable(context, r)
			}
			is ErrorResult -> {
				Log.w(TAG, "Cover load failed: ${r.throwable.javaClass.simpleName}")
				null
			}
		}
	}

	private fun rasterizeDrawable(context: Context, result: SuccessResult): Bitmap? = runCatching {
		val drawable = result.image.asDrawable(context.resources)
		val w = drawable.intrinsicWidth.takeIf { it > 0 } ?: 480
		val h = drawable.intrinsicHeight.takeIf { it > 0 } ?: 640
		val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
		val canvas = Canvas(bmp)
		drawable.setBounds(0, 0, w, h)
		drawable.draw(canvas)
		bmp
	}.onFailure { Log.w(TAG, "rasterizeDrawable failed", it) }.getOrNull()

	private fun Bitmap.roundedCenterCrop(
		@Px outW: Int,
		@Px outH: Int,
		@Px corner: Float,
	): Bitmap {
		val output = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
		val canvas = Canvas(output)
		val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
			isFilterBitmap = true
			color = Color.BLACK
		}
		val rectF = RectF(0f, 0f, outW.toFloat(), outH.toFloat())
		canvas.drawRoundRect(rectF, corner, corner, paint)
		paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
		val srcRatio = width.toFloat() / height.toFloat()
		val dstRatio = outW.toFloat() / outH.toFloat()
		val src = if (srcRatio > dstRatio) {
			val scaledW = (height * dstRatio).toInt().coerceAtLeast(1)
			val offsetX = ((width - scaledW) / 2).coerceAtLeast(0)
			Rect(offsetX, 0, offsetX + scaledW, height)
		} else {
			val scaledH = (width / dstRatio).toInt().coerceAtLeast(1)
			val offsetY = ((height - scaledH) / 2).coerceAtLeast(0)
			Rect(0, offsetY, width, offsetY + scaledH)
		}
		val dst = Rect(0, 0, outW, outH)
		canvas.drawBitmap(this, src, dst, paint)
		return output
	}

	fun dpToPx(context: Context, dp: Int): Int = (dp * context.resources.displayMetrics.density).toInt()
}
