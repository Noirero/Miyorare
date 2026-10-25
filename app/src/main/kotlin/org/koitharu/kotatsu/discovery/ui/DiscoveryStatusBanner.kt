package org.koitharu.kotatsu.discovery.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.ui.LocalMiyorareVisualPalette
import org.koitharu.kotatsu.core.ui.miyorareSurface
import org.koitharu.kotatsu.discovery.domain.CatalogFailure
import org.koitharu.kotatsu.discovery.domain.CatalogFailureKind
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService
import java.text.DateFormat
import java.util.Date

@Composable
fun CatalogStatusBanner(
	status: DiscoveryHomeStatus,
	failure: CatalogFailure?,
	service: ScrobblerService,
	onRetry: () -> Unit,
	modifier: Modifier = Modifier,
) {
	val palette = LocalMiyorareVisualPalette.current
	val shape = RoundedCornerShape(16.dp)
	Surface(
		modifier = modifier.fillMaxWidth().miyorareSurface(palette, shape).testTag("catalog_status_${status.name}"),
		shape = shape,
		color = if (palette.isModern) Color.Transparent else MaterialTheme.colorScheme.surfaceContainer,
	) {
		Row(
			Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
			verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(8.dp),
		) {
			Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
				when (status) {
					DiscoveryHomeStatus.CACHED_REFRESHING -> Text(
						stringResource(R.string.hub_cached_refreshing),
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
					DiscoveryHomeStatus.CACHED_ERROR -> {
						failure?.let { Text(cachedFailureText(it, service), style = MaterialTheme.typography.bodySmall) }
						Text(
							stringResource(R.string.hub_stale, catalogProviderName(service)),
							style = MaterialTheme.typography.labelSmall,
							color = MaterialTheme.colorScheme.onSurfaceVariant,
						)
						failure?.retryAt?.let {
							Text(
								stringResource(R.string.hub_retry_at, DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it))),
								style = MaterialTheme.typography.labelSmall,
								color = MaterialTheme.colorScheme.onSurfaceVariant,
							)
						}
					}
					DiscoveryHomeStatus.PARTIAL -> Text(
						stringResource(R.string.hub_partial_update),
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
					else -> Unit
				}
			}
			if (status == DiscoveryHomeStatus.CACHED_ERROR || status == DiscoveryHomeStatus.PARTIAL) {
				TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) {
					Text(stringResource(R.string.hub_retry))
				}
			}
		}
	}
}

@Composable
private fun cachedFailureText(failure: CatalogFailure, service: ScrobblerService): String = when (failure.kind) {
	CatalogFailureKind.OFFLINE -> stringResource(R.string.hub_offline_cached)
	CatalogFailureKind.RATE_LIMITED -> stringResource(R.string.hub_rate_limited, catalogProviderName(service))
	CatalogFailureKind.INVALID_DATA -> stringResource(R.string.hub_invalid_data, catalogProviderName(service))
	CatalogFailureKind.UNSUPPORTED -> stringResource(R.string.hub_unsupported)
	CatalogFailureKind.PROVIDER -> stringResource(R.string.hub_provider_error, catalogProviderName(service))
}
