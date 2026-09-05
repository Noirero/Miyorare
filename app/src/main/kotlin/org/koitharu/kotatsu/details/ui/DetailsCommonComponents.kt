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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.ui.LocalMiyorareVisualPalette
import org.koitharu.kotatsu.core.ui.MiyorareVisualTokens

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
		.padding(horizontal = SCREEN_PADDING, vertical = 8.dp)
	Surface(
		shape = shape,
		color = if (palette.isModern) {
			MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.97f)
		} else {
			MaterialTheme.colorScheme.surfaceContainerHigh
		},
		border = if (palette.isModern) {
			BorderStroke(
				1.dp,
				palette.borderHighlight.copy(alpha = palette.borderHighlight.alpha * 0.34f),
			)
		} else {
			null
		},
		tonalElevation = if (palette.isModern) 0.dp else 0.dp,
		modifier = if (onClick != null) base.clickable(onClick = onClick) else base,
	) {
		Column(modifier = Modifier.padding(20.dp), content = content)
	}
}

@Composable
internal fun SectionHeader(title: String, action: String, accent: Color, onAction: () -> Unit) {
	val palette = LocalMiyorareVisualPalette.current
	Spacer(Modifier.height(8.dp))
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
			color = if (palette.isModern) {
				MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.86f)
			} else {
				accent.copy(alpha = 0.14f)
			},
			border = if (palette.isModern) {
				BorderStroke(
					1.dp,
					palette.borderHighlight.copy(alpha = palette.borderHighlight.alpha * 0.34f),
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
				color = if (palette.isModern) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.90f) else accent,
				modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
			)
		}
	}
	Spacer(Modifier.height(12.dp))
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
	val container = if (palette.isModern) {
		if (highlighted) {
			palette.selectedSurface.copy(alpha = 0.76f)
		} else {
			MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.88f)
		}
	} else if (highlighted) {
		accent.copy(alpha = 0.20f)
	} else {
		MaterialTheme.colorScheme.surfaceContainerHigh
	}
	val content = if (palette.isModern) {
		if (highlighted) palette.primary else MaterialTheme.colorScheme.onSurfaceVariant
	} else if (highlighted) {
		accent
	} else {
		MaterialTheme.colorScheme.onSurfaceVariant
	}
	Surface(
		shape = RoundedCornerShape(50),
		color = container,
		border = if (palette.isModern) {
			BorderStroke(
				1.dp,
				palette.borderHighlight.copy(
					alpha = palette.borderHighlight.alpha * if (highlighted) 0.74f else 0.30f,
				),
			)
		} else {
			null
		},
		modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
	) {
		Row(
			modifier = Modifier.padding(horizontal = 13.dp, vertical = 8.dp),
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
