package org.koitharu.kotatsu.details.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.prefs.VisualEffectLevel
import org.koitharu.kotatsu.core.ui.LocalMiyorareVisualPalette
import org.koitharu.kotatsu.core.ui.MiyorareVisualTokens
import org.koitharu.kotatsu.core.ui.detailsBorderBrush

internal val SCREEN_PADDING = 20.dp
internal val CARD_CORNER = 26.dp
internal const val TAGS_COLLAPSED_ROWS = 3
internal val DETAIL_DOCK_RESERVE = 128.dp

internal fun Color.luminanceIsLight(): Boolean =
	(0.299f * red + 0.587f * green + 0.114f * blue) > 0.5f

@Composable
internal fun SectionCard(
	onClick: (() -> Unit)? = null,
	content: @Composable ColumnScope.() -> Unit,
) {
	val palette = LocalMiyorareVisualPalette.current
	val shape = if (palette.isModern) {
		RoundedCornerShape(MiyorareVisualTokens.RADIUS_SURFACE_DP.dp)
	} else {
		RoundedCornerShape(CARD_CORNER)
	}
	val base = Modifier
		.fillMaxWidth()
		.padding(
			horizontal = SCREEN_PADDING,
			vertical = if (palette.isModern) 4.dp else 8.dp,
		)
	val adaptiveCustom = palette.isModern && palette.adaptiveCustomBackground
	val lightSurface = MaterialTheme.colorScheme.background.luminanceIsLight()
	val modernCardColor = if (adaptiveCustom) {
		val neutralBase = if (lightSurface) {
			lerp(MaterialTheme.colorScheme.surfaceContainerHigh, Color.White, 0.10f)
		} else {
			lerp(MaterialTheme.colorScheme.surfaceContainerHigh, Color.Black, 0.10f)
		}
		val adaptiveTarget = if (lightSurface) {
			lerp(palette.primary, palette.accent, 0.30f)
		} else {
			lerp(palette.secondary, palette.accent, 0.24f)
		}
		val mix = when (palette.effectLevel) {
			VisualEffectLevel.LIGHT -> if (lightSurface) 0.06f else 0.05f
			VisualEffectLevel.BALANCED -> if (lightSurface) 0.09f else 0.08f
			VisualEffectLevel.FULL -> if (lightSurface) 0.13f else 0.12f
		}
		lerp(neutralBase, adaptiveTarget, mix).copy(
			alpha = when (palette.effectLevel) {
				VisualEffectLevel.LIGHT -> if (lightSurface) 0.78f else 0.76f
				VisualEffectLevel.BALANCED -> if (lightSurface) 0.82f else 0.81f
				VisualEffectLevel.FULL -> 0.86f
			},
		)
	} else if (palette.isModern) {
		when (palette.effectLevel) {
			VisualEffectLevel.LIGHT ->
				lerp(MaterialTheme.colorScheme.surfaceContainerHigh, palette.secondary, 0.010f).copy(alpha = 0.72f)
			VisualEffectLevel.BALANCED ->
				lerp(MaterialTheme.colorScheme.surfaceContainerHigh, palette.secondary, 0.025f).copy(alpha = 0.79f)
			VisualEffectLevel.FULL ->
				lerp(MaterialTheme.colorScheme.surfaceContainerHigh, palette.secondary, 0.050f).copy(alpha = 0.84f)
		}
	} else {
		MaterialTheme.colorScheme.surfaceContainerHigh
	}
	val modernBorderColor = if (adaptiveCustom) {
		val adaptiveBorder = if (lightSurface) {
			lerp(palette.primary, palette.accent, 0.34f)
		} else {
			lerp(palette.primary, palette.secondary, 0.46f)
		}
		adaptiveBorder.copy(
			alpha = when (palette.effectLevel) {
				VisualEffectLevel.LIGHT -> if (lightSurface) 0.26f else 0.30f
				VisualEffectLevel.BALANCED -> if (lightSurface) 0.42f else 0.46f
				VisualEffectLevel.FULL -> if (lightSurface) 0.62f else 0.68f
			},
		)
	} else if (palette.isModern) {
		when (palette.effectLevel) {
			VisualEffectLevel.LIGHT -> palette.borderHighlight.copy(alpha = 0.14f)
			VisualEffectLevel.BALANCED -> palette.borderHighlight.copy(alpha = 0.24f)
			VisualEffectLevel.FULL -> lerp(palette.primary, palette.secondary, 0.55f).copy(alpha = 0.42f)
		}
	} else {
		Color.Transparent
	}
	Surface(
		shape = shape,
		color = modernCardColor,
		border = if (palette.isModern) {
			BorderStroke(
				if (palette.effectLevel == VisualEffectLevel.FULL) 1.dp else 0.75.dp,
				palette.detailsBorderBrush(fallback = modernBorderColor),
			)
		} else {
			null
		},
		tonalElevation = 0.dp,
		modifier = if (onClick != null) base.clickable(onClick = onClick) else base,
	) {
		Column(
			modifier = Modifier.padding(if (palette.isModern) 12.dp else 20.dp),
			content = content,
		)
	}
}

