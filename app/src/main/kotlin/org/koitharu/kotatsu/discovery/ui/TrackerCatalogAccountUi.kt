package org.koitharu.kotatsu.discovery.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.discovery.domain.CatalogAccountState
import org.koitharu.kotatsu.discovery.domain.CatalogTrackingDraft
import org.koitharu.kotatsu.discovery.domain.CatalogTrackingEditorState
import org.koitharu.kotatsu.discovery.domain.CatalogTrackingEntry
import org.koitharu.kotatsu.discovery.domain.CatalogTrackingMutationFailure
import org.koitharu.kotatsu.discovery.domain.CatalogTrackingState
import org.koitharu.kotatsu.discovery.domain.TrackerCatalogItem
import org.koitharu.kotatsu.discovery.domain.catalogSupportedTrackingStatuses
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblingStatus
import java.net.URI
import java.util.Locale
import kotlin.math.roundToInt

@Composable
internal fun CatalogAccountBar(
	account: CatalogAccountState,
	imageLoader: ImageLoader,
	onLogin: () -> Unit,
	onProfile: () -> Unit,
	onMyList: () -> Unit,
	onManage: () -> Unit,
	onLogout: () -> Unit,
	modifier: Modifier = Modifier,
) {
	var expanded by remember(account.service, account.authorized) { mutableStateOf(false) }
	Surface(
		modifier = modifier.fillMaxWidth().testTag("catalog_account"),
		shape = RoundedCornerShape(18.dp),
		color = MaterialTheme.colorScheme.surfaceContainer,
	) {
		Row(
			Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(10.dp),
		) {
			CatalogAccountAvatar(account, imageLoader)
			Column(Modifier.weight(1f)) {
				Text(catalogProviderName(account.service), style = MaterialTheme.typography.labelLarge)
				Text(
					when {
						!account.authorized -> stringResource(R.string.hub_account_guest)
						account.user != null -> account.user.nickname
						account.loading -> stringResource(R.string.hub_account_loading)
						else -> stringResource(R.string.hub_account_connected)
					},
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
				)
			}
			if (!account.authorized) {
				OutlinedButton(
					onClick = onLogin,
					modifier = Modifier.heightIn(min = 48.dp).testTag("catalog_account_login"),
				) { Text(stringResource(R.string.hub_account_login)) }
			} else {
				Box {
					OutlinedButton(
						onClick = { expanded = true },
						modifier = Modifier.heightIn(min = 48.dp).testTag("catalog_account_menu"),
					) {
						if (account.loading) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
						else Icon(painterResource(R.drawable.ic_check), null, Modifier.size(16.dp))
						Spacer(Modifier.size(6.dp))
						Text(stringResource(R.string.hub_account_connected))
					}
					DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
						if (account.profileUrl != null) {
							DropdownMenuItem(
								text = { Text(stringResource(R.string.hub_account_profile)) },
								onClick = { expanded = false; onProfile() },
								modifier = Modifier.testTag("catalog_account_profile"),
							)
						}
						DropdownMenuItem(
							text = { Text(stringResource(R.string.hub_account_my_list)) },
							onClick = { expanded = false; onMyList() },
							modifier = Modifier.testTag("catalog_account_list"),
						)
						DropdownMenuItem(
							text = { Text(stringResource(R.string.hub_account_manage)) },
							onClick = { expanded = false; onManage() },
							modifier = Modifier.testTag("catalog_account_manage"),
						)
						DropdownMenuItem(
							text = { Text(stringResource(R.string.hub_account_logout)) },
							onClick = { expanded = false; onLogout() },
							modifier = Modifier.testTag("catalog_account_logout"),
						)
					}
				}
			}
		}
	}
}

@Composable
private fun CatalogAccountAvatar(account: CatalogAccountState, imageLoader: ImageLoader) {
	val avatar = safeHttps(account.user?.avatar)
	val modifier = Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest)
	if (avatar != null) {
		AsyncImage(
			model = avatar,
			imageLoader = imageLoader,
			contentDescription = null,
			contentScale = ContentScale.Crop,
			modifier = modifier.testTag("catalog_account_avatar"),
		)
	} else {
		Box(modifier, contentAlignment = Alignment.Center) {
			Icon(painterResource(R.drawable.ic_account_circle), null, Modifier.size(30.dp))
		}
	}
}

