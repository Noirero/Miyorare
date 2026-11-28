package org.koitharu.kotatsu.settings.discord

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.fragment.app.viewModels
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.StateFlow
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.scrobbling.discord.ui.DiscordAuthActivity
import org.koitharu.kotatsu.settings.compose.ActionSettingsItem
import org.koitharu.kotatsu.settings.compose.BaseComposeSettingsFragment
import org.koitharu.kotatsu.settings.compose.MiyorareSettingsTheme
import org.koitharu.kotatsu.settings.compose.SettingsGroup
import org.koitharu.kotatsu.settings.compose.SettingsScaffold
import org.koitharu.kotatsu.settings.compose.SwitchSettingsItem
import org.koitharu.kotatsu.settings.compose.rememberBooleanPref

@AndroidEntryPoint
class DiscordSettingsFragment : BaseComposeSettingsFragment(R.string.discord) {

	private val viewModel by viewModels<DiscordSettingsViewModel>()

	private val signInLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
		if (result.resultCode == Activity.RESULT_OK) viewModel.refreshTokenState()
	}

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?,
	): View = ComposeView(requireContext()).apply {
		setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
		setContent {
			MiyorareSettingsTheme("services") {
				DiscordScreen(
					tokenState = viewModel.tokenState,
					onSaveToken = viewModel::saveToken,
					onSignIn = ::openSignIn,
				)
			}
		}
	}

	private fun openSignIn() {
		signInLauncher.launch(Intent(requireContext(), DiscordAuthActivity::class.java))
	}
}

@Composable
private fun DiscordScreen(
	tokenState: StateFlow<TokenState>,
	onSaveToken: (String?) -> Unit,
	onSignIn: () -> Unit,
) {
	val ctx = LocalContext.current
	val state by tokenState.collectAsState()

	var rpcEnabled by rememberBooleanPref(AppSettings.KEY_DISCORD_RPC, false)
	var skipNsfw by rememberBooleanPref(AppSettings.KEY_DISCORD_RPC_SKIP_NSFW, false)

	var showTokenDialog by remember { mutableStateOf(false) }

	val isWarning = when (state) {
		TokenState.REQUIRED, TokenState.INVALID, TokenState.RATE_LIMITED, TokenState.UNAVAILABLE -> true
		else -> false
	}
	val tokenSubtitle = when (state) {
		TokenState.EMPTY, TokenState.REQUIRED -> stringResource(R.string.discord_token_summary)
		TokenState.SAVED, TokenState.VALID -> stringResource(R.string.discord_token_saved)
		TokenState.INVALID -> stringResource(R.string.discord_token_rejected)
		TokenState.RATE_LIMITED -> stringResource(R.string.discord_token_rate_limited)
		TokenState.UNAVAILABLE -> stringResource(R.string.discord_token_verification_unavailable)
		TokenState.CHECKING -> stringResource(R.string.loading_)
	}

	SettingsScaffold {
		item {
			SettingsGroup {
				item { pos ->
					SwitchSettingsItem(
						title = stringResource(R.string.discord_rpc),
						subtitle = stringResource(R.string.discord_rpc_summary),
						checked = rpcEnabled,
						onCheckedChange = { rpcEnabled = it },
						icon = R.drawable.ic_discord,
						shape = pos.shape,
					)
				}
				item { pos ->
					ActionSettingsItem(
						title = stringResource(R.string.discord_token),
						subtitle = tokenSubtitle,
						icon = if (isWarning) R.drawable.ic_alert_outline else R.drawable.ic_lock,
						shape = pos.shape,
						onClick = { showTokenDialog = true },
					)
				}
				item { pos ->
					SwitchSettingsItem(
						title = stringResource(R.string.disable_nsfw),
						subtitle = stringResource(R.string.rpc_skip_nsfw_summary),
						checked = skipNsfw,
						onCheckedChange = { skipNsfw = it },
						icon = R.drawable.ic_nsfw,
						shape = pos.shape,
						enabled = rpcEnabled,
					)
				}
			}
		}
		item { Spacer(Modifier.height(24.dp).fillMaxWidth()) }
	}

	if (showTokenDialog) {
		DiscordTokenDialog(
			hasSavedToken = state != TokenState.EMPTY && state != TokenState.REQUIRED,
			message = ctx.getString(
				R.string.discord_token_description,
				ctx.getString(R.string.sign_in),
			),
			onConfirm = onSaveToken,
			onSignIn = {
				showTokenDialog = false
				onSignIn()
			},
			onDismiss = { showTokenDialog = false },
		)
	}
}

@Composable
private fun DiscordTokenDialog(
	hasSavedToken: Boolean,
	message: String,
	onConfirm: (String?) -> Unit,
	onSignIn: () -> Unit,
	onDismiss: () -> Unit,
) {
	var value by remember { mutableStateOf("") }
	AlertDialog(
		onDismissRequest = onDismiss,
		properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
		title = { Text(stringResource(R.string.discord_token)) },
		text = {
			androidx.compose.foundation.layout.Column {
				Text(message, style = androidx.compose.material3.MaterialTheme.typography.bodyMedium)
				Spacer(Modifier.height(12.dp))
				OutlinedTextField(
					value = value,
					onValueChange = { value = it },
					placeholder = { Text(stringResource(R.string.discord_token_hint)) },
					singleLine = true,
					keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
					visualTransformation = PasswordVisualTransformation(),
					modifier = Modifier.fillMaxWidth(),
				)
				if (hasSavedToken) {
					TextButton(onClick = {
						onConfirm(null)
						onDismiss()
					}) { Text(stringResource(R.string.discord_token_remove)) }
				}
			}
		},
		confirmButton = {
			TextButton(
				enabled = value.isNotBlank(),
				onClick = {
					onConfirm(value)
					onDismiss()
				},
			) { Text(stringResource(android.R.string.ok)) }
		},
		dismissButton = {
			TextButton(onClick = onSignIn) { Text(stringResource(R.string.sign_in)) }
		},
	)
}
