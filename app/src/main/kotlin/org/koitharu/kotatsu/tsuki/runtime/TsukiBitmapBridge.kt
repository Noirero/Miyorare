package org.koitharu.kotatsu.tsuki.runtime

import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect as AndroidRect
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import tsuki.bitmap.Bitmap
import tsuki.bitmap.Rect
import java.io.ByteArrayOutputStream

/** Android-backed implementation of the tiny bitmap surface exposed by Tsuki 1.0.x. */
internal object TsukiBitmapBridge {

	fun create(width: Int, height: Int): Bitmap {
		require(width > 0 && height > 0) { "Bitmap dimensions must be positive" }
		return Wrapper(android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888))
	}

	fun redraw(response: Response, block: (Bitmap) -> Bitmap): Response {
		val bytes = response.body.bytes()
		val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
			?: error("Could not decode image for Tsuki redraw")
		val mutable = if (decoded.isMutable) decoded else decoded.copy(android.graphics.Bitmap.Config.ARGB_8888, true)
			?: error("Could not allocate mutable image for Tsuki redraw")
		if (mutable !== decoded) decoded.recycle()
		val input = Wrapper(mutable)
		val output = block(input) as? Wrapper
			?: error("Tsuki image redraw must return a bitmap created by the host")
		val stream = ByteArrayOutputStream()
		try {
			require(output.bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream)) {
				"Could not encode Tsuki redraw result"
			}
			return response.newBuilder()
				.body(stream.toByteArray().toResponseBody("image/png".toMediaType()))
				.build()
		} finally {
			if (output !== input) input.bitmap.recycle()
			output.bitmap.recycle()
		}
	}

	private class Wrapper(val bitmap: android.graphics.Bitmap) : Bitmap {
		override val width: Int
		get() = bitmap.width

		override val height: Int
		get() = bitmap.height

		override fun drawBitmap(sourceBitmap: Bitmap, src: Rect, dst: Rect) {
			val source = (sourceBitmap as? Wrapper)?.bitmap
				?: error("Tsuki bitmap belongs to a different host")
			Canvas(bitmap).drawBitmap(
				source,
				AndroidRect(src.left, src.top, src.right, src.bottom),
				AndroidRect(dst.left, dst.top, dst.right, dst.bottom),
				null,
			)
		}
	}
}
