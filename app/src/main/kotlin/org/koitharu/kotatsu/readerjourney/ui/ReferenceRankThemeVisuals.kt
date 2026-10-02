package org.koitharu.kotatsu.readerjourney.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.koitharu.kotatsu.readerjourney.theme.RankThemeTokens
import org.koitharu.kotatsu.readerjourney.theme.RankThemeSignatureRegistry
import org.koitharu.kotatsu.readerjourney.theme.ReferenceBadgeStyle
import org.koitharu.kotatsu.readerjourney.theme.ReferenceCardStyle
import org.koitharu.kotatsu.readerjourney.theme.ReferenceFrameStyle
import org.koitharu.kotatsu.readerjourney.theme.ReferenceNameplateStyle
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
	state: BadgeState = BadgeState.UNLOCKED,
	animate: Boolean = false,
	qualityMode: BadgeQualityMode = BadgeQualityMode.NORMAL,
	useThumbnail: Boolean = false,
	profileMode: Boolean = false,
) {
	ExclusiveBadge(
		spec = spec,
		tokens = tokens,
		modifier = modifier,
		state = state,
		animate = animate,
		qualityMode = qualityMode,
		useThumbnail = useThumbnail,
		profileMode = profileMode,
	)
}

@Composable
fun ReferenceRankThemeFrame(
	spec: ReferenceRankThemeVisualSpec,
	tokens: RankThemeTokens,
	modifier: Modifier = Modifier,
	levelText: String? = null,
	state: ProfileFrameState = ProfileFrameState.UNLOCKED,
	animate: Boolean = false,
	qualityMode: ProfileFrameQualityMode = ProfileFrameQualityMode.NORMAL,
	content: @Composable () -> Unit,
) {
	ExclusiveProfileFrame(
		spec = spec,
		tokens = tokens,
		modifier = modifier,
		levelText = levelText,
		state = state,
		animate = animate,
		qualityMode = qualityMode,
		content = content,
	)
}

@Composable
fun ReferenceRankThemeNameplate(
	spec: ReferenceRankThemeVisualSpec,
	tokens: RankThemeTokens,
	title: String,
	modifier: Modifier = Modifier,
	state: NameplateState = NameplateState.UNLOCKED,
	animate: Boolean = false,
	qualityMode: NameplateQualityMode = NameplateQualityMode.NORMAL,
	usage: NameplateUsage = NameplateUsage.PROFILE,
	pressed: Boolean = false,
) {
	// Rank text is authored directly into the nameplate artwork. Keep the title parameter for
	// source/API compatibility and accessibility call sites, but do not render runtime text over
	// the transparent center: doing so would duplicate or obscure the baked rank title.
	ExclusiveNameplate(
		spec = spec,
		tokens = tokens,
		modifier = modifier,
		state = state,
		animate = animate,
		qualityMode = qualityMode,
		usage = usage,
		pressed = pressed,
	) {}
}

