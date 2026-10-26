package org.koitharu.kotatsu.discovery.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.discovery.domain.CatalogAccountState
import org.koitharu.kotatsu.discovery.domain.CatalogTrackingEntry
import org.koitharu.kotatsu.discovery.domain.CatalogTrackingState
import org.koitharu.kotatsu.discovery.domain.TrackerCatalogItem
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblingStatus
import java.net.URI
import java.util.Locale

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
	onAdd: () -> Unit,
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
				tracking.entry == null -> {
					Text(
						if (tracking.failed) stringResource(R.string.hub_tracking_unavailable) else stringResource(R.string.hub_not_in_list),
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
					Button(
						onClick = onAdd,
						enabled = !tracking.adding,
						modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("catalog_add_to_list"),
					) {
						if (tracking.adding) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
						else Text(stringResource(R.string.hub_add_to_list))
					}
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
						onClick = onManage,
						modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("catalog_manage_tracking"),
					) { Text(stringResource(R.string.hub_manage_tracking)) }
				}
			}
		}
	}
}

@Composable
private fun CatalogPersonalInfoRow(label: String, value: String) {
	Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
		Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
		Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
	}
}

private fun catalogStatusLabel(status: ScrobblingStatus): Int = when (status) {
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