@Composable
internal fun SectionHeader(title: String, action: String, accent: Color, onAction: () -> Unit) {
	val palette = LocalMiyorareVisualPalette.current
	Spacer(Modifier.height(if (palette.isModern) 6.dp else 8.dp))
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.padding(horizontal = SCREEN_PADDING),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Text(
			text = title,
			style = MaterialTheme.typography.titleMedium,
			fontWeight = FontWeight.SemiBold,
			color = MaterialTheme.colorScheme.onSurface,
			modifier = Modifier.weight(1f),
		)
		Surface(
			shape = RoundedCornerShape(50),
			color = if (palette.isModern && palette.adaptiveCustomBackground) {
				val light = MaterialTheme.colorScheme.background.luminanceIsLight()
				val target = if (light) palette.primary else palette.secondary
				lerp(
					MaterialTheme.colorScheme.surfaceContainer,
					target,
					when (palette.effectLevel) {
						VisualEffectLevel.LIGHT -> 0.05f
						VisualEffectLevel.BALANCED -> 0.08f
						VisualEffectLevel.FULL -> 0.12f
					},
				).copy(
					alpha = when (palette.effectLevel) {
						VisualEffectLevel.LIGHT -> 0.80f
						VisualEffectLevel.BALANCED -> 0.86f
						VisualEffectLevel.FULL -> 0.92f
					},
				)
			} else if (palette.isModern) {
				when (palette.effectLevel) {
					VisualEffectLevel.LIGHT ->
						MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.82f)
					VisualEffectLevel.BALANCED ->
						MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.86f)
					VisualEffectLevel.FULL ->
						lerp(MaterialTheme.colorScheme.surfaceContainer, palette.secondary, 0.07f).copy(alpha = 0.92f)
				}
			} else {
				accent.copy(alpha = 0.14f)
			},
			border = if (palette.isModern) {
				BorderStroke(
					if (palette.effectLevel == VisualEffectLevel.FULL) 1.dp else 0.75.dp,
					palette.detailsBorderBrush(
						fallback = palette.borderHighlight,
						alpha = when (palette.effectLevel) {
							VisualEffectLevel.LIGHT -> 0.16f
							VisualEffectLevel.BALANCED -> 0.30f
							VisualEffectLevel.FULL -> 0.52f
						},
					),
				)
			} else {
				null
			},
			onClick = onAction,
		) {
			Text(
				text = action,
				style = MaterialTheme.typography.labelMedium,
				fontWeight = FontWeight.Medium,
				color = if (palette.isModern) {
					when (palette.effectLevel) {
						VisualEffectLevel.LIGHT -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.90f)
						VisualEffectLevel.BALANCED -> palette.primary
						VisualEffectLevel.FULL -> lerp(palette.primary, palette.secondary, 0.22f)
					}
				} else {
					accent
				},
				modifier = Modifier.padding(
					horizontal = if (palette.isModern) 12.dp else 14.dp,
					vertical = if (palette.isModern) 6.dp else 7.dp,
				),
			)
		}
	}
	Spacer(Modifier.height(if (palette.isModern) 10.dp else 12.dp))
}

