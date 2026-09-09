package org.koitharu.kotatsu.settings.about

import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import eu.kanade.tachiyomi.util.system.WebViewUtil
import eu.kanade.tachiyomi.util.system.setDefaultSettings
import org.koitharu.kotatsu.BuildConfig
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.settings.compose.BaseComposeSettingsFragment
import org.koitharu.kotatsu.settings.compose.DropSauceTheme

@AndroidEntryPoint
class WhatsNewFragment : BaseComposeSettingsFragment(R.string.whats_new_title) {

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?,
	): View = ComposeView(requireContext()).apply {
		setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
		setContent {
			DropSauceTheme {
				WhatsNewScreen(
					appVersion = BuildConfig.VERSION_NAME,
					onOpenPreview = ::openPreview,
				)
			}
		}
	}

	private fun openPreview(url: String) {
		val videoId = Uri.parse(url).lastPathSegment?.takeIf { it.isNotBlank() }
		if (videoId == null) {
			openExternalPreview(url)
			return
		}
		val context = requireContext()
		val appOrigin = "https://${context.packageName.lowercase()}"
		val embedUrl = buildString {
			append("https://www.youtube.com/embed/")
			append(videoId)
			append("?playsinline=1&rel=0&origin=")
			append(Uri.encode(appOrigin))
			append("&widget_referrer=")
			append(Uri.encode(appOrigin))
		}
		val webView = WebView(context).apply {
			setDefaultSettings()
			setBackgroundColor(Color.BLACK)
			settings.userAgentString = WebViewUtil.getInferredUserAgent(context)
			settings.mediaPlaybackRequiresUserGesture = true
			settings.loadsImagesAutomatically = true
			settings.allowFileAccess = false
			settings.allowContentAccess = false
			settings.javaScriptCanOpenWindowsAutomatically = false
			webChromeClient = WebChromeClient()
			webViewClient = WebViewClient()
			overScrollMode = View.OVER_SCROLL_NEVER
			setLayerType(View.LAYER_TYPE_HARDWARE, null)
		}
		webView.layoutParams = ViewGroup.LayoutParams(
			ViewGroup.LayoutParams.MATCH_PARENT,
			(resources.displayMetrics.heightPixels * 0.62f).toInt(),
		)
		val dialog = MaterialAlertDialogBuilder(context)
			.setTitle(R.string.whats_new_preview_title)
			.setView(webView)
			.setPositiveButton(R.string.whats_new_open_youtube) { _, _ ->
				openExternalPreview(url)
			}
			.setNegativeButton(R.string.close, null)
			.create()
		dialog.setOnShowListener {
			webView.loadUrl(
				embedUrl,
				mapOf("Referer" to "$appOrigin/"),
			)
		}
		dialog.setOnDismissListener {
			webView.stopLoading()
			webView.loadUrl("about:blank")
			webView.destroy()
		}
		dialog.show()
	}

	private fun openExternalPreview(url: String) {
		if (!router.openExternalBrowser(url, getString(R.string.whats_new_preview))) {
			view?.let {
				Snackbar.make(it, R.string.operation_not_supported, Snackbar.LENGTH_SHORT).show()
			}
		}
	}

	companion object {
		const val EXTRA_OPEN_WHATS_NEW = "miyorare_open_whats_new"
		const val CONTENT_ID = "miyorare_a_new_chapter_3"
	}
}

private data class WhatsNewFeature(
	val thumbnailUrl: String,
	@StringRes val badge: Int,
	@StringRes val title: Int,
	@StringRes val tagline: Int,
	@StringRes val description: Int,
	@StringRes val location: Int,
	@StringRes val usage: Int,
	val previewUrl: String,
)