@Composable
internal fun CatalogTrackedCard(
	item: TrackerCatalogItem,
	imageLoader: ImageLoader,
	tracking: CatalogTrackingEntry?,
	onClick: () -> Unit,
	modifier: Modifier = Modifier,
	ordinal: Int? = null,
) {
	Box(modifier) {
		CatalogCard(item, imageLoader, onClick, Modifier.fillMaxWidth(), ordinal)
		tracking?.let {
			CatalogTrackingBadge(
				entry = it,
				modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
			)
		}
	}
}

@Composable
internal fun CatalogTrackingBadge(entry: CatalogTrackingEntry, modifier: Modifier = Modifier) {
	Surface(
		modifier = modifier.testTag("catalog_tracking_badge_${entry.targetId}"),
		shape = RoundedCornerShape(50),
		color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.94f),
		contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
	) {
		Text(
			text = entry.status?.let { stringResource(catalogStatusLabel(it)) }
				?: stringResource(R.string.hub_account_in_list),
			modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
			style = MaterialTheme.typography.labelSmall,
			fontWeight = FontWeight.SemiBold,
			maxLines = 1,
		)
	}
}

@Composable
internal fun CatalogTrackingCard(
	account: CatalogAccountState,
	tracking: CatalogTrackingState,
	onLogin: () -> Unit,
	onEdit: () -> Unit,
	onManage: () -> Unit,
	modifier: Modifier = Modifier,
) {
	Surface(
		modifier = modifier.fillMaxWidth().testTag("catalog_tracking_card"),
		shape = RoundedCornerShape(18.dp),
		color = MaterialTheme.colorScheme.surfaceContainer,
	) {
		Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
			Text(
				stringResource(R.string.hub_track_with, catalogProviderName(account.service)),
				style = MaterialTheme.typography.titleMedium,
			)
			when {
				!account.authorized -> {
					Text(stringResource(R.string.hub_login_to_track), color = MaterialTheme.colorScheme.onSurfaceVariant)
					Button(onClick = onLogin, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("catalog_tracking_login")) {
						Text(stringResource(R.string.hub_account_login))
					}
				}
				tracking.loading -> {
					Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
						CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
						Text(stringResource(R.string.hub_tracking_loading), color = MaterialTheme.colorScheme.onSurfaceVariant)
					}
				}
				tracking.failed -> {
					Text(stringResource(R.string.hub_tracking_unavailable), color = MaterialTheme.colorScheme.onSurfaceVariant)
					OutlinedButton(
						onClick = onManage,
						modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("catalog_manage_tracking"),
					) { Text(stringResource(R.string.hub_manage_tracking)) }
				}
				tracking.entry == null -> {
					Text(stringResource(R.string.hub_not_in_list), color = MaterialTheme.colorScheme.onSurfaceVariant)
					Button(
						onClick = onEdit,
						modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("catalog_add_to_list"),
					) { Text(stringResource(R.string.hub_add_to_list)) }
				}
				else -> {
					val entry = tracking.entry
					entry.status?.let { CatalogPersonalInfoRow(stringResource(R.string.hub_tracking_status), stringResource(catalogStatusLabel(it))) }
					CatalogPersonalInfoRow(stringResource(R.string.hub_tracking_progress), entry.chapter.toString())
					CatalogPersonalInfoRow(
						stringResource(R.string.hub_tracking_score),
						if (entry.rating > 0f) String.format(Locale.getDefault(), "%.1f/10", entry.rating * 10f)
						else stringResource(R.string.hub_tracking_unrated),
					)
					OutlinedButton(
						onClick = onEdit,
						modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("catalog_edit_tracking"),
					) { Text(stringResource(R.string.hub_tracking_edit)) }
				}
			}
		}
	}
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun CatalogTrackingEditorSheet(
	account: CatalogAccountState,
	editor: CatalogTrackingEditorState,
	onDismiss: () -> Unit,
	onSave: (CatalogTrackingDraft) -> Unit,
	onReconnect: () -> Unit,
	onOpenProvider: () -> Unit,
	onRemove: () -> Unit,
) {
	if (!editor.visible) return
	val supportedStatuses = catalogSupportedTrackingStatuses(account.service)
	val initialStatus = editor.existing?.status?.takeIf { it in supportedStatuses } ?: ScrobblingStatus.PLANNED
	var status by remember(editor.existing?.targetId, editor.existing?.status) { mutableStateOf(initialStatus) }
	var chapterText by remember(editor.existing?.targetId, editor.existing?.chapter) {
		mutableStateOf((editor.existing?.chapter ?: 0).toString())
	}
	var scoreOutOfTen by remember(editor.existing?.targetId, editor.existing?.rating) {
		mutableStateOf((editor.existing?.rating ?: 0f) * 10f)
	}
	var menuExpanded by remember { mutableStateOf(false) }
	var confirmRemove by remember { mutableStateOf(false) }
	val chapter = chapterText.toIntOrNull()?.takeIf { it >= 0 }
	val busy = editor.saving || editor.removing
	fun draftOrNull(): CatalogTrackingDraft? = chapter?.let {
		CatalogTrackingDraft(status = status, chapter = it, rating = (scoreOutOfTen / 10f).coerceIn(0f, 1f))
	}

	ModalBottomSheet(
		onDismissRequest = { if (!busy) onDismiss() },
		modifier = Modifier.testTag("catalog_tracking_editor"),
	) {
		Column(
			Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
			verticalArrangement = Arrangement.spacedBy(16.dp),
		) {
			Row(verticalAlignment = Alignment.CenterVertically) {
				Column(Modifier.weight(1f)) {
					Text(
						stringResource(
							if (editor.existing == null) R.string.hub_tracking_editor_add_title else R.string.hub_tracking_editor_edit_title,
							catalogProviderName(account.service),
						),
						style = MaterialTheme.typography.titleLarge,
					)
					Text(
						stringResource(R.string.hub_tracking_editor_remote_hint),
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
				}
				Box {
					TextButton(onClick = { menuExpanded = true }, enabled = !busy, modifier = Modifier.testTag("catalog_tracking_editor_menu")) {
						Text("⋮", style = MaterialTheme.typography.headlineSmall)
					}
					DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
						DropdownMenuItem(
							text = { Text(stringResource(R.string.hub_tracking_editor_open_provider, catalogProviderName(account.service))) },
							onClick = { menuExpanded = false; onOpenProvider() },
						)
						if (editor.existing != null) {
							DropdownMenuItem(
								text = { Text(stringResource(R.string.hub_tracking_editor_remove)) },
								onClick = { menuExpanded = false; confirmRemove = true },
								modifier = Modifier.testTag("catalog_tracking_editor_remove"),
							)
						}
					}
				}
			}

			Text(stringResource(R.string.hub_tracking_editor_status), style = MaterialTheme.typography.titleSmall)
			FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
				supportedStatuses.forEach { candidate ->
					FilterChip(
						selected = status == candidate,
						onClick = { if (!busy) status = candidate },
						label = { Text(stringResource(catalogStatusLabel(candidate))) },
						enabled = !busy,
						modifier = Modifier.testTag("catalog_tracking_status_${candidate.name.lowercase(Locale.ROOT)}"),
					)
				}
			}

			OutlinedTextField(
				value = chapterText,
				onValueChange = { value -> if (value.length <= 7 && value.all(Char::isDigit)) chapterText = value },
				label = { Text(stringResource(R.string.hub_tracking_editor_progress)) },
				supportingText = {
					if (chapter == null) Text(stringResource(R.string.hub_tracking_editor_progress_invalid))
				},
				isError = chapter == null,
				enabled = !busy,
				keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
				singleLine = true,
				modifier = Modifier.fillMaxWidth().testTag("catalog_tracking_editor_progress"),
			)

			Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
				Text(stringResource(R.string.hub_tracking_editor_score), style = MaterialTheme.typography.titleSmall)
				Text(
					if (scoreOutOfTen <= 0f) stringResource(R.string.hub_tracking_editor_not_rated)
					else String.format(Locale.getDefault(), "★ %.1f/10", scoreOutOfTen),
					style = MaterialTheme.typography.bodyMedium,
					fontWeight = FontWeight.SemiBold,
				)
				Slider(
					value = scoreOutOfTen,
					onValueChange = { raw ->
						val quantized = if (account.service == ScrobblerService.MAL) raw.roundToInt().toFloat() else (raw * 2f).roundToInt() / 2f
						scoreOutOfTen = if (account.service == ScrobblerService.KITSU && quantized in 0.1f..0.9f) 1f else quantized
					},
					valueRange = 0f..10f,
					steps = if (account.service == ScrobblerService.MAL) 9 else 19,
					enabled = !busy,
					modifier = Modifier.testTag("catalog_tracking_editor_score"),
				)
			}

			when (editor.failure) {
				CatalogTrackingMutationFailure.AUTH_EXPIRED -> {
					Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.errorContainer) {
						Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
							Text(stringResource(R.string.hub_tracking_editor_session_expired), color = MaterialTheme.colorScheme.onErrorContainer)
							OutlinedButton(onClick = onReconnect, enabled = !busy) {
								Text(stringResource(R.string.hub_tracking_editor_reconnect))
							}
						}
					}
				}
				CatalogTrackingMutationFailure.PROVIDER -> {
					Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.errorContainer) {
						Row(
							Modifier.fillMaxWidth().padding(12.dp),
							verticalAlignment = Alignment.CenterVertically,
							horizontalArrangement = Arrangement.spacedBy(8.dp),
						) {
							Text(
								stringResource(R.string.hub_tracking_editor_provider_error, catalogProviderName(account.service)),
								color = MaterialTheme.colorScheme.onErrorContainer,
								modifier = Modifier.weight(1f),
							)
							TextButton(onClick = { draftOrNull()?.let(onSave) }, enabled = !busy && draftOrNull() != null) {
								Text(stringResource(R.string.retry))
							}
						}
					}
				}
				null -> Unit
			}

			Button(
				onClick = { draftOrNull()?.let(onSave) },
				enabled = !busy && draftOrNull() != null,
				modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("catalog_tracking_editor_save"),
			) {
				if (editor.saving) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
				else Text(stringResource(if (editor.existing == null) R.string.hub_tracking_editor_add else R.string.hub_tracking_editor_save))
			}
			Spacer(Modifier.size(8.dp))
		}
	}

	if (confirmRemove) {
		AlertDialog(
			onDismissRequest = { if (!busy) confirmRemove = false },
			title = { Text(stringResource(R.string.hub_tracking_editor_remove_title)) },
			text = { Text(stringResource(R.string.hub_tracking_editor_remove_message, catalogProviderName(account.service))) },
			confirmButton = {
				Button(
					onClick = { confirmRemove = false; onRemove() },
					enabled = !busy,
					modifier = Modifier.testTag("catalog_tracking_editor_remove_confirm"),
				) { Text(stringResource(R.string.hub_tracking_editor_remove)) }
			},
			dismissButton = {
				TextButton(onClick = { confirmRemove = false }, enabled = !busy) { Text(stringResource(android.R.string.cancel)) }
			},
		)
	}
}

@Composable
private fun CatalogPersonalInfoRow(label: String, value: String) {
	Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
		Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
		Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
	}
}

internal fun catalogStatusLabel(status: ScrobblingStatus): Int = when (status) {
	ScrobblingStatus.PLANNED -> R.string.status_planned
	ScrobblingStatus.READING -> R.string.status_reading
	ScrobblingStatus.RE_READING -> R.string.status_re_reading
	ScrobblingStatus.COMPLETED -> R.string.status_completed
	ScrobblingStatus.ON_HOLD -> R.string.status_on_hold
	ScrobblingStatus.DROPPED -> R.string.status_dropped
}

private fun safeHttps(value: String?): String? = value?.let {
	runCatching { URI(it) }.getOrNull()?.takeIf { uri ->
		uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null
	}?.toString()
}
