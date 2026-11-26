package org.koitharu.kotatsu.settings.compose

import android.graphics.BitmapFactory
import androidx.annotation.DrawableRes
import androidx.compose.foundation.border
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.prefs.PrivateFavouritesThemePreset
import org.koitharu.kotatsu.core.prefs.VisualEffectLevel
import org.koitharu.kotatsu.core.ui.PrivateFavouritesVisualResolver
import org.koitharu.kotatsu.core.ui.PrivateFavouritesVisualSpec
import kotlin.math.roundToInt

/** A projection of the existing theme observer, never a second persisted theme owner. */
internal data class PrivateSettingsThemeSnapshot(
	val theme: PrivateFavouritesThemePreset,
	val modern: Boolean,
	val effects: VisualEffectLevel,
)

internal val LocalPrivateSettingsThemeSnapshot = compositionLocalOf<PrivateSettingsThemeSnapshot?> { null }

internal data class SanctuaryAssets(@DrawableRes val ornament: Int, @DrawableRes val landscape: Int)

internal fun sanctuaryAssets(theme: PrivateFavouritesThemePreset): SanctuaryAssets? = when (theme) {
	PrivateFavouritesThemePreset.FOLLOW_NORMAL -> null
	PrivateFavouritesThemePreset.MIYORARE -> SanctuaryAssets(R.drawable.private_sanctuary_ornament_miyorare, R.drawable.private_sanctuary_landscape_miyorare)
	PrivateFavouritesThemePreset.SAKURA -> SanctuaryAssets(R.drawable.private_sanctuary_ornament_sakura, R.drawable.private_sanctuary_landscape_sakura)
	PrivateFavouritesThemePreset.VIOLET -> SanctuaryAssets(R.drawable.private_sanctuary_ornament_violet, R.drawable.private_sanctuary_landscape_violet)
	PrivateFavouritesThemePreset.CYAN -> SanctuaryAssets(R.drawable.private_sanctuary_ornament_cyan, R.drawable.private_sanctuary_landscape_cyan)
	PrivateFavouritesThemePreset.EMERALD -> SanctuaryAssets(R.drawable.private_sanctuary_ornament_emerald, R.drawable.private_sanctuary_landscape_emerald)
	PrivateFavouritesThemePreset.AMBER -> SanctuaryAssets(R.drawable.private_sanctuary_ornament_amber, R.drawable.private_sanctuary_landscape_amber)
}

internal fun sanctuaryEnabled(snapshot: PrivateSettingsThemeSnapshot?, isPrivateSettings: Boolean): Boolean =
	isPrivateSettings && snapshot?.modern == true && sanctuaryAssets(snapshot.theme) != null

internal enum class SanctuaryRole { APPEARANCE, PROTECTION, STANDARD, ACCENTED_STANDARD, DESTRUCTIVE }

/** Only the actual Private Settings screen calls this. Two active-theme bitmaps are shared by its rows. */
@Composable
internal fun rememberPrivateSanctuary(snapshot: PrivateSettingsThemeSnapshot?): PrivateSanctuaryTreatment? {
	if (!sanctuaryEnabled(snapshot, isPrivateSettings = true)) return null
	val current = requireNotNull(snapshot)
	val spec = requireNotNull(PrivateFavouritesVisualResolver.resolve(current.theme))
	val assets = requireNotNull(sanctuaryAssets(current.theme))
	val resources = LocalContext.current.resources
	val ornament = remember(resources, assets.ornament) {
		runCatching { BitmapFactory.decodeResource(resources, assets.ornament)?.asImageBitmap() }.getOrNull()
	}
	val landscape = remember(resources, assets.landscape) {
		runCatching { BitmapFactory.decodeResource(resources, assets.landscape)?.asImageBitmap() }.getOrNull()
	}
	val fontScale = LocalDensity.current.fontScale
	val error = MaterialTheme.colorScheme.error
	return remember(spec, ornament, landscape, fontScale, current.effects, error) {
		PrivateSanctuaryTreatment(spec, ornament, landscape, fontScale, current.effects, error)
	}
}

