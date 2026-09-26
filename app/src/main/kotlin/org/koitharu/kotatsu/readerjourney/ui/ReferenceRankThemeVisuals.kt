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
) {
	val primary = Color(tokens.primaryAccent.toInt())
	val secondary = Color(tokens.secondaryAccent.toInt())
	val border = Color(tokens.borderEmphasis.toInt())
	val mark = Color(tokens.onAccent.toInt()).copy(alpha = 0.86f)
	val signature = RankThemeSignatureRegistry.resolve(spec.themeId)
	val badgeGradient = signature?.badgeStops?.map { Color(it.toInt()) } ?: listOf(primary, secondary)

	Box(
		modifier = modifier.aspectRatio(1f),
		contentAlignment = Alignment.Center,
	) {
		Canvas(modifier = Modifier.fillMaxSize()) {
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

		val center = Offset(w * 0.5f, h * 0.5f)
		if (signature != null) {
			drawCircle(
				brush = Brush.radialGradient(
					listOf(
						Color(signature.badgeStops.first().toInt()).copy(alpha = .34f),
						Color(signature.badgeStops.last().toInt()).copy(alpha = .12f),
						Color.Transparent,
					),
					center = center,
					radius = min * .52f,
				),
				radius = min * .52f,
				center = center,
			)
		}
		drawCircle(
			brush = Brush.radialGradient(
				listOf(primary.copy(alpha = .30f), secondary.copy(alpha = .10f), Color.Transparent),
				center = center,
				radius = min * .50f,
			),
			radius = min * .49f,
			center = center,
		)
		drawCircle(
			color = Color(tokens.surface.toInt()).copy(alpha = .92f),
			radius = min * .43f,
			center = center,
		)
		drawCircle(
			color = secondary.copy(alpha = .34f),
			radius = min * .43f,
			center = center,
			style = Stroke(stroke * .55f),
		)
		drawCircle(
			color = primary.copy(alpha = .62f),
			radius = min * .47f,
			center = center,
			style = Stroke(stroke * .42f),
		)
		if (spec.badgeStyle in setOf(
				ReferenceBadgeStyle.CROWN_BOOK,
				ReferenceBadgeStyle.AURORA_PRISM_CREST,
				ReferenceBadgeStyle.CELESTIAL_PRISM_CROWN,
			)) {
			drawCircle(
				brush = Brush.sweepGradient(listOf(primary, secondary, mark.copy(alpha = .72f), primary)),
				radius = min * .485f,
				center = center,
				style = Stroke(stroke * .34f),
			)
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
				drawPath(path, Brush.linearGradient(badgeGradient))
				drawPath(path, border, style = Stroke(stroke))
				drawLine(mark.copy(alpha = .58f), Offset(w*.5f,h*.13f), Offset(w*.5f,h*.84f), min*.025f)
			}

			ReferenceBadgeStyle.STAR -> {
				val path = starPath()
				drawPath(path, Brush.linearGradient(badgeGradient))
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
				drawPath(path, Brush.linearGradient(badgeGradient))
				drawPath(path, border, style = Stroke(stroke))
				drawLine(mark.copy(alpha=.55f), Offset(w*.5f,h*.12f), Offset(w*.5f,h*.86f), min*.02f)
			}

			ReferenceBadgeStyle.ARCANE_STAR -> {
				val path = starPath(inner=.28f, outer=.43f)
				drawPath(path, Brush.linearGradient(badgeGradient))
				drawPath(path, border, style = Stroke(stroke))
				drawCircle(mark.copy(alpha=.28f), min*.19f, Offset(w*.5f,h*.5f), style = Stroke(min*.025f))
			}

			ReferenceBadgeStyle.ARCHIVE_SEAL -> {
				drawRoundRect(
					brush = Brush.linearGradient(badgeGradient),
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
				drawPath(crown, Brush.linearGradient(badgeGradient))
				drawPath(crown, border, style=Stroke(stroke))
				drawRoundRect(primary.copy(alpha=.85f), Offset(w*.20f,h*.62f), Size(w*.60f,h*.22f), CornerRadius(min*.05f))
				drawLine(mark, Offset(w*.50f,h*.63f), Offset(w*.50f,h*.82f), min*.025f)
			}


			ReferenceBadgeStyle.AURORA_PRISM_CREST -> {
				// Rank 90: crystal/prism crest, explicitly more collectible than the generic rune crown.
				val core = polygon(
					.50f to .08f, .64f to .25f, .86f to .21f, .75f to .46f,
					.90f to .62f, .64f to .66f, .50f to .92f, .36f to .66f,
					.10f to .62f, .25f to .46f, .14f to .21f, .36f to .25f,
				)
				drawPath(core, Brush.linearGradient(badgeGradient))
				drawPath(core, Color.White.copy(alpha=.72f), style=Stroke(stroke*.76f))
				val inner = polygon(.50f to .20f, .70f to .40f, .61f to .70f, .50f to .82f, .39f to .70f, .30f to .40f)
				drawPath(inner, Color(tokens.surface.toInt()).copy(alpha=.70f))
				drawPath(inner, Brush.linearGradient(badgeGradient), style=Stroke(stroke*.72f))
				// Aurora crystal wings.
				repeat(3) { i ->
					val dy = h * (.30f + i*.13f)
					val span = w * (.16f + i*.035f)
					drawLine(primary.copy(alpha=.88f-i*.12f), Offset(w*.30f,dy), Offset(w*.30f-span,dy+h*.07f), stroke*.78f)
					drawLine(secondary.copy(alpha=.88f-i*.12f), Offset(w*.70f,dy), Offset(w*.70f+span,dy+h*.07f), stroke*.78f)
				}
				listOf(
					Offset(w*.50f,h*.08f), Offset(w*.15f,h*.52f), Offset(w*.85f,h*.52f), Offset(w*.50f,h*.91f),
				).forEach { p ->
					val r=min*.045f
					val gem=polygon(
						(p.x/w) to ((p.y-r)/h), ((p.x+r*.65f)/w) to (p.y/h),
						(p.x/w) to ((p.y+r)/h), ((p.x-r*.65f)/w) to (p.y/h),
					)
					drawPath(gem, mark.copy(alpha=.82f))
				}
				drawCircle(
					brush=Brush.radialGradient(listOf(Color.White.copy(alpha=.36f), Color.Transparent),center),
					radius=min*.31f,
					center=center,
				)
			}

			ReferenceBadgeStyle.CELESTIAL_PRISM_CROWN -> {
				// Rank 100: ceremonial crystal crown + opal core + white/gold outline.
				val crown = polygon(
					.08f to .48f, .20f to .18f, .34f to .31f, .45f to .08f,
					.55f to .08f, .66f to .31f, .80f to .18f, .92f to .48f,
					.80f to .72f, .62f to .66f, .50f to .93f, .38f to .66f, .20f to .72f,
				)
				drawPath(crown, Brush.linearGradient(badgeGradient))
				drawPath(crown, Color(0xFFFFE29A).copy(alpha=.92f), style=Stroke(stroke*.92f))
				val opal = polygon(.50f to .17f, .70f to .38f, .63f to .70f, .50f to .84f, .37f to .70f, .30f to .38f)
				drawPath(opal, Color(tokens.surface.toInt()).copy(alpha=.62f))
				drawPath(opal, Brush.linearGradient(badgeGradient), style=Stroke(stroke*.72f))
				// Small prism rays.
				repeat(8) { i ->
					val a = -PI/2 + i*PI/4
					val innerR=min*.36f
					val outerR=if(i%2==0) min*.49f else min*.44f
					val p1=Offset(center.x+cos(a).toFloat()*innerR,center.y+sin(a).toFloat()*innerR)
					val p2=Offset(center.x+cos(a).toFloat()*outerR,center.y+sin(a).toFloat()*outerR)
					drawLine(if(i%2==0) Color(0xFFFFE29A) else mark, p1,p2,stroke*.46f)
				}
				drawCircle(
					brush=Brush.sweepGradient(badgeGradient + badgeGradient.first(), center),
					radius=min*.43f,
					center=center,
					style=Stroke(stroke*.45f),
				)
				drawCircle(Color.White.copy(alpha=.38f),min*.23f,center,style=Stroke(stroke*.30f))
			}
		}
		}

		if (spec.themeId.rank.minLevel >= 90) {
			Text(
				text = spec.themeId.rank.minLevel.toString(),
				style = MaterialTheme.typography.labelLarge,
				fontWeight = FontWeight.Black,
				color = if (spec.themeId.rank.minLevel >= 100) {
					Color(0xFFFFF2C2)
				} else {
					Color.White
				},
			)
		}
	}
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
	modifier: Modifier = Modifier,
	content: @Composable () -> Unit,
) {
	val primary = Color(tokens.primaryAccent.toInt())
	val secondary = Color(tokens.secondaryAccent.toInt())
	val surface = Color(tokens.surface.toInt())
	val mark = Color(tokens.onAccent.toInt())
	val signature = RankThemeSignatureRegistry.resolve(spec.themeId)
	val plateStops = signature?.borderStops?.map { Color(it.toInt()) }
		?: listOf(primary, secondary, mark.copy(alpha = .58f), primary)

	Box(modifier = modifier, contentAlignment = Alignment.Center) {
		Canvas(modifier = Modifier.fillMaxSize()) {
			val w=size.width
			val h=size.height
			val mid=h/2f
			val stroke=(h*.035f).coerceAtLeast(1f)
			fun body(notch:Float=.08f,tip:Float=.02f):Path=Path().apply{
				moveTo(w*notch,0f)
				lineTo(w*(1f-notch),0f)
				lineTo(w*(1f-tip),mid)
				lineTo(w*(1f-notch),h)
				lineTo(w*notch,h)
				lineTo(w*tip,mid)
				close()
			}
			fun gem(cx:Float,r:Float):Path=Path().apply{
				moveTo(cx,mid-r);lineTo(cx+r*.65f,mid);lineTo(cx,mid+r);lineTo(cx-r*.65f,mid);close()
			}
			val base=body()
			drawPath(base,Brush.horizontalGradient(listOf(surface.copy(alpha=.96f),primary.copy(alpha=.34f),surface.copy(alpha=.96f))))
			drawPath(base,Brush.horizontalGradient(plateStops),style=Stroke(if (signature != null) stroke * 1.18f else stroke))
			drawLine(Color.White.copy(alpha=.18f),Offset(w*.15f,h*.18f),Offset(w*.85f,h*.18f),stroke*.55f)

			when(spec.nameplateStyle){
				ReferenceNameplateStyle.NEWCOMER_CRYSTAL_CAPSULE -> {
					drawPath(gem(w*.08f,h*.18f),secondary.copy(alpha=.82f))
					drawPath(gem(w*.92f,h*.18f),primary.copy(alpha=.82f))
				}
				ReferenceNameplateStyle.READER_BOOKMARK -> {
					drawLine(primary.copy(alpha=.70f),Offset(w*.10f,h*.30f),Offset(w*.18f,h*.08f),stroke)
					drawLine(secondary.copy(alpha=.70f),Offset(w*.90f,h*.30f),Offset(w*.82f,h*.08f),stroke)
					drawLine(primary.copy(alpha=.32f),Offset(w*.18f,h*.72f),Offset(w*.82f,h*.72f),stroke*.55f)
				}
				ReferenceNameplateStyle.BOOKWORM_CODEX_TAB -> {
					repeat(3){i->
						val x=w*(.08f+i*.04f)
						drawRoundRect(primary.copy(alpha=.70f),Offset(x,h*.23f),Size(w*.018f,h*.54f),CornerRadius(h*.04f))
						val xr=w*(.92f-i*.04f)
						drawRoundRect(secondary.copy(alpha=.70f),Offset(xr-w*.018f,h*.23f),Size(w*.018f,h*.54f),CornerRadius(h*.04f))
					}
				}
				ReferenceNameplateStyle.EXPLORER_COMPASS_BANNER -> {
					drawLine(primary,Offset(w*.05f,mid),Offset(w*.16f,mid),stroke*1.4f)
					drawLine(secondary,Offset(w*.95f,mid),Offset(w*.84f,mid),stroke*1.4f)
					drawCircle(primary,h*.09f,Offset(w*.09f,mid),style=Stroke(stroke))
					drawCircle(secondary,h*.09f,Offset(w*.91f,mid),style=Stroke(stroke))
				}
				ReferenceNameplateStyle.COLLECTOR_GEM_PLAQUE -> {
					drawPath(gem(w*.08f,h*.23f),Brush.linearGradient(listOf(primary,secondary)))
					drawPath(gem(w*.92f,h*.23f),Brush.linearGradient(listOf(secondary,primary)))
					drawCircle(mark.copy(alpha=.45f),h*.045f,Offset(w*.14f,mid))
					drawCircle(mark.copy(alpha=.45f),h*.045f,Offset(w*.86f,mid))
				}
				ReferenceNameplateStyle.SCHOLAR_ARCANE_PLAQUE -> {
					drawCircle(primary.copy(alpha=.56f),h*.18f,Offset(w*.10f,mid),style=Stroke(stroke))
					drawCircle(secondary.copy(alpha=.56f),h*.18f,Offset(w*.90f,mid),style=Stroke(stroke))
					drawPath(gem(w*.10f,h*.08f),mark.copy(alpha=.65f))
					drawPath(gem(w*.90f,h*.08f),mark.copy(alpha=.65f))
				}
				ReferenceNameplateStyle.ARCHIVIST_NEON_ARCHIVE -> {
					repeat(3){i->
						val y=h*(.25f+i*.25f)
						drawLine(primary.copy(alpha=.78f),Offset(w*.035f,y),Offset(w*.13f,y),stroke)
						drawLine(secondary.copy(alpha=.78f),Offset(w*.965f,y),Offset(w*.87f,y),stroke)
					}
				}
				ReferenceNameplateStyle.BIBLIOPHILE_ROSE_BANNER -> {
					repeat(3){i->
						val y=mid+(i-1)*h*.10f
						drawCircle(primary.copy(alpha=.75f),h*.055f,Offset(w*.075f,y))
						drawCircle(secondary.copy(alpha=.75f),h*.055f,Offset(w*.925f,y))
					}
				}
				ReferenceNameplateStyle.VETERAN_EMBER_BANNER -> {
					val l=Path().apply{moveTo(w*.03f,mid);lineTo(w*.14f,h*.08f);lineTo(w*.11f,mid);lineTo(w*.14f,h*.92f);close()}
					val r=Path().apply{moveTo(w*.97f,mid);lineTo(w*.86f,h*.08f);lineTo(w*.89f,mid);lineTo(w*.86f,h*.92f);close()}
					drawPath(l,Brush.verticalGradient(listOf(secondary,primary)))
					drawPath(r,Brush.verticalGradient(listOf(secondary,primary)))
				}
				ReferenceNameplateStyle.MASTER_GOLDEN_MANUSCRIPT -> {
					drawPath(gem(w*.06f,h*.18f),mark.copy(alpha=.78f))
					drawPath(gem(w*.94f,h*.18f),mark.copy(alpha=.78f))
					drawLine(mark.copy(alpha=.42f),Offset(w*.18f,h*.84f),Offset(w*.82f,h*.84f),stroke*.70f)
				}
				ReferenceNameplateStyle.GRAND_AURORA_CEREMONIAL -> {
					drawLine(primary.copy(alpha=.76f),Offset(w*.04f,h*.20f),Offset(w*.17f,mid),stroke)
					drawLine(secondary.copy(alpha=.76f),Offset(w*.04f,h*.80f),Offset(w*.17f,mid),stroke)
					drawLine(secondary.copy(alpha=.76f),Offset(w*.96f,h*.20f),Offset(w*.83f,mid),stroke)
					drawLine(primary.copy(alpha=.76f),Offset(w*.96f,h*.80f),Offset(w*.83f,mid),stroke)
					drawCircle(mark.copy(alpha=.70f),h*.045f,Offset(w*.50f,h*.10f))
				}
				ReferenceNameplateStyle.LEGEND_PRISM_RELIC -> {
					val crown=Path().apply{
						moveTo(w*.42f,h*.02f);lineTo(w*.46f,h*.16f);lineTo(w*.50f,h*.04f);lineTo(w*.54f,h*.16f);lineTo(w*.58f,h*.02f)
					}
					drawPath(crown,mark.copy(alpha=.86f),style=Stroke(stroke*1.25f))
					drawPath(gem(w*.055f,h*.22f),Brush.linearGradient(listOf(primary,mark,secondary)))
					drawPath(gem(w*.945f,h*.22f),Brush.linearGradient(listOf(secondary,mark,primary)))
					drawLine(Color.White.copy(alpha=.36f),Offset(w*.17f,h*.82f),Offset(w*.83f,h*.82f),stroke)
				}
			}
		}
		Box(
			modifier=Modifier
				.fillMaxSize()
				.padding(horizontal=22.dp,vertical=8.dp),
			contentAlignment=Alignment.Center,
		){
			content()
		}
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
