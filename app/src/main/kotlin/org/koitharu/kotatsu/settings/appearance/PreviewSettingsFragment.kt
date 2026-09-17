package org.koitharu.kotatsu.settings.appearance

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.DetailsUiMode
import org.koitharu.kotatsu.settings.compose.BaseComposeSettingsFragment
import org.koitharu.kotatsu.settings.compose.MiyorareTheme
import org.koitharu.kotatsu.settings.compose.ListSettingsItem
import org.koitharu.kotatsu.settings.compose.SettingsGroup
import org.koitharu.kotatsu.settings.compose.SettingsScaffold
import org.koitharu.kotatsu.settings.compose.SliderSettingsItem
import org.koitharu.kotatsu.settings.compose.SwitchSettingsItem
import org.koitharu.kotatsu.settings.compose.rememberBooleanPref
import org.koitharu.kotatsu.settings.compose.rememberDetailsBackdropBlurPref
import org.koitharu.kotatsu.settings.compose.rememberStringPref

@AndroidEntryPoint
class PreviewSettingsFragment : BaseComposeSettingsFragment(R.string.details_appearance) {

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?,
	): View = ComposeView(requireContext()).apply {
		setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
		setContent {
			MiyorareTheme {
				DetailsAppearanceScreen()
			}
		}
	}
}

@Composable
private fun DetailsAppearanceScreen() {
	val ctx = LocalContext.current
	// Compact is listed first (and is the default for new installs), then Centralized.
	val uiModeEntries = remember {
		listOf(ctx.getString(R.string.details_ui_compact), ctx.getString(R.string.details_ui_expressive))
	}
	val uiModeValues = remember { listOf(DetailsUiMode.COMPACT.name, DetailsUiMode.EXPRESSIVE.name) }

	var uiMode by rememberStringPref(AppSettings.KEY_DETAILS_UI, DetailsUiMode.COMPACT.name)
	var backdrop by rememberBooleanPref(AppSettings.KEY_DETAILS_BACKDROP, true)
	var backdropBlurAmount by rememberDetailsBackdropBlurPref(AppSettings.KEY_DETAILS_BACKDROP_BLUR_AMOUNT, 2)

	val mode = remember(uiMode) {
		DetailsUiMode.entries.firstOrNull { it.name == uiMode } ?: DetailsUiMode.COMPACT
	}

	SettingsScaffold {
		item {
			DetailsStylePreview(centered = mode != DetailsUiMode.COMPACT, backdropEnabled = backdrop)
		}
		item { Spacer(Modifier.height(8.dp).fillMaxWidth()) }
		item {
			SettingsGroup(title = stringResource(R.string.details_ui)) {
				item { pos ->
					ListSettingsItem(
						title = stringResource(R.string.details_ui),
						entries = uiModeEntries,
						entryValues = uiModeValues,
						selectedValue = uiMode,
						onValueChange = { uiMode = it },
						icon = R.drawable.ic_appearance,
						shape = pos.shape,
					)
				}
				item { pos ->
					SwitchSettingsItem(
						title = stringResource(R.string.details_backdrop),
						subtitle = stringResource(R.string.details_backdrop_summary),
						checked = backdrop,
						onCheckedChange = { backdrop = it },
						icon = R.drawable.ic_images,
						shape = pos.shape,
					)
				}
				item { pos ->
					SliderSettingsItem(
						title = stringResource(R.string.details_backdrop_blur),
						value = backdropBlurAmount,
						valueFrom = 0,
						valueTo = 2,
						stepSize = 1,
						onValueChange = { backdropBlurAmount = it },
						icon = R.drawable.ic_images,
						shape = pos.shape,
						enabled = backdrop,
						valueLabel = { valVal ->
							when (valVal) {
								0 -> ctx.getString(R.string.details_backdrop_blur_none)
								1 -> ctx.getString(R.string.details_backdrop_blur_light)
								else -> ctx.getString(R.string.details_backdrop_blur_default)
							}
						}
					)
				}
			}
		}
		item { Spacer(Modifier.height(24.dp).fillMaxWidth()) }
	}
}

@Composable
private fun DetailsStylePreview(centered: Boolean, backdropEnabled: Boolean) {
	val scheme = MaterialTheme.colorScheme
	Surface(
		modifier = Modifier
			.fillMaxWidth()
			.padding(horizontal = 16.dp)
			.height(280.dp),
		shape = RoundedCornerShape(20.dp),
		color = scheme.surface,
		border = BorderStroke(1.dp, scheme.outlineVariant),
	) {
		Box(Modifier.fillMaxSize()) {
			// Backdrop gradient covers the full preview
			if (backdropEnabled) {
				Box(
					Modifier
						.fillMaxWidth()
						.fillMaxHeight()
						.background(
							Brush.verticalGradient(
								listOf(
									scheme.primary.copy(alpha = 0.38f),
									scheme.tertiary.copy(alpha = 0.18f),
									Color.Transparent,
								),
							),
						),
				)
			}
			// Thin status-bar strip
			Box(
				Modifier
					.fillMaxWidth()
					.height(5.dp)
					.background(scheme.outlineVariant.copy(alpha = 0.25f)),
			)
			if (centered) PreviewCentered(scheme) else PreviewCompact(scheme)
		}
	}
}

