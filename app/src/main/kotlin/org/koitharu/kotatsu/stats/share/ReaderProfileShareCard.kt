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
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.readerjourney.theme.RankThemeRegistry
import org.koitharu.kotatsu.readerjourney.theme.RankThemeVariant
import org.koitharu.kotatsu.readerjourney.ui.titleRes
import org.koitharu.kotatsu.stats.domain.ReaderProfileShareModel
import java.io.File
import java.io.FileOutputStream
import java.text.NumberFormat

/**
 * Image renderer for the Phase 9 Reader Card.
 *
 * The only input is [ReaderProfileShareModel], a strict aggregate-only whitelist. This renderer has
 * no access to Manga, covers, sources, genres, tags, history, Reader Title, display name, showcase,
 * or Mature Include state.
 */
object ReaderProfileShareCard {

	private const val WIDTH = 1080
	private const val HEIGHT = 1350
	private const val PADDING = 84f

	fun renderToShareUri(context: Context, model: ReaderProfileShareModel) = run {
		val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
		val canvas = Canvas(bitmap)
		drawCard(context, canvas, model)

		val dir = File(context.cacheDir, "shares").apply { mkdirs() }
		val file = File(dir, "miyorare-reader-profile.png")
		FileOutputStream(file).use { output ->
			check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
		}
		bitmap.recycle()
		FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.files", file)
	}

	private fun drawCard(
		context: Context,
		canvas: Canvas,
		model: ReaderProfileShareModel,
	) {
		val themeTokens = model.theme
			?.let { RankThemeRegistry.resolve(it.stableId) }
			?.tokens(RankThemeVariant.DARK)
		val backgroundStart = themeTokens?.background?.toInt() ?: Color.rgb(12, 18, 31)
		val backgroundEnd = themeTokens?.surface?.toInt() ?: Color.rgb(30, 31, 58)
		val accentColor = themeTokens?.primaryAccent?.toInt() ?: Color.rgb(113, 224, 220)
		val secondaryAccent = themeTokens?.secondaryAccent?.toInt() ?: Color.rgb(125, 139, 255)

		val background = Paint(Paint.ANTI_ALIAS_FLAG).apply {
			shader = LinearGradient(
				0f,
				0f,
				WIDTH.toFloat(),
				HEIGHT.toFloat(),
				intArrayOf(backgroundStart, backgroundEnd, Color.rgb(16, 22, 38)),
				null,
				Shader.TileMode.CLAMP,
			)
		}
		canvas.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), background)

		val accent = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accentColor }
		canvas.drawRoundRect(RectF(PADDING, 70f, WIDTH - PADDING, 82f), 6f, 6f, accent)

		val eyebrow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
			color = accentColor
			textSize = 30f
			typeface = android.graphics.Typeface.DEFAULT_BOLD
		}
		val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
			color = Color.WHITE
			textSize = 58f
			typeface = android.graphics.Typeface.DEFAULT_BOLD
		}
		val hero = Paint(Paint.ANTI_ALIAS_FLAG).apply {
			color = Color.WHITE
			textSize = 78f
			typeface = android.graphics.Typeface.DEFAULT_BOLD
		}
		val value = Paint(Paint.ANTI_ALIAS_FLAG).apply {
			color = Color.WHITE
			textSize = 52f
			typeface = android.graphics.Typeface.DEFAULT_BOLD
		}
		val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
			color = Color.rgb(193, 201, 216)
			textSize = 28f
		}
		val footer = Paint(Paint.ANTI_ALIAS_FLAG).apply {
			color = Color.rgb(167, 177, 196)
			textSize = 23f
		}

		canvas.drawText("MIYORARE", PADDING, 150f, eyebrow)
		canvas.drawText(context.getString(R.string.reader_journey_share_profile_card_title), PADDING, 230f, title)

		canvas.drawText(context.getString(model.rank.titleRes), PADDING, 390f, hero)
		canvas.drawText(context.getString(R.string.reader_journey_profile_rank), PADDING, 438f, label)

		val panel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
			color = Color.argb(76, 255, 255, 255)
		}
		canvas.drawRoundRect(
			RectF(PADDING, 535f, WIDTH - PADDING, 790f),
			34f,
			34f,
			panel,
		)

		canvas.drawText(
			context.getString(R.string.reader_journey_level, model.level),
			PADDING + 38f,
			630f,
			value,
		)
		canvas.drawText(
			context.getString(R.string.reader_journey_profile_level),
			PADDING + 38f,
			674f,
			label,
		)

		val formattedXp = NumberFormat.getIntegerInstance().format(model.lifetimeXp)
		canvas.drawText(formattedXp, WIDTH / 2f + 24f, 630f, value)
		canvas.drawText(
			context.getString(R.string.reader_journey_profile_lifetime_xp),
			WIDTH / 2f + 24f,
			674f,
			label,
		)

		val themeName = model.theme?.displayName
			?: context.getString(R.string.reader_journey_share_profile_theme_default)
		val themePanel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
			color = Color.argb(62, 255, 255, 255)
		}
		canvas.drawRoundRect(
			RectF(PADDING, 860f, WIDTH - PADDING, 1065f),
			30f,
			30f,
			themePanel,
		)
		val themeAccent = Paint(Paint.ANTI_ALIAS_FLAG).apply {
			color = secondaryAccent
			textSize = 31f
			typeface = android.graphics.Typeface.DEFAULT_BOLD
		}
		canvas.drawText(
			context.getString(R.string.reader_journey_share_profile_theme),
			PADDING + 36f,
			930f,
			label,
		)
		canvas.drawText(themeName, PADDING + 36f, 1002f, themeAccent)

		canvas.drawText(
			context.getString(R.string.reader_journey_share_profile_privacy),
			PADDING,
			1218f,
			footer,
		)
		canvas.drawText("Reader Journey", PADDING, 1285f, eyebrow)
	}
}
