package org.koitharu.kotatsu.favourites.ui.categories.select

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TriStateCheckbox
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
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.ui.ComposeAlertDialogFragment
import org.koitharu.kotatsu.core.util.ext.getDisplayMessage
import org.koitharu.kotatsu.core.util.ext.joinToStringWithLimit
import org.koitharu.kotatsu.core.util.ext.mangaExtra
import org.koitharu.kotatsu.core.util.ext.observeEvent
import org.koitharu.kotatsu.favourites.data.EXTRA_FAVOURITE_SPACE
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
import org.koitharu.kotatsu.favourites.ui.categories.edit.FavouritesCategoryEditActivity
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
		viewModel.onSaved.observeEvent(viewLifecycleOwner) {
			dismiss()
		}
	}

	private fun openCategoryCreate() {
		viewModel.prepareCategoryManagement()
		if (viewModel.favouriteSpace == FavouriteSpace.NORMAL) {
			router.openFavoriteCategoryCreate()
		} else {
			startActivity(
				Intent(requireContext(), FavouritesCategoryEditActivity::class.java)
					.putExtra(AppRouter.KEY_ID, FavouritesCategoryEditActivity.NO_ID)
					.putExtra(EXTRA_FAVOURITE_SPACE, FavouriteSpace.PRIVATE.dbValue),
			)
		}
	}

	@Composable
	override fun Content() {
		val context = LocalContext.current
		val configuration = LocalConfiguration.current
		val content by viewModel.content.collectAsState()
		val isSaving by viewModel.isSaving.collectAsState()
		val title = remember { viewModel.manga.joinToStringWithLimit(context, 92) { it.title } }
		var showHeader by rememberSaveable { mutableStateOf(true) }
		val pageSize = remember(configuration.screenHeightDp, showHeader) {
			calculatePageSize(configuration.screenHeightDp, showHeader)
		}

		Box(
			modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
			contentAlignment = Alignment.Center,
		) {
			Surface(
				shape = RoundedCornerShape(28.dp),
				color = MaterialTheme.colorScheme.surfaceContainerHigh,
				tonalElevation = 6.dp,
				modifier = Modifier.fillMaxWidth().widthIn(max = 760.dp),
			) {
				Column(modifier = Modifier.padding(20.dp)) {
					DialogHeader(
						showHeader = showHeader,
						enabled = !isSaving,
						onHeaderChanged = { showHeader = it },
					)
					Spacer(Modifier.height(12.dp))
					if (showHeader) {
						MangaHeader(title)
						Spacer(Modifier.height(12.dp))
					}
					CategoryContent(content, enabled = !isSaving, pageSize = pageSize)
					Spacer(Modifier.height(12.dp))
					ActionRow(isSaving)
				}
			}
		}
	}

	@Composable
	private fun DialogHeader(
		showHeader: Boolean,
		enabled: Boolean,
		onHeaderChanged: (Boolean) -> Unit,
	) {
		Row(
			modifier = Modifier.fillMaxWidth(),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(12.dp),
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
			)
			Switch(
				checked = showHeader,
				onCheckedChange = onHeaderChanged,
				enabled = enabled,
			)
		}
	}

	@Composable
	private fun MangaHeader(title: String) {
		val context = LocalContext.current
		val manga = viewModel.manga.firstOrNull()
		Row(
			modifier = Modifier.fillMaxWidth(),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(14.dp),
		) {
			if (manga?.coverUrl?.isNotBlank() == true) {
				AsyncImage(
					model = ImageRequest.Builder(context)
						.data(manga.coverUrl)
						.mangaExtra(manga)
						.build(),
					contentDescription = manga.title,
					contentScale = ContentScale.Crop,
					modifier = Modifier
						.width(52.dp)
						.height(72.dp)
						.clip(RoundedCornerShape(12.dp)),
				)
			} else {
				Box(
					modifier = Modifier
						.width(52.dp)
						.height(72.dp)
						.background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(12.dp)),
					contentAlignment = Alignment.Center,
				) {
					Icon(
						painter = painterResource(R.drawable.ic_heart),
						contentDescription = null,
						modifier = Modifier.size(28.dp),
					)
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
				Spacer(Modifier.height(4.dp))
				Text(
					text = stringResource(R.string.categories),
					style = MaterialTheme.typography.bodyMedium,
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
			) {
				CircularProgressIndicator()
			}
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
		}
	}

	@Composable
	private fun CategoryPages(categories: List<MangaCategoryItem>, enabled: Boolean, pageSize: Int) {
		val pages = remember(categories, pageSize) { categories.chunked(pageSize) }
		val pagerState = rememberPagerState(pageCount = { pages.size })
		val firstPageSize = minOf(pageSize, categories.size)
		val useTwoColumns = categories.size > SINGLE_COLUMN_LIMIT
		val rowCount = if (useTwoColumns) (firstPageSize + 1) / 2 else firstPageSize
		val categoryAreaHeight =
			(rowCount * CATEGORY_ROW_HEIGHT) + (maxOf(0, rowCount - 1) * CATEGORY_ROW_GAP)
		HorizontalPager(
			state = pagerState,
			modifier = Modifier.fillMaxWidth().height(categoryAreaHeight.dp),
		) { page ->
			CategoryPage(pages[page], enabled, useTwoColumns)
		}
		if (pages.size > 1) {
			Spacer(Modifier.height(10.dp))
			Row(
				modifier = Modifier.fillMaxWidth(),
				horizontalArrangement = Arrangement.Center,
				verticalAlignment = Alignment.CenterVertically,
			) {
				pages.indices.forEach { page ->
					Box(
						modifier = Modifier
							.padding(horizontal = 3.dp)
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
			Column(
				modifier = Modifier.weight(1f),
				verticalArrangement = Arrangement.spacedBy(CATEGORY_ROW_GAP.dp),
			) {
				left.forEach { CategoryRow(it, enabled) }
			}
			Column(
				modifier = Modifier.weight(1f),
				verticalArrangement = Arrangement.spacedBy(CATEGORY_ROW_GAP.dp),
			) {
				right.forEach { CategoryRow(it, enabled) }
			}
		}
	}

	@Composable
	private fun ActionRow(isSaving: Boolean) {
		Row(
			modifier = Modifier.fillMaxWidth(),
			horizontalArrangement = Arrangement.spacedBy(8.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			TextButton(
				onClick = { dismiss() },
				enabled = !isSaving,
				modifier = Modifier.weight(1f).height(48.dp),
			) {
				Text(stringResource(android.R.string.cancel))
			}
			FilledTonalButton(
				onClick = ::openCategoryCreate,
				enabled = !isSaving,
				modifier = Modifier.size(48.dp),
				contentPadding = PaddingValues(0.dp),
			) {
				Text(text = "+", style = MaterialTheme.typography.titleLarge)
			}
			Button(
				onClick = { viewModel.save() },
				enabled = !isSaving,
				modifier = Modifier.weight(1f).height(48.dp),
			) {
				Text(stringResource(android.R.string.ok))
			}
		}
	}

	@Composable
	private fun CategoryRow(item: MangaCategoryItem, enabled: Boolean) {
		val checked = item.checkedState == MaterialCheckBox.STATE_CHECKED
		val toggle = {
			if (enabled) viewModel.setChecked(item.category.id, !checked)
		}
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.height(CATEGORY_ROW_HEIGHT.dp)
				.clip(RoundedCornerShape(CATEGORY_CORNER_RADIUS.dp))
				.background(
					if (checked) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f)
					else MaterialTheme.colorScheme.surfaceContainer,
				)
				.clickable(enabled = enabled) { toggle() }
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
			)
			Spacer(Modifier.size(CATEGORY_CHECKBOX_TEXT_GAP.dp))
			Text(
				text = item.category.title,
				style = MaterialTheme.typography.bodyMedium,
				color = MaterialTheme.colorScheme.onSurface,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
				modifier = Modifier.weight(1f),
			)
			if (item.isTrackerEnabled && item.category.isTrackingEnabled) {
				Icon(
					painter = painterResource(R.drawable.ic_notification),
					contentDescription = null,
					tint = MaterialTheme.colorScheme.onSurfaceVariant,
					modifier = Modifier.size(16.dp),
				)
			}
			if (!item.category.isVisibleInLibrary) {
				Icon(
					painter = painterResource(R.drawable.ic_eye_off),
					contentDescription = null,
					tint = MaterialTheme.colorScheme.onSurfaceVariant,
					modifier = Modifier.size(16.dp),
				)
			}
		}
	}

	private fun calculatePageSize(screenHeightDp: Int, showHeader: Boolean): Int {
		val reservedHeight = if (showHeader) 236 else 152
		val available = (screenHeightDp - reservedHeight).coerceAtLeast(CATEGORY_ROW_HEIGHT)
		val rows = ((available + CATEGORY_ROW_GAP) / (CATEGORY_ROW_HEIGHT + CATEGORY_ROW_GAP))
			.coerceIn(MIN_ROWS_PER_PAGE, MAX_ROWS_PER_PAGE)
		return (rows * 2).coerceAtMost(PAGE_SIZE)
	}

	private companion object {
		const val SINGLE_COLUMN_LIMIT = 10
		const val PAGE_SIZE = 20
		const val MIN_ROWS_PER_PAGE = 5
		const val MAX_ROWS_PER_PAGE = 10
		const val CATEGORY_ROW_HEIGHT = 56
		const val CATEGORY_CORNER_RADIUS = 12
		const val CATEGORY_ROW_GAP = 8
		const val CATEGORY_COLUMN_GAP = 12
		const val CATEGORY_HORIZONTAL_PADDING = 12
		const val CATEGORY_CHECKBOX_TEXT_GAP = 8
	}
}
