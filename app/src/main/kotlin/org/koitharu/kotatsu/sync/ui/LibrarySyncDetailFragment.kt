package org.koitharu.kotatsu.sync.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.fragment.app.viewModels
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.db.entity.MangaEntity
import org.koitharu.kotatsu.settings.compose.BaseComposeSettingsFragment
import org.koitharu.kotatsu.settings.compose.MiyorareTheme
import org.koitharu.kotatsu.settings.compose.SwitchSettingsItem
import org.koitharu.kotatsu.sync.library.*

@AndroidEntryPoint
class LibrarySyncDetailFragment : BaseComposeSettingsFragment(0) {
	private val viewModel: LibrarySyncViewModel by viewModels()
	private val serviceId: LibrarySyncServiceId
		get() = LibrarySyncServiceId.valueOf(requireArguments().getString("service")!!)

	override fun onResume() {
		super.onResume()
		activity?.setTitle(serviceTitle(serviceId))
		viewModel.refresh()
	}

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?,
	): View =
		ComposeView(requireContext()).apply {
			setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
			setContent {
				MiyorareTheme {
					val rows by viewModel.services.collectAsState()
					val busy by viewModel.busy.collectAsState()
					val message by viewModel.message.collectAsState()
					val row = rows.firstOrNull { it.id == serviceId }
					var showLogin by remember { mutableStateOf(false) }
					var showPushLink by remember { mutableStateOf(false) }
					var importEntry by remember { mutableStateOf<SyncEntry?>(null) }
					var editEntry by remember { mutableStateOf<SyncEntry?>(null) }
					LazyColumn(
						contentPadding = PaddingValues(16.dp),
						verticalArrangement = Arrangement.spacedBy(12.dp),
					) {
						if (row == null) item { CircularProgressIndicator() }
						else if (row.status == LibrarySyncConnectionStatus.BLOCKED) {
							item { Text(stringResource(R.string.library_sync_blocked)) }
							item { Text(row.reason.orEmpty()) }
						} else {
							item {
								Text(
									stringResource(
										R.string.library_sync_status,
										statusText(row.status, row.running),
									)
								)
							}
							item {
								Text(
									stringResource(
										R.string.library_sync_last,
										row.lastSync?.let(::formatSyncTime)
											?: stringResource(R.string.library_sync_never),
									)
								)
							}
							item {
								Button(
									enabled = !busy && !row.running,
									onClick = {
										if (row.status == LibrarySyncConnectionStatus.DISCONNECTED)
											showLogin = true
										else viewModel.logout(serviceId)
									},
								) {
									Text(
										stringResource(
											if (
												row.status ==
													LibrarySyncConnectionStatus.DISCONNECTED
											)
												R.string.library_sync_login
											else R.string.library_sync_logout
										)
									)
								}
							}
							item {
								SwitchSettingsItem(
									title = stringResource(R.string.library_sync_pull),
									checked = LibrarySyncDirection.PULL in row.directions,
									enabled = !busy && !row.running,
									onCheckedChange = {
										viewModel.direction(
											serviceId,
											LibrarySyncDirection.PULL,
											it,
										)
									},
								)
							}
							item {
								SwitchSettingsItem(
									title = stringResource(R.string.library_sync_push),
									checked = LibrarySyncDirection.PUSH in row.directions,
									enabled = !busy && !row.running,
									onCheckedChange = {
										viewModel.direction(
											serviceId,
											LibrarySyncDirection.PUSH,
											it,
										)
									},
								)
							}
							item { Text(stringResource(R.string.library_sync_periodic_hint)) }
							item {
								Button(
									enabled =
										!busy &&
											!row.running &&
											row.status !=
												LibrarySyncConnectionStatus.DISCONNECTED &&
											row.directions.isNotEmpty(),
									onClick = { viewModel.sync(serviceId) },
								) {
									Text(stringResource(R.string.library_sync_now))
								}
							}
							row.error?.let { error -> item { Text(error) } }
							message?.let { error -> item { Text(error) } }
							if (busy) item { CircularProgressIndicator() }
							if (row.status != LibrarySyncConnectionStatus.DISCONNECTED) {
								item { Text(stringResource(R.string.library_sync_mappings)) }
								item {
									TextButton(
										enabled = !busy && !row.running,
										onClick = {
											viewModel.resetLookup()
											showPushLink = true
										},
									) {
										Text(stringResource(R.string.library_sync_link))
									}
								}
								items(row.linked, key = { "linked:${it.externalId}" }) { entry ->
									Column {
										Text(
											"${entry.title} · ${entry.progress} · ${entry.status.orEmpty()}"
										)
										TextButton(
											enabled = !busy && !row.running,
											onClick = { editEntry = entry },
										) {
											Text(stringResource(R.string.library_sync_edit))
										}
										TextButton(
											enabled = !busy && !row.running,
											onClick = {
												viewModel.unlink(
													serviceId,
													requireNotNull(entry.localMangaId),
												)
											},
										) {
											Text(stringResource(R.string.library_sync_unlink))
										}
									}
								}
								if (LibrarySyncDirection.PULL in row.directions) {
									item {
										Text(
											stringResource(
												R.string.library_sync_inbox,
												row.pending.size,
											)
										)
									}
									item { Text(stringResource(R.string.library_sync_import_hint)) }
									items(row.pending, key = { "pending:${it.externalId}" }) { entry
										->
										TextButton(
											enabled = !busy && !row.running,
											onClick = {
												viewModel.search(entry.title)
												importEntry = entry
											},
										) {
											Text("${entry.title} · ${entry.progress}")
										}
									}
								}
							}
						}
					}
					if (showLogin)
						LoginDialog(
							serviceId,
							busy,
							message,
							onDismiss = { showLogin = false },
							onOpenAuth = { clientId ->
								val uri =
									Uri.parse("https://anilist.co/api/v2/oauth/authorize")
										.buildUpon()
										.appendQueryParameter("client_id", clientId)
										.appendQueryParameter("response_type", "token")
										.build()
								startActivity(Intent(Intent.ACTION_VIEW, uri))
							},
							onLogin = { credentials ->
								viewModel.login(serviceId, credentials)
								showLogin = false
							},
						)
					importEntry?.let { entry ->
						ImportDialog(
							entry,
							viewModel,
							busy,
							onDismiss = { importEntry = null },
							onConfirm = { localId, categoryId ->
								viewModel.import(entry, localId, categoryId)
								importEntry = null
							},
						)
					}
					if (showPushLink)
						PushLinkDialog(
							serviceId,
							viewModel,
							busy,
							onDismiss = { showPushLink = false },
							onConfirm = { localId, externalId ->
								viewModel.link(serviceId, localId, externalId)
								showPushLink = false
							},
						)
					editEntry?.let { entry ->
						EditDialog(
							entry,
							onDismiss = { editEntry = null },
							onSave = { progress, status ->
								viewModel.edit(entry, progress, status)
								editEntry = null
							},
						)
					}
				}
			}
		}
}

