package org.koitharu.kotatsu.core.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import androidx.core.content.edit
import androidx.core.graphics.ColorUtils
import androidx.preference.PreferenceManager
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import org.koitharu.kotatsu.core.prefs.MiyorareAppearance

/**
 * Owns the user-selected Normal-Miyorare wallpaper pipeline.
 *
 * Expensive work happens only when the user selects a new image: normalize once to 1080x2408,
 * extract three dominant colour families, generate a tiny pre-blurred copy, and persist all
 * products in app-private storage. Runtime screens only decode the cached products.
 */
object MiyorareCustomBackgroundStore {

    data class ImportResult(
        val primary: Int,
        val secondary: Int,
        val tertiary: Int,
        val revision: Int,
    )

    fun hasBackground(context: Context): Boolean =
        sharpFile(context).isFile && blurFile(context).isFile && previewFile(context).isFile

    fun sharpPathOrNull(context: Context): String? =
        sharpFile(context).takeIf(File::isFile)?.absolutePath

    fun blurPathOrNull(context: Context): String? =
        blurFile(context).takeIf(File::isFile)?.absolutePath

    fun previewPathOrNull(context: Context): String? =
        previewFile(context).takeIf(File::isFile)?.absolutePath

    fun revision(context: Context): Int =
        PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
            .getInt(MiyorareAppearance.KEY_CUSTOM_BACKGROUND_REVISION, 0)

    fun import(context: Context, uri: Uri): Result<ImportResult> = runCatching {
        val appContext = context.applicationContext
        val source = decodeSampled(appContext, uri)
            ?: error("Unable to decode selected background")
        val portrait = try {
            centerCrop(source, SHARP_WIDTH, SHARP_HEIGHT)
        } finally {
            source.recycle()
        }

        val palette = extractDominantPalette(portrait)
        val preview = Bitmap.createScaledBitmap(portrait, PREVIEW_WIDTH, PREVIEW_HEIGHT, true)
        val blurBase = Bitmap.createScaledBitmap(portrait, BLUR_WIDTH, BLUR_HEIGHT, true)
        val blurred = boxBlur(blurBase, BLUR_RADIUS, BLUR_PASSES)
        if (blurred !== blurBase) blurBase.recycle()

        val dir = backgroundDir(appContext).apply { mkdirs() }
        val sharpTmp = File(dir, "$SHARP_FILE.tmp")
        val blurTmp = File(dir, "$BLUR_FILE.tmp")
        val previewTmp = File(dir, "$PREVIEW_FILE.tmp")
        try {
            writeJpeg(portrait, sharpTmp, 92)
            writeJpeg(blurred, blurTmp, 88)
            writeJpeg(preview, previewTmp, 88)
            replaceAtomically(sharpTmp, sharpFile(appContext))
            replaceAtomically(blurTmp, blurFile(appContext))
            replaceAtomically(previewTmp, previewFile(appContext))
        } finally {
            portrait.recycle()
            preview.recycle()
            blurred.recycle()
            sharpTmp.delete()
            blurTmp.delete()
            previewTmp.delete()
        }

        val prefs = PreferenceManager.getDefaultSharedPreferences(appContext)
        val nextRevision = prefs.getInt(MiyorareAppearance.KEY_CUSTOM_BACKGROUND_REVISION, 0) + 1
        prefs.edit {
            putString(MiyorareAppearance.KEY_CUSTOM_BACKGROUND_PRIMARY, MiyorareAppearance.formatAccent(palette[0]))
            putString(MiyorareAppearance.KEY_CUSTOM_BACKGROUND_SECONDARY, MiyorareAppearance.formatAccent(palette[1]))
            putString(MiyorareAppearance.KEY_CUSTOM_BACKGROUND_TERTIARY, MiyorareAppearance.formatAccent(palette[2]))
            putBoolean(MiyorareAppearance.KEY_CUSTOM_BACKGROUND_COLOR_SYNC, true)
            putInt(MiyorareAppearance.KEY_CUSTOM_BACKGROUND_REVISION, nextRevision)
        }
        ImportResult(palette[0], palette[1], palette[2], nextRevision)
    }

    fun clear(context: Context) {
        val appContext = context.applicationContext
        sharpFile(appContext).delete()
        blurFile(appContext).delete()
        previewFile(appContext).delete()
        val prefs = PreferenceManager.getDefaultSharedPreferences(appContext)
        prefs.edit {
            remove(MiyorareAppearance.KEY_CUSTOM_BACKGROUND_PRIMARY)
            remove(MiyorareAppearance.KEY_CUSTOM_BACKGROUND_SECONDARY)
            remove(MiyorareAppearance.KEY_CUSTOM_BACKGROUND_TERTIARY)
            putInt(
                MiyorareAppearance.KEY_CUSTOM_BACKGROUND_REVISION,
                prefs.getInt(MiyorareAppearance.KEY_CUSTOM_BACKGROUND_REVISION, 0) + 1,
            )
        }
    }

