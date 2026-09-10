package org.koitharu.kotatsu.favourites.groups.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.getLocalizedTitle
import org.koitharu.kotatsu.core.model.getTitle
import org.koitharu.kotatsu.core.util.ext.mangaExtra
import org.koitharu.kotatsu.core.util.ext.mangaSourceExtra
import org.koitharu.kotatsu.favourites.groups.domain.LibraryGroup
import org.koitharu.kotatsu.favourites.groups.domain.LibraryGroupTimelineItem
import org.koitharu.kotatsu.favourites.groups.tracking.LibraryGroupTracking
import org.koitharu.kotatsu.parsers.model.MangaChapter

private data class ReadingTimelineRowUi(
	val position: Int,
	val member: LibraryGroupDetailsMemberUi,
	val chapter: MangaChapter,
)

@Composable
fun LibraryGroupDetailsScreen(
	state: LibraryGroupDetailsState,
	onRetry: () -> Unit,
	onToggleMember: (Long) -> Unit,
	onRefreshMember: (Long) -> Unit,
	onOpenMember: (LibraryGroupDetailsMemberUi) -> Unit,
	onChapterClick: (LibraryGroupDetailsMemberUi, MangaChapter) -> Unit,
	onEditGroup: () -> Unit,
	onManageTimeline: () -> Unit,
	onManagePlacement: () -> Unit,
	onManageTracking: () -> Unit,
	onSyncTracking: () -> Unit,
	onDeleteGroup: () -> Unit,
) {
	when {
		state.isLoading && state.group == null -> LoadingGroupState()
		state.group == null -> GroupErrorState(state.error, onRetry)
		else -> GroupContent(
			state = state,
			onToggleMember = onToggleMember,
			onRefreshMember = onRefreshMember,
			onOpenMember = onOpenMember,
			onChapterClick = onChapterClick,
			onEditGroup = onEditGroup,
			onManageTimeline = onManageTimeline,
			onManagePlacement = onManagePlacement,
			onManageTracking = onManageTracking,
			onSyncTracking = onSyncTracking,
			onDeleteGroup = onDeleteGroup,
		)
	}
}

@Composable
private fun LoadingGroupState() {
	Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
		CircularProgressIndicator()
	}
}

@Composable
private fun GroupErrorState(error: String?, onRetry: () -> Unit) {
	Column(
		modifier = Modifier.fillMaxSize().padding(24.dp),
		horizontalAlignment = Alignment.CenterHorizontally,
		verticalArrangement = Arrangement.Center,
	) {
		Text(
			text = error?.takeIf { it.isNotBlank() } ?: stringResource(R.string.library_group_unavailable),
			style = MaterialTheme.typography.bodyLarge,
		)
		Spacer(Modifier.height(16.dp))
		Button(onClick = onRetry) { Text(stringResource(R.string.retry)) }
	}
}