@Composable
private fun LoginDialog(
	id: LibrarySyncServiceId,
	busy: Boolean,
	error: String?,
	onDismiss: () -> Unit,
	onOpenAuth: (String) -> Unit,
	onLogin: (LibrarySyncCredentials) -> Unit,
) {
	var username by remember { mutableStateOf("") }
	var password by remember { mutableStateOf("") }
	var token by remember { mutableStateOf("") }
	var clientId by remember { mutableStateOf("") }
	AlertDialog(
		onDismissRequest = onDismiss,
		title = { Text(stringResource(R.string.library_sync_login)) },
		text = {
			Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
				if (id == LibrarySyncServiceId.ANILIST) {
					Text(stringResource(R.string.library_sync_anilist_auth_hint))
					OutlinedTextField(
						value = clientId,
						onValueChange = { clientId = it },
						label = { Text("Client ID") },
					)
					TextButton(
						enabled = clientId.toLongOrNull()?.let { it > 0 } == true,
						onClick = { onOpenAuth(clientId.trim()) },
					) {
						Text(stringResource(R.string.library_sync_authorize))
					}
					OutlinedTextField(
						value = token,
						onValueChange = { token = it },
						label = { Text("Access token") },
						visualTransformation = PasswordVisualTransformation(),
						keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
						singleLine = true,
					)
				} else {
					OutlinedTextField(
						value = username,
						onValueChange = { username = it },
						label = { Text(stringResource(R.string.library_sync_username)) },
					)
					OutlinedTextField(
						value = password,
						onValueChange = { password = it },
						label = { Text(stringResource(R.string.library_sync_password)) },
						visualTransformation = PasswordVisualTransformation(),
						keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
						singleLine = true,
					)
				}
				error?.let { Text(it) }
			}
		},
		confirmButton = {
			TextButton(
				enabled =
					!busy &&
						(if (id == LibrarySyncServiceId.ANILIST) token.isNotBlank()
						else username.isNotBlank() && password.isNotBlank()),
				onClick = {
					onLogin(
						LibrarySyncCredentials(
							accessToken = token.takeIf { it.isNotBlank() },
							username = username,
							password = password,
						)
					)
				},
			) {
				Text(stringResource(R.string.library_sync_login))
			}
		},
		dismissButton = {
			TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
		},
	)
}

