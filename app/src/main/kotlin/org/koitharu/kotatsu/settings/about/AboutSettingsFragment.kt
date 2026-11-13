package org.koitharu.kotatsu.settings.about

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
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
import org.koitharu.kotatsu.core.ui.LocalMiyorareVisualPalette
import org.koitharu.kotatsu.core.ui.MiyorareVisualTokens
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.util.ext.getDisplayMessage
import org.koitharu.kotatsu.core.util.ext.observeEvent
import org.koitharu.kotatsu.main.ui.nav.rememberAnyDrawablePainter
import org.koitharu.kotatsu.settings.SettingsActivity
import org.koitharu.kotatsu.settings.about.changelog.ChangelogFragment
import org.koitharu.kotatsu.settings.compose.ActionSettingsItem
import org.koitharu.kotatsu.settings.compose.BaseComposeSettingsFragment
import org.koitharu.kotatsu.settings.compose.CategoryPalette
import org.koitharu.kotatsu.settings.compose.MiyorareTheme
import org.koitharu.kotatsu.settings.compose.SettingsGroup
import org.koitharu.kotatsu.settings.compose.SettingsItem
import org.koitharu.kotatsu.settings.compose.SettingsIconModern
import org.koitharu.kotatsu.settings.compose.groupItemShape
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
			MiyorareTheme {
				val isUpdateSupported by viewModel.isUpdateSupported.collectAsState()
				val isLoading by viewModel.isLoading.collectAsState()
				val isVerboseLogging by viewModel.isVerboseLogging.collectAsState()
				AboutScreen(
					appVersion = BuildConfig.VERSION_NAME,
					checkUpdatesEnabled = isUpdateSupported && !isLoading,
					isVerboseLogging = isVerboseLogging,
					onCheckUpdates = viewModel::checkForUpdates,
					onWhatsNew = ::openWhatsNew,
					onChangelog = ::openChangelog,
					onOpenLink = ::openLink,
					onOpenProjectCredits = ::openProjectCredits,
					onVerboseLoggingToggle = viewModel::setVerboseLogging,
					onOpenDeveloperTools = ::openDeveloperTools,
				)
			}
		}
	}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)
		if (requireActivity().intent.getBooleanExtra(WhatsNewFragment.EXTRA_OPEN_WHATS_NEW, false)) {
			requireActivity().intent.removeExtra(WhatsNewFragment.EXTRA_OPEN_WHATS_NEW)
			view.post(::openWhatsNew)
		}
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

	private fun openWhatsNew() {
		(activity as? SettingsActivity)?.openFragment(
			WhatsNewFragment::class.java,
			null,
			isFromRoot = false,
		)
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

	private fun openProjectCredits() {
		(activity as? SettingsActivity)?.openFragment(
			ProjectCreditsFragment::class.java,
			null,
			isFromRoot = false,
		)
	}

	private fun onUpdateAvailable(version: AppVersion?) {
		if (version == null) {
			Snackbar.make(requireView(), R.string.no_update_available, Snackbar.LENGTH_SHORT).show()
		} else {
			startActivity(Intent(requireContext(), AppUpdateActivity::class.java))
		}
	}
}

class ProjectCreditsFragment : BaseComposeSettingsFragment(R.string.about_project_credits_title) {

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?,
	): View = ComposeView(requireContext()).apply {
		setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
		setContent {
			MiyorareTheme {
				SettingsScaffold {
					item { AboutProjectCredits { url, title -> openLink(url, title) } }
				}
			}
		}
	}
}

private fun Fragment.openLink(@StringRes urlRes: Int, titleRes: Int) {
	val opened = router.openExternalBrowser(getString(urlRes), getString(titleRes))
	if (!opened) {
		Snackbar.make(
			requireView(),
			R.string.operation_not_supported,
			Snackbar.LENGTH_SHORT,
		).show()
	}
}