private val FEATURES = listOf(
	WhatsNewFeature(
		thumbnailUrl = "https://i.ytimg.com/vi/ac1_ixH33Nc/hqdefault.jpg",
		badge = R.string.whats_new_badge_new,
		title = R.string.whats_new_private_favourites_title,
		tagline = R.string.whats_new_private_favourites_tagline,
		description = R.string.whats_new_private_favourites_description,
		location = R.string.whats_new_private_favourites_location,
		usage = R.string.whats_new_private_favourites_usage,
		previewUrl = "https://youtube.com/shorts/ac1_ixH33Nc",
	),
	WhatsNewFeature(
		thumbnailUrl = "https://i.ytimg.com/vi/6LfijRQvjoM/hqdefault.jpg",
		badge = R.string.whats_new_badge_new,
		title = R.string.whats_new_library_groups_title,
		tagline = R.string.whats_new_library_groups_tagline,
		description = R.string.whats_new_library_groups_description,
		location = R.string.whats_new_library_groups_location,
		usage = R.string.whats_new_library_groups_usage,
		previewUrl = "https://youtube.com/shorts/6LfijRQvjoM",
	),
	WhatsNewFeature(
		thumbnailUrl = "https://i.ytimg.com/vi/0t49YM5OjUs/hqdefault.jpg",
		badge = R.string.whats_new_badge_new,
		title = R.string.whats_new_favourites_themes_title,
		tagline = R.string.whats_new_favourites_themes_tagline,
		description = R.string.whats_new_favourites_themes_description,
		location = R.string.whats_new_favourites_themes_location,
		usage = R.string.whats_new_favourites_themes_usage,
		previewUrl = "https://youtube.com/shorts/0t49YM5OjUs",
	),
	WhatsNewFeature(
		thumbnailUrl = "https://i.ytimg.com/vi/LspCcHb455Y/hqdefault.jpg",
		badge = R.string.whats_new_badge_new,
		title = R.string.whats_new_private_themes_title,
		tagline = R.string.whats_new_private_themes_tagline,
		description = R.string.whats_new_private_themes_description,
		location = R.string.whats_new_private_themes_location,
		usage = R.string.whats_new_private_themes_usage,
		previewUrl = "https://youtube.com/shorts/LspCcHb455Y",
	),
	WhatsNewFeature(
		thumbnailUrl = "https://i.ytimg.com/vi/2qQ-2oQ88-c/hqdefault.jpg",
		badge = R.string.whats_new_badge_improved,
		title = R.string.whats_new_miyorare_modern_title,
		tagline = R.string.whats_new_miyorare_modern_tagline,
		description = R.string.whats_new_miyorare_modern_description,
		location = R.string.whats_new_miyorare_modern_location,
		usage = R.string.whats_new_miyorare_modern_usage,
		previewUrl = "https://youtube.com/shorts/2qQ-2oQ88-c",
	),
)

@Composable
private fun WhatsNewScreen(
	appVersion: String,
	onOpenPreview: (String) -> Unit,
) {
	Column(
		modifier = Modifier
			.fillMaxWidth()
			.verticalScroll(rememberScrollState())
			.padding(horizontal = 16.dp, vertical = 12.dp),
		verticalArrangement = Arrangement.spacedBy(14.dp),
	) {
		WhatsNewHero()
		FEATURES.forEach { feature ->
			FeatureCard(
				feature = feature,
				appVersion = appVersion,
				onOpenPreview = onOpenPreview,
			)
		}
		MoreImprovementsCard()
		Spacer(Modifier.height(16.dp))
	}
}

@Composable
private fun WhatsNewHero() {
	val colors = MaterialTheme.colorScheme
	Surface(
		modifier = Modifier.fillMaxWidth(),
		shape = RoundedCornerShape(28.dp),
		color = colors.primaryContainer,
	) {
		Column(
			modifier = Modifier.padding(horizontal = 24.dp, vertical = 26.dp),
			horizontalAlignment = Alignment.CenterHorizontally,
			verticalArrangement = Arrangement.spacedBy(8.dp),
		) {
			Text(
				text = stringResource(R.string.whats_new_hero_title),
				style = MaterialTheme.typography.headlineSmall,
				fontWeight = FontWeight.Bold,
				color = colors.onPrimaryContainer,
				textAlign = TextAlign.Center,
			)
			Text(
				text = stringResource(R.string.whats_new_hero_subtitle),
				style = MaterialTheme.typography.bodyLarge,
				color = colors.onPrimaryContainer.copy(alpha = 0.82f),
				textAlign = TextAlign.Center,
			)
		}
	}
}

