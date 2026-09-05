package org.koitharu.kotatsu.settings.compose

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.koitharu.kotatsu.core.ui.LocalMiyorareVisualPalette
import org.koitharu.kotatsu.core.ui.MiyorareVisualTokens

/**
 * Per-position corner radius. Classic keeps its connected-card geometry. Modern intentionally uses
 * independent rounded cards, matching the Miyorare reference language and avoiding one oversized
 * flat settings slab when a group contains many rows.
 */
fun groupItemShape(index: Int, total: Int, modern: Boolean = false): Shape {
	if (modern) {
		return RoundedCornerShape(MiyorareVisualTokens.RADIUS_CARD_DP.dp)
	}
	val outer = 24.dp
	val inner = 4.dp
	return when {
		total <= 1 -> RoundedCornerShape(outer)
		index == 0 -> RoundedCornerShape(topStart = outer, topEnd = outer, bottomStart = inner, bottomEnd = inner)
		index == total - 1 -> RoundedCornerShape(topStart = inner, topEnd = inner, bottomStart = outer, bottomEnd = outer)
		else -> RoundedCornerShape(inner)
	}
}

data class GroupItemPosition(val index: Int, val total: Int, val modern: Boolean = false) {
	val shape: Shape get() = groupItemShape(index, total, modern)
}

class SettingsGroupScope {
	internal val items = mutableListOf<@Composable (GroupItemPosition) -> Unit>()
	fun item(content: @Composable (GroupItemPosition) -> Unit) {
		items += content
	}
}

/**
 * Visual container for a stack of settings rows. Modern separates cards with the shared compact
 * spacing token so borders and gradients read as deliberate surfaces; Classic preserves its
 * original connected 2dp seam.
 */
@Composable
fun SettingsGroup(
	modifier: Modifier = Modifier,
	title: String? = null,
	content: SettingsGroupScope.() -> Unit,
) {
	val palette = LocalMiyorareVisualPalette.current
	val modern = palette.isModern
	val scope = SettingsGroupScope()
	scope.content()
	Column(modifier = modifier) {
		if (title != null) {
			Text(
				text = if (modern) title else title.uppercase(),
				style = MaterialTheme.typography.labelMedium,
				fontWeight = FontWeight.SemiBold,
				color = if (modern) {
					MaterialTheme.colorScheme.primary.copy(alpha = 0.92f)
				} else {
					MaterialTheme.colorScheme.primary
				},
				modifier = Modifier.padding(
					start = 12.dp,
					top = if (modern) MiyorareVisualTokens.SPACING_L_DP.dp else 12.dp,
					bottom = MiyorareVisualTokens.SPACING_S_DP.dp,
				),
			)
		}
		val total = scope.items.size
		scope.items.forEachIndexed { i, render ->
			render(GroupItemPosition(index = i, total = total, modern = modern))
			if (i < total - 1) {
				Spacer(
					Modifier.height(
						if (modern) MiyorareVisualTokens.SPACING_S_DP.dp else 2.dp,
					),
				)
			}
		}
	}
}
