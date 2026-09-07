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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import org.koitharu.kotatsu.parsers.model.MangaChapter

@Composable
fun LibraryGroupDetailsScreen(
	state: LibraryGroupDetailsState,
	onRetry: () -> Unit,
	onToggleMember: (Long) -> Unit,
	onRefreshMember: (Long) -> Unit,
	onOpenMember: (LibraryGroupDetailsMemberUi) -> Unit,
	onChapterClick: (LibraryGroupDetailsMemberUi, MangaChapter) -> Unit,
	onManageTimeline: () -> Unit,
) {
	when {
		state.isLoading && state.group == null -> LoadingGroupState()
		state.group == null -> GroupErrorState(state.error, onRetry)
		else -> GroupContent(
			group = state.group,
			members = state.members,
			onToggleMember = onToggleMember,
			onRefreshMember = onRefreshMember,
			onOpenMember = onOpenMember,
			onChapterClick = onChapterClick,
			onManageTimeline = onManageTimeline,
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
		modifier = Modifier
			.fillMaxSize()
			.padding(24.dp),
		horizontalAlignment = Alignment.CenterHorizontally,
		verticalArrangement = Arrangement.Center,
	) {
		Text(
			text = error?.takeIf { it.isNotBlank() } ?: stringResource(R.string.library_group_unavailable),
			style = MaterialTheme.typography.bodyLarge,
		)
		Spacer(Modifier.height(16.dp))
		Button(onClick = onRetry) {
			Text(stringResource(R.string.retry))
		}
	}
}

@Composable
private fun GroupContent(
	group: LibraryGroup,
	members: List<LibraryGroupDetailsMemberUi>,
	onToggleMember: (Long) -> Unit,
	onRefreshMember: (Long) -> Unit,
	onOpenMember: (LibraryGroupDetailsMemberUi) -> Unit,
	onChapterClick: (LibraryGroupDetailsMemberUi, MangaChapter) -> Unit,
	onManageTimeline: () -> Unit,
) {
	LazyColumn(
		modifier = Modifier.fillMaxSize(),
		contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
		verticalArrangement = Arrangement.spacedBy(10.dp),
	) {
		item(key = "group_header") {
			GroupHeader(group, members, onManageTimeline)
		}

		members.forEach { member ->
			val mangaId = member.member.mangaId
			item(key = "member_$mangaId") {
				MemberHeader(
					member = member,
					onToggle = { onToggleMember(mangaId) },
					onRefresh = { onRefreshMember(mangaId) },
					onOpen = { onOpenMember(member) },
				)
			}
			if (member.isExpanded) {
				when {
					member.chapters.isNotEmpty() -> {
						items(
							items = member.chapters,
							key = { chapter -> "chapter_${mangaId}_${chapter.id}" },
						) { chapter ->
							ChapterRow(chapter) { onChapterClick(member, chapter) }
						}
					}
					member.isLoading -> item(key = "member_loading_$mangaId") {
						MemberMessage(stringResource(R.string.library_group_loading_chapters), loading = true)
					}
					else -> item(key = "member_empty_$mangaId") {
						MemberMessage(
							member.error?.takeIf { it.isNotBlank() }
								?: stringResource(R.string.library_group_no_chapters),
							loading = false,
						)
					}
			}
		}
	}
}

@Composable
private fun GroupHeader(
	group: LibraryGroup,
	members: List<LibraryGroupDetailsMemberUi>,
	onManageTimeline: () -> Unit,
) {
	val context = LocalContext.current
	val first = members.firstOrNull()
	val coverUrl = group.coverUrl ?: first?.manga?.coverUrl
	val request = ImageRequest.Builder(context)
		.data(coverUrl)
		.apply {
			if (group.coverUrl == null && first != null) mangaSourceExtra(first.manga.source)
		}
		.build()
	Card(
		modifier = Modifier.fillMaxWidth(),
		shape = RoundedCornerShape(20.dp),
	) {
		Row(
			modifier = Modifier.padding(16.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			AsyncImage(
				model = request,
				contentDescription = group.title,
				modifier = Modifier.size(width = 76.dp, height = 104.dp),
				contentScale = ContentScale.Crop,
			)
			Spacer(Modifier.width(16.dp))
			Column(Modifier.weight(1f)) {
				Text(
					text = group.title,
					style = MaterialTheme.typography.headlineSmall,
					fontWeight = FontWeight.SemiBold,
				)
				Spacer(Modifier.height(6.dp))
				Text(
					text = context.resources.getQuantityString(
						R.plurals.library_group_members,
						members.size,
						members.size,
					),
					style = MaterialTheme.typography.bodyMedium,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
				Spacer(Modifier.height(8.dp))
				Text(
					text = stringResource(R.string.library_group_details_summary),
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
				Spacer(Modifier.height(10.dp))
				Button(onClick = onManageTimeline) {
					Text(stringResource(R.string.library_group_timeline))
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
	Card(
		modifier = Modifier
			.fillMaxWidth()
			.clickable(onClick = onToggle),
		shape = RoundedCornerShape(16.dp),
	) {
		Row(
			modifier = Modifier.padding(12.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			AsyncImage(
				model = ImageRequest.Builder(context)
					.data(cover)
					.mangaExtra(member.manga)
					.build(),
				contentDescription = member.manga.title,
				modifier = Modifier.size(width = 58.dp, height = 80.dp),
				contentScale = ContentScale.Crop,
			)
			Spacer(Modifier.width(12.dp))
			Column(Modifier.weight(1f)) {
				Text(
					text = member.manga.title,
					style = MaterialTheme.typography.titleMedium,
					fontWeight = FontWeight.SemiBold,
					maxLines = 2,
					overflow = TextOverflow.Ellipsis,
				)
				Spacer(Modifier.height(4.dp))
				Text(
					text = member.manga.source.getTitle(context),
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
				)
				if (chapterCount > 0) {
					Text(
						text = context.resources.getQuantityString(
							R.plurals.library_group_chapters,
							chapterCount,
							chapterCount,
						),
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.primary,
					)
				}
			}
			IconButton(onClick = onOpen) {
				Icon(
					painter = painterResource(R.drawable.ic_arrow_forward),
					contentDescription = stringResource(R.string.library_group_open_details),
				)
			}
			IconButton(onClick = onRefresh, enabled = !member.isLoading) {
				if (member.isLoading) {
					CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
				} else {
					Icon(
						painter = painterResource(R.drawable.ic_refresh),
						contentDescription = stringResource(R.string.library_group_refresh_chapters),
					)
				}
			}
			Icon(
				painter = painterResource(R.drawable.ic_expand_more),
				contentDescription = if (member.isExpanded) {
					stringResource(R.string.collapse)
				} else {
					stringResource(R.string.expand)
				},
				tint = MaterialTheme.colorScheme.onSurfaceVariant,
			)
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
	Column(
		modifier = Modifier
			.fillMaxWidth()
			.clickable(onClick = onClick)
			.padding(horizontal = 16.dp, vertical = 11.dp),
	) {
		Text(
			text = title,
			style = MaterialTheme.typography.bodyLarge,
			maxLines = 2,
			overflow = TextOverflow.Ellipsis,
		)
		if (meta.isNotEmpty()) {
			Spacer(Modifier.height(3.dp))
			Text(
				text = meta,
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)
		}
		Spacer(Modifier.height(10.dp))
		HorizontalDivider()
	}
}

@Composable
private fun MemberMessage(text: String, loading: Boolean) {
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.padding(horizontal = 18.dp, vertical = 14.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		if (loading) {
			CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
			Spacer(Modifier.width(10.dp))
		}
		Text(
			text = text,
			style = MaterialTheme.typography.bodyMedium,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)
	}
}
