package org.koitharu.kotatsu.favourites.ui.categories.select

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.fragment.app.viewModels
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.google.android.material.checkbox.MaterialCheckBox
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.ui.ComposeAlertDialogFragment
import org.koitharu.kotatsu.core.util.ext.getDisplayMessage
import org.koitharu.kotatsu.core.util.ext.joinToStringWithLimit
import org.koitharu.kotatsu.core.util.ext.mangaExtra
import org.koitharu.kotatsu.core.util.ext.observeEvent
import org.koitharu.kotatsu.favourites.ui.sets.CategorySetSelector
import org.koitharu.kotatsu.favourites.ui.sets.showCategorySets
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
import org.koitharu.kotatsu.favourites.ui.categories.select.model.MangaCategoryItem
import org.koitharu.kotatsu.list.ui.model.EmptyState
import org.koitharu.kotatsu.list.ui.model.LoadingState

@AndroidEntryPoint
class FavoriteDialog : ComposeAlertDialogFragment() {

	private val viewModel by viewModels<FavoriteDialogViewModel>()

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)
		viewModel.onError.observeEvent(viewLifecycleOwner) { e ->
			Toast.makeText(context ?: return@observeEvent, e.getDisplayMessage(resources), Toast.LENGTH_SHORT).show()
		}
		viewModel.onSaved.observeEvent(viewLifecycleOwner) { dismiss() }
	}

	override fun onStart() {
		super.onStart()
		if (viewModel.favouriteSpace == FavouriteSpace.PRIVATE) dialog?.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
	}

	private fun openCategoryCreate() {
		viewModel.prepareCategoryManagement()
		router.openFavoriteCategoryCreate(viewModel.favouriteSpace, viewModel.activeSet.value?.setId, viewModel.contentType)

	}

	@Composable
	override fun Content() {
		val context = LocalContext.current
		val configuration = LocalConfiguration.current
		val content by viewModel.content.collectAsState()
		val isSaving by viewModel.isSaving.collectAsState()
		val sets by viewModel.sets.collectAsState()
		val activeSet by viewModel.activeSet.collectAsState()
		val query by viewModel.searchQuery.collectAsState()
		val title = remember { viewModel.manga.joinToStringWithLimit(context, 92) { it.title } }
		var showHeader by rememberSaveable { mutableStateOf(false) }
		val pageSize = remember(configuration.screenHeightDp, showHeader) {
			calculatePageSize(configuration.screenHeightDp, showHeader)
		}

		Box(
			modifier = Modifier.fillMaxWidth().padding(horizontal = DIALOG_OUTER_PADDING.dp),
			contentAlignment = Alignment.Center,
		) {
			Surface(
				shape = RoundedCornerShape(28.dp),
				color = MaterialTheme.colorScheme.surfaceContainerHigh,
				tonalElevation = 6.dp,
				modifier = Modifier.fillMaxWidth().widthIn(max = 760.dp),
			) {
				Column(modifier = Modifier.padding(DIALOG_INNER_PADDING.dp)) {
					DialogHeader(showHeader, !isSaving) { showHeader = it }
					Spacer(Modifier.height(HEADER_CONTENT_GAP.dp))
					if (showHeader) {
						MangaHeader(title)
						Spacer(Modifier.height(HEADER_CONTENT_GAP.dp))
					}
					CategorySetSelector(sets, activeSet, !isSaving, viewModel::selectSet) { create ->
						viewModel.prepareCategoryManagement()
						showCategorySets(parentFragmentManager, viewModel.favouriteSpace, create)
					}
					OutlinedTextField(
						value = query, onValueChange = { viewModel.searchQuery.value = it }, enabled = !isSaving,
						label = { Text(stringResource(R.string.category_set_search)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
					)
					Spacer(Modifier.height(8.dp))
					key(activeSet?.setId, query) { CategoryContent(content, !isSaving, pageSize) }
					Spacer(Modifier.height(12.dp))
					ActionRow(isSaving)
				}
			}
		}
	}

	@Composable
	private fun DialogHeader(showHeader: Boolean, enabled: Boolean, onHeaderChanged: (Boolean) -> Unit) {
		Row(
			modifier = Modifier.fillMaxWidth(),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(8.dp),
		) {
			Text(
				text = stringResource(R.string.categories),
				style = MaterialTheme.typography.headlineSmall,
				fontWeight = FontWeight.SemiBold,
				modifier = Modifier.weight(1f),
			)
			Text(
				text = stringResource(R.string.title_over_cover),
				style = MaterialTheme.typography.labelMedium,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
				maxLines = 1,
			)
			val headerDescription = stringResource(R.string.title_over_cover)
			Switch(checked = showHeader, onCheckedChange = onHeaderChanged, enabled = enabled, modifier = Modifier.semantics { contentDescription = headerDescription })
		}
	}

	@Composable
	private fun MangaHeader(title: String) {
		val context = LocalContext.current
		val manga = viewModel.manga.firstOrNull()
		Row(
			modifier = Modifier.fillMaxWidth(),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(12.dp),
		) {
			if (manga?.coverUrl?.isNotBlank() == true) {
				AsyncImage(
					model = ImageRequest.Builder(context).data(manga.coverUrl).mangaExtra(manga).build(),
					contentDescription = manga.title,
					contentScale = ContentScale.Crop,
					modifier = Modifier.width(48.dp).height(68.dp).clip(RoundedCornerShape(10.dp)),
				)
			} else {
				Box(
					modifier = Modifier.width(48.dp).height(68.dp)
						.background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(10.dp)),
					contentAlignment = Alignment.Center,
				) {
					Icon(painterResource(R.drawable.ic_heart), null, modifier = Modifier.size(24.dp))
				}
			}
			Column(modifier = Modifier.weight(1f)) {
				Text(
					text = title,
					style = MaterialTheme.typography.titleMedium,
					fontWeight = FontWeight.SemiBold,
					maxLines = 2,
					overflow = TextOverflow.Ellipsis,
				)
				Spacer(Modifier.height(2.dp))
				Text(
					text = stringResource(R.string.categories),
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			}
		}
	}

	@Composable
	private fun CategoryContent(content: List<Any>, enabled: Boolean, pageSize: Int) {
		val categories = content.filterIsInstance<MangaCategoryItem>()
		when {
			content.any { it is LoadingState } -> Box(
				modifier = Modifier.fillMaxWidth().height(180.dp),
				contentAlignment = Alignment.Center,
			) { CircularProgressIndicator() }
			content.any { it is EmptyState } -> {
				val empty = content.filterIsInstance<EmptyState>().first()
				Text(
					text = stringResource(empty.textPrimary),
					style = MaterialTheme.typography.bodyMedium,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
				)
			}
			categories.isNotEmpty() -> CategoryPages(categories, enabled, pageSize)
			else -> Text(stringResource(R.string.category_set_empty), modifier = Modifier.padding(vertical = 24.dp))
		}
	}

	@Composable
	private fun CategoryPages(categories: List<MangaCategoryItem>, enabled: Boolean, pageSize: Int) {
		val pages = remember(categories, pageSize) { categories.chunked(pageSize) }
		val setId = viewModel.activeSet.value?.setId ?: 0L
		val pagerState = rememberPagerState(initialPage = viewModel.getPage(setId).coerceIn(0, pages.lastIndex), pageCount = { pages.size })
		LaunchedEffect(pagerState, setId) { snapshotFlow { pagerState.currentPage }.collect { viewModel.rememberPage(setId, it) } }
		val firstPageSize = minOf(pageSize, categories.size)
		val useTwoColumns = categories.size > SINGLE_COLUMN_LIMIT
		val rowCount = if (useTwoColumns) (firstPageSize + 1) / 2 else firstPageSize
		val categoryAreaHeight = (rowCount * CATEGORY_ROW_HEIGHT) + (maxOf(0, rowCount - 1) * CATEGORY_ROW_GAP)
		val pageDescription = stringResource(R.string.category_set_page, pagerState.currentPage + 1, pages.size)
		HorizontalPager(
			state = pagerState,
			modifier = Modifier.fillMaxWidth().height(categoryAreaHeight.dp).testTag("category-pages").semantics { contentDescription = pageDescription },
		) { page ->
			CategoryPage(pages[page], enabled, useTwoColumns)
		}
		if (pages.size > 1) {
			Spacer(Modifier.height(8.dp))
			Row(
				modifier = Modifier.fillMaxWidth(),
				horizontalArrangement = Arrangement.Center,
				verticalAlignment = Alignment.CenterVertically,
			) {
				pages.indices.forEach { page ->
					Box(
						modifier = Modifier.padding(horizontal = 3.dp)
							.size(if (page == pagerState.currentPage) 8.dp else 6.dp)
							.background(
								if (page == pagerState.currentPage) MaterialTheme.colorScheme.primary
								else MaterialTheme.colorScheme.outlineVariant,
								CircleShape,
							),
					)
				}
			}
		}
	}

	@Composable
	private fun CategoryPage(items: List<MangaCategoryItem>, enabled: Boolean, useTwoColumns: Boolean) {
		if (!useTwoColumns) {
			Column(verticalArrangement = Arrangement.spacedBy(CATEGORY_ROW_GAP.dp)) {
				items.forEach { CategoryRow(it, enabled) }
			}
			return
		}
		val split = (items.size + 1) / 2
		val left = items.take(split)
		val right = items.drop(split)
		Row(
			modifier = Modifier.fillMaxWidth(),
			horizontalArrangement = Arrangement.spacedBy(CATEGORY_COLUMN_GAP.dp),
		) {
			Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(CATEGORY_ROW_GAP.dp)) {
				left.forEach { CategoryRow(it, enabled) }
			}
			Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(CATEGORY_ROW_GAP.dp)) {
				right.forEach { CategoryRow(it, enabled) }
			}
		}
	}

	@Composable
	private fun ActionRow(isSaving: Boolean) {
		val addDescription = stringResource(R.string.add_new_category)
		Row(
			modifier = Modifier.fillMaxWidth(),
			horizontalArrangement = Arrangement.spacedBy(10.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			TextButton(
				onClick = { dismiss() },
				enabled = !isSaving,
				modifier = Modifier.weight(1f).height(48.dp),
			) { Text(stringResource(android.R.string.cancel)) }
			FilledTonalButton(
				onClick = ::openCategoryCreate,
				enabled = !isSaving,
				modifier = Modifier.size(48.dp).semantics { contentDescription = addDescription },
				contentPadding = PaddingValues(0.dp),
			) { Text(text = "+", style = MaterialTheme.typography.titleLarge) }
			Button(
				onClick = { viewModel.save() },
				enabled = !isSaving,
				modifier = Modifier.weight(1f).height(48.dp),
			) { Text(stringResource(android.R.string.ok)) }
		}
	}

	@OptIn(ExperimentalFoundationApi::class)
	@Composable
	private fun CategoryRow(item: MangaCategoryItem, enabled: Boolean) {
		val checked = item.checkedState == MaterialCheckBox.STATE_CHECKED
		val toggle = { if (enabled) viewModel.setChecked(item.category.id, !checked) }
		var titleOverflow by remember(item.category.id) { mutableStateOf(false) }
		var showFullTitle by remember(item.category.id) { mutableStateOf(false) }
		Box(modifier = Modifier.fillMaxWidth().height(CATEGORY_ROW_HEIGHT.dp)) {
			Row(
				modifier = Modifier.fillMaxWidth().height(CATEGORY_ROW_HEIGHT.dp)
					.clip(RoundedCornerShape(CATEGORY_CORNER_RADIUS.dp))
					.background(
						if (checked) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f)
						else MaterialTheme.colorScheme.surfaceContainer,
					)
					.combinedClickable(
						enabled = enabled,
						onClick = { toggle() },
						onLongClick = { if (titleOverflow) showFullTitle = true },
					)
					.padding(horizontal = CATEGORY_HORIZONTAL_PADDING.dp),
				verticalAlignment = Alignment.CenterVertically,
			) {
				TriStateCheckbox(
					state = when (item.checkedState) {
						MaterialCheckBox.STATE_CHECKED -> ToggleableState.On
						MaterialCheckBox.STATE_INDETERMINATE -> ToggleableState.Indeterminate
						else -> ToggleableState.Off
					},
					onClick = if (enabled) ({ toggle() }) else null,
					modifier = Modifier.size(CATEGORY_CHECKBOX_SIZE.dp).testTag("category-checkbox:${item.category.id}").semantics { contentDescription = item.category.title },
				)
				Spacer(Modifier.width(CATEGORY_CHECKBOX_TEXT_GAP.dp))
				Text(
					text = item.category.title,
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurface,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
					onTextLayout = { titleOverflow = it.hasVisualOverflow },
					modifier = Modifier.weight(1f),
				)
				if (item.isTrackerEnabled && item.category.isTrackingEnabled) {
					Icon(
						painter = painterResource(R.drawable.ic_notification),
						contentDescription = null,
						tint = MaterialTheme.colorScheme.onSurfaceVariant,
						modifier = Modifier.size(14.dp),
					)
				}
				if (!item.category.isVisibleInLibrary) {
					Icon(
						painter = painterResource(R.drawable.ic_eye_off),
						contentDescription = null,
						tint = MaterialTheme.colorScheme.onSurfaceVariant,
						modifier = Modifier.size(14.dp),
					)
				}
			}
			DropdownMenu(
				expanded = showFullTitle,
				onDismissRequest = { showFullTitle = false },
			) {
				Text(
					text = item.category.title,
					style = MaterialTheme.typography.bodyMedium,
					modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
				)
			}
		}
	}

	private fun calculatePageSize(screenHeightDp: Int, showHeader: Boolean): Int {
		val reservedHeight = if (showHeader) 336 else 260
		val available = (screenHeightDp - reservedHeight).coerceAtLeast(CATEGORY_ROW_HEIGHT)
		val rows = ((available + CATEGORY_ROW_GAP) / (CATEGORY_ROW_HEIGHT + CATEGORY_ROW_GAP))
			.coerceIn(MIN_ROWS_PER_PAGE, MAX_ROWS_PER_PAGE)
		return (rows * 2).coerceAtMost(PAGE_SIZE)
	}

	private companion object {
		const val SINGLE_COLUMN_LIMIT = 10
		const val PAGE_SIZE = 20
		const val MIN_ROWS_PER_PAGE = 1
		const val MAX_ROWS_PER_PAGE = 10
		const val DIALOG_OUTER_PADDING = 8
		const val DIALOG_INNER_PADDING = 14
		const val HEADER_CONTENT_GAP = 10
		const val CATEGORY_ROW_HEIGHT = 44
		const val CATEGORY_CORNER_RADIUS = 10
		const val CATEGORY_ROW_GAP = 6
		const val CATEGORY_COLUMN_GAP = 8
		const val CATEGORY_HORIZONTAL_PADDING = 8
		const val CATEGORY_CHECKBOX_SIZE = 32
		const val CATEGORY_CHECKBOX_TEXT_GAP = 4
	}
}
