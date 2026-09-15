package org.koitharu.kotatsu.details.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.ui.LocalMiyorareVisualPalette

@Composable
internal fun DiscoveryControlsCard(
	relatedVisible: Boolean,
	genreVisible: Boolean,
	accent: Color,
	onRelatedToggle: () -> Unit,
	onGenreToggle: () -> Unit,
) {
	val palette = LocalMiyorareVisualPalette.current
	Spacer(Modifier.height(if (palette.isModern) 18.dp else 14.dp))
	SectionCard {
		Row(verticalAlignment = Alignment.CenterVertically) {
			Icon(
				painter = painterResource(R.drawable.ic_star_small),
				contentDescription = null,
				tint = accent,
				modifier = Modifier.size(24.dp),
			)
			Spacer(Modifier.width(10.dp))
			Column(modifier = Modifier.weight(1f)) {
				Text(
					text = stringResource(R.string.discover_more),
					style = MaterialTheme.typography.titleMedium,
					fontWeight = FontWeight.SemiBold,
					color = MaterialTheme.colorScheme.onSurface,
				)
				Spacer(Modifier.height(2.dp))
				Text(
					text = stringResource(R.string.discover_more_summary),
					style = MaterialTheme.typography.bodyMedium,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			}
		}

		Spacer(Modifier.height(if (palette.isModern) 14.dp else 16.dp))
		DiscoveryControlRow(
			icon = R.drawable.ic_search,
			title = stringResource(R.string.similar_titles),
			summary = stringResource(R.string.similar_titles_summary),
			showDescription = stringResource(R.string.show_related_title_suggestions),
			hideDescription = stringResource(R.string.hide_related_title_suggestions),
			isVisible = relatedVisible,
			accent = accent,
			onToggle = onRelatedToggle,
		)
		Spacer(Modifier.height(8.dp))
		DiscoveryControlRow(
			icon = R.drawable.ic_tag,
			title = stringResource(R.string.similar_genres),
			summary = stringResource(R.string.similar_genres_summary),
			showDescription = stringResource(R.string.show_genre_recommendations),
			hideDescription = stringResource(R.string.hide_genre_recommendations),
			isVisible = genreVisible,
			accent = accent,
			onToggle = onGenreToggle,
		)
	}
}

@Composable
private fun DiscoveryControlRow(
	@DrawableRes icon: Int,
	title: String,
	summary: String,
	showDescription: String,
	hideDescription: String,
	isVisible: Boolean,
	accent: Color,
	onToggle: () -> Unit,
) {
	val palette = LocalMiyorareVisualPalette.current
	val container = if (palette.isModern) {
		MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.76f)
	} else {
		MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.58f)
	}
	Surface(
		shape = RoundedCornerShape(if (palette.isModern) 16.dp else 18.dp),
		color = container,
		tonalElevation = 0.dp,
	) {
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.padding(start = 14.dp, top = 8.dp, end = 6.dp, bottom = 8.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			Icon(
				painter = painterResource(icon),
				contentDescription = null,
				tint = accent,
				modifier = Modifier.size(24.dp),
			)
			Spacer(Modifier.width(12.dp))
			Column(modifier = Modifier.weight(1f)) {
				Text(
					text = title,
					style = MaterialTheme.typography.titleSmall,
					fontWeight = FontWeight.SemiBold,
					color = MaterialTheme.colorScheme.onSurface,
				)
				Spacer(Modifier.height(2.dp))
				Text(
					text = summary,
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			}
			Spacer(Modifier.width(6.dp))
			IconButton(onClick = onToggle) {
				Icon(
					painter = painterResource(
						if (isVisible) R.drawable.ic_visibility else R.drawable.ic_visibility_off,
					),
					contentDescription = if (isVisible) hideDescription else showDescription,
					tint = if (isVisible) accent else MaterialTheme.colorScheme.onSurfaceVariant,
				)
			}
		}
	}
}
