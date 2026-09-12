package org.koitharu.kotatsu.settings.sources

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.ui.LocalMiyorareVisualPalette
import org.koitharu.kotatsu.settings.SettingsActivity
import org.koitharu.kotatsu.settings.compose.ActionSettingsItem
import org.koitharu.kotatsu.settings.compose.BaseComposeSettingsFragment
import org.koitharu.kotatsu.settings.compose.DropSauceTheme
import org.koitharu.kotatsu.tsuki.MiyorareOfficialSourcePack
import org.koitharu.kotatsu.tsuki.MiyorareOfficialSourcePacks
import org.koitharu.kotatsu.tsuki.TsukiPluginManager
import org.koitharu.kotatsu.tsuki.model.TsukiPluginDescriptor
import org.koitharu.kotatsu.tsuki.model.TsukiPluginProvider
import javax.inject.Inject

@AndroidEntryPoint
class MiyorareSourcePacksSettingsFragment : BaseComposeSettingsFragment(R.string.miyorare_source_packs_title) {

	@Inject
	lateinit var pluginManager: TsukiPluginManager

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		pluginManager.initialize()
	}

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?,
	): View = ComposeView(requireContext()).apply {
		setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
		setContent {
			DropSauceTheme {
				val plugins by pluginManager.plugins.collectAsState()
				MiyorareSourcePacksOverview(
					plugins = plugins,
					onOpenPack = ::openPack,
				)
			}
		}
	}

	private fun openPack(pack: MiyorareOfficialSourcePack) {
		(requireActivity() as SettingsActivity).openFragment(
			MiyorareSourcePackDetailSettingsFragment::class.java,
			args = Bundle().apply {
				putString(MiyorareSourcePackDetailSettingsFragment.ARG_PACK_ID, pack.pluginId)
			},
			isFromRoot = false,
		)
	}
}

private data class MiyorarePackOverviewModel(
	val pack: MiyorareOfficialSourcePack,
	val plugins: List<TsukiPluginDescriptor>,
	val availableCount: Int,
	val enabledCount: Int,
	val versionLabel: String,
)

@Composable
private fun MiyorareSourcePacksOverview(
	plugins: List<TsukiPluginDescriptor>,
	onOpenPack: (MiyorareOfficialSourcePack) -> Unit,
) {
	val models = remember(plugins) {
		MiyorareOfficialSourcePacks.packs.map { pack ->
			val packPlugins = plugins.filter { plugin ->
				plugin.provider == TsukiPluginProvider.MIYORARE &&
					MiyorareOfficialSourcePacks.findByInstalledPluginId(plugin.pluginId)?.pluginId == pack.pluginId
			}
			val available = packPlugins.flatMap { plugin ->
				plugin.sources.filterNot { it.isBroken }.map { source -> plugin to source }
			}
			val versions = packPlugins.map { it.version }.distinct()
			MiyorarePackOverviewModel(
				pack = pack,
				plugins = packPlugins,
				availableCount = available.size,
				enabledCount = available.count { (plugin, source) -> source.name in plugin.enabledSourceNames },
				versionLabel = versions.singleOrNull()
					?: versions.takeIf { it.isNotEmpty() }?.joinToString(" / ").orEmpty(),
			)
		}
	}

	LazyColumn(
		modifier = Modifier.fillMaxSize(),
		contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 32.dp),
		verticalArrangement = Arrangement.spacedBy(8.dp),
	) {
		item(key = "intro") {
			MiyorareSourcePacksHeader()
		}

		models.forEach { model ->
			item(key = "pack:${model.pack.pluginId}") {
				val flag = if (model.pack.language == "id") "🇮🇩" else "🇬🇧"
				val status = if (model.plugins.isEmpty()) {
					stringResource(R.string.miyorare_source_pack_overview_not_installed)
				} else {
					stringResource(
						R.string.miyorare_source_pack_overview_installed,
						model.versionLabel.ifBlank { "—" },
						model.availableCount,
						model.enabledCount,
					)
				}
				ActionSettingsItem(
					title = "$flag ${model.pack.displayName}",
					subtitle = "$status\n${stringResource(R.string.miyorare_source_pack_manage_hint)}",
					icon = R.drawable.ic_launcher_main_art,
					tintIcon = false,
					onClick = { onOpenPack(model.pack) },
				)
			}
		}
	}
}

@Composable
private fun MiyorareSourcePacksHeader() {
	val palette = LocalMiyorareVisualPalette.current
	val gradient = remember(palette.primary, palette.secondary) {
		Brush.horizontalGradient(
			listOf(
				palette.primary,
				lerp(palette.primary, palette.secondary, 0.45f),
				palette.secondary,
			),
		)
	}
	Surface(
		modifier = Modifier.fillMaxWidth(),
		shape = RoundedCornerShape(20.dp),
		color = MaterialTheme.colorScheme.surfaceContainer,
		tonalElevation = 1.dp,
	) {
		Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
			Row(
				modifier = Modifier.fillMaxWidth(),
				horizontalArrangement = Arrangement.SpaceBetween,
				verticalAlignment = Alignment.CenterVertically,
			) {
				Row(verticalAlignment = Alignment.CenterVertically) {
					Text(
						text = stringResource(R.string.miyorare_source_packs_brand),
						style = MaterialTheme.typography.titleLarge.copy(
							fontWeight = FontWeight.Bold,
							brush = gradient,
						),
					)
					Spacer(Modifier.width(5.dp))
					Text(
						text = stringResource(R.string.miyorare_source_packs_kind),
						style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
						color = MaterialTheme.colorScheme.onSurface,
					)
				}
				Box(
					modifier = Modifier
						.background(gradient, RoundedCornerShape(999.dp))
						.padding(horizontal = 10.dp, vertical = 5.dp),
				) {
					Text(
						text = stringResource(R.string.miyorare_source_packs_official_badge),
						style = MaterialTheme.typography.labelSmall,
						fontWeight = FontWeight.Bold,
						color = MaterialTheme.colorScheme.onPrimary,
					)
				}
			}
			Spacer(Modifier.height(8.dp))
			Text(
				text = stringResource(R.string.miyorare_source_packs_summary),
				style = MaterialTheme.typography.bodyMedium,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
			Spacer(Modifier.height(12.dp))
			Box(
				modifier = Modifier
					.fillMaxWidth()
					.height(2.dp)
					.background(gradient, RoundedCornerShape(999.dp)),
			)
		}
	}
}