@Composable
private fun AboutScreen(
	appVersion: String,
	checkUpdatesEnabled: Boolean,
	isVerboseLogging: Boolean,
	onCheckUpdates: () -> Unit,
	onWhatsNew: () -> Unit,
	onChangelog: () -> Unit,
	onOpenLink: (urlRes: Int, titleRes: Int) -> Unit,
	onOpenProjectCredits: () -> Unit,
	onVerboseLoggingToggle: (Boolean) -> Unit,
	onOpenDeveloperTools: () -> Unit,
) {
	val updateColors = CategoryPalette.forKey("downloads")
	val whatsNewColors = CategoryPalette.forKey("favourites")
	val changelogColors = CategoryPalette.forKey("services")
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
						title = stringResource(R.string.whats_new_title),
						subtitle = stringResource(R.string.whats_new_about_summary),
						icon = R.drawable.ic_info_outline,
						iconColors = whatsNewColors,
						shape = pos.shape,
						onClick = onWhatsNew,
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
		item { AboutProjectCommunity(onOpenLink, onOpenProjectCredits) }
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


private data class AboutProject(
	@StringRes val title: Int,
	@StringRes val summary: Int,
	@DrawableRes val icon: Int,
	@StringRes val github: Int,
	@StringRes val discord: Int? = null,
	@StringRes val githubLabel: Int = R.string.about_github_label,
	@StringRes val discordLabel: Int = R.string.discord,
	val extraGithub: AboutGithubLink? = null,
)

private data class AboutGithubLink(@StringRes val url: Int, @StringRes val label: Int)

private val miyorareProject = AboutProject(
	R.string.about_brand_name, R.string.about_miyorare_project_summary,
	R.drawable.ic_launcher_main_art, R.string.url_github, R.string.url_discord_web,
)

private val upstreamProjects = listOf(
	AboutProject(
		R.string.about_dropsauce_name, R.string.about_dropsauce_summary,
		R.drawable.ic_book_page, R.string.about_url_dropsauce_github, R.string.about_url_dropsauce_discord,
	),
	AboutProject(
		R.string.about_kotatsu_name, R.string.about_kotatsu_project_summary,
		R.drawable.ic_book_page, R.string.about_url_kotatsu_github, R.string.about_url_kotatsu_redo_discord,
		githubLabel = R.string.about_github_kotatsu_label,
		discordLabel = R.string.about_discord_kotatsu_redo_label,
		extraGithub = AboutGithubLink(R.string.about_url_kotatsu_redo_github, R.string.about_github_kotatsu_redo_label),
	),
	AboutProject(
		R.string.about_mihon_name, R.string.about_mihon_project_summary,
		R.drawable.ic_book_page, R.string.about_url_mihon_github, R.string.about_url_mihon_discord,
	),
	AboutProject(
		R.string.about_lnreader_name, R.string.about_lnreader_project_summary,
		R.drawable.ic_book_page, R.string.about_url_lnreader_github, R.string.about_url_lnreader_discord,
	),
	AboutProject(
		R.string.about_tachiyomi_name, R.string.about_tachiyomi_project_summary,
		R.drawable.ic_book_page, R.string.about_url_tachiyomi_github,
	),
	AboutProject(
		R.string.about_tsuki_uma_gekkoushi_name, R.string.about_tsuki_uma_gekkoushi_summary,
		R.drawable.ic_book_page, R.string.about_url_uma_github, R.string.about_url_tsuki_uma_gekkoushi_discord,
		githubLabel = R.string.about_github_uma_label,
		extraGithub = AboutGithubLink(R.string.about_url_gekkoushi_github, R.string.about_github_gekkoushi_label),
	),
)

@Composable
internal fun AboutProjectCommunity(
	onOpenLink: (Int, Int) -> Unit,
	onOpenProjectCredits: () -> Unit,
) {
	Column(Modifier.fillMaxWidth()) {
		AboutCommunityHeading(R.string.about_project_community_section, primary = true)
		Spacer(Modifier.height(8.dp))
		Column(verticalArrangement = Arrangement.spacedBy(MiyorareVisualTokens.SPACING_S_DP.dp)) {
			AboutProjectCard(miyorareProject, onOpenLink)
			AboutCommunityNavigationCard(
				title = stringResource(R.string.about_project_credits_title),
				icon = R.drawable.ic_book_page,
				onClick = onOpenProjectCredits,
			)
			AboutCommunityNavigationCard(
				title = stringResource(R.string.about_license_title),
				summary = stringResource(R.string.about_license_summary),
				icon = R.drawable.ic_info_outline,
				onClick = { onOpenLink(R.string.url_project_license, R.string.about_license_title) },
			)
		}
	}
}

@Composable
internal fun AboutProjectCredits(onOpenLink: (Int, Int) -> Unit) {
	Column(Modifier.fillMaxWidth()) {
		AboutCommunityHeading(R.string.about_upstream_section)
		Spacer(Modifier.height(8.dp))
		Column(verticalArrangement = Arrangement.spacedBy(MiyorareVisualTokens.SPACING_S_DP.dp)) {
			upstreamProjects.forEach { project -> AboutProjectCard(project, onOpenLink) }
		}
	}
}

@Composable
private fun AboutCommunityNavigationCard(
	title: String,
	@DrawableRes icon: Int,
	onClick: () -> Unit,
	summary: String? = null,
) {
	AboutCommunityCard(title = title, onClick = onClick) {
		Row(
			modifier = Modifier.fillMaxWidth(),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(12.dp),
		) {
			AboutProjectIdentity(title, summary, icon, modifier = Modifier.weight(1f))
			Icon(
				painterResource(R.drawable.ic_arrow_forward),
				contentDescription = null,
				tint = MaterialTheme.colorScheme.onSurfaceVariant,
				modifier = Modifier.size(18.dp),
			)
		}
	}
}

@Composable
private fun AboutCommunityHeading(@StringRes title: Int, primary: Boolean = false) {
	Text(
		text = stringResource(title),
		style = if (primary) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelMedium,
		fontWeight = FontWeight.SemiBold,
		color = MaterialTheme.colorScheme.primary,
		modifier = Modifier.padding(horizontal = 12.dp).semantics { heading() },
	)
}

@Composable
private fun AboutCommunityCard(
	title: String,
	onClick: (() -> Unit)? = null,
	content: @Composable () -> Unit,
) {
	val palette = LocalMiyorareVisualPalette.current
	val shape = groupItemShape(index = 0, total = 1, modern = palette.isModern)
	val modifier = Modifier.fillMaxWidth()
	SettingsItem(
		title = title,
		modifier = if (palette.isModern) modifier else {
			modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
		},
		shape = shape,
		onClick = onClick,
		contentOverride = content,
	)
}

@Composable
private fun AboutProjectCard(project: AboutProject, onOpenLink: (Int, Int) -> Unit) {
	val title = stringResource(project.title)
	val summary = stringResource(project.summary)
	val fontScale = LocalDensity.current.fontScale
	val actionCount = 1 + (if (project.discord != null) 1 else 0) + (if (project.extraGithub != null) 1 else 0)
	val actionsWidth = AboutActionTarget * actionCount + AboutActionGap * (actionCount - 1)
	AboutCommunityCard(title = title) {
		BoxWithConstraints(Modifier.fillMaxWidth()) {
			val inline = project.extraGithub == null &&
				maxWidth >= 154.dp * fontScale.coerceAtLeast(1f) + 21.dp + actionsWidth
			if (inline) {
				Row(
					modifier = Modifier.fillMaxWidth(),
					verticalAlignment = Alignment.CenterVertically,
				) {
					AboutProjectIdentity(title, summary, project.icon, Modifier.weight(1f), project == miyorareProject)
					Box(
						Modifier.padding(horizontal = 10.dp).width(1.dp).height(34.dp)
							.background(MaterialTheme.colorScheme.outlineVariant),
					)
					AboutProjectActions(project, title, onOpenLink, inline = true)
				}
			} else {
				Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
					AboutProjectIdentity(title, summary, project.icon, keepArtwork = project == miyorareProject)
					// Let wrapped actions use the entire card, not the narrower text column.
					AboutProjectActions(project, title, onOpenLink)
				}
			}
		}
	}
}

