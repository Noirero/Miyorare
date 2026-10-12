package org.koitharu.kotatsu.details.ui.prediction

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.AtomicFile
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File

internal const val PREDICTION_PREFS = "release_prediction_calendar"
internal const val KEY_BACKGROUND = "background_mode"
internal const val KEY_BACKGROUND_REVISION = "background_revision"
internal const val KEY_COVER_BLUR = "cover_blur"

enum class PredictionCalendarBackgroundMode { DEFAULT, MANGA_COVER, CUSTOM }

internal fun PredictionCalendarBackgroundMode.shouldBlur(blurCover: Boolean) =
	this == PredictionCalendarBackgroundMode.MANGA_COVER && blurCover

/** App-owned, global calendar image; mirrors the appearance import's sampled/atomic file pattern. */
internal object PredictionCalendarBackgroundStore {
	private const val FILE_NAME = "release_prediction_calendar_background"
	private const val MAX_INPUT_BYTES = 16L * 1024 * 1024
	private const val MAX_DIMENSION = 2048

	fun file(context: Context) = File(context.filesDir, FILE_NAME)

	fun readMode(context: Context): PredictionCalendarBackgroundMode {
		val name = context.getSharedPreferences(PREDICTION_PREFS, Context.MODE_PRIVATE).getString(KEY_BACKGROUND, null)
		return PredictionCalendarBackgroundMode.entries.firstOrNull { it.name == name } ?: PredictionCalendarBackgroundMode.DEFAULT
	}

	fun writeMode(context: Context, mode: PredictionCalendarBackgroundMode) {
		context.getSharedPreferences(PREDICTION_PREFS, Context.MODE_PRIVATE).edit().putString(KEY_BACKGROUND, mode.name).apply()
	}

	fun readCoverBlur(context: Context) =
		context.getSharedPreferences(PREDICTION_PREFS, Context.MODE_PRIVATE).getBoolean(KEY_COVER_BLUR, true)

	fun writeCoverBlur(context: Context, enabled: Boolean) {
		context.getSharedPreferences(PREDICTION_PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_COVER_BLUR, enabled).apply()
	}

	suspend fun import(context: Context, uri: Uri) {
		val temporary = File.createTempFile("prediction-background-", ".tmp", context.cacheDir)
		try {
			context.contentResolver.openInputStream(uri)?.use { input ->
				temporary.outputStream().use { output ->
					val buffer = ByteArray(8192)
					var total = 0L
					while (true) {
						currentCoroutineContext().ensureActive()
						val read = input.read(buffer)
						if (read < 0) break
						total += read
						require(total <= MAX_INPUT_BYTES) { "Selected image is too large" }
						output.write(buffer, 0, read)
					}
				}
			} ?: error("Selected image cannot be read")
			val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
			BitmapFactory.decodeFile(temporary.path, bounds)
			require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Selected document is not an image" }
			var sample = 1
			while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_DIMENSION) sample *= 2
			val decoded = BitmapFactory.decodeFile(temporary.path, BitmapFactory.Options().apply { inSampleSize = sample })
				?: error("Selected image cannot be decoded")
			val bitmap = orient(temporary, decoded)
			if (bitmap !== decoded) decoded.recycle()
			try {
				currentCoroutineContext().ensureActive()
				val atomic = AtomicFile(file(context))
				val output = atomic.startWrite()
				try {
					check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output))
					currentCoroutineContext().ensureActive()
					atomic.finishWrite(output)
				} catch (error: Throwable) {
					atomic.failWrite(output)
					throw error
				}
			} finally { bitmap.recycle() }
		} finally { temporary.delete() }
	}

	private fun orient(file: File, source: Bitmap): Bitmap {
		val orientation = runCatching { ExifInterface(file).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
			.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
		val matrix = Matrix()
		when (orientation) {
			ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
			ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
			ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
			ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.setRotate(90f); matrix.postScale(-1f, 1f) }
			ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
			ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.setRotate(-90f); matrix.postScale(-1f, 1f) }
			ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
			else -> return source
		}
		return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
	}
}
