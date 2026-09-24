package org.koitharu.kotatsu.readerjourney.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import org.koitharu.kotatsu.readerjourney.theme.ReferenceCardStyle
import org.koitharu.kotatsu.readerjourney.theme.ReferenceFrameStyle
import org.koitharu.kotatsu.readerjourney.theme.ReferenceProgressStyle
import org.koitharu.kotatsu.readerjourney.theme.ReferenceRankThemeVisualSpec
import org.koitharu.kotatsu.readerjourney.theme.ReferenceWallpaperStyle
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * One static/local-first visual renderer for all 12 rank themes.
 *
 * No bitmap decode, network fetch, shader loop or persistent animation is required. These renderers
 * are presentation-only and never read/write progression.
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
	val mark = Color(tokens.onAccent.toInt()).copy(alpha = 0.86f)

	Canvas(modifier = modifier.aspectRatio(1f)) {
		val w = size.width
		val h = size.height
		val min = size.minDimension
		val stroke = min * 0.045f

		fun polygon(vararg points: Pair<Float, Float>): Path = Path().apply {
			points.forEachIndexed { index, point ->
				val p = Offset(w * point.first, h * point.second)
				if (index == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
			}
			close()
		}

		fun starPath(inner: Float = 0.22f, outer: Float = 0.43f): Path = Path().apply {
			repeat(10) { index ->
				val radius = if (index % 2 == 0) outer else inner
				val angle = -PI / 2.0 + index * PI / 5.0
				val x = w * 0.5f + cos(angle).toFloat() * min * radius
				val y = h * 0.5f + sin(angle).toFloat() * min * radius
				if (index == 0) moveTo(x, y) else lineTo(x, y)
			}
			close()
		}

		when (spec.badgeStyle) {
			ReferenceBadgeStyle.CRYSTAL -> {
				val path = polygon(.50f to .08f, .86f to .42f, .50f to .92f, .14f to .42f)
				drawPath(path, Brush.linearGradient(listOf(primary, secondary)))
				drawPath(path, border, style = Stroke(stroke))
				drawLine(mark.copy(alpha = .58f), Offset(w*.5f,h*.13f), Offset(w*.5f,h*.84f), min*.025f)
			}

			ReferenceBadgeStyle.STAR -> {
				val path = starPath()
				drawPath(path, Brush.linearGradient(listOf(primary, secondary)))
				drawPath(path, border, style = Stroke(stroke))
			}

			ReferenceBadgeStyle.OPEN_BOOK -> {
				val left = polygon(.10f to .28f, .48f to .20f, .48f to .80f, .12f to .72f)
				val right = polygon(.52f to .20f, .90f to .28f, .88f to .72f, .52f to .80f)
				drawPath(left, primary)
				drawPath(right, secondary)
				drawPath(left, border, style = Stroke(stroke))
				drawPath(right, border, style = Stroke(stroke))
				drawLine(mark, Offset(w*.5f,h*.22f), Offset(w*.5f,h*.80f), min*.025f)
			}

			ReferenceBadgeStyle.COMPASS -> {
				drawCircle(Brush.radialGradient(listOf(primary, secondary)), min*.38f, Offset(w*.5f,h*.5f))
				drawCircle(border, min*.38f, Offset(w*.5f,h*.5f), style = Stroke(stroke))
				val needle = polygon(.50f to .12f, .61f to .50f, .50f to .88f, .39f to .50f)
				drawPath(needle, mark)
				drawCircle(primary, min*.07f, Offset(w*.5f,h*.5f))
			}

			ReferenceBadgeStyle.GEM -> {
				val path = polygon(.50f to .08f, .82f to .30f, .72f to .72f, .50f to .92f, .28f to .72f, .18f to .30f)
				drawPath(path, Brush.linearGradient(listOf(primary, secondary)))
				drawPath(path, border, style = Stroke(stroke))
				drawLine(mark.copy(alpha=.55f), Offset(w*.5f,h*.12f), Offset(w*.5f,h*.86f), min*.02f)
			}

			ReferenceBadgeStyle.ARCANE_STAR -> {
				val path = starPath(inner=.28f, outer=.43f)
				drawPath(path, Brush.linearGradient(listOf(primary, secondary)))
				drawPath(path, border, style = Stroke(stroke))
				drawCircle(mark.copy(alpha=.28f), min*.19f, Offset(w*.5f,h*.5f), style = Stroke(min*.025f))
			}

			ReferenceBadgeStyle.ARCHIVE_SEAL -> {
				drawRoundRect(
					brush = Brush.linearGradient(listOf(primary, secondary)),
					topLeft = Offset(w*.12f,h*.18f),
					size = Size(w*.76f,h*.66f),
					cornerRadius = CornerRadius(min*.16f),
				)
				drawRoundRect(
					color = border,
					topLeft = Offset(w*.12f,h*.18f),
					size = Size(w*.76f,h*.66f),
					cornerRadius = CornerRadius(min*.16f),
					style = Stroke(stroke),
				)
				repeat(3) { index ->
					val y = h * (.37f + index * .13f)
					drawLine(mark, Offset(w*.30f,y), Offset(w*.70f,y), min*.035f)
				}
			}

			ReferenceBadgeStyle.ROSE_BOOK -> {
				val book = polygon(.14f to .32f, .48f to .24f, .48f to .80f, .14f to .72f)
				val book2 = polygon(.52f to .24f, .86f to .32f, .86f to .72f, .52f to .80f)
				drawPath(book, primary); drawPath(book2, secondary)
				drawPath(book, border, style=Stroke(stroke)); drawPath(book2, border, style=Stroke(stroke))
				repeat(5) { i ->
					val angle = i * (2f * Math.PI.toFloat()/5f)
					drawCircle(
						mark.copy(alpha=.72f),
						min*.085f,
						Offset(
							w*.50f + cos(angle.toDouble()).toFloat()*min*.11f,
							h*.43f + sin(angle.toDouble()).toFloat()*min*.11f,
						),
					)
				}
				drawCircle(primary, min*.055f, Offset(w*.5f,h*.43f))
			}

			ReferenceBadgeStyle.FLAME_WING -> {
				val flame = polygon(.50f to .08f, .68f to .38f, .58f to .82f, .50f to .92f, .42f to .82f, .32f to .38f)
				drawPath(flame, Brush.verticalGradient(listOf(secondary, primary)))
				drawPath(flame, border, style=Stroke(stroke))
				repeat(3) { i ->
					val dy = h * (.36f + i*.13f)
					drawLine(mark.copy(alpha=.65f), Offset(w*.10f,dy), Offset(w*.34f,dy+h*.05f), min*.026f)
					drawLine(mark.copy(alpha=.65f), Offset(w*.90f,dy), Offset(w*.66f,dy+h*.05f), min*.026f)
				}
			}

			ReferenceBadgeStyle.CROWN_BOOK -> {
				val crown = polygon(.16f to .34f, .30f to .14f, .45f to .34f, .58f to .12f, .72f to .34f, .86f to .16f, .82f to .55f, .18f to .55f)
				drawPath(crown, Brush.linearGradient(listOf(primary, secondary)))
				drawPath(crown, border, style=Stroke(stroke))
				drawRoundRect(primary.copy(alpha=.85f), Offset(w*.20f,h*.62f), Size(w*.60f,h*.22f), CornerRadius(min*.05f))
				drawLine(mark, Offset(w*.50f,h*.63f), Offset(w*.50f,h*.82f), min*.025f)
			}

			ReferenceBadgeStyle.CROWN_RUNE -> {
				val crown = polygon(.12f to .40f, .28f to .12f, .44f to .38f, .58f to .10f, .72f to .38f, .88f to .14f, .82f to .62f, .18f to .62f)
				drawPath(crown, Brush.linearGradient(listOf(primary, secondary)))
				drawPath(crown, border, style=Stroke(stroke))
				drawLine(mark, Offset(w*.38f,h*.32f), Offset(w*.62f,h*.68f), min*.025f)
				drawLine(mark, Offset(w*.62f,h*.32f), Offset(w*.38f,h*.68f), min*.025f)
				drawCircle(mark.copy(alpha=.6f), min*.06f, Offset(w*.5f,h*.50f))
			}

			ReferenceBadgeStyle.PRISM_CROWN -> {
				val crown = polygon(.10f to .42f, .26f to .10f, .42f to .34f, .54f to .08f, .68f to .34f, .86f to .12f, .82f to .66f, .18f to .66f)
				drawPath(crown, Brush.linearGradient(listOf(primary, secondary, mark.copy(alpha=.7f), primary)))
				drawPath(crown, border, style=Stroke(stroke))
				drawCircle(mark.copy(alpha=.42f), min*.16f, Offset(w*.5f,h*.48f), style=Stroke(min*.025f))
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
	val (radius, width) = when (spec.frameStyle) {
		ReferenceFrameStyle.SIMPLE_GRAPHITE,
		ReferenceFrameStyle.SIMPLE_BLUE -> 18.dp to 1.dp
		ReferenceFrameStyle.CYAN_EDGE,
		ReferenceFrameStyle.EMERALD_EDGE,
		ReferenceFrameStyle.VIOLET_EDGE,
		ReferenceFrameStyle.ARCANE_EDGE,
		ReferenceFrameStyle.NEON_MAGENTA_EDGE,
		ReferenceFrameStyle.CRIMSON_EDGE,
		ReferenceFrameStyle.EMBER_EDGE -> 22.dp to 2.dp
		ReferenceFrameStyle.CHAMPAGNE_EDGE,
		ReferenceFrameStyle.AURORA_EDGE,
		ReferenceFrameStyle.PRISM_EDGE -> 26.dp to 2.dp
	}
	val shape = RoundedCornerShape(radius)
	Box(
		modifier = modifier
			.border(BorderStroke(width, Brush.linearGradient(listOf(primary, secondary))), shape)
			.padding(width),
	) {
		content()
	}
}

@Composable
fun ReferenceRankThemeCard(
	spec: ReferenceRankThemeVisualSpec,
	tokens: RankThemeTokens,
	modifier: Modifier = Modifier,
	content: @Composable () -> Unit,
) {
	val primary = Color(tokens.primaryAccent.toInt())
	val secondary = Color(tokens.secondaryAccent.toInt())
	val surface = Color(tokens.surface.toInt())
	val container = Color(tokens.container.toInt())
	val border = Color(tokens.borderSubtle.toInt())
	val highRank = spec.cardStyle in setOf(
		ReferenceCardStyle.GOLDEN_MANUSCRIPT_GLASS,
		ReferenceCardStyle.AURORA_LIBRARY_GLASS,
		ReferenceCardStyle.ETERNAL_LIBRARY_GLASS,
	)
	val shape = RoundedCornerShape(if (highRank) 24.dp else 18.dp)
	val background = Brush.linearGradient(
		listOf(
			surface,
			container,
			primary.copy(alpha = if (highRank) .18f else .10f),
			secondary.copy(alpha = if (highRank) .12f else .07f),
		),
	)
	Box(
		modifier = modifier
			.clip(shape)
			.background(background)
			.border(
				BorderStroke(if (highRank) 2.dp else 1.dp, if (highRank) primary else border),
				shape,
			)
			.padding(12.dp),
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
		val w = size.width
		val h = size.height
		val min = size.minDimension
		drawRect(
			brush = Brush.linearGradient(
				colors = listOf(background, surface, primary.copy(alpha = .16f)),
				start = Offset.Zero,
				end = Offset(w, h),
			),
		)

		fun shelves(lines: Int, alpha: Float = .22f) {
			repeat(lines) { i ->
				val y = h * (.18f + i * (.64f / (lines.coerceAtLeast(2)-1)))
				drawLine(subtle.copy(alpha=alpha), Offset(w*.08f,y), Offset(w*.92f,y), min*.008f)
			}
		}

		fun stars(count: Int) {
			repeat(count) { i ->
				val x = w * (.10f + ((i * 37) % 80) / 100f)
				val y = h * (.10f + ((i * 53) % 78) / 100f)
				drawCircle(secondary.copy(alpha=.30f + (i%3)*.08f), min*(.010f + (i%2)*.006f), Offset(x,y))
			}
		}

		when (spec.wallpaperStyle) {
			ReferenceWallpaperStyle.MINIMAL_PAGES -> {
				repeat(3) { index ->
					val x = w * (.08f + index * .27f)
					val y = h * (.18f + (index % 2) * .16f)
					drawRoundRect(
						subtle.copy(alpha=.48f),
						Offset(x,y),
						Size(w*.30f,h*.42f),
						CornerRadius(min*.035f),
						style=Stroke(min*.008f),
					)
				}
			}

			ReferenceWallpaperStyle.NIGHT_LIBRARY_BLUE -> {
				shelves(4, .30f)
				repeat(7) { i ->
					val x = w * (.10f + i*.12f)
					drawLine(primary.copy(alpha=.18f), Offset(x,h*.18f), Offset(x,h*.82f), min*.006f)
				}
				drawCircle(primary.copy(alpha=.18f), min*.28f, Offset(w*.78f,h*.22f))
			}

			ReferenceWallpaperStyle.DIGITAL_CODEX -> {
				val left = Offset(w*.14f,h*.18f)
				drawRoundRect(subtle.copy(alpha=.28f), left, Size(w*.32f,h*.62f), CornerRadius(min*.04f), style=Stroke(min*.008f))
				drawRoundRect(subtle.copy(alpha=.28f), Offset(w*.54f,h*.18f), Size(w*.32f,h*.62f), CornerRadius(min*.04f), style=Stroke(min*.008f))
				repeat(5) { i ->
					val y=h*(.30f+i*.09f)
					drawLine(primary.copy(alpha=.28f),Offset(w*.20f,y),Offset(w*.40f,y),min*.006f)
					drawLine(secondary.copy(alpha=.25f),Offset(w*.60f,y),Offset(w*.80f,y),min*.006f)
				}
			}

			ReferenceWallpaperStyle.MAP_LABYRINTH -> {
				val points=listOf(
					Offset(w*.10f,h*.74f),Offset(w*.22f,h*.52f),Offset(w*.36f,h*.60f),
					Offset(w*.48f,h*.34f),Offset(w*.62f,h*.44f),Offset(w*.78f,h*.22f),Offset(w*.90f,h*.34f)
				)
				points.zipWithNext().forEach { (a,b) -> drawLine(primary.copy(alpha=.32f),a,b,min*.010f) }
				points.forEach { drawCircle(secondary.copy(alpha=.38f),min*.025f,it) }
			}

			ReferenceWallpaperStyle.VIOLET_VAULT -> {
				shelves(4,.26f)
				repeat(4) { i ->
					val cx=w*(.20f+i*.20f)
					val gem=Path().apply{
						moveTo(cx,h*.28f);lineTo(cx+min*.07f,h*.38f);lineTo(cx,h*.48f);lineTo(cx-min*.07f,h*.38f);close()
					}
					drawPath(gem, primary.copy(alpha=.24f))
				}
			}

			ReferenceWallpaperStyle.ARCANE_MANUSCRIPT -> {
				shelves(3,.18f)
				drawCircle(primary.copy(alpha=.20f),min*.26f,Offset(w*.68f,h*.42f),style=Stroke(min*.008f))
				drawCircle(secondary.copy(alpha=.18f),min*.16f,Offset(w*.68f,h*.42f),style=Stroke(min*.006f))
				repeat(5){i->drawLine(subtle.copy(alpha=.24f),Offset(w*.12f,h*(.28f+i*.09f)),Offset(w*.44f,h*(.28f+i*.09f)),min*.006f)}
			}

			ReferenceWallpaperStyle.DIGITAL_NIGHT_ARCHIVE -> {
				repeat(5){i->val y=h*(i+1)/6f;drawLine(secondary.copy(alpha=.18f),Offset(w*.08f,y),Offset(w*.92f,y),min*.006f)}
				repeat(4){i->val x=w*(i+1)/5f;drawLine(secondary.copy(alpha=.18f),Offset(x,h*.12f),Offset(x,h*.88f),min*.005f)}
				drawCircle(primary.copy(alpha=.16f),min*.25f,Offset(w*.72f,h*.30f))
			}

			ReferenceWallpaperStyle.CLASSIC_CRIMSON_LIBRARY -> {
				shelves(5,.25f)
				repeat(4){i->
					val x=w*(.14f+i*.22f)
					drawRoundRect(primary.copy(alpha=.15f),Offset(x,h*.20f),Size(w*.13f,h*.58f),CornerRadius(min*.05f),style=Stroke(min*.007f))
				}
			}

			ReferenceWallpaperStyle.WARM_EMBER_LIBRARY -> {
				shelves(4,.22f)
				repeat(8){i->
					val x=w*(.12f+((i*29)%76)/100f)
					val y=h*(.18f+((i*41)%68)/100f)
					drawCircle(secondary.copy(alpha=.22f+(i%3)*.06f),min*(.014f+(i%2)*.008f),Offset(x,y))
				}
			}

			ReferenceWallpaperStyle.GOLDEN_MANUSCRIPT_LIBRARY -> {
				shelves(3,.20f)
				repeat(3){i->
					val x=w*(.16f+i*.28f)
					drawRoundRect(primary.copy(alpha=.20f),Offset(x,h*.18f),Size(w*.20f,h*.60f),CornerRadius(min*.04f),style=Stroke(min*.010f))
				}
				drawLine(secondary.copy(alpha=.34f),Offset(w*.18f,h*.30f),Offset(w*.82f,h*.30f),min*.006f)
			}

			ReferenceWallpaperStyle.AURORA_COSMIC_ARCHIVE -> {
				stars(14)
				drawCircle(primary.copy(alpha=.12f),min*.36f,Offset(w*.25f,h*.26f))
				drawCircle(secondary.copy(alpha=.10f),min*.44f,Offset(w*.78f,h*.68f))
				shelves(2,.14f)
			}

			ReferenceWallpaperStyle.ETERNAL_COSMIC_LIBRARY -> {
				stars(18)
				shelves(4,.13f)
				drawCircle(primary.copy(alpha=.12f),min*.30f,Offset(w*.50f,h*.42f),style=Stroke(min*.010f))
				drawCircle(secondary.copy(alpha=.16f),min*.22f,Offset(w*.50f,h*.42f),style=Stroke(min*.006f))
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
		drawRoundRect(track, cornerRadius = CornerRadius(radius))
		if (fraction > 0f) {
			val colors = when (spec.progressStyle) {
				ReferenceProgressStyle.SILVER_GRAPHITE -> listOf(start, end.copy(alpha=.86f))
				ReferenceProgressStyle.VIOLET_GOLD,
				ReferenceProgressStyle.SUBTLE_PRISM -> listOf(start, end, start.copy(alpha=.78f))
				else -> listOf(start, end)
			}
			drawRoundRect(
				brush = Brush.horizontalGradient(colors),
				size = Size(size.width * fraction, size.height),
				cornerRadius = CornerRadius(radius),
			)
		}
	}
}
