package org.koitharu.kotatsu.settings.about

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.BuildConfig
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.github.AppVersion
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.util.ext.getDisplayMessage
import org.koitharu.kotatsu.core.util.ext.observeEvent
import org.koitharu.kotatsu.main.ui.nav.rememberAnyDrawablePainter
import org.koitharu.kotatsu.settings.SettingsActivity
import org.koitharu.kotatsu.settings.about.changelog.ChangelogFragment
import org.koitharu.kotatsu.settings.compose.ActionSettingsItem
import org.koitharu.kotatsu.settings.compose.BaseComposeSettingsFragment
import org.koitharu.kotatsu.settings.compose.CategoryPalette
import org.koitharu.kotatsu.settings.compose.DropSauceTheme
import org.koitharu.kotatsu.settings.compose.SettingsGroup
import org.koitharu.kotatsu.settings.compose.SettingsScaffold
import org.koitharu.kotatsu.settings.compose.SwitchSettingsItem
import org.koitharu.kotatsu.settings.developer.DeveloperToolsFragment

@AndroidEntryPoint
class AboutSettingsFragment : BaseComposeSettingsFragment(R.string.about) {

	private val viewModel by viewModels<AboutSettingsViewModel>()

	private val saveLogLauncher = registerForActivityResult(
		ActivityResultContracts.CreateDocument("text/plain"),
	) { uri: Uri? ->
		val content = viewModel.pendingLogExport.value ?: return@registerForActivityResult
		if (uri != null) {
			try {
				requireContext().contentResolver.openOutputStream(uri)?.use { output ->
					output.write(content.toByteArray(Charsets.UTF_8))
				}
			} catch (_: Exception) {
				view?.let { Snackbar.make(it, R.string.error_occurred, Snackbar.LENGTH_SHORT).show() }
			}
		}
		viewModel.consumePendingLogExport(content)
	}

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?,
	): View = ComposeView(requireContext()).apply {
		setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
		setContent {
			DropSauceTheme {
				val isUpdateSupported by viewModel.isUpdateSupported.collectAsState()
				val isLoading by viewModel.isLoading.collectAsState()
				val isVerboseLogging by viewModel.isVerboseLogging.collectAsState()
				AboutScreen(
					appVersion = BuildConfig.VERSION_NAME,
					checkUpdatesEnabled = isUpdateSupported && !isLoading,
					isVerboseLogging = isVerboseLogging,
					onCheckUpdates = viewModel::checkForUpdates,
					onChangelog = ::openChangelog,
					onOpenLink = ::openLink,
					onVerboseLoggingToggle = viewModel::setVerboseLogging,
					onOpenDeveloperTools = ::openDeveloperTools,
				)
			}
		}
	}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)
		viewModel.onUpdateAvailable.observeEvent(viewLifecycleOwner, ::onUpdateAvailable)
		viewModel.onError.observeEvent(viewLifecycleOwner) { error ->
			Snackbar.make(view, error.getDisplayMessage(resources), Snackbar.LENGTH_SHORT).show()
		}
		viewLifecycleOwner.lifecycleScope.launch {
			viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
				viewModel.pendingLogExport.filterNotNull().collect { content ->
					if (viewModel.requestLogExport(content)) {
						saveLogLauncher.launch("miyorare_log_${System.currentTimeMillis()}.txt")
					}
				}
			}
		}
	}

	private fun openChangelog() {
		(activity as? SettingsActivity)?.openFragment(
			ChangelogFragment::class.java,
			null,
			isFromRoot = false,
		)
	}

	private fun openDeveloperTools() {
		(activity as? SettingsActivity)?.openFragment(
			DeveloperToolsFragment::class.java,
			null,
			isFromRoot = false,
		)
	}

	private fun openLink(@StringRes urlRes: Int, titleRes: Int) {
		val opened = router.openExternalBrowser(getString(urlRes), getString(titleRes))
		if (!opened) {
			Snackbar.make(
				requireView(),
				R.string.operation_not_supported,
				Snackbar.LENGTH_SHORT,
			).show()
		}
	}

	private fun onUpdateAvailable(version: AppVersion?) {
		if (version == null) {
			Snackbar.make(requireView(), R.string.no_update_available, Snackbar.LENGTH_SHORT).show()
		} else {
			startActivity(Intent(requireContext(), AppUpdateActivity::class.java))
		}
	}
}

