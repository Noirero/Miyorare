package org.koitharu.kotatsu.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.download.ui.worker.DownloadPerformanceSettings
import org.koitharu.kotatsu.settings.compose.ListSettingsItem
import org.koitharu.kotatsu.settings.compose.PlainInfoSettingsItem
import org.koitharu.kotatsu.settings.compose.SettingsGroup
import org.koitharu.kotatsu.settings.compose.SliderSettingsItem
import org.koitharu.kotatsu.settings.compose.rememberStringPref

private enum class DownloadPerformanceMode(
	val parallelPages: Int?,
	val parallelDownloads: Int?,
) {
	STABLE(4, 2),
	BALANCED(8, 3),
	FAST(10, 4),
	CUSTOM(null, null),
	;

	companion object {
		fun fromLimits(pages: Int, downloads: Int): DownloadPerformanceMode =
			entries.firstOrNull { it.parallelPages == pages && it.parallelDownloads == downloads } ?: CUSTOM
	}
}

@Composable
internal fun DownloadPerformanceSettingsSection() {
	var parallelPagesRaw by rememberStringPref(
		DownloadPerformanceSettings.KEY_PARALLEL_PAGES,
		DownloadPerformanceSettings.DEFAULT_PARALLEL_PAGES.toString(),
	)
	var parallelDownloadsRaw by rememberStringPref(
		DownloadPerformanceSettings.KEY_PARALLEL_SOURCES,
		DownloadPerformanceSettings.DEFAULT_PARALLEL_SOURCES.toString(),
	)

	val parallelPages = parallelPagesRaw.toIntOrNull()
		?.coerceIn(
			DownloadPerformanceSettings.MIN_PARALLEL_PAGES,
			DownloadPerformanceSettings.MAX_PARALLEL_PAGES,
		)
		?: DownloadPerformanceSettings.DEFAULT_PARALLEL_PAGES
	val parallelDownloads = parallelDownloadsRaw.toIntOrNull()
		?.coerceIn(
			DownloadPerformanceSettings.MIN_PARALLEL_SOURCES,
			DownloadPerformanceSettings.MAX_PARALLEL_SOURCES,
		)
		?: DownloadPerformanceSettings.DEFAULT_PARALLEL_SOURCES

	val inferredMode = DownloadPerformanceMode.fromLimits(parallelPages, parallelDownloads)
	var storedModeName by rememberStringPref(KEY_DOWNLOAD_PERFORMANCE_MODE, inferredMode.name)
	val storedMode = DownloadPerformanceMode.entries.firstOrNull { it.name == storedModeName }
	val mode = when {
		storedMode == null -> inferredMode
		storedMode == DownloadPerformanceMode.CUSTOM -> storedMode
		storedMode.parallelPages == parallelPages && storedMode.parallelDownloads == parallelDownloads -> storedMode
		else -> DownloadPerformanceMode.CUSTOM
	}

	val modeEntries = listOf(
		stringResource(R.string.download_performance_stable),
		stringResource(R.string.download_performance_balanced),
		stringResource(R.string.download_performance_fast),
		stringResource(R.string.download_performance_custom),
	)
	val modeValues = remember { DownloadPerformanceMode.entries.map { it.name } }

	Column {
		SettingsGroup(title = stringResource(R.string.download_performance)) {
			item { pos ->
				ListSettingsItem(
					title = stringResource(R.string.download_performance_mode),
					entries = modeEntries,
					entryValues = modeValues,
					selectedValue = mode.name,
					onValueChange = { value ->
						val selected = DownloadPerformanceMode.entries.firstOrNull { it.name == value }
						if (selected != null) {
							storedModeName = selected.name
							selected.parallelPages?.let { parallelPagesRaw = it.toString() }
							selected.parallelDownloads?.let { parallelDownloadsRaw = it.toString() }
						}
					},
					icon = R.drawable.ic_download,
					shape = pos.shape,
				)
			}

			if (mode == DownloadPerformanceMode.CUSTOM) {
				item { pos ->
					SliderSettingsItem(
						title = stringResource(R.string.parallel_downloads),
						value = parallelDownloads,
						valueFrom = DownloadPerformanceSettings.MIN_PARALLEL_SOURCES,
						valueTo = DownloadPerformanceSettings.MAX_PARALLEL_SOURCES,
						stepSize = 1,
						onValueChange = { value ->
							storedModeName = DownloadPerformanceMode.CUSTOM.name
							parallelDownloadsRaw = value.toString()
						},
						shape = pos.shape,
					)
				}
				item { pos ->
					SliderSettingsItem(
						title = stringResource(R.string.parallel_pages_per_download),
						value = parallelPages,
						valueFrom = DownloadPerformanceSettings.MIN_PARALLEL_PAGES,
						valueTo = DownloadPerformanceSettings.MAX_PARALLEL_PAGES,
						stepSize = 1,
						onValueChange = { value ->
							storedModeName = DownloadPerformanceMode.CUSTOM.name
							parallelPagesRaw = value.toString()
						},
						shape = pos.shape,
					)
				}
			}
		}

		PlainInfoSettingsItem(
			text = stringResource(R.string.download_performance_info),
			icon = R.drawable.ic_info_outline,
		)

		if (mode == DownloadPerformanceMode.CUSTOM) {
			val possibleRequests = parallelPages * parallelDownloads
			when {
				possibleRequests > HIGH_CONCURRENCY_THRESHOLD -> PlainInfoSettingsItem(
					text = stringResource(R.string.download_performance_warning_high, possibleRequests),
					icon = R.drawable.ic_alert_outline,
				)
				possibleRequests > SAFE_CONCURRENCY_THRESHOLD -> PlainInfoSettingsItem(
					text = stringResource(R.string.download_performance_warning_moderate, possibleRequests),
					icon = R.drawable.ic_alert_outline,
				)
			}
		}
	}
}

private const val KEY_DOWNLOAD_PERFORMANCE_MODE = "downloads_performance_mode"
private const val SAFE_CONCURRENCY_THRESHOLD = 32
private const val HIGH_CONCURRENCY_THRESHOLD = 60
