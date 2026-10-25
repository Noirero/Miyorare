package org.koitharu.kotatsu.favourites.ui.sets

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.fragment.app.viewModels
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.ui.LocalMiyorareVisualPalette
import org.koitharu.kotatsu.core.ui.MiyorareVisualPalette
import org.koitharu.kotatsu.core.util.ext.getDisplayMessage
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.core.util.ext.observeEvent
import org.koitharu.kotatsu.favourites.data.EXTRA_FAVOURITE_CONTENT_TYPE
import org.koitharu.kotatsu.favourites.data.EXTRA_FAVOURITE_SET_ID
import org.koitharu.kotatsu.favourites.data.EXTRA_FAVOURITE_SPACE
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
import org.koitharu.kotatsu.favourites.ui.categories.FavouriteCategoriesActivity
import org.koitharu.kotatsu.settings.compose.MiyorareTheme

@AndroidEntryPoint
class CategorySetsDialog : BottomSheetDialogFragment() {
	private val viewModel by viewModels<CategorySetsViewModel>()

	override fun onCreateDialog(savedInstanceState: Bundle?) = super.onCreateDialog(savedInstanceState).apply {
		if (viewModel.space == FavouriteSpace.PRIVATE) window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
	}

	override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
		val composeView = ComposeView(requireContext()).apply {
			setBackgroundColor(android.graphics.Color.TRANSPARENT)
		}
		composeView.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
		composeView.setContent { MiyorareTheme { Content() } }
		return composeView
	}

	override fun onStart() {
		super.onStart()
		// Compose owns the rounded translucent surface; the native host owns anchoring, scrim and dismissal.
		(dialog as BottomSheetDialog).findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
			?.setBackgroundColor(android.graphics.Color.TRANSPARENT)
		(dialog as BottomSheetDialog).window?.setDimAmount(0.16f)
		(dialog as BottomSheetDialog).behavior.apply {
			skipCollapsed = true
			state = BottomSheetBehavior.STATE_EXPANDED
		}
	}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)
		viewModel.onError.observeEvent(viewLifecycleOwner) { Toast.makeText(requireContext(), it.getDisplayMessage(resources), Toast.LENGTH_LONG).show() }
		viewModel.isLoading.observe(viewLifecycleOwner) { busy -> isCancelable = !busy }
	}

	@Composable private fun Content() {
		val palette = LocalMiyorareVisualPalette.current
		val sets by viewModel.sets.collectAsState()
		val rows by viewModel.rows.collectAsState()
		val busy by viewModel.isLoading.collectAsState()
		var editing by rememberSaveable { mutableStateOf(arguments?.getBoolean("create") == true) }
		var editId by rememberSaveable { mutableStateOf<Long?>(null) }
		var name by rememberSaveable { mutableStateOf("") }
		var deleteId by rememberSaveable { mutableStateOf<Long?>(null) }
		var destructive by rememberSaveable { mutableStateOf(false) }
		var targetId by rememberSaveable { mutableStateOf<Long?>(null) }
		var impact by remember { mutableStateOf<Pair<Int, Int>?>(null) }
		LaunchedEffect(deleteId, sets) {
			impact = null
			deleteId?.let { id ->
				try { impact = viewModel.impact(id) }
				catch (error: kotlinx.coroutines.CancellationException) { throw error }
				catch (error: Exception) { Toast.makeText(requireContext(), error.getDisplayMessage(resources), Toast.LENGTH_LONG).show() }
			}
		}
		val hostView = LocalView.current
		val list = rememberLazyListState()
		val reorder = remember { CategorySetReorderState(list) }
		var pending by remember { mutableStateOf(false) }
		var revealCreated by rememberSaveable { mutableStateOf(false) }
		val dragging = reorder.draggingId != null
		LaunchedEffect(rows, dragging, pending) { if (!dragging && !pending) reorder.order = rows }
		LaunchedEffect(rows, revealCreated) {
			if (revealCreated) {
				val index = rows.indexOfFirst { it.isActive && it.set.title == name.trim() }
				if (index >= 0) { list.animateScrollToItem(index); revealCreated = false }
			}
		}
		val edge = with(LocalDensity.current) { 48.dp.toPx() }
		LaunchedEffect(reorder.draggingId) { if (dragging) reorder.autoScroll(edge) }
		LaunchedEffect(list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset, dragging, busy) {
			(dialog as? BottomSheetDialog)?.behavior?.isDraggable = !dragging && !busy && !list.canScrollBackward
		}
		fun commit(ids: List<Long>) {
			if (ids == rows.map { it.set.setId }) return
			pending = true
			viewModel.reorder(ids) { pending = false }
		}
		fun move(id: Long, offset: Int): Boolean {
			if (busy || pending || dragging) return false
			val ids = rows.map { it.set.setId }.toMutableList()
			val from = ids.indexOf(id)
			if (from < 0 || from + offset !in ids.indices) return false
			ids.add(from + offset, ids.removeAt(from))
			commit(ids)
			return true
		}
		val enabled = !busy && !pending && !dragging
		val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.86f).dp
		val sheetShape = RoundedCornerShape(topStart = 34.dp, topEnd = 34.dp)
		Box(
			Modifier
				.fillMaxWidth()
				.heightIn(max = maxHeight)
				.categorySetsGlass(palette, sheetShape, emphasis = 0.38f, glowWidth = 2.dp)
				.imePadding()
				.navigationBarsPadding()
				.testTag("category-sets-sheet"),
		) {
			Column(Modifier.fillMaxWidth()) {
				Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
					Box(
						Modifier
							.padding(top = 12.dp, bottom = 8.dp)
							.size(48.dp, 5.dp)
							.background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f), RoundedCornerShape(3.dp)),
					)
				}
				Row(
					Modifier.fillMaxWidth().padding(start = 24.dp, end = 14.dp),
					verticalAlignment = Alignment.CenterVertically,
				) {
					Text(
						stringResource(R.string.category_set_manage),
						Modifier.weight(1f),
						style = MaterialTheme.typography.headlineMedium,
						color = MaterialTheme.colorScheme.onSurface,
					)
					IconButton(
						onClick = { dismiss() },
						enabled = enabled,
						modifier = Modifier
							.size(54.dp)
							.categorySetsGlass(palette, CircleShape, emphasis = 0.26f, glowWidth = 3.dp)
							.testTag("set-close"),
					) {
						Icon(
							painterResource(R.drawable.ic_close),
							stringResource(R.string.close),
							modifier = Modifier.size(28.dp),
							tint = MaterialTheme.colorScheme.onSurface,
						)
					}
				}
				Text(
					stringResource(R.string.category_set_manage_subtitle),
					Modifier.padding(horizontal = 24.dp).padding(top = 6.dp, bottom = 18.dp),
					style = MaterialTheme.typography.bodyLarge,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
				if (busy || pending) {
					LinearProgressIndicator(
						Modifier.fillMaxWidth().padding(horizontal = 22.dp),
						color = palette.resolvedPrimary(),
						trackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.34f),
					)
				}
				LazyColumn(
					Modifier.weight(1f, fill = false).fillMaxWidth().testTag("set-list"),
					state = list,
					contentPadding = PaddingValues(horizontal = 18.dp, vertical = 6.dp),
					verticalArrangement = Arrangement.spacedBy(12.dp),
				) {
					itemsIndexed(reorder.order, key = { _, row -> row.set.setId }) { index, row ->
						val set = row.set
						val up = stringResource(R.string.category_set_up)
						val down = stringResource(R.string.category_set_down)
						var overflow by remember { mutableStateOf(false) }
						val rowDragging = reorder.draggingId == set.setId
						val rowShape = RoundedCornerShape(24.dp)
						Box(
							modifier = Modifier
								.testTag("set-row:${set.setId}")
								.animateItem(placementSpec = if (rowDragging) null else androidx.compose.animation.core.spring())
								.zIndex(if (rowDragging) 1f else 0f)
								.graphicsLayer { translationY = reorder.translation(set.setId) }
								.semantics(mergeDescendants = true) {
									selected = row.isActive
									customActions = buildList {
										if (enabled && index > 0) add(CustomAccessibilityAction(up) { move(set.setId, -1) })
										if (enabled && index < rows.lastIndex) add(CustomAccessibilityAction(down) { move(set.setId, 1) })
									}
								}
								.categorySetsGlass(
									palette = palette,
									shape = rowShape,
									emphasis = if (row.isActive) 0.88f else 0.18f,
									glowWidth = if (row.isActive) 5.dp else 2.dp,
								)
						) {
							Row(
								Modifier.fillMaxWidth().heightIn(min = 86.dp).padding(horizontal = 6.dp, vertical = 8.dp),
								verticalAlignment = Alignment.CenterVertically,
							) {
								IconButton(
									onClick = {},
									enabled = !busy && !pending,
									modifier = Modifier.testTag("set-drag:${set.setId}")
										.onPreviewKeyEvent { event ->
											event.type == KeyEventType.KeyDown && when (event.key) {
												Key.DirectionUp -> move(set.setId, -1)
												Key.DirectionDown -> move(set.setId, 1)
												else -> false
											}
										}
										.pointerInput(Unit) {
											awaitEachGesture {
												awaitFirstDown(requireUnconsumed = false)
												hostView.parent?.requestDisallowInterceptTouchEvent(true)
												waitForUpOrCancellation()
											}
										}
										.pointerInput(set.setId, busy, pending) {
											if (!busy && !pending) detectDragGestures(
												onDragStart = { reorder.start(set.setId) },
												onDragEnd = { commit(reorder.stop()) },
												onDragCancel = { reorder.stop(); reorder.order = rows },
											) { change, delta -> change.consume(); reorder.drag(delta.y) }
										},
								) {
									Icon(
										painterResource(R.drawable.ic_reorder_handle),
										stringResource(R.string.category_set_drag, set.title),
										tint = MaterialTheme.colorScheme.onSurfaceVariant,
									)
								}
								Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
									Text(
										set.title,
										maxLines = 1,
										overflow = TextOverflow.Ellipsis,
										style = MaterialTheme.typography.titleLarge,
										color = MaterialTheme.colorScheme.onSurface,
									)
									Text(
										if (row.categoryCount == 0) stringResource(R.string.category_set_no_categories)
										else pluralStringResource(R.plurals.category_set_category_count, row.categoryCount, row.categoryCount),
										style = MaterialTheme.typography.bodyMedium,
										color = MaterialTheme.colorScheme.onSurfaceVariant,
									)
								}
								if (row.isActive) {
									Row(
										Modifier
											.padding(start = 8.dp)
											.categorySetsGlass(palette, RoundedCornerShape(50), emphasis = 0.72f, glowWidth = 2.dp)
											.padding(horizontal = 12.dp, vertical = 7.dp),
										verticalAlignment = Alignment.CenterVertically,
									) {
										Icon(
											painterResource(R.drawable.ic_check),
											null,
											Modifier.size(18.dp),
											tint = palette.resolvedPrimary(),
										)
										Text(
											stringResource(R.string.category_set_active),
											Modifier.padding(start = 6.dp),
											style = MaterialTheme.typography.labelLarge,
											color = MaterialTheme.colorScheme.onSurface,
										)
									}
								}
								Box {
									IconButton(
										onClick = { overflow = true },
										enabled = enabled,
										modifier = Modifier
											.size(50.dp)
											.categorySetsGlass(palette, CircleShape, emphasis = if (row.isActive) 0.68f else 0.20f, glowWidth = 2.dp)
											.testTag("set-overflow:${set.setId}"),
									) {
										Icon(
											painterResource(R.drawable.ic_more_vert),
											stringResource(R.string.category_set_overflow, set.title),
											tint = MaterialTheme.colorScheme.onSurface,
										)
									}
									DropdownMenu(expanded = overflow && enabled, onDismissRequest = { overflow = false }) {
										DropdownMenuItem(text = { Text(stringResource(R.string.category_set_rename_action)) }, modifier = Modifier.testTag("set-rename:${set.setId}"), onClick = { overflow = false; editId = set.setId; name = set.title; editing = true })
										DropdownMenuItem(text = { Text(stringResource(R.string.category_set_manage_categories)) }, modifier = Modifier.testTag("set-categories:${set.setId}"), onClick = {
											overflow = false
											startActivity(Intent(requireContext(), FavouriteCategoriesActivity::class.java)
												.putExtra(EXTRA_FAVOURITE_SPACE, viewModel.space.dbValue)
												.putExtra(EXTRA_FAVOURITE_SET_ID, set.setId)
												.putExtra(EXTRA_FAVOURITE_CONTENT_TYPE, viewModel.contentType.name))
											dismiss()
										})
										DropdownMenuItem(
											text = { Text(stringResource(R.string.category_set_delete), color = MaterialTheme.colorScheme.error) },
											enabled = sets.size > 1,
											modifier = Modifier.testTag("set-delete:${set.setId}"),
											onClick = { overflow = false; impact = null; deleteId = set.setId; targetId = sets.firstOrNull { it.setId != set.setId }?.setId; destructive = false },
										)
									}
								}
							}
						}
					}
				}
				if (sets.size == 1) {
					Text(
						stringResource(R.string.category_set_last),
						Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
				}
				val createShape = RoundedCornerShape(24.dp)
				Button(
					enabled = enabled,
					onClick = { editId = null; name = ""; editing = true },
					modifier = Modifier
						.padding(horizontal = 18.dp, vertical = 10.dp)
						.fillMaxWidth()
						.heightIn(min = 58.dp)
						.categorySetsGlass(palette, createShape, emphasis = 0.12f, glowWidth = 0.dp, solidBorder = false)
						.dashedOutline(palette.resolvedPrimary().copy(alpha = 0.88f), 24.dp, 1.5.dp)
						.testTag("set-create"),
					shape = createShape,
					colors = ButtonDefaults.buttonColors(
						containerColor = Color.Transparent,
						contentColor = palette.resolvedPrimary(),
						disabledContainerColor = Color.Transparent,
						disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
					),
					contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
				) {
					Box(
						Modifier
							.size(36.dp)
							.border(1.5.dp, palette.resolvedPrimary(), CircleShape),
						contentAlignment = Alignment.Center,
					) {
						Icon(painterResource(R.drawable.ic_add), null, Modifier.size(24.dp))
					}
					Text(
						stringResource(R.string.category_set_create_new),
						Modifier.padding(start = 12.dp),
						style = MaterialTheme.typography.titleMedium,
					)
				}
				val doneShape = RoundedCornerShape(26.dp)
				Button(
					onClick = { dismiss() },
					enabled = enabled,
					modifier = Modifier
						.padding(horizontal = 18.dp)
						.padding(bottom = 14.dp)
						.fillMaxWidth()
						.heightIn(min = 58.dp)
						.categorySetsAccent(palette, doneShape)
						.testTag("set-done"),
					shape = doneShape,
					colors = ButtonDefaults.buttonColors(
						containerColor = Color.Transparent,
						contentColor = palette.resolvedOnPrimary(),
						disabledContainerColor = Color.Transparent,
						disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
					),
				) {
					Text(stringResource(R.string.done), style = MaterialTheme.typography.titleLarge)
				}
			}
		}
		if (editing) AlertDialog(
			onDismissRequest = { if (!busy) editing = false },
			properties = androidx.compose.ui.window.DialogProperties(securePolicy = securePolicy()),
			title = { Text(stringResource(if (editId == null) R.string.category_set_create else R.string.category_set_rename)) },
			text = { OutlinedTextField(value = name, onValueChange = { name = it }, enabled = !busy, singleLine = true, label = { Text(stringResource(R.string.category_set_name)) }) },
			confirmButton = { TextButton(enabled = !busy && name.isNotBlank(), onClick = { viewModel.save(editId, name) { editing = false; revealCreated = editId == null } }) { Text(stringResource(R.string.save)) } },
			dismissButton = { TextButton(enabled = !busy, onClick = { editing = false }) { Text(stringResource(android.R.string.cancel)) } },
		)
		val deletedSet = sets.firstOrNull { it.setId == deleteId }
		if (deletedSet != null) AlertDialog(
			onDismissRequest = { if (!busy) deleteId = null },
			properties = androidx.compose.ui.window.DialogProperties(securePolicy = securePolicy()),
			title = { Text(stringResource(R.string.category_set_delete)) },
			text = {
				Column {
					val counts = impact
					if (counts == null) CircularProgressIndicator() else Text(stringResource(if (destructive) R.string.category_set_destructive_confirm else R.string.category_set_delete_confirm, deletedSet.title, counts.first, counts.second))
					if (!destructive) {
						Text(stringResource(R.string.category_set_target))
						LazyColumn(Modifier.heightIn(max = 180.dp)) {
							itemsIndexed(sets.filterNot { it.setId == deleteId }) { _, target ->
								Row(verticalAlignment = Alignment.CenterVertically) {
									RadioButton(modifier = Modifier.semantics { contentDescription = target.title }, selected = targetId == target.setId, onClick = { targetId = target.setId }, enabled = !busy)
									Text(target.title)
								}
							}
						}
						TextButton(enabled = !busy && impact != null, onClick = { destructive = true }) { Text(stringResource(R.string.category_set_delete_categories), color = MaterialTheme.colorScheme.error) }
					}
				}
			},
			confirmButton = { TextButton(enabled = !busy && sets.size > 1 && impact != null && (destructive || sets.any { it.setId == targetId && it.setId != deleteId }), onClick = { viewModel.delete(deletedSet.setId, targetId, destructive) { deleteId = null } }) { Text(stringResource(if (destructive) R.string.category_set_delete_categories else R.string.category_set_move_delete)) } },
			dismissButton = { TextButton(enabled = !busy, onClick = { deleteId = null }) { Text(stringResource(android.R.string.cancel)) } },
		)
	}

	private fun securePolicy() = if (viewModel.space == FavouriteSpace.PRIVATE) {
		androidx.compose.ui.window.SecureFlagPolicy.SecureOn
	} else androidx.compose.ui.window.SecureFlagPolicy.Inherit
}