@Composable
private fun PreviewCentered(scheme: ColorScheme) {
	Column(
		modifier = Modifier
			.fillMaxSize()
			.padding(top = 14.dp, start = 14.dp, end = 14.dp, bottom = 10.dp),
		horizontalAlignment = Alignment.CenterHorizontally,
	) {
		PreviewCover(scheme, 68.dp, 98.dp)
		Spacer(Modifier.height(8.dp))
		PreviewBar(148.dp, 11.dp, scheme.onSurface.copy(alpha = 0.9f))
		Spacer(Modifier.height(5.dp))
		PreviewBar(96.dp, 7.dp, scheme.onSurfaceVariant.copy(alpha = 0.6f))
		Spacer(Modifier.height(10.dp))
		Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
			PreviewPill(44.dp, scheme)
			PreviewPill(32.dp, scheme)
			PreviewPill(50.dp, scheme)
		}
		Spacer(Modifier.height(10.dp))
		Box(
			Modifier
				.fillMaxWidth()
				.height(24.dp)
				.clip(RoundedCornerShape(12.dp))
				.background(scheme.primaryContainer.copy(alpha = 0.75f)),
		)
		Spacer(Modifier.height(8.dp))
		Box(
			Modifier
				.fillMaxWidth()
				.height(6.dp)
				.clip(RoundedCornerShape(50))
				.background(scheme.onSurfaceVariant.copy(alpha = 0.25f)),
		)
		Spacer(Modifier.height(5.dp))
		Box(
			Modifier
				.fillMaxWidth(0.8f)
				.height(6.dp)
				.clip(RoundedCornerShape(50))
				.background(scheme.onSurfaceVariant.copy(alpha = 0.18f)),
		)
		Spacer(Modifier.height(5.dp))
		Box(
			Modifier
				.fillMaxWidth(0.55f)
				.height(6.dp)
				.clip(RoundedCornerShape(50))
				.background(scheme.onSurfaceVariant.copy(alpha = 0.12f)),
		)
	}
}

@Composable
private fun PreviewCompact(scheme: ColorScheme) {
	Column(
		modifier = Modifier
			.fillMaxSize()
			.padding(top = 14.dp, start = 14.dp, end = 14.dp, bottom = 10.dp),
	) {
		Row {
			PreviewCover(scheme, 76.dp, 110.dp)
			Spacer(Modifier.width(12.dp))
			Column(modifier = Modifier.weight(1f).padding(top = 4.dp)) {
				PreviewBar(140.dp, 11.dp, scheme.onSurface.copy(alpha = 0.9f))
				Spacer(Modifier.height(5.dp))
				PreviewBar(104.dp, 7.dp, scheme.onSurface.copy(alpha = 0.9f))
				Spacer(Modifier.height(6.dp))
				PreviewBar(96.dp, 7.dp, scheme.onSurfaceVariant.copy(alpha = 0.6f))
				Spacer(Modifier.height(5.dp))
				PreviewBar(80.dp, 7.dp, scheme.onSurfaceVariant.copy(alpha = 0.45f))
				Spacer(Modifier.height(12.dp))
				Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
					PreviewPill(40.dp, scheme)
					PreviewPill(30.dp, scheme)
					PreviewPill(36.dp, scheme)
				}
			}
		}
		Spacer(Modifier.height(10.dp))
		Box(
			Modifier
				.fillMaxWidth()
				.height(24.dp)
				.clip(RoundedCornerShape(12.dp))
				.background(scheme.primaryContainer.copy(alpha = 0.75f)),
		)
		Spacer(Modifier.height(8.dp))
		Box(
			Modifier
				.fillMaxWidth()
				.height(6.dp)
				.clip(RoundedCornerShape(50))
				.background(scheme.onSurfaceVariant.copy(alpha = 0.25f)),
		)
		Spacer(Modifier.height(5.dp))
		Box(
			Modifier
				.fillMaxWidth(0.8f)
				.height(6.dp)
				.clip(RoundedCornerShape(50))
				.background(scheme.onSurfaceVariant.copy(alpha = 0.18f)),
		)
	}
}

@Composable
private fun PreviewCover(scheme: ColorScheme, width: Dp, height: Dp) {
	Box(
		Modifier
			.size(width, height)
			.clip(RoundedCornerShape(10.dp))
			.background(scheme.surfaceVariant),
	)
}

@Composable
private fun PreviewBar(width: Dp, height: Dp, color: Color) {
	Box(
		Modifier
			.size(width, height)
			.clip(RoundedCornerShape(50))
			.background(color),
	)
}

@Composable
private fun PreviewPill(width: Dp, scheme: ColorScheme) {
	Box(
		Modifier
			.size(width, 18.dp)
			.clip(RoundedCornerShape(50))
			.background(scheme.secondaryContainer),
	)
}