@Composable
fun ReferenceRankThemeNameplate(
	spec: ReferenceRankThemeVisualSpec,
	tokens: RankThemeTokens,
	modifier: Modifier = Modifier,
	state: NameplateState = NameplateState.UNLOCKED,
	animate: Boolean = false,
	qualityMode: NameplateQualityMode = NameplateQualityMode.NORMAL,
	usage: NameplateUsage = NameplateUsage.PROFILE,
	pressed: Boolean = false,
	content: @Composable () -> Unit,
) {
	ExclusiveNameplate(
		spec = spec,
		tokens = tokens,
		modifier = modifier,
		state = state,
		animate = animate,
		qualityMode = qualityMode,
		usage = usage,
		pressed = pressed,
		content = content,
	)
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
	val signature = RankThemeSignatureRegistry.resolve(spec.themeId)
	val highRank = spec.cardStyle in setOf(
		ReferenceCardStyle.GOLDEN_MANUSCRIPT_GLASS,
		ReferenceCardStyle.AURORA_LIBRARY_GLASS,
		ReferenceCardStyle.ETERNAL_LIBRARY_GLASS,
	)
	val shape = RoundedCornerShape(if (highRank) 26.dp else 20.dp)
	val base = Brush.linearGradient(
		listOf(
			surface,
			container,
			primary.copy(alpha = if (highRank) .20f else .12f),
			secondary.copy(alpha = if (highRank) .14f else .08f),
		),
	)
	val edge = when {
		signature != null -> Brush.horizontalGradient(signature.borderStops.map { Color(it.toInt()) })
		highRank -> Brush.linearGradient(listOf(primary, secondary, Color.White.copy(alpha = .30f), primary))
		else -> Brush.linearGradient(listOf(border, primary.copy(alpha = .60f), border))
	}
	Box(
		modifier = modifier
			.clip(shape)
			.background(base)
			.border(BorderStroke(if (highRank) 1.5.dp else 1.dp, edge), shape),
	) {
		Box(
			modifier = Modifier
				.fillMaxSize()
				.background(
					Brush.verticalGradient(
						listOf(
							secondary.copy(alpha = if (highRank) .10f else .05f),
							Color.Transparent,
							primary.copy(alpha = if (highRank) .08f else .035f),
						),
					),
				),
		)
		Box(
			modifier = Modifier
				.align(Alignment.TopCenter)
				.fillMaxWidth()
				.height(if (highRank) 2.dp else 1.dp)
				.background(
					Brush.horizontalGradient(
						listOf(Color.Transparent, secondary.copy(alpha = .72f), primary.copy(alpha = .76f), Color.Transparent),
					),
				),
		)
		Box(modifier = Modifier.padding(if (highRank) 14.dp else 12.dp)) {
			content()
		}
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

	val signature = RankThemeSignatureRegistry.resolve(spec.themeId)

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
		drawCircle(
			brush = Brush.radialGradient(
				listOf(primary.copy(alpha = .24f), primary.copy(alpha = .06f), Color.Transparent),
				center = Offset(w * .82f, h * .16f),
				radius = min * .58f,
			),
			radius = min * .58f,
			center = Offset(w * .82f, h * .16f),
		)
		drawCircle(
			brush = Brush.radialGradient(
				listOf(secondary.copy(alpha = .15f), Color.Transparent),
				center = Offset(w * .18f, h * .78f),
				radius = min * .46f,
			),
			radius = min * .46f,
			center = Offset(w * .18f, h * .78f),
		)
		drawRect(
			brush = Brush.verticalGradient(
				listOf(Color.Transparent, background.copy(alpha = .28f), background.copy(alpha = .58f)),
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

		if (signature != null) {
			val aurora = signature.backgroundAuroraStops.map { Color(it.toInt()) }
			drawRect(
				brush = Brush.linearGradient(
					aurora.mapIndexed { index, color ->
						color.copy(alpha = if (index == 0) .06f else if (spec.themeId.rank.minLevel >= 100) .09f else .13f)
					},
					start = Offset(0f, h * .10f),
					end = Offset(w, h * .90f),
				),
			)
			repeat(signature.staticStarCount) { i ->
				val x = w * (.07f + ((i * 37) % 86) / 100f)
				val y = h * (.08f + ((i * 53) % 84) / 100f)
				val star = aurora[(i + 1) % aurora.size]
				drawCircle(
					star.copy(alpha = if (spec.themeId.rank.minLevel >= 100) .32f else .24f),
					min * (.006f + (i % 3) * .003f),
					Offset(x, y),
				)
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
	val border = Color(tokens.borderSubtle.toInt())
	val signature = RankThemeSignatureRegistry.resolve(spec.themeId)
	val exclusiveTier = spec.progressStyle in setOf(
		ReferenceProgressStyle.DARK_GOLD_CHAMPAGNE,
		ReferenceProgressStyle.AURORA_PRISM,
		ReferenceProgressStyle.CELESTIAL_PRISM,
	)
	Canvas(modifier = modifier) {
		val radius = size.height / 2f
		drawRoundRect(track.copy(alpha = .88f), cornerRadius = CornerRadius(radius))
		drawRoundRect(
			color = border.copy(alpha = .72f),
			cornerRadius = CornerRadius(radius),
			style = Stroke(size.height * .08f),
		)
		if (fraction > 0f) {
			val colors = if (signature != null) {
				signature.selectedStops.map { Color(it.toInt()) }
			} else when (spec.progressStyle) {
				ReferenceProgressStyle.SILVER_GRAPHITE -> listOf(start, end.copy(alpha = .86f))
				ReferenceProgressStyle.DARK_GOLD_CHAMPAGNE -> listOf(start, end, Color.White.copy(alpha = .42f), end)
				ReferenceProgressStyle.AURORA_PRISM,
				ReferenceProgressStyle.CELESTIAL_PRISM -> listOf(start, end, start.copy(alpha = .78f))
				else -> listOf(start, end)
			}
			val fillWidth = size.width * fraction
			drawRoundRect(
				brush = Brush.horizontalGradient(colors),
				size = Size(fillWidth, size.height),
				cornerRadius = CornerRadius(radius),
			)
			if (exclusiveTier && fillWidth > size.height) {
				drawLine(
					color = Color.White.copy(alpha = .20f),
					start = Offset(size.height * .45f, size.height * .27f),
					end = Offset((fillWidth - size.height * .45f).coerceAtLeast(size.height * .45f), size.height * .27f),
					strokeWidth = (size.height * .10f).coerceAtLeast(1f),
				)
			}
		}
	}
}
