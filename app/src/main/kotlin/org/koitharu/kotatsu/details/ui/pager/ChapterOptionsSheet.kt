package org.koitharu.kotatsu.details.ui.pager

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.koitharu.kotatsu.R

private enum class ChapterOptionsTab {
	FILTER,
	SORT,
	DISPLAY,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChapterOptionsSheet(
	options: ChapterListOptions,
	branches: List<String>,
	selectedBranch: String?,
	allowAllBranches: Boolean,
	scanlators: List<String>,
	selectedScanlator: String?,
	downloadedFilterAvailable: Boolean,
	onDismiss: () -> Unit,
	onDownloadedChange: (Boolean) -> Unit,
	onUnreadChange: (Boolean) -> Unit,
	onBookmarkedChange: (Boolean) -> Unit,
	onNewChange: (Boolean) -> Unit,
	onBranchChange: (String?) -> Unit,
	onScanlatorChange: (String?) -> Unit,
	onSortModeChange: (ChapterSortMode) -> Unit,
	onTitleModeChange: (ChapterTitleMode) -> Unit,
	onGridChange: (Boolean) -> Unit,
	onSetDefault: () -> Unit,
	onReset: () -> Unit,
) {
	var tab by remember { mutableStateOf(ChapterOptionsTab.FILTER) }
	var moreExpanded by remember { mutableStateOf(false) }
	val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)

	ModalBottomSheet(
		onDismissRequest = onDismiss,
		sheetState = sheetState,
	) {
		Column(
			modifier = Modifier
				.fillMaxWidth()
				.verticalScroll(rememberScrollState())
				.padding(bottom = 20.dp),
		) {
			Row(
				modifier = Modifier
					.fillMaxWidth()
					.padding(horizontal = 8.dp),
				verticalAlignment = Alignment.CenterVertically,
			) {
				ChapterOptionsTabButton(
					text = stringResource(R.string.chapter_options_filter),
					selected = tab == ChapterOptionsTab.FILTER,
					onClick = { tab = ChapterOptionsTab.FILTER },
					modifier = Modifier.weight(1f),
				)
				ChapterOptionsTabButton(
					text = stringResource(R.string.chapter_options_sort),
					selected = tab == ChapterOptionsTab.SORT,
					onClick = { tab = ChapterOptionsTab.SORT },
					modifier = Modifier.weight(1f),
				)
				ChapterOptionsTabButton(
					text = stringResource(R.string.chapter_options_display),
					selected = tab == ChapterOptionsTab.DISPLAY,
					onClick = { tab = ChapterOptionsTab.DISPLAY },
					modifier = Modifier.weight(1f),
				)
				Box {
					IconButton(onClick = { moreExpanded = true }) {
						Icon(
							painter = painterResource(R.drawable.ic_more_vert),
							contentDescription = stringResource(R.string.more),
						)
					}
					DropdownMenu(
						expanded = moreExpanded,
						onDismissRequest = { moreExpanded = false },
					) {
						DropdownMenuItem(
							text = { Text(stringResource(R.string.chapter_options_set_default)) },
							onClick = {
								moreExpanded = false
								onSetDefault()
							},
						)
						DropdownMenuItem(
							text = { Text(stringResource(R.string.reset)) },
							onClick = {
								moreExpanded = false
								onReset()
							},
						)
					}
				}
			}
			HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

			when (tab) {
				ChapterOptionsTab.FILTER -> FilterTab(
					options = options,
					branches = branches,
					selectedBranch = selectedBranch,
					allowAllBranches = allowAllBranches,
					scanlators = scanlators,
					selectedScanlator = selectedScanlator,
					downloadedFilterAvailable = downloadedFilterAvailable,
					onDownloadedChange = onDownloadedChange,
					onUnreadChange = onUnreadChange,
					onBookmarkedChange = onBookmarkedChange,
					onNewChange = onNewChange,
					onBranchChange = onBranchChange,
					onScanlatorChange = onScanlatorChange,
				)
				ChapterOptionsTab.SORT -> SortTab(options, onSortModeChange)
				ChapterOptionsTab.DISPLAY -> DisplayTab(options, onTitleModeChange, onGridChange)
			}
		}
	}
}

