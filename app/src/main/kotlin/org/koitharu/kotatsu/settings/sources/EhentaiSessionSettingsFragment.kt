package org.koitharu.kotatsu.settings.sources

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.settings.compose.BaseComposeSettingsFragment
import org.koitharu.kotatsu.settings.compose.MiyorareTheme
import org.koitharu.kotatsu.tsuki.EhentaiSessionManager
import javax.inject.Inject

@AndroidEntryPoint
class EhentaiSessionSettingsFragment : BaseComposeSettingsFragment(R.string.ehentai_session_title) {

	@Inject
	lateinit var sessionManager: EhentaiSessionManager

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?,
	): View = ComposeView(requireContext()).apply {
		setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
		setContent {
			MiyorareTheme {
				val initialCredentials = remember { sessionManager.getCredentials() }
				var mode by remember { mutableStateOf(sessionManager.getMode()) }
				var memberId by remember { mutableStateOf(initialCredentials.ipbMemberId) }
				var passHash by remember { mutableStateOf(initialCredentials.ipbPassHash) }
				var igneous by remember { mutableStateOf(initialCredentials.igneous) }

				EhentaiSessionScreen(
					mode = mode,
					memberId = memberId,
					passHash = passHash,
					igneous = igneous,
					onModeChanged = { mode = it },
					onMemberIdChanged = { memberId = it },
					onPassHashChanged = { passHash = it },
					onIgneousChanged = { igneous = it },
					onSave = {
						sessionManager.save(
							mode,
							EhentaiSessionManager.Credentials(memberId, passHash, igneous),
						)
						Toast.makeText(requireContext(), R.string.ehentai_saved, Toast.LENGTH_SHORT).show()
					},
					onClear = {
						sessionManager.clearCredentials()
						memberId = ""
						passHash = ""
						igneous = ""
						Toast.makeText(requireContext(), R.string.ehentai_cleared, Toast.LENGTH_SHORT).show()
					},
				)
			}
		}
	}
}

@Composable
private fun EhentaiSessionScreen(
	mode: EhentaiSessionManager.Mode,
	memberId: String,
	passHash: String,
	igneous: String,
	onModeChanged: (EhentaiSessionManager.Mode) -> Unit,
	onMemberIdChanged: (String) -> Unit,
	onPassHashChanged: (String) -> Unit,
	onIgneousChanged: (String) -> Unit,
	onSave: () -> Unit,
	onClear: () -> Unit,
) {
	val complete = memberId.isNotBlank() && passHash.isNotBlank() && igneous.isNotBlank()
	LazyColumn(
		modifier = Modifier.fillMaxSize(),
		contentPadding = PaddingValues(16.dp),
		verticalArrangement = Arrangement.spacedBy(14.dp),
	) {
		item {
			Text(
				text = stringResource(R.string.ehentai_session_summary),
				style = MaterialTheme.typography.bodyMedium,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
		}
		item {
			Text(
				text = stringResource(R.string.ehentai_mode_title),
				style = MaterialTheme.typography.titleMedium,
			)
		}
		item {
			ModeRow(
				selected = mode == EhentaiSessionManager.Mode.AUTO,
				title = stringResource(R.string.ehentai_mode_auto),
				summary = stringResource(R.string.ehentai_mode_auto_summary),
				onClick = { onModeChanged(EhentaiSessionManager.Mode.AUTO) },
			)
		}
		item {
			ModeRow(
				selected = mode == EhentaiSessionManager.Mode.EHENTAI_ONLY,
				title = stringResource(R.string.ehentai_mode_public),
				summary = stringResource(R.string.ehentai_mode_public_summary),
				onClick = { onModeChanged(EhentaiSessionManager.Mode.EHENTAI_ONLY) },
			)
		}
		item {
			ModeRow(
				selected = mode == EhentaiSessionManager.Mode.EXHENTAI_PREFERRED,
				title = stringResource(R.string.ehentai_mode_exhentai),
				summary = stringResource(R.string.ehentai_mode_exhentai_summary),
				onClick = { onModeChanged(EhentaiSessionManager.Mode.EXHENTAI_PREFERRED) },
			)
		}
		item {
			Text(
				text = stringResource(R.string.ehentai_credentials_title),
				style = MaterialTheme.typography.titleMedium,
			)
		}
		item {
			OutlinedTextField(
				value = memberId,
				onValueChange = onMemberIdChanged,
				modifier = Modifier.fillMaxWidth(),
				label = { Text(stringResource(R.string.ehentai_ipb_member_id)) },
				singleLine = true,
				visualTransformation = PasswordVisualTransformation(),
			)
		}
		item {
			OutlinedTextField(
				value = passHash,
				onValueChange = onPassHashChanged,
				modifier = Modifier.fillMaxWidth(),
				label = { Text(stringResource(R.string.ehentai_ipb_pass_hash)) },
				singleLine = true,
				visualTransformation = PasswordVisualTransformation(),
			)
		}
		item {
			OutlinedTextField(
				value = igneous,
				onValueChange = onIgneousChanged,
				modifier = Modifier.fillMaxWidth(),
				label = { Text(stringResource(R.string.ehentai_igneous)) },
				singleLine = true,
				visualTransformation = PasswordVisualTransformation(),
			)
		}
		item {
			Text(
				text = stringResource(R.string.ehentai_credentials_hint),
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
		}
		item {
			val status = when {
				mode == EhentaiSessionManager.Mode.EHENTAI_ONLY -> R.string.ehentai_status_public
				complete -> R.string.ehentai_status_exhentai
				mode == EhentaiSessionManager.Mode.EXHENTAI_PREFERRED -> R.string.ehentai_status_incomplete
				else -> R.string.ehentai_status_public
			}
			Text(
				text = stringResource(status),
				style = MaterialTheme.typography.bodyMedium,
				color = MaterialTheme.colorScheme.primary,
			)
		}
		item {
			Row(
				modifier = Modifier.fillMaxWidth(),
				horizontalArrangement = Arrangement.spacedBy(12.dp),
			) {
				Button(onClick = onSave, modifier = Modifier.weight(1f)) {
					Text(stringResource(R.string.ehentai_save))
				}
				OutlinedButton(onClick = onClear, modifier = Modifier.weight(1f)) {
					Text(stringResource(R.string.ehentai_clear))
				}
			}
		}
	}
}

@Composable
private fun ModeRow(
	selected: Boolean,
	title: String,
	summary: String,
	onClick: () -> Unit,
) {
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.clickable(onClick = onClick)
			.padding(vertical = 4.dp),
		verticalAlignment = Alignment.Top,
	) {
		RadioButton(selected = selected, onClick = onClick)
		Column(modifier = Modifier.padding(start = 8.dp)) {
			Text(text = title, style = MaterialTheme.typography.bodyLarge)
			Text(
				text = summary,
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
		}
	}
}