internal class PrivateSanctuaryTreatment(
	private val spec: PrivateFavouritesVisualSpec,
	private val ornament: ImageBitmap?,
	private val landscape: ImageBitmap?,
	private val fontScale: Float,
	private val effects: VisualEffectLevel,
	private val error: Color,
) {
	private val primary = Color(spec.primary)
	private val base = Color(spec.surface)
	private val high = Color(spec.surfaceHigh)
	private val background = Color(spec.background)
	private val strength = when (effects) {
		VisualEffectLevel.LIGHT -> 0.55f
		VisualEffectLevel.BALANCED -> 0.85f
		VisualEffectLevel.FULL -> 1f
	}

	fun iconColors(role: SanctuaryRole): CategoryIconColors {
		val accent = if (role == SanctuaryRole.DESTRUCTIVE) error else primary
		return CategoryIconColors(lerp(base, accent, 0.16f), accent)
	}

	fun surface(role: SanctuaryRole): (Modifier, Shape, Float) -> Modifier = { modifier, shape, highlight ->
		val signature = role == SanctuaryRole.APPEARANCE || role == SanctuaryRole.PROTECTION
		val edge = if (role == SanctuaryRole.DESTRUCTIVE) error else primary
		val start = lerp(high, edge, (if (signature) 0.13f else 0.04f) * strength)
		val end = lerp(background, base, 0.55f)
		val selected = highlight.coerceIn(0f, 1f)
		val selectedSurface = lerp(high, primary, 0.23f)
		val fill = Brush.linearGradient(listOf(lerp(start, selectedSurface, selected), lerp(base, selectedSurface, selected * 0.65f), end))
		val outline = Brush.linearGradient(
			listOf(edge.copy(alpha = (if (signature) 0.86f else 0.62f) * strength),
				edge.copy(alpha = (0.24f + selected * 0.45f) * strength),
				edge.copy(alpha = (if (signature) 0.72f else 0.38f) * strength)),
		)
		modifier.clip(shape).drawWithCache {
			val rtl = layoutDirection == LayoutDirection.Rtl
			val decorative = size.width >= 300.dp.toPx() && fontScale < 1.3f
			val image = when (role) {
				SanctuaryRole.PROTECTION -> landscape
				SanctuaryRole.APPEARANCE, SanctuaryRole.ACCENTED_STANDARD -> ornament
				else -> null
			}
			val imageWidth = when (role) {
				SanctuaryRole.PROTECTION -> size.width * 0.60f
				SanctuaryRole.APPEARANCE -> (size.width * 0.28f).coerceAtMost(96.dp.toPx()).coerceAtMost(size.height)
				else -> 48.dp.toPx().coerceAtMost(size.height)
			}.roundToInt().coerceAtLeast(1)
			val imageHeight = if (role == SanctuaryRole.PROTECTION) size.height.roundToInt().coerceAtLeast(1)
				else imageWidth.coerceAtMost(size.height.roundToInt().coerceAtLeast(1))
			val x = if (rtl) 0 else (size.width.roundToInt() - imageWidth).coerceAtLeast(0)
			val y = if (role == SanctuaryRole.ACCENTED_STANDARD) (size.height.roundToInt() - imageHeight).coerceAtLeast(0) else 0
			val srcWidth = image?.let { (it.height * imageWidth.toFloat() / imageHeight).roundToInt().coerceIn(1, it.width) } ?: 1
			val srcHeight = image?.let { (srcWidth * imageHeight.toFloat() / imageWidth).roundToInt().coerceIn(1, it.height) } ?: 1
			val scrim = Brush.horizontalGradient(
				colors = if (rtl) listOf(base.copy(alpha = 0.45f), base.copy(alpha = 0.96f), base)
					else listOf(base, base.copy(alpha = 0.96f), base.copy(alpha = 0.45f)),
				startX = x.toFloat(), endX = x + imageWidth.toFloat(),
			)
			// Feather the artwork AND its readability scrim as one group. Masking only
			// the bitmap leaves an opaque scrim edge against the underlying gradient.
			val protectionBlend = if (role == SanctuaryRole.PROTECTION && decorative && image != null) {
				Brush.horizontalGradient(
					colors = if (rtl) listOf(Color.White, Color.White, Color.Transparent)
						else listOf(Color.Transparent, Color.White, Color.White),
					startX = x.toFloat(), endX = x + imageWidth.toFloat(),
				)
			} else null
			val protectionLayerBounds = protectionBlend?.let {
				Rect(x.toFloat(), 0f, x + imageWidth.toFloat(), size.height)
			}
			val protectionLayerPaint = protectionBlend?.let { Paint() }
			val glow = Brush.radialGradient(
				colors = listOf(edge.copy(alpha = if (signature) 0.13f * strength else 0.06f * strength), Color.Transparent),
				center = Offset(if (rtl) size.width else 0f, 0f),
				radius = (size.width * 0.55f).coerceAtLeast(1f),
			)
			onDrawWithContent {
				drawRect(fill)
				if (role != SanctuaryRole.DESTRUCTIVE) drawRect(glow)
				if (decorative && image != null) {
					val canvas = drawContext.canvas
					if (protectionLayerBounds != null && protectionLayerPaint != null) {
						canvas.saveLayer(protectionLayerBounds, protectionLayerPaint)
					}
					try {
						// Ornaments are authored in the top-left corner: mirror to the trailing edge.
						val ornamentRole = role != SanctuaryRole.PROTECTION
						scale(
							scaleX = if (ornamentRole && !rtl) -1f else 1f,
							scaleY = if (role == SanctuaryRole.ACCENTED_STANDARD) -1f else 1f,
							pivot = Offset(x + imageWidth / 2f, y + imageHeight / 2f),
						) {
							drawImage(
								image = image,
								srcOffset = IntOffset(if (role == SanctuaryRole.PROTECTION) image.width - srcWidth else 0, (image.height - srcHeight) / 2),
								srcSize = IntSize(srcWidth, srcHeight),
								dstOffset = IntOffset(x, y),
								dstSize = IntSize(imageWidth, imageHeight),
								alpha = (when (role) {
									SanctuaryRole.PROTECTION -> 0.90f
									SanctuaryRole.APPEARANCE -> 1f
									else -> 0.23f
								}) * strength,
							)
						}
						if (role == SanctuaryRole.PROTECTION) {
							drawRect(scrim, topLeft = Offset(x.toFloat(), 0f), size = androidx.compose.ui.geometry.Size(imageWidth.toFloat(), size.height))
						}
						if (protectionBlend != null) {
							drawRect(
								brush = protectionBlend,
								topLeft = Offset(x.toFloat(), 0f),
								size = androidx.compose.ui.geometry.Size(imageWidth.toFloat(), size.height),
								blendMode = BlendMode.DstIn,
							)
						}
					} finally {
						if (protectionLayerBounds != null && protectionLayerPaint != null) canvas.restore()
					}
				}
				drawContent()
			}
		}.border(1.dp, outline, shape)
	}
}