@Composable
private fun GroupContent(
	state: LibraryGroupDetailsState,
	onToggleMember: (Long) -> Unit,
	onRefreshMember: (Long) -> Unit,
	onOpenMember: (LibraryGroupDetailsMemberUi) -> Unit,
	onChapterClick: (LibraryGroupDetailsMemberUi, MangaChapter) -> Unit,
	onEditGroup: () -> Unit,
	onManageTimeline: () -> Unit,
	onManagePlacement: () -> Unit,
	onManageTracking: () -> Unit,
	onSyncTracking: () -> Unit,
	onDeleteGroup: () -> Unit,
) {
	val group = requireNotNull(state.group)
	var showSources by remember { mutableStateOf(false) }
	val membersById = state.members.associateBy { it.member.mangaId }
	val timelineRows = state.timeline.mapNotNull { item ->
		val member = membersById[item.mangaId] ?: return@mapNotNull null
		val chapter = member.chapters.firstOrNull { it.id == item.chapterId } ?: return@mapNotNull null
		ReadingTimelineRowUi(item.position, member, chapter)
	}

	LazyColumn(
		modifier = Modifier.fillMaxSize(),
		contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
		verticalArrangement = Arrangement.spacedBy(10.dp),
	) {
		item(key = "group_header") {
			GroupHeader(
				group = group,
				members = state.members,
				onEditGroup = onEditGroup,
				onManageTimeline = onManageTimeline,
				onManagePlacement = onManagePlacement,
				onManageSources = { showSources = true },
				onDeleteGroup = onDeleteGroup,
			)
		}
		item(key = "tracking") {
			TrackingSection(
				tracking = state.tracking,
				groupProgress = state.trackingProgress,
				onManage = onManageTracking,
				onSync = onSyncTracking,
			)
		}
		if (timelineRows.isNotEmpty()) {
			item(key = "reading_timeline_header") {
				SectionHeader(
					title = stringResource(R.string.library_group_reading_timeline),
					summary = stringResource(R.string.library_group_reading_timeline_summary),
				)
			}
			items(
				items = timelineRows,
				key = { row -> "timeline_${row.member.member.mangaId}_${row.chapter.id}" },
			) { row -> ReadingTimelineRow(row) { onChapterClick(row.member, row.chapter) } }
		}
	}

	if (showSources) {
		SourceEntriesSheet(
			members = state.members,
			onDismiss = { showSources = false },
			onToggleMember = onToggleMember,
			onRefreshMember = onRefreshMember,
			onOpenMember = onOpenMember,
			onChapterClick = onChapterClick,
		)
	}
}

@Composable
private fun GroupHeader(
	group: LibraryGroup,
	members: List<LibraryGroupDetailsMemberUi>,
	onEditGroup: () -> Unit,
	onManageTimeline: () -> Unit,
	onManagePlacement: () -> Unit,
	onManageSources: () -> Unit,
	onDeleteGroup: () -> Unit,
) {
	val context = LocalContext.current
	val first = members.firstOrNull()
	val coverUrl = group.coverUrl ?: first?.manga?.coverUrl
	val request = ImageRequest.Builder(context)
		.data(coverUrl)
		.apply { if (group.coverUrl == null && first != null) mangaSourceExtra(first.manga.source) }
		.build()
	var menuExpanded by remember { mutableStateOf(false) }

	Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
		Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
			AsyncImage(
				model = request,
				contentDescription = group.title,
				modifier = Modifier.size(width = 120.dp, height = 168.dp),
				contentScale = ContentScale.Crop,
			)
			Spacer(Modifier.width(16.dp))
			Column(Modifier.weight(1f)) {
				Row(verticalAlignment = Alignment.Top) {
					Text(
						text = group.title,
						style = MaterialTheme.typography.headlineSmall,
						fontWeight = FontWeight.SemiBold,
						modifier = Modifier.weight(1f),
					)
					Box {
						IconButton(onClick = { menuExpanded = true }) {
							Icon(painterResource(R.drawable.ic_more_vert), contentDescription = stringResource(R.string.library_group_more_actions))
						}
						DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
							DropdownMenuItem(text = { Text(stringResource(R.string.library_group_edit)) }, onClick = { menuExpanded = false; onEditGroup() })
							DropdownMenuItem(text = { Text(stringResource(R.string.library_group_timeline)) }, onClick = { menuExpanded = false; onManageTimeline() })
							DropdownMenuItem(text = { Text(stringResource(R.string.library_group_placement)) }, onClick = { menuExpanded = false; onManagePlacement() })
							DropdownMenuItem(text = { Text(stringResource(R.string.library_group_source_entries)) }, onClick = { menuExpanded = false; onManageSources() })
							HorizontalDivider()
							DropdownMenuItem(text = { Text(stringResource(R.string.library_group_delete)) }, onClick = { menuExpanded = false; onDeleteGroup() })
						}
					}
				}
				group.alternativeTitle?.let {
					Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
				}
				Spacer(Modifier.height(8.dp))
				group.author?.let { MetadataLine(stringResource(R.string.library_group_author), it) }
				group.artist?.let { MetadataLine(stringResource(R.string.library_group_artist), it) }
				Text(
					text = context.resources.getQuantityString(R.plurals.library_group_members, members.size, members.size),
					style = MaterialTheme.typography.bodyMedium,
					color = MaterialTheme.colorScheme.primary,
				)
			}
		}
		group.description?.takeIf { it.isNotBlank() }?.let { description ->
			HorizontalDivider()
			Text(
				text = description,
				style = MaterialTheme.typography.bodyMedium,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
				modifier = Modifier.padding(16.dp),
			)
		}
	}
}

