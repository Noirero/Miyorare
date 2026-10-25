package org.koitharu.kotatsu.main.ui

import com.google.android.material.search.SearchBar
import com.google.android.material.search.SearchView
import org.koitharu.kotatsu.R

/**
 * Small ownership boundary for the shared MainActivity SearchBar.
 *
 * A destination may temporarily replace the global/source-search action. Releasing an older owner
 * is intentionally a no-op once another destination has claimed the bar, which prevents stale
 * Fragment lifecycle callbacks from clobbering the newer destination.
 */
internal fun MainActivity.installSharedSearchBarOwner(
	owner: Any,
	hint: CharSequence,
	onClick: () -> Unit,
): Boolean {
	val searchBar = findViewById<SearchBar>(R.id.search_bar) ?: return false
	searchBar.setTag(R.id.shared_search_owner, owner)
	searchBar.hint = hint
	searchBar.setOnClickListener {
		if (searchBar.getTag(R.id.shared_search_owner) === owner) {
			onClick()
		}
	}
	return true
}

internal fun MainActivity.updateSharedSearchBarHint(owner: Any, hint: CharSequence) {
	val searchBar = findViewById<SearchBar>(R.id.search_bar) ?: return
	if (searchBar.getTag(R.id.shared_search_owner) === owner) {
		searchBar.hint = hint
	}
}

internal fun MainActivity.releaseSharedSearchBarOwner(owner: Any) {
	val searchBar = findViewById<SearchBar>(R.id.search_bar) ?: return
	if (searchBar.getTag(R.id.shared_search_owner) !== owner) return
	searchBar.setTag(R.id.shared_search_owner, null)
	val searchView = findViewById<SearchView>(R.id.search_view) ?: return
	searchBar.hint = getString(R.string.search_manga)
	searchBar.setOnClickListener { searchView.show() }
}

internal fun MainActivity.hasSharedSearchBar(): Boolean =
	findViewById<SearchBar>(R.id.search_bar) != null