private fun MiyorareVisualPalette.resolvedPrimary(): Color = primary

@Composable
private fun MiyorareVisualPalette.resolvedOnPrimary(): Color = if (isModern) onButton else MaterialTheme.colorScheme.onPrimary

@Composable
private fun Modifier.categorySetsGlass(
	palette: MiyorareVisualPalette,
	shape: Shape,
	emphasis: Float,
	glowWidth: Dp,
	solidBorder: Boolean = true,
): Modifier {
	val strength = emphasis.coerceIn(0f, 1f)
	val colors = if (palette.isModern) {
		listOf(
			palette.surfaceGradientStart.copy(alpha = 0.70f + strength * 0.12f),
			palette.surfaceGradientMiddle.copy(alpha = 0.60f + strength * 0.18f),
			palette.surfaceGradientEnd.copy(alpha = 0.66f + strength * 0.16f),
		)
	} else {
		listOf(
			MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.94f),
			MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.94f),
			MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.94f),
		)
	}
	val borderColor = if (palette.isModern) {
		palette.borderHighlight.copy(alpha = 0.48f + strength * 0.42f)
	} else MaterialTheme.colorScheme.outlineVariant
	val glow = if (palette.isModern) palette.glow.copy(alpha = 0.16f + strength * 0.30f) else Color.Transparent
	return this
		.background(Brush.verticalGradient(colors), shape)
		.then(if (glowWidth > 0.dp && glow.alpha > 0f) Modifier.border(glowWidth, glow, shape) else Modifier)
		.then(if (solidBorder) Modifier.border(1.dp, borderColor, shape) else Modifier)
}

@Composable
private fun Modifier.categorySetsAccent(palette: MiyorareVisualPalette, shape: Shape): Modifier {
	if (!palette.isModern) {
		return background(MaterialTheme.colorScheme.primary, shape)
	}
	return background(
		Brush.horizontalGradient(
			listOf(
				palette.accentGradientStart.copy(alpha = 0.92f),
				palette.accentGradientMiddle.copy(alpha = 0.94f),
				palette.accentGradientEnd.copy(alpha = 0.92f),
			),
		),
		shape,
	)
		.border(5.dp, palette.glow.copy(alpha = 0.42f), shape)
		.border(1.dp, palette.borderHighlight.copy(alpha = 0.92f), shape)
}

@Composable
private fun Modifier.dashedOutline(color: Color, cornerRadius: Dp, width: Dp): Modifier {
	val density = LocalDensity.current
	val strokePx = with(density) { width.toPx() }
	val radius = with(density) { cornerRadius.toPx() }
	val dash = with(density) { 8.dp.toPx() }
	val gap = with(density) { 6.dp.toPx() }
	return drawBehind {
		drawRoundRect(
			color = color,
			cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius),
			style = Stroke(
				width = strokePx,
				pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, gap)),
			),
		)
	}
}
