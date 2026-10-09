package org.koitharu.kotatsu.local.library

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import coil3.decode.DecodeUtils
import coil3.gif.isAnimatedWebP
import coil3.gif.isGif
import okio.Buffer
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.math.roundToInt

/** Format-aware presentation-sized cover. Bounds decoding before allocating the bitmap. */
internal object LocalCoverThumbnail {
    const val MAX_EDGE = LocalCoverRecipe.STATIC_MAX_EDGE

    /** Preserve encoded animation; eligibility changes persistence, never its presentation. */
    fun prepare(bytes: ByteArray, candidateIndex: Int): GeneratedLocalCover? {
        val animated = animation(bytes)
        if (animated == false) return encode(bytes)?.let { GeneratedLocalCover(it, candidateIndex) }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val cacheable = animated == true && bytes.size <= SmartLocalCoverCache.MAX_THUMBNAIL_BYTES &&
            maxOf(bounds.outWidth, bounds.outHeight) <= LocalCoverRecipe.ANIMATION_MAX_EDGE
        return GeneratedLocalCover(bytes, candidateIndex, cacheable)
    }

    /** Null means an uncertain GIF structure: leave decoding to Coil and do not persist it. */
    internal fun animation(bytes: ByteArray): Boolean? {
        val header = Buffer().write(bytes, 0, minOf(21, bytes.size))
        return when {
            DecodeUtils.isAnimatedWebP(header) -> true
            DecodeUtils.isGif(header) -> gifHasMultipleFrames(bytes)
            else -> false
        }
    }

    // GIF89a block structure: https://www.w3.org/Graphics/GIF/spec-gif89a.txt
    // Skip palettes and length-prefixed compressed sub-blocks, without decoding any pixels.
    private fun gifHasMultipleFrames(bytes: ByteArray): Boolean? {
        if (bytes.size < 13) return null
        fun unsigned(index: Int) = bytes[index].toInt() and 0xff
        fun paletteSize(flags: Int) = if (flags and 0x80 != 0) 3 * (1 shl ((flags and 7) + 1)) else 0
        var offset = 13 + paletteSize(unsigned(10))
        var frames = 0
        fun skipSubBlocks(): Boolean {
            while (offset < bytes.size) {
                val length = unsigned(offset++)
                if (length == 0) return true
                if (length > bytes.size - offset) return false
                offset += length
            }
            return false
        }
        while (offset < bytes.size) {
            when (unsigned(offset++)) {
                0x3b -> return if (frames == 1) false else null // Trailer; a one-frame GIF is static.
                0x21 -> {
                    if (offset >= bytes.size) return null
                    val label = unsigned(offset++)
                    // Plain text may itself render a graphic; unsupported extensions are uncertain.
                    if ((label != 0xf9 && label != 0xfe && label != 0xff) || !skipSubBlocks()) return null
                }
                0x2c -> {
                    if (bytes.size - offset < 9) return null
                    val flags = unsigned(offset + 8)
                    offset += 9 + paletteSize(flags)
                    if (offset >= bytes.size) return null
                    offset++ // LZW minimum code size.
                    if (!skipSubBlocks()) return null
                    if (++frames == 2) return true
                }
                else -> return null
            }
        }
        return null
    }

    fun encode(bytes: ByteArray): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val sample = LocalCoverRecipe.sampleSize(bounds.outWidth, bounds.outHeight)
        val options = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        var bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        try {
            val matrix = orientation(bytes)
            if (!matrix.isIdentity) {
                val oriented = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                if (oriented !== bitmap) { bitmap.recycle(); bitmap = oriented }
            }
            val scale = minOf(1f, MAX_EDGE.toFloat() / maxOf(bitmap.width, bitmap.height))
            if (scale < 1f) {
                val scaled = Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).roundToInt().coerceAtLeast(1),
                    (bitmap.height * scale).roundToInt().coerceAtLeast(1), true)
                if (scaled !== bitmap) { bitmap.recycle(); bitmap = scaled }
            }
            return encodeBitmap(bitmap)
        } finally { bitmap.recycle() }
    }

    /** PDF callers already render to the target; do not decode/transcode their encoded output. */
    fun encodeBitmap(bitmap: Bitmap): ByteArray {
        val transparent = hasTransparency(bitmap)
        val output = ByteArrayOutputStream()
        val format = if (transparent) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
        val quality = if (transparent) 100 else LocalCoverRecipe.JPEG_QUALITY
        if (!bitmap.compress(format, quality, output)) throw IOException("Cannot encode local thumbnail")
        return output.toByteArray()
    }

    // hasAlpha is a capability flag. An opaque ARGB PNG should still take the compact JPEG path.
    private fun hasTransparency(bitmap: Bitmap): Boolean {
        if (!bitmap.hasAlpha()) return false
        val row = IntArray(bitmap.width)
        for (y in 0 until bitmap.height) {
            bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
            if (row.any { (it ushr 24) != 255 }) return true
        }
        return false
    }

    private fun orientation(bytes: ByteArray): Matrix {
        val value = runCatching { ByteArrayInputStream(bytes).use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        return Matrix().apply {
            when (value) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(-90f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(-90f)
            }
        }
    }
}