@Composable
internal fun Pill(
	text: String,
	accent: Color,
	highlighted: Boolean = false,
	onClick: (() -> Unit)? = null,
	leading: (@Composable () -> Unit)? = null,
) {
	val palette = LocalMiyorareVisualPalette.current
	val container = if (palette.isModern && palette.adaptiveCustomBackground) {
		val light = MaterialTheme.colorScheme.background.luminanceIsLight()
		val target = if (highlighted) {
			if (light) lerp(palette.primary, palette.accent, 0.24f) else lerp(palette.primary, palette.secondary, 0.38f)
		} else {
			if (light) palette.secondary else palette.primary
		}
		lerp(
			MaterialTheme.colorScheme.surfaceContainer,
			target,
			if (highlighted) {
				when (palette.effectLevel) {
					VisualEffectLevel.LIGHT -> 0.06f
					VisualEffectLevel.BALANCED -> 0.10f
					VisualEffectLevel.FULL -> 0.15f
				}
			} else {
				when (palette.effectLevel) {
					VisualEffectLevel.LIGHT -> 0.03f
					VisualEffectLevel.BALANCED -> 0.05f
					VisualEffectLevel.FULL -> 0.08f
				}
			},
		).copy(alpha = if (light) 0.82f else 0.78f)
	} else if (palette.isModern) {
		if (highlighted) {
			lerp(
				MaterialTheme.colorScheme.surfaceContainer,
				accent,
				if (palette.effectLevel == VisualEffectLevel.FULL) 0.08f else 0.04f,
			).copy(
				alpha = when (palette.effectLevel) {
					VisualEffectLevel.LIGHT -> 0.68f
					VisualEffectLevel.BALANCED -> 0.73f
					VisualEffectLevel.FULL -> 0.79f
				},
			)
		} else {
			MaterialTheme.colorScheme.surfaceContainer.copy(
				alpha = when (palette.effectLevel) {
					VisualEffectLevel.LIGHT -> 0.62f
					VisualEffectLevel.BALANCED -> 0.69f
					VisualEffectLevel.FULL -> 0.78f
				},
			)
		}
	} else if (highlighted) {
		accent.copy(alpha = 0.20f)
	} else {
		MaterialTheme.colorScheme.surfaceContainerHigh
	}
	val content = if (palette.isModern) {
		if (highlighted) {
			if (palette.effectLevel == VisualEffectLevel.LIGHT) palette.primary else accent
		} else {
			MaterialTheme.colorScheme.onSurfaceVariant
		}
	} else if (highlighted) {
		accent
	} else {
		MaterialTheme.colorScheme.onSurfaceVariant
	}
	val shape = if (palette.isModern) {
		RoundedCornerShape(MiyorareVisualTokens.RADIUS_CONTROL_DP.dp)
	} else {
		RoundedCornerShape(50)
	}
	Surface(
		shape = shape,
		color = container,
		border = if (palette.isModern) {
			BorderStroke(
				0.75.dp,
				palette.detailsBorderBrush(
					fallback = if (highlighted) accent else palette.borderHighlight,
					alpha = if (highlighted) {
						when (palette.effectLevel) {
							VisualEffectLevel.LIGHT -> 0.28f
							VisualEffectLevel.BALANCED -> 0.44f
							VisualEffectLevel.FULL -> 0.66f
						}
					} else {
						when (palette.effectLevel) {
							VisualEffectLevel.LIGHT -> 0.12f
							VisualEffectLevel.BALANCED -> 0.22f
							VisualEffectLevel.FULL -> 0.36f
						}
					},
				),
			)
		} else {
			null
		},
		modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
	) {
		Row(
			modifier = Modifier.padding(
				horizontal = if (palette.isModern) 12.dp else 13.dp,
				vertical = if (palette.isModern) 7.dp else 8.dp,
			),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(6.dp),
		) {
			leading?.invoke()
			Text(
				text = text,
				style = MaterialTheme.typography.labelMedium,
				fontWeight = FontWeight.Medium,
				color = content,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)
		}
	}
}

@Composable
internal fun LoadingHero() {
	Box(
		modifier = Modifier
			.fillMaxWidth()
			.height(240.dp),
		contentAlignment = Alignment.Center,
	) {
		Text(
			text = stringResource(R.string.loading_),
			style = MaterialTheme.typography.titleMedium,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)
	}
}
