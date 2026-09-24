package org.koitharu.kotatsu.readerjourney.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import org.koitharu.kotatsu.readerjourney.theme.RankThemeTokens
import org.koitharu.kotatsu.readerjourney.theme.ReferenceBadgeStyle
import org.koitharu.kotatsu.readerjourney.theme.ReferenceFrameStyle
import org.koitharu.kotatsu.readerjourney.theme.ReferenceProgressStyle
import org.koitharu.kotatsu.readerjourney.theme.ReferenceRankThemeVisualSpec
import org.koitharu.kotatsu.readerjourney.theme.ReferenceWallpaperStyle

/**
 * Static, local-first reference visuals. No bitmap decode, network fetch, shader loop or persistent
 * animation is required. These renderers are presentation-only and never read/write progression.
 */
@Composable
fun ReferenceRankThemeBadge(
	spec: ReferenceRankThemeVisualSpec,
	tokens: RankThemeTokens,
	modifier: Modifier = Modifier,
) {
	val primary = Color(tokens.primaryAccent.toInt())
	val secondary = Color(tokens.secondaryAccent.toInt())
	val border = Color(tokens.borderEmphasis.toInt())
	Canvas(modifier = modifier.aspectRatio(1f)) {
		when (spec.badgeStyle) {
			ReferenceBadgeStyle.CRYSTAL -> {
				val path = Path().apply {
					moveTo(size.width * 0.50f, size.height * 0.08f)
					lineTo(size.width * 0.86f, size.height * 0.42f)
					lineTo(size.width * 0.50f, size.height * 0.92f)
					lineTo(size.width * 0.14f, size.height * 0.42f)
					close()
				}
				drawPath(
					path = path,
					brush = Brush.linearGradient(listOf(primary, secondary)),
				)
				drawPath(path = path, color = border, style = Stroke(width = size.minDimension * 0.045f))
				drawLine(
					color = Color(tokens.onAccent.toInt()).copy(alpha = 0.58f),
					start = Offset(size.width * 0.50f, size.height * 0.13f),
					end = Offset(size.width * 0.50f, size.height * 0.84f),
					strokeWidth = size.minDimension * 0.025f,
				)
			}

			ReferenceBadgeStyle.ARCHIVE_SEAL -> {
				val stroke = size.minDimension * 0.05f
				drawRoundRect(
					brush = Brush.linearGradient(listOf(primary, secondary)),
					topLeft = Offset(size.width * 0.12f, size.height * 0.18f),
					size = Size(size.width * 0.76f, size.height * 0.66f),
					cornerRadius = CornerRadius(size.minDimension * 0.16f),
				)
				drawRoundRect(
					color = border,
					topLeft = Offset(size.width * 0.12f, size.height * 0.18f),
					size = Size(size.width * 0.76f, size.height * 0.66f),
					cornerRadius = CornerRadius(size.minDimension * 0.16f),
					style = Stroke(width = stroke),
				)
				val mark = Color(tokens.onAccent.toInt()).copy(alpha = 0.88f)
				repeat(3) { index ->
					val y = size.height * (0.37f + index * 0.13f)
					drawLine(
						color = mark,
						start = Offset(size.width * 0.30f, y),
						end = Offset(size.width * 0.70f, y),
						strokeWidth = size.minDimension * 0.035f,
					)
				}
			}
		}
	}
}

@Composable
fun ReferenceRankThemeFrame(
	spec: ReferenceRankThemeVisualSpec,
	tokens: RankThemeTokens,
	modifier: Modifier = Modifier,
	content: @Composable () -> Unit,
) {
	val primary = Color(tokens.primaryAccent.toInt())
	val secondary = Color(tokens.secondaryAccent.toInt())
	val shape = RoundedCornerShape(if (spec.frameStyle == ReferenceFrameStyle.SIMPLE_GRAPHITE) 18.dp else 24.dp)
	val width = if (spec.frameStyle == ReferenceFrameStyle.SIMPLE_GRAPHITE) 1.dp else 2.dp
	Box(
		modifier = modifier
			.border(
				BorderStroke(width, Brush.linearGradient(listOf(primary, secondary))),
				shape,
			)
			.padding(width),
	) {
		content()
	}
}

@Composable
fun ReferenceRankThemeWallpaper(
	spec: ReferenceRankThemeVisualSpec,
	tokens: RankThemeTokens,
	modifier: Modifier = Modifier,
) {
	val background = Color(tokens.background.toInt())
	val surface = Color(tokens.surface.toInt())
	val primary = Color(tokens.primaryAccent.toInt())
	val secondary = Color(tokens.secondaryAccent.toInt())
	val subtle = Color(tokens.borderSubtle.toInt())

	Canvas(modifier = modifier) {
		drawRect(
			brush = Brush.linearGradient(
				colors = listOf(background, surface, primary.copy(alpha = 0.18f)),
				start = Offset.Zero,
				end = Offset(size.width, size.height),
			),
		)
		when (spec.wallpaperStyle) {
			ReferenceWallpaperStyle.MINIMAL_PAGES -> {
				val pageW = size.width * 0.30f
				val pageH = size.height * 0.42f
				repeat(3) { index ->
					val x = size.width * (0.08f + index * 0.27f)
					val y = size.height * (0.18f + (index % 2) * 0.16f)
					drawRoundRect(
						color = subtle.copy(alpha = 0.48f),
						topLeft = Offset(x, y),
						size = Size(pageW, pageH),
						cornerRadius = CornerRadius(size.minDimension * 0.035f),
						style = Stroke(width = size.minDimension * 0.008f),
					)
				}
			}

			ReferenceWallpaperStyle.DIGITAL_NIGHT_ARCHIVE -> {
				val line = secondary.copy(alpha = 0.18f)
				for (i in 1..5) {
					val y = size.height * i / 6f
					drawLine(
						color = line,
						start = Offset(size.width * 0.08f, y),
						end = Offset(size.width * 0.92f, y),
						strokeWidth = size.minDimension * 0.006f,
					)
				}
				for (i in 1..4) {
					val x = size.width * i / 5f
					drawLine(
						color = line,
						start = Offset(x, size.height * 0.12f),
						end = Offset(x, size.height * 0.88f),
						strokeWidth = size.minDimension * 0.005f,
					)
				}
				drawCircle(
					color = primary.copy(alpha = 0.16f),
					radius = size.minDimension * 0.25f,
					center = Offset(size.width * 0.72f, size.height * 0.30f),
				)
			}
		}
	}
}

@Composable
fun ReferenceRankThemeProgress(
	spec: ReferenceRankThemeVisualSpec,
	tokens: RankThemeTokens,
	progress: Float,
	modifier: Modifier = Modifier,
) {
	val fraction = progress.coerceIn(0f, 1f)
	val start = Color(tokens.progressStart.toInt())
	val end = Color(tokens.progressEnd.toInt())
	val track = Color(tokens.surfaceVariant.toInt())
	Canvas(modifier = modifier) {
		val radius = size.height / 2f
		drawRoundRect(
			color = track,
			cornerRadius = CornerRadius(radius),
		)
		if (fraction > 0f) {
			val endColor = when (spec.progressStyle) {
				ReferenceProgressStyle.SILVER_GRAPHITE -> end.copy(alpha = 0.86f)
				ReferenceProgressStyle.PINK_MAGENTA -> end
			}
			drawRoundRect(
				brush = Brush.horizontalGradient(listOf(start, endColor)),
				size = Size(size.width * fraction, size.height),
				cornerRadius = CornerRadius(radius),
			)
		}
	}
}
