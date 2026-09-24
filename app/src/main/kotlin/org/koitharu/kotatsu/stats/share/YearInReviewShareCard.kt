package org.koitharu.kotatsu.stats.share

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import androidx.core.content.FileProvider
import org.koitharu.kotatsu.BuildConfig
import org.koitharu.kotatsu.stats.domain.YearInReview
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * Generates an image from aggregate-only [YearInReview] data.
 *
 * This renderer intentionally has no Manga/StatsRecord/source/genre input. Even when the dashboard
 * is in Include mode, the generated card cannot leak identifying mature metadata.
 */
object YearInReviewShareCard {

	private const val WIDTH = 1080
	private const val HEIGHT = 1350
	private const val PADDING = 84f

	fun renderToShareUri(context: Context, review: YearInReview) = run {
		val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
		val canvas = Canvas(bitmap)
		drawCard(canvas, review)

		val dir = File(context.cacheDir, "shares").apply { mkdirs() }
		val file = File(dir, "miyorare-year-in-review-${review.year}.png")
		FileOutputStream(file).use { output ->
			check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
		}
		bitmap.recycle()
		FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.files", file)
	}

	private fun drawCard(canvas: Canvas, review: YearInReview) {
		val background = Paint(Paint.ANTI_ALIAS_FLAG).apply {
			shader = LinearGradient(
				0f,
				0f,
				WIDTH.toFloat(),
				HEIGHT.toFloat(),
				intArrayOf(Color.rgb(12, 18, 31), Color.rgb(30, 31, 58), Color.rgb(22, 52, 63)),
				null,
				Shader.TileMode.CLAMP,
			)
		}
		canvas.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), background)

		val accent = Paint(Paint.ANTI_ALIAS_FLAG).apply {
			color = Color.rgb(113, 224, 220)
		}
		canvas.drawRoundRect(RectF(PADDING, 70f, WIDTH - PADDING, 82f), 6f, 6f, accent)

		val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
			color = Color.WHITE
			textSize = 54f
			typeface = android.graphics.Typeface.DEFAULT_BOLD
		}
		val eyebrow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
			color = Color.rgb(143, 222, 219)
			textSize = 30f
			letterSpacing = 0.08f
			typeface = android.graphics.Typeface.DEFAULT_BOLD
		}
		val value = Paint(Paint.ANTI_ALIAS_FLAG).apply {
			color = Color.WHITE
			textSize = 52f
			typeface = android.graphics.Typeface.DEFAULT_BOLD
		}
		val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
			color = Color.rgb(188, 197, 214)
			textSize = 27f
		}
		val footer = Paint(Paint.ANTI_ALIAS_FLAG).apply {
			color = Color.rgb(156, 168, 188)
			textSize = 23f
		}

		canvas.drawText("MIYORARE", PADDING, 150f, eyebrow)
		canvas.drawText("${review.year} Year in Review", PADDING, 225f, title)

		val rows = listOf(
			"Reading time" to formatDuration(review.totalDuration),
			"Chapters" to review.chapters.toString(),
			"Pages" to review.pages.toString(),
			"Active days" to review.activeDays.toString(),
			"Titles" to review.titleCount.toString(),
			"Longest streak" to "${review.longestStreak} days",
		)

		var y = 340f
		rows.forEachIndexed { index, (name, amount) ->
			val leftColumn = index % 2 == 0
			val x = if (leftColumn) PADDING else WIDTH / 2f + 18f
			if (index > 0 && leftColumn) y += 178f
			canvas.drawText(amount, x, y, value)
			canvas.drawText(name, x, y + 43f, label)
		}

		val panelTop = 940f
		val panel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
			color = Color.argb(105, 255, 255, 255)
		}
		canvas.drawRoundRect(
			RectF(PADDING, panelTop, WIDTH - PADDING, panelTop + 180f),
			32f,
			32f,
			panel,
		)
		canvas.drawText("Manga", PADDING + 38f, panelTop + 58f, label)
		canvas.drawText(review.mangaChapters.toString(), PADDING + 38f, panelTop + 126f, value)
		canvas.drawText("Novel", WIDTH / 2f + 28f, panelTop + 58f, label)
		canvas.drawText(review.novelChapters.toString(), WIDTH / 2f + 28f, panelTop + 126f, value)

		canvas.drawText(
			"Private by default · no titles, sources or genres shared",
			PADDING,
			1248f,
			footer,
		)
		canvas.drawText("Reader Journey", PADDING, 1295f, eyebrow)
	}

	private fun formatDuration(durationMs: Long): String {
		val minutes = TimeUnit.MILLISECONDS.toMinutes(durationMs.coerceAtLeast(0L))
		val hours = minutes / 60
		val remainder = minutes % 60
		return when {
			hours > 0L && remainder > 0L -> "${hours}h ${remainder}m"
			hours > 0L -> "${hours}h"
			else -> "${remainder}m"
		}
	}
}