@Composable
private fun AboutScreen(
	appVersion: String,
	checkUpdatesEnabled: Boolean,
	isVerboseLogging: Boolean,
	onCheckUpdates: () -> Unit,
	onChangelog: () -> Unit,
	onOpenLink: (urlRes: Int, titleRes: Int) -> Unit,
	onVerboseLoggingToggle: (Boolean) -> Unit,
	onOpenDeveloperTools: () -> Unit,
) {
	val updateColors = CategoryPalette.forKey("downloads")
	val changelogColors = CategoryPalette.forKey("services")
	val manualColors = CategoryPalette.forKey("reader")
	val sourceColors = CategoryPalette.forKey("about")
	val discordColors = CategoryPalette.forKey("services")
	val loggingColors = CategoryPalette.forKey("downloads")
	val developerColors = CategoryPalette.forKey("extensions")

	SettingsScaffold {
		item { AboutHero(appVersion = appVersion) }
		item { Spacer(Modifier.height(16.dp).fillMaxWidth()) }
		item {
			SettingsGroup(title = stringResource(R.string.about_updates_section)) {
				item { pos ->
					ActionSettingsItem(
						title = stringResource(R.string.check_for_updates),
						subtitle = stringResource(R.string.about_check_updates_summary),
						icon = R.drawable.ic_app_update,
						iconColors = updateColors,
						shape = pos.shape,
						enabled = checkUpdatesEnabled,
						onClick = onCheckUpdates,
					)
				}
				item { pos ->
					ActionSettingsItem(
						title = stringResource(R.string.changelog),
						subtitle = stringResource(R.string.about_changelog_summary),
						icon = R.drawable.ic_history,
						iconColors = changelogColors,
						shape = pos.shape,
						onClick = onChangelog,
					)
				}
			}
		}
		item { Spacer(Modifier.height(8.dp).fillMaxWidth()) }
		item {
			SettingsGroup(title = stringResource(R.string.about_project_community_section)) {
				item { pos ->
					ActionSettingsItem(
						title = stringResource(R.string.user_manual),
						subtitle = stringResource(R.string.about_user_manual_summary),
						icon = R.drawable.ic_book_page,
						iconColors = manualColors,
						shape = pos.shape,
						onClick = { onOpenLink(R.string.url_user_manual, R.string.user_manual) },
					)
				}
				item { pos ->
					ActionSettingsItem(
						title = stringResource(R.string.source_code),
						subtitle = stringResource(R.string.about_source_code_summary),
						icon = R.drawable.ic_github,
						iconColors = sourceColors,
						shape = pos.shape,
						onClick = { onOpenLink(R.string.url_github, R.string.source_code) },
					)
				}
				item { pos ->
					ActionSettingsItem(
						title = stringResource(R.string.about_license_title),
						subtitle = stringResource(R.string.about_license_summary),
						icon = R.drawable.ic_info_outline,
						iconColors = sourceColors,
						shape = pos.shape,
						onClick = { onOpenLink(R.string.url_project_license, R.string.about_license_title) },
					)
				}
				item { pos ->
					ActionSettingsItem(
						title = stringResource(R.string.about_credits_title),
						subtitle = stringResource(R.string.about_credits_summary),
						icon = R.drawable.ic_github,
						iconColors = sourceColors,
						shape = pos.shape,
						onClick = { onOpenLink(R.string.url_project_credits, R.string.about_credits_title) },
					)
				}
				item { pos ->
					ActionSettingsItem(
						title = stringResource(R.string.discord),
						subtitle = stringResource(R.string.about_discord_summary),
						icon = R.drawable.ic_discord,
						iconColors = discordColors,
						shape = pos.shape,
						onClick = { onOpenLink(R.string.url_discord_web, R.string.discord) },
					)
				}
			}
		}
		item { Spacer(Modifier.height(8.dp).fillMaxWidth()) }
		item {
			SettingsGroup(title = stringResource(R.string.about_advanced_section)) {
				item { pos ->
					SwitchSettingsItem(
						title = stringResource(R.string.about_verbose_logging_title),
						subtitle = if (isVerboseLogging) {
							stringResource(R.string.about_verbose_logging_enabled_summary)
						} else {
							stringResource(R.string.about_verbose_logging_disabled_summary)
						},
						icon = R.drawable.ic_script,
						iconColors = loggingColors,
						shape = pos.shape,
						checked = isVerboseLogging,
						onCheckedChange = onVerboseLoggingToggle,
					)
				}
				item { pos ->
					ActionSettingsItem(
						title = stringResource(R.string.developer_testing_tools),
						subtitle = stringResource(R.string.developer_testing_tools_summary),
						icon = R.drawable.ic_timer_run,
						iconColors = developerColors,
						shape = pos.shape,
						onClick = onOpenDeveloperTools,
					)
				}
			}
		}
		item { Spacer(Modifier.height(24.dp).fillMaxWidth()) }
	}
}