@Composable
private fun ChapterOptionsTabButton(
	text: String,
	selected: Boolean,
	onClick: () -> Unit,
	modifier: Modifier = Modifier,
) {
	Column(
		modifier = modifier,
		horizontalAlignment = Alignment.CenterHorizontally,
	) {
		TextButton(onClick = onClick) {
			Text(
				text = text,
				color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
				fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
			)
		}
		Box(
			modifier = Modifier
				.width(56.dp)
				.height(3.dp)
				.background(
					if (selected) MaterialTheme.colorScheme.primary
					else MaterialTheme.colorScheme.surface.copy(alpha = 0f),
				),
		)
	}
}

@Composable
private fun FilterTab(
	options: ChapterListOptions,
	branches: List<String>,
	selectedBranch: String?,
	allowAllBranches: Boolean,
	scanlators: List<String>,
	selectedScanlator: String?,
	downloadedFilterAvailable: Boolean,
	onDownloadedChange: (Boolean) -> Unit,
	onUnreadChange: (Boolean) -> Unit,
	onBookmarkedChange: (Boolean) -> Unit,
	onNewChange: (Boolean) -> Unit,
	onBranchChange: (String?) -> Unit,
	onScanlatorChange: (String?) -> Unit,
) {
	Column(modifier = Modifier.padding(vertical = 8.dp)) {
		if (downloadedFilterAvailable) {
			CheckboxRow(
				icon = painterResource(R.drawable.ic_storage),
				text = stringResource(R.string.downloaded),
				checked = options.downloadedOnly,
				onCheckedChange = onDownloadedChange,
			)
		}
		CheckboxRow(
			icon = painterResource(R.drawable.ic_eye),
			text = stringResource(R.string.unread),
			checked = options.unreadOnly,
			onCheckedChange = onUnreadChange,
		)
		CheckboxRow(
			icon = painterResource(R.drawable.ic_bookmark),
			text = stringResource(R.string.chapter_options_bookmarked),
			checked = options.bookmarkedOnly,
			onCheckedChange = onBookmarkedChange,
		)
		CheckboxRow(
			icon = painterResource(R.drawable.ic_new),
			text = stringResource(R.string.chapter_options_new),
			checked = options.newOnly,
			onCheckedChange = onNewChange,
		)
		when {
			branches.isNotEmpty() -> ChapterGroupRow(
				options = branches,
				selected = selectedBranch,
				allowAll = allowAllBranches,
				onChange = onBranchChange,
			)
			scanlators.isNotEmpty() -> ChapterGroupRow(
				options = scanlators,
				selected = selectedScanlator,
				allowAll = true,
				onChange = onScanlatorChange,
			)
		}
	}
}