@Composable
private fun FeatureCard(
	feature: WhatsNewFeature,
	appVersion: String,
	onOpenPreview: (String) -> Unit,
) {
	val colors = MaterialTheme.colorScheme
	Card(
		modifier = Modifier.fillMaxWidth(),
		shape = RoundedCornerShape(24.dp),
		colors = CardDefaults.cardColors(containerColor = colors.surfaceContainer),
	) {
		AsyncImage(
			model = feature.thumbnailUrl,
			contentDescription = null,
			contentScale = ContentScale.Crop,
			modifier = Modifier
				.fillMaxWidth()
				.aspectRatio(16f / 9f),
		)
		Column(
			modifier = Modifier.padding(18.dp),
			verticalArrangement = Arrangement.spacedBy(10.dp),
		) {
			Surface(
				shape = RoundedCornerShape(50),
				color = colors.secondaryContainer,
			) {
				Text(
					text = stringResource(feature.badge),
					modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
					style = MaterialTheme.typography.labelMedium,
					fontWeight = FontWeight.Bold,
					color = colors.onSecondaryContainer,
				)
			}
			Text(
				text = stringResource(feature.title),
				style = MaterialTheme.typography.titleLarge,
				fontWeight = FontWeight.Bold,
			)
			Text(
				text = stringResource(feature.tagline),
				style = MaterialTheme.typography.titleSmall,
				fontWeight = FontWeight.SemiBold,
				color = colors.primary,
			)
			Text(
				text = stringResource(feature.description),
				style = MaterialTheme.typography.bodyMedium,
				color = colors.onSurfaceVariant,
			)
			FeatureMeta(
				label = stringResource(R.string.whats_new_location_label),
				value = stringResource(feature.location),
			)
			FeatureMeta(
				label = stringResource(R.string.whats_new_usage_label),
				value = stringResource(feature.usage),
			)
			Text(
				text = stringResource(R.string.whats_new_introduced_version, appVersion),
				style = MaterialTheme.typography.labelMedium,
				color = colors.onSurfaceVariant,
			)
			OutlinedButton(
				onClick = { onOpenPreview(feature.previewUrl) },
				modifier = Modifier.fillMaxWidth(),
				colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.primary),
			) {
				Text(stringResource(R.string.whats_new_preview))
			}
		}
	}
}

@Composable
private fun FeatureMeta(label: String, value: String) {
	Column(
		modifier = Modifier.fillMaxWidth(),
		verticalArrangement = Arrangement.spacedBy(2.dp),
	) {
		Text(
			text = label,
			style = MaterialTheme.typography.labelLarge,
			fontWeight = FontWeight.Bold,
		)
		Text(
			text = value,
			style = MaterialTheme.typography.bodyMedium,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)
	}
}

@Composable
private fun MoreImprovementsCard() {
	val colors = MaterialTheme.colorScheme
	Card(
		modifier = Modifier.fillMaxWidth(),
		shape = RoundedCornerShape(24.dp),
		colors = CardDefaults.cardColors(containerColor = colors.tertiaryContainer),
	) {
		Column(
			modifier = Modifier.padding(20.dp),
			verticalArrangement = Arrangement.spacedBy(10.dp),
		) {
			Text(
				text = stringResource(R.string.whats_new_more_improvements),
				style = MaterialTheme.typography.titleLarge,
				fontWeight = FontWeight.Bold,
				color = colors.onTertiaryContainer,
			)
			Text(
				text = stringResource(R.string.whats_new_improvement_private),
				style = MaterialTheme.typography.bodyMedium,
				color = colors.onTertiaryContainer,
			)
			Text(
				text = stringResource(R.string.whats_new_improvement_stability),
				style = MaterialTheme.typography.bodyMedium,
				color = colors.onTertiaryContainer,
			)
		}
	}
}