    private fun backgroundDir(context: Context): File =
        File(context.filesDir, "miyorare/custom-background")

    private fun sharpFile(context: Context): File = File(backgroundDir(context), SHARP_FILE)
    private fun blurFile(context: Context): File = File(backgroundDir(context), BLUR_FILE)
    private fun previewFile(context: Context): File = File(backgroundDir(context), PREVIEW_FILE)

    private fun decodeSampled(context: Context, uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (
            bounds.outWidth / (sample * 2) >= DECODE_MIN_WIDTH &&
            bounds.outHeight / (sample * 2) >= DECODE_MIN_HEIGHT
        ) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        }
    }

    private fun centerCrop(source: Bitmap, width: Int, height: Int): Bitmap {
        val sourceAspect = source.width.toFloat() / source.height.toFloat()
        val targetAspect = width.toFloat() / height.toFloat()
        val src = if (sourceAspect > targetAspect) {
            val cropWidth = (source.height * targetAspect).roundToInt().coerceIn(1, source.width)
            val left = (source.width - cropWidth) / 2
            Rect(left, 0, left + cropWidth, source.height)
        } else {
            val cropHeight = (source.width / targetAspect).roundToInt().coerceIn(1, source.height)
            val top = (source.height - cropHeight) / 2
            Rect(0, top, source.width, top + cropHeight)
        }
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { target ->
            Canvas(target).drawBitmap(
                source,
                src,
                Rect(0, 0, width, height),
                Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG),
            )
        }
    }

    /**
     * Lightweight hue clustering. It preserves several strong colour families instead of averaging
     * a colourful image into one muddy RGB value.
     */
    private fun extractDominantPalette(source: Bitmap): IntArray {
        val sample = Bitmap.createScaledBitmap(source, PALETTE_WIDTH, PALETTE_HEIGHT, true)
        try {
            val weights = FloatArray(HUE_BUCKETS)
            val reds = FloatArray(HUE_BUCKETS)
            val greens = FloatArray(HUE_BUCKETS)
            val blues = FloatArray(HUE_BUCKETS)
            val hsl = FloatArray(3)
            val pixels = IntArray(sample.width * sample.height)
            sample.getPixels(pixels, 0, sample.width, 0, 0, sample.width, sample.height)

            for (pixel in pixels) {
                if (Color.alpha(pixel) < 200) continue
                ColorUtils.colorToHSL(pixel, hsl)
                val saturation = hsl[1]
                val lightness = hsl[2]
                if (saturation < 0.14f || lightness < 0.08f || lightness > 0.94f) continue
                val bucket = ((hsl[0] / 360f) * HUE_BUCKETS).toInt().coerceIn(0, HUE_BUCKETS - 1)
                val centerBias = 1f - abs(lightness - 0.55f) * 0.65f
                val weight = (0.22f + saturation * 0.78f) * centerBias.coerceAtLeast(0.35f)
                weights[bucket] += weight
                reds[bucket] += Color.red(pixel) * weight
                greens[bucket] += Color.green(pixel) * weight
                blues[bucket] += Color.blue(pixel) * weight
            }

            val selected = ArrayList<Int>(3)
            val ordered = weights.indices.sortedByDescending { weights[it] }
            for (bucket in ordered) {
                if (weights[bucket] <= 0f) break
                val farEnough = selected.all { circularBucketDistance(it, bucket) >= MIN_BUCKET_DISTANCE }
                if (farEnough) selected += bucket
                if (selected.size == 3) break
            }

            val colors = selected.map { bucket ->
                val weight = weights[bucket].coerceAtLeast(0.0001f)
                Color.rgb(
                    (reds[bucket] / weight).roundToInt().coerceIn(0, 255),
                    (greens[bucket] / weight).roundToInt().coerceIn(0, 255),
                    (blues[bucket] / weight).roundToInt().coerceIn(0, 255),
                )
            }.toMutableList()

            if (colors.isEmpty()) {
                colors += DEFAULT_PRIMARY
            }
            while (colors.size < 3) {
                colors += deriveCompanion(colors.first(), if (colors.size == 1) 52f else 205f)
            }
            return intArrayOf(colors[0], colors[1], colors[2])
        } finally {
            sample.recycle()
        }
    }

    private fun deriveCompanion(color: Int, hueShift: Float): Int {
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(color, hsl)
        hsl[0] = (hsl[0] + hueShift) % 360f
        hsl[1] = max(hsl[1], 0.46f)
        hsl[2] = hsl[2].coerceIn(0.44f, 0.64f)
        return ColorUtils.HSLToColor(hsl)
    }

    private fun circularBucketDistance(a: Int, b: Int): Int {
        val raw = abs(a - b)
        return minOf(raw, HUE_BUCKETS - raw)
    }

    private fun boxBlur(source: Bitmap, radius: Int, passes: Int): Bitmap {
        val width = source.width
        val height = source.height
        val input = IntArray(width * height)
        val temp = IntArray(input.size)
        source.getPixels(input, 0, width, 0, 0, width, height)
        repeat(passes) {
            blurHorizontal(input, temp, width, height, radius)
            blurVertical(temp, input, width, height, radius)
        }
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
            it.setPixels(input, 0, width, 0, 0, width, height)
        }
    }

    private fun blurHorizontal(input: IntArray, output: IntArray, width: Int, height: Int, radius: Int) {
        val diameter = radius * 2 + 1
        for (y in 0 until height) {
            val row = y * width
            var a = 0
            var r = 0
            var g = 0
            var b = 0
            for (offset in -radius..radius) {
                val pixel = input[row + offset.coerceIn(0, width - 1)]
                a += Color.alpha(pixel); r += Color.red(pixel); g += Color.green(pixel); b += Color.blue(pixel)
            }
            for (x in 0 until width) {
                output[row + x] = Color.argb(a / diameter, r / diameter, g / diameter, b / diameter)
                val remove = input[row + (x - radius).coerceIn(0, width - 1)]
                val add = input[row + (x + radius + 1).coerceIn(0, width - 1)]
                a += Color.alpha(add) - Color.alpha(remove)
                r += Color.red(add) - Color.red(remove)
                g += Color.green(add) - Color.green(remove)
                b += Color.blue(add) - Color.blue(remove)
            }
        }
    }

    private fun blurVertical(input: IntArray, output: IntArray, width: Int, height: Int, radius: Int) {
        val diameter = radius * 2 + 1
        for (x in 0 until width) {
            var a = 0
            var r = 0
            var g = 0
            var b = 0
            for (offset in -radius..radius) {
                val pixel = input[offset.coerceIn(0, height - 1) * width + x]
                a += Color.alpha(pixel); r += Color.red(pixel); g += Color.green(pixel); b += Color.blue(pixel)
            }
            for (y in 0 until height) {
                output[y * width + x] = Color.argb(a / diameter, r / diameter, g / diameter, b / diameter)
                val remove = input[(y - radius).coerceIn(0, height - 1) * width + x]
                val add = input[(y + radius + 1).coerceIn(0, height - 1) * width + x]
                a += Color.alpha(add) - Color.alpha(remove)
                r += Color.red(add) - Color.red(remove)
                g += Color.green(add) - Color.green(remove)
                b += Color.blue(add) - Color.blue(remove)
            }
        }
    }

    private fun writeJpeg(bitmap: Bitmap, file: File, quality: Int) {
        file.outputStream().buffered().use { out ->
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out))
        }
    }

    private fun replaceAtomically(temp: File, target: File) {
        if (target.exists() && !target.delete()) error("Unable to replace custom background")
        if (!temp.renameTo(target)) error("Unable to save custom background")
    }

    private const val SHARP_FILE = "wallpaper.jpg"
    private const val BLUR_FILE = "wallpaper_blur.jpg"
    private const val PREVIEW_FILE = "wallpaper_preview.jpg"
    private const val SHARP_WIDTH = 1080
    private const val SHARP_HEIGHT = 2408
    private const val PREVIEW_WIDTH = 270
    private const val PREVIEW_HEIGHT = 602
    private const val BLUR_WIDTH = 180
    private const val BLUR_HEIGHT = 401
    private const val BLUR_RADIUS = 9
    private const val BLUR_PASSES = 2
    private const val DECODE_MIN_WIDTH = 1400
    private const val DECODE_MIN_HEIGHT = 1800
    private const val PALETTE_WIDTH = 72
    private const val PALETTE_HEIGHT = 160
    private const val HUE_BUCKETS = 18
    private const val MIN_BUCKET_DISTANCE = 2
    private const val DEFAULT_PRIMARY = 0xFF5B6CFF.toInt()
}