@Composable
private fun ImportDialog(
	entry: SyncEntry,
	vm: LibrarySyncViewModel,
	busy: Boolean,
	onDismiss: () -> Unit,
	onConfirm: (Long, Int) -> Unit,
) {
	val candidates by vm.candidates.collectAsState()
	val categories by vm.categories.collectAsState()
	var query by remember { mutableStateOf(entry.title) }
	var selected by remember { mutableStateOf<MangaEntity?>(null) }
	var category by remember { mutableStateOf<Int?>(null) }
	AlertDialog(
		onDismissRequest = onDismiss,
		title = { Text(stringResource(R.string.library_sync_confirm_import)) },
		text = {
			LazyColumn(
				modifier = Modifier.heightIn(max = 360.dp),
				verticalArrangement = Arrangement.spacedBy(8.dp),
			) {
				item { Text(entry.title) }
				item { Text(stringResource(R.string.library_sync_import_hint)) }
				item {
					OutlinedTextField(
						value = query,
						onValueChange = {
							query = it
							selected = null
							vm.search(it)
						},
						label = { Text(stringResource(R.string.library_sync_search_local)) },
					)
				}
				items(candidates, key = { it.id }) { manga ->
					TextButton(onClick = { selected = manga }) {
						Text(
							(if (selected?.id == manga.id) "✓ " else "") +
								manga.title +
								" · " +
								(manga.sourceTitle ?: manga.source)
						)
					}
				}
				item { Text(stringResource(R.string.library_sync_category)) }
				items(categories, key = { it.categoryId }) { cat ->
					TextButton(onClick = { category = cat.categoryId }) {
						Text((if (category == cat.categoryId) "✓ " else "") + cat.title)
					}
				}
			}
		},
		confirmButton = {
			TextButton(
				enabled = !busy && selected != null && category != null,
				onClick = { onConfirm(requireNotNull(selected).id, requireNotNull(category)) },
			) {
				Text(stringResource(R.string.library_sync_confirm_import))
			}
		},
		dismissButton = {
			TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
		},
	)
}

@Composable
private fun PushLinkDialog(
	id: LibrarySyncServiceId,
	vm: LibrarySyncViewModel,
	busy: Boolean,
	onDismiss: () -> Unit,
	onConfirm: (Long, String) -> Unit,
) {
	val favourites by vm.favourites.collectAsState()
	val result by vm.lookupResult.collectAsState()
	val suggested by vm.suggestedExternalId.collectAsState()
	val message by vm.message.collectAsState()
	var externalId by remember { mutableStateOf("") }
	var query by remember { mutableStateOf("") }
	var selected by remember { mutableStateOf<MangaEntity?>(null) }
	AlertDialog(
		onDismissRequest = onDismiss,
		title = { Text(stringResource(R.string.library_sync_link)) },
		text = {
			LazyColumn(
				modifier = Modifier.heightIn(max = 360.dp),
				verticalArrangement = Arrangement.spacedBy(8.dp),
			) {
				item {
					OutlinedTextField(
						value = externalId,
						onValueChange = {
							externalId = it
							vm.resetLookup()
						},
						label = { Text("External ID") },
					)
				}
				item {
					TextButton(
						enabled = !busy && externalId.toLongOrNull()?.let { it > 0 } == true,
						onClick = { vm.lookup(id, externalId) },
					) {
						Text(stringResource(R.string.library_sync_lookup))
					}
				}
				result?.let { entry -> item { Text(entry.title) } }
				message?.let { error -> item { Text(error) } }
				item {
					OutlinedTextField(
						value = query,
						onValueChange = { query = it },
						label = { Text(stringResource(R.string.library_sync_search_local)) },
					)
				}
				items(
					favourites.filter { it.title.contains(query, ignoreCase = true) },
					key = { it.id },
				) { manga ->
					TextButton(
						onClick = {
							selected = manga
							vm.suggestTrackingMapping(id, manga.id)
						}
					) {
						Text(
							(if (selected?.id == manga.id) "✓ " else "") +
								manga.title +
								" · " +
								(manga.sourceTitle ?: manga.source)
						)
					}
				}
				suggested?.let { value ->
					item {
						TextButton(
							onClick = {
								externalId = value
								vm.resetLookup()
							}
						) {
							Text(stringResource(R.string.library_sync_reuse_mapping, value))
						}
					}
				}
				item { Text(stringResource(R.string.library_sync_link_hint)) }
			}
		},
		confirmButton = {
			TextButton(
				enabled = !busy && selected != null && result?.externalId == externalId.trim(),
				onClick = {
					onConfirm(requireNotNull(selected).id, requireNotNull(result).externalId)
				},
			) {
				Text(stringResource(android.R.string.ok))
			}
		},
		dismissButton = {
			TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
		},
	)
}

@Composable
private fun EditDialog(entry: SyncEntry, onDismiss: () -> Unit, onSave: (Int, String) -> Unit) {
	var progress by remember { mutableStateOf(entry.progress.toString()) }
	var status by remember {
		mutableStateOf(entry.status ?: LibrarySyncEngine.plannedStatus(entry.service))
	}
	val statuses =
		if (entry.service == LibrarySyncServiceId.ANILIST) AniListLibrarySyncService.STATUSES
		else KitsuLibrarySyncService.STATUSES
	AlertDialog(
		onDismissRequest = onDismiss,
		title = { Text(entry.title) },
		text = {
			Column {
				OutlinedTextField(
					value = progress,
					onValueChange = { progress = it },
					label = { Text(stringResource(R.string.library_sync_progress)) },
				)
				statuses.forEach { value ->
					TextButton(onClick = { status = value }) {
						Text((if (status == value) "✓ " else "") + value)
					}
				}
			}
		},
		confirmButton = {
			TextButton(
				enabled = progress.toIntOrNull()?.let { it >= 0 } == true,
				onClick = { onSave(progress.toInt(), status) },
			) {
				Text(stringResource(android.R.string.ok))
			}
		},
		dismissButton = {
			TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
		},
	)
}