@Composable
private fun AboutProjectIdentity(
	title: String,
	summary: String?,
	@DrawableRes icon: Int,
	modifier: Modifier = Modifier,
	keepArtwork: Boolean = false,
) {
	Row(
		modifier = modifier,
		horizontalArrangement = Arrangement.spacedBy(12.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		SettingsIconModern(
			iconRes = icon,
			palette = LocalMiyorareVisualPalette.current,
			enabled = true,
			tintIcon = !keepArtwork,
		)
		Column(Modifier.weight(1f)) {
			Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
			if (summary != null) {
				Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
			}
		}
	}
}

private val AboutActionGap = 6.dp
private val AboutActionTarget = 48.dp
private val AboutActionVisualSize = 38.dp
private val AboutActionIcon = 18.dp

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AboutProjectActions(
	project: AboutProject,
	title: String,
	onOpenLink: (Int, Int) -> Unit,
	inline: Boolean = false,
) {
	val links: @Composable () -> Unit = {
		AboutProjectLink(project.githubLabel, R.drawable.ic_github, title) {
			onOpenLink(project.github, project.title)
		}
		project.extraGithub?.let { link ->
			AboutProjectLink(link.label, R.drawable.ic_github, title) {
				onOpenLink(link.url, project.title)
			}
		}
		project.discord?.let { url ->
			AboutProjectLink(project.discordLabel, R.drawable.ic_discord, title) {
				onOpenLink(url, project.title)
			}
		}
	}
	if (inline) {
		Row(horizontalArrangement = Arrangement.spacedBy(AboutActionGap)) { links() }
	} else {
		FlowRow(
			horizontalArrangement = Arrangement.spacedBy(AboutActionGap),
			verticalArrangement = Arrangement.spacedBy(2.dp),
		) { links() }
	}
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AboutProjectLink(
	@StringRes label: Int,
	@DrawableRes icon: Int,
	projectTitle: String,
	onClick: () -> Unit,
) {
	val text = stringResource(label)
	val description = stringResource(R.string.about_project_link_description, projectTitle, text)
	val colors = MaterialTheme.colorScheme
	val accent = if (icon == R.drawable.ic_discord) colors.secondary else colors.primary
	Box(Modifier.size(AboutActionTarget), contentAlignment = Alignment.Center) {
		TooltipBox(
			positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
			tooltip = { PlainTooltip { Text(text) } },
			state = rememberTooltipState(),
		) {
			OutlinedButton(
				onClick = onClick,
				modifier = Modifier.minimumInteractiveComponentSize()
					.size(AboutActionVisualSize)
					.semantics { contentDescription = description },
				shape = CircleShape,
				colors = ButtonDefaults.outlinedButtonColors(contentColor = accent),
				border = BorderStroke(1.dp, accent.copy(alpha = 0.72f)),
				contentPadding = PaddingValues(0.dp),
			) {
				Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(AboutActionIcon))
			}
		}
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
					painter = rememberAnyDrawablePainter(R.drawable.ic_launcher_main_art),
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