@Composable
private fun MetadataLine(label: String, value: String) {
	Text(
		text = "$label: $value",
		style = MaterialTheme.typography.bodySmall,
		color = MaterialTheme.colorScheme.onSurfaceVariant,
		maxLines = 2,
		overflow = TextOverflow.Ellipsis,
	)
}

@Composable
private fun TrackingSection(
	tracking: List<LibraryGroupTracking>,
	groupProgress: Int,
	onManage: () -> Unit,
	onSync: () -> Unit,
) {
	Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
		Column(Modifier.padding(16.dp)) {
			Row(verticalAlignment = Alignment.CenterVertically) {
				Text(
					text = stringResource(R.string.library_group_tracking),
					style = MaterialTheme.typography.titleMedium,
					fontWeight = FontWeight.SemiBold,
					modifier = Modifier.weight(1f),
				)
				TextButton(onClick = onManage) { Text(stringResource(R.string.library_group_tracking_manage)) }
			}
			Text(
				text = stringResource(R.string.library_group_tracking_progress, groupProgress),
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
			if (tracking.isEmpty()) {
				Spacer(Modifier.height(8.dp))
				Text(
					text = stringResource(R.string.library_group_tracking_empty),
					style = MaterialTheme.typography.bodyMedium,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			} else {
				val linkedLabel = stringResource(R.string.library_group_tracking_linked)
				tracking.forEach { item ->
					val statusLabel = item.status?.replace('_', ' ')?.lowercase()?.replaceFirstChar { it.uppercase() }
					val chapterLabel = stringResource(R.string.library_group_tracking_chapter, item.progress)
					val ratingLabel = if (item.rating > 0f) {
						"${((item.rating * 10f) * 10).toInt() / 10f}/10"
					} else null
					val detail = listOfNotNull(statusLabel ?: linkedLabel, chapterLabel, ratingLabel).joinToString(" • ")
					Spacer(Modifier.height(10.dp))
					Row(verticalAlignment = Alignment.CenterVertically) {
						Icon(painter = painterResource(item.service.iconResId), contentDescription = null, modifier = Modifier.size(24.dp))
						Spacer(Modifier.width(10.dp))
						Column(Modifier.weight(1f)) {
							Text(item.targetTitle, style = MaterialTheme.typography.labelLarge)
							Text(text = detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
						}
					}
				}
				Spacer(Modifier.height(12.dp))
				OutlinedButton(onClick = onSync) { Text(stringResource(R.string.library_group_tracking_sync)) }
			}
		}
	}
}

@Composable
private fun SectionHeader(title: String, summary: String) {
	Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp)) {
		Text(text = title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
		Spacer(Modifier.height(2.dp))
		Text(text = summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
	}
}

@Composable
private fun ReadingTimelineRow(row: ReadingTimelineRowUi, onClick: () -> Unit) {
	val resources = LocalContext.current.resources
	val chapterTitle = row.chapter.title?.takeIf { it.isNotBlank() } ?: row.chapter.getLocalizedTitle(resources)
	Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick), shape = RoundedCornerShape(14.dp)) {
		Row(modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
			Text(text = (row.position + 1).toString(), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(30.dp))
			Column(Modifier.weight(1f)) {
				Text(text = row.member.manga.title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
				Spacer(Modifier.height(2.dp))
				Text(text = chapterTitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
			}
		}
	}
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SourceEntriesSheet(
	members: List<LibraryGroupDetailsMemberUi>,
	onDismiss: () -> Unit,
	onToggleMember: (Long) -> Unit,
	onRefreshMember: (Long) -> Unit,
	onOpenMember: (LibraryGroupDetailsMemberUi) -> Unit,
	onChapterClick: (LibraryGroupDetailsMemberUi, MangaChapter) -> Unit,
) {
	ModalBottomSheet(onDismissRequest = onDismiss) {
		Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
			Text(text = stringResource(R.string.library_group_source_entries), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
			Spacer(Modifier.height(4.dp))
			Text(text = stringResource(R.string.library_group_source_entries_summary), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
			Spacer(Modifier.height(12.dp))
			LazyColumn(
				modifier = Modifier.fillMaxWidth().heightIn(max = 620.dp),
				verticalArrangement = Arrangement.spacedBy(8.dp),
				contentPadding = PaddingValues(bottom = 24.dp),
			) {
				members.forEach { member ->
					val mangaId = member.member.mangaId
					item(key = "source_$mangaId") {
						MemberHeader(member, { onToggleMember(mangaId) }, { onRefreshMember(mangaId) }, { onOpenMember(member) })
					}
					if (member.isExpanded) {
						when {
							member.chapters.isNotEmpty() -> items(
								items = member.chapters,
								key = { chapter -> "source_chapter_${mangaId}_${chapter.id}" },
							) { chapter -> ChapterRow(chapter) { onChapterClick(member, chapter) } }
							member.isLoading -> item(key = "source_loading_$mangaId") {
								MemberMessage(stringResource(R.string.library_group_loading_chapters), loading = true)
							}
							else -> item(key = "source_empty_$mangaId") {
								MemberMessage(member.error?.takeIf { it.isNotBlank() } ?: stringResource(R.string.library_group_no_chapters), loading = false)
							}
						}
					}
				}
			}
		}
	}
}

@Composable
private fun MemberHeader(
	member: LibraryGroupDetailsMemberUi,
	onToggle: () -> Unit,
	onRefresh: () -> Unit,
	onOpen: () -> Unit,
) {
	val context = LocalContext.current
	val chapterCount = member.chapters.size
	val cover = member.manga.coverUrl?.takeIf { it.isNotBlank() } ?: member.member.displayCoverUrl
	Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle), shape = RoundedCornerShape(16.dp)) {
		Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
			AsyncImage(
				model = ImageRequest.Builder(context).data(cover).mangaExtra(member.manga).build(),
				contentDescription = member.manga.title,
				modifier = Modifier.size(width = 58.dp, height = 80.dp),
				contentScale = ContentScale.Crop,
			)
			Spacer(Modifier.width(12.dp))
			Column(Modifier.weight(1f)) {
				Text(member.manga.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
				Spacer(Modifier.height(4.dp))
				Text(member.manga.source.getTitle(context), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
				if (chapterCount > 0) {
					Text(context.resources.getQuantityString(R.plurals.library_group_chapters, chapterCount, chapterCount), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
				}
			}
			IconButton(onClick = onOpen) { Icon(painterResource(R.drawable.ic_arrow_forward), contentDescription = stringResource(R.string.library_group_open_details)) }
			IconButton(onClick = onRefresh, enabled = !member.isLoading) {
				if (member.isLoading) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
				else Icon(painterResource(R.drawable.ic_refresh), contentDescription = stringResource(R.string.library_group_refresh_chapters))
			}
			Icon(painter = painterResource(R.drawable.ic_expand_more), contentDescription = if (member.isExpanded) stringResource(R.string.collapse) else stringResource(R.string.expand), tint = MaterialTheme.colorScheme.onSurfaceVariant)
		}
	}
}

@Composable
private fun ChapterRow(chapter: MangaChapter, onClick: () -> Unit) {
	val resources = LocalContext.current.resources
	val title = chapter.title?.takeIf { it.isNotBlank() } ?: chapter.getLocalizedTitle(resources)
	val meta = buildList {
		chapter.branch?.takeIf { it.isNotBlank() }?.let(::add)
		chapter.scanlator?.takeIf { it.isNotBlank() }?.let(::add)
	}.joinToString(" • ")
	Column(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 11.dp)) {
		Text(text = title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
		if (meta.isNotEmpty()) {
			Spacer(Modifier.height(3.dp))
			Text(text = meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
		}
		Spacer(Modifier.height(10.dp))
		HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
	}
}

@Composable
private fun MemberMessage(text: String, loading: Boolean) {
	Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
		if (loading) {
			CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
			Spacer(Modifier.width(10.dp))
		}
		Text(text = text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
	}
}