@Composable
private fun CheckboxRow(
	icon: Painter,
	text: String,
	checked: Boolean,
	onCheckedChange: (Boolean) -> Unit,
) {
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.clickable { onCheckedChange(!checked) }
			.padding(horizontal = 20.dp, vertical = 6.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
		Spacer(Modifier.width(14.dp))
		Text(text = text, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
		Checkbox(checked = checked, onCheckedChange = onCheckedChange)
	}
}

@Composable
private fun ChapterGroupRow(
	options: List<String>,
	selected: String?,
	allowAll: Boolean,
	onChange: (String?) -> Unit,
) {
	var expanded by remember { mutableStateOf(false) }
	Box {
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.clickable { expanded = true }
				.padding(horizontal = 20.dp, vertical = 14.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			Icon(painterResource(R.drawable.ic_list_group), contentDescription = null, modifier = Modifier.size(22.dp))
			Spacer(Modifier.width(14.dp))
			Column(modifier = Modifier.weight(1f)) {
				Text(stringResource(R.string.chapter_options_scanlator_branch), style = MaterialTheme.typography.bodyLarge)
				Text(
					selected ?: stringResource(R.string.chapter_options_all_branches),
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			}
			Text("›", style = MaterialTheme.typography.headlineSmall)
		}
		DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
			if (allowAll) {
				DropdownMenuItem(
					text = { Text(stringResource(R.string.chapter_options_all_branches)) },
					onClick = {
						expanded = false
						onChange(null)
					},
				)
			}
			options.forEach { option ->
				DropdownMenuItem(
					text = { Text(option) },
					onClick = {
						expanded = false
						onChange(option)
					},
				)
			}
		}
	}
}

@Composable
private fun SortTab(
	options: ChapterListOptions,
	onSortModeChange: (ChapterSortMode) -> Unit,
) {
	Column(modifier = Modifier.padding(vertical = 8.dp)) {
		SortRow(ChapterSortMode.SOURCE, stringResource(R.string.chapter_options_source_order), options, onSortModeChange)
		SortRow(ChapterSortMode.NUMBER, stringResource(R.string.chapter_options_chapter_number), options, onSortModeChange)
		SortRow(ChapterSortMode.UPLOAD_DATE, stringResource(R.string.chapter_options_upload_date), options, onSortModeChange)
		SortRow(ChapterSortMode.ALPHABETICAL, stringResource(R.string.chapter_options_alphabetically), options, onSortModeChange)
	}
}

@Composable
private fun SortRow(
	mode: ChapterSortMode,
	label: String,
	options: ChapterListOptions,
	onSortModeChange: (ChapterSortMode) -> Unit,
) {
	val selected = options.sortMode == mode
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.clickable { onSortModeChange(mode) }
			.padding(horizontal = 20.dp, vertical = 10.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		if (selected) {
			Icon(
				painter = painterResource(R.drawable.ic_sort_asc),
				contentDescription = null,
				tint = MaterialTheme.colorScheme.primary,
				modifier = Modifier
					.size(22.dp)
					.rotate(if (options.descending) 180f else 0f),
			)
		} else {
			Spacer(Modifier.size(22.dp))
		}
		Spacer(Modifier.width(14.dp))
		Column(modifier = Modifier.weight(1f)) {
			Text(label, style = MaterialTheme.typography.bodyLarge)
			if (selected) {
				Text(
					stringResource(
						if (options.descending) R.string.chapter_options_descending
						else R.string.chapter_options_ascending,
					),
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			}
		}
		RadioButton(selected = selected, onClick = { onSortModeChange(mode) })
	}
}

@Composable
private fun DisplayTab(
	options: ChapterListOptions,
	onTitleModeChange: (ChapterTitleMode) -> Unit,
	onGridChange: (Boolean) -> Unit,
) {
	Column(modifier = Modifier.padding(vertical = 8.dp)) {
		SectionLabel(
			icon = painterResource(R.drawable.ic_title),
			text = stringResource(R.string.chapter_options_title_display),
		)
		RadioRow(
			label = stringResource(R.string.chapter_options_source_title),
			summary = stringResource(R.string.chapter_options_source_title_summary),
			selected = options.titleMode == ChapterTitleMode.SOURCE,
			onClick = { onTitleModeChange(ChapterTitleMode.SOURCE) },
		)
		RadioRow(
			label = stringResource(R.string.chapter_options_chapter_number),
			summary = stringResource(R.string.chapter_options_number_title_summary),
			selected = options.titleMode == ChapterTitleMode.NUMBER,
			onClick = { onTitleModeChange(ChapterTitleMode.NUMBER) },
		)
		HorizontalDivider(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
		SectionLabel(
			icon = painterResource(R.drawable.ic_grid),
			text = stringResource(R.string.chapter_options_layout),
		)
		RadioRow(
			label = stringResource(R.string.list),
			summary = stringResource(R.string.chapter_options_list_summary),
			selected = !options.grid,
			onClick = { onGridChange(false) },
		)
		RadioRow(
			label = stringResource(R.string.grid),
			summary = stringResource(R.string.chapter_options_grid_summary),
			selected = options.grid,
			onClick = { onGridChange(true) },
		)
	}
}

@Composable
private fun SectionLabel(icon: Painter, text: String) {
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.padding(horizontal = 20.dp, vertical = 10.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
		Spacer(Modifier.width(14.dp))
		Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
	}
}

@Composable
private fun RadioRow(
	label: String,
	summary: String,
	selected: Boolean,
	onClick: () -> Unit,
) {
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.clickable(onClick = onClick)
			.padding(horizontal = 20.dp, vertical = 8.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		RadioButton(selected = selected, onClick = onClick)
		Spacer(Modifier.width(8.dp))
		Column {
			Text(label, style = MaterialTheme.typography.bodyLarge)
			Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
		}
	}
}
