package org.koitharu.kotatsu.favourites.ui.sets

import android.os.Bundle
import android.view.View
import androidx.appcompat.widget.PopupMenu
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.fragment.app.FragmentManager
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.favourites.data.*
import org.koitharu.kotatsu.favourites.domain.FavouriteContentType

@Composable
fun CategorySetSelector(
	sets: List<FavouriteCategorySetEntity>, active: FavouriteCategorySetEntity?, enabled: Boolean = true,
	onSelect: (FavouriteCategorySetEntity) -> Unit, onManage: (Boolean) -> Unit,
) {
	var expanded by remember { mutableStateOf(false) }
	Box {
		TextButton(onClick = { expanded = true }, enabled = enabled && active != null) {
			Text(stringResource(R.string.category_set_selector, active?.title.orEmpty()))
		}
		DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
			for (set in sets) DropdownMenuItem(
				text = { Text(set.title) },
				modifier = Modifier.semantics { selected = set.setId == active?.setId },
				onClick = { expanded = false; onSelect(set) },
				leadingIcon = if (set.setId == active?.setId) ({ Text("✓") }) else null,
			)
			DropdownMenuItem(text = { Text(stringResource(R.string.category_set_create)) }, onClick = { expanded = false; onManage(true) })
			DropdownMenuItem(text = { Text(stringResource(R.string.category_set_manage)) }, onClick = { expanded = false; onManage(false) })
		}
	}
}

fun showCategorySetMenu(
	anchor: View, manager: FragmentManager, sets: List<FavouriteCategorySetEntity>, activeId: Long?,
	space: FavouriteSpace, onSelect: (FavouriteCategorySetEntity) -> Unit,
) {
	if (!anchor.isEnabled) return
	PopupMenu(anchor.context, anchor).apply {
		sets.forEachIndexed { index, set -> menu.add(0, index + 1, index, set.title).apply { isCheckable = true; isChecked = set.setId == activeId } }
		menu.add(1, -1, sets.size, R.string.category_set_create)
		menu.add(1, -2, sets.size + 1, R.string.category_set_manage)
		setOnMenuItemClickListener { item ->
			if (item.itemId > 0) onSelect(sets[item.itemId - 1])
			else showCategorySets(manager, space, item.itemId == -1)
			true
		}
		show()
	}
}

fun showCategorySets(
	manager: FragmentManager,
	space: FavouriteSpace,
	create: Boolean = false,
	contentType: FavouriteContentType? = null,
) {
	if (manager.findFragmentByTag("category_sets") != null) return
	CategorySetsDialog().apply {
		arguments = Bundle().apply {
			putInt(EXTRA_FAVOURITE_SPACE, space.dbValue)
			putBoolean("create", create)
			contentType?.let { putString(EXTRA_FAVOURITE_CONTENT_TYPE, it.name) }
		}
	}.show(manager, "category_sets")
}