@Composable
private fun AboutHero(appVersion: String) {
	val cs = MaterialTheme.colorScheme
	Surface(
		modifier = Modifier.fillMaxWidth(),
		shape = RoundedCornerShape(28.dp),
		color = cs.primaryContainer,
	) {
		Column(
			modifier = Modifier
				.fillMaxWidth()
				.padding(horizontal = 24.dp, vertical = 28.dp),
			horizontalAlignment = Alignment.CenterHorizontally,
			verticalArrangement = Arrangement.spacedBy(14.dp),
		) {
			Box(
				modifier = Modifier
					.size(96.dp)
					.clip(RoundedCornerShape(30.dp))
					.background(cs.onPrimaryContainer.copy(alpha = 0.10f)),
				contentAlignment = Alignment.Center,
			) {
				Image(
					painter = rememberAnyDrawablePainter(R.mipmap.ic_launcher),
					contentDescription = null,
					modifier = Modifier
						.size(78.dp)
						.clip(RoundedCornerShape(24.dp)),
				)
			}
			Text(
				text = stringResource(R.string.about_brand_name),
				style = MaterialTheme.typography.headlineSmall,
				color = cs.onPrimaryContainer,
				fontWeight = FontWeight.Bold,
				textAlign = TextAlign.Center,
			)
			AboutMetaPill(
				icon = R.drawable.ic_info_outline,
				text = "v$appVersion",
			)
			Text(
				text = stringResource(R.string.about_project_description),
				style = MaterialTheme.typography.bodyMedium,
				color = cs.onPrimaryContainer.copy(alpha = 0.82f),
				textAlign = TextAlign.Center,
			)
		}
	}
}

@Composable
private fun AboutMetaPill(
	@androidx.annotation.DrawableRes icon: Int,
	text: String,
) {
	val cs = MaterialTheme.colorScheme
	Surface(
		shape = RoundedCornerShape(50),
		color = cs.onPrimaryContainer.copy(alpha = 0.14f),
	) {
		Row(
			modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
			horizontalArrangement = Arrangement.spacedBy(7.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			Icon(
				painter = painterResource(icon),
				contentDescription = null,
				tint = cs.onPrimaryContainer,
				modifier = Modifier.size(15.dp),
			)
			Text(
				text = text,
				style = MaterialTheme.typography.labelLarge,
				color = cs.onPrimaryContainer,
				fontWeight = FontWeight.Medium,
			)
		}
	}
}
