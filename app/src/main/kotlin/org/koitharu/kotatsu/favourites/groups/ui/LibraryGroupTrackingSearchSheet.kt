package org.koitharu.kotatsu.favourites.groups.ui

import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.ui.BaseListAdapter
import org.koitharu.kotatsu.core.ui.list.OnListItemClickListener
import org.koitharu.kotatsu.list.ui.adapter.ListItemType
import org.koitharu.kotatsu.list.ui.model.ListModel
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerManga
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService
import org.koitharu.kotatsu.scrobbling.common.ui.selector.adapter.scrobblingMangaAD

/**
 * Group-specific tracking search keeps the Group tracking model independent while reusing the
 * standard tracker result row (cover, alternative title, best-match marker and long-press actions).
 */
internal fun Fragment.showLibraryGroupTrackingSearchSheet(
	service: ScrobblerService,
	initialQuery: String,
	search: suspend (String) -> List<ScrobblerManga>,
	onSelected: (ScrobblerManga) -> Unit,
	onError: () -> Unit,
) {
	val context = requireContext()
	val density = resources.displayMetrics.density
	fun dp(value: Int): Int = (value * density).toInt()

	val horizontalPadding = dp(16)
	val topPadding = dp(12)
	val bottomPadding = dp(16)
	val dialog = BottomSheetDialog(context)
	val root = LinearLayout(context).apply {
		orientation = LinearLayout.VERTICAL
		layoutParams = ViewGroup.LayoutParams(
			ViewGroup.LayoutParams.MATCH_PARENT,
			ViewGroup.LayoutParams.MATCH_PARENT,
		)
		setPadding(horizontalPadding, topPadding, horizontalPadding, bottomPadding)
	}

	val title = TextView(context).apply {
		text = "${getString(R.string.library_group_tracking_search)} · ${getString(service.titleResId)}"
		textSize = 20f
		setTypeface(typeface, Typeface.BOLD)
	}
	root.addView(
		title,
		LinearLayout.LayoutParams(
			ViewGroup.LayoutParams.MATCH_PARENT,
			ViewGroup.LayoutParams.WRAP_CONTENT,
		),
	)

	val inputLayout = TextInputLayout(context).apply {
		hint = getString(R.string.library_group_metadata_search_hint)
		boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
	}
	val input = TextInputEditText(inputLayout.context).apply {
		inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
		imeOptions = EditorInfo.IME_ACTION_SEARCH
		setSingleLine(true)
		setText(initialQuery)
		setSelection(text?.length ?: 0)
	}
	inputLayout.addView(input)
	val searchButton = MaterialButton(context).apply {
		setText(R.string.search)
	}
	val searchRow = LinearLayout(context).apply {
		orientation = LinearLayout.HORIZONTAL
		gravity = Gravity.CENTER_VERTICAL
	}
	searchRow.addView(
		inputLayout,
		LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
	)
	searchRow.addView(
		searchButton,
		LinearLayout.LayoutParams(
			ViewGroup.LayoutParams.WRAP_CONTENT,
			ViewGroup.LayoutParams.WRAP_CONTENT,
		).apply { marginStart = dp(8) },
	)
	root.addView(
		searchRow,
		LinearLayout.LayoutParams(
			ViewGroup.LayoutParams.MATCH_PARENT,
			ViewGroup.LayoutParams.WRAP_CONTENT,
		).apply { topMargin = dp(12) },
	)

	val resultHost = FrameLayout(context)
	val recyclerView = RecyclerView(context).apply {
		layoutManager = LinearLayoutManager(context)
		clipToPadding = false
		setPadding(0, dp(8), 0, dp(8))
		isVerticalScrollBarEnabled = true
	}
	val progress = ProgressBar(context).apply { isVisible = false }
	val status = TextView(context).apply {
		gravity = Gravity.CENTER
		setPadding(dp(16), dp(24), dp(16), dp(24))
		isVisible = false
	}
	resultHost.addView(
		recyclerView,
		FrameLayout.LayoutParams(
			ViewGroup.LayoutParams.MATCH_PARENT,
			ViewGroup.LayoutParams.MATCH_PARENT,
		),
	)
	resultHost.addView(
		status,
		FrameLayout.LayoutParams(
			ViewGroup.LayoutParams.MATCH_PARENT,
			ViewGroup.LayoutParams.WRAP_CONTENT,
			Gravity.CENTER,
		),
	)
	resultHost.addView(
		progress,
		FrameLayout.LayoutParams(
			ViewGroup.LayoutParams.WRAP_CONTENT,
			ViewGroup.LayoutParams.WRAP_CONTENT,
			Gravity.CENTER,
		),
	)
	root.addView(
		resultHost,
		LinearLayout.LayoutParams(
			ViewGroup.LayoutParams.MATCH_PARENT,
			0,
			1f,
		).apply { topMargin = dp(8) },
	)

	val adapter = GroupTrackingSearchAdapter { item ->
		dialog.dismiss()
		onSelected(item)
	}
	recyclerView.adapter = adapter

	var searchJob: Job? = null
	fun runSearch() {
		val query = input.text?.toString()?.trim().orEmpty()
		if (query.isEmpty()) {
			inputLayout.error = getString(R.string.library_group_metadata_search_hint)
			return
		}
		inputLayout.error = null
		searchJob?.cancel()
		adapter.items = emptyList()
		progress.isVisible = true
		status.isVisible = false
		recyclerView.isVisible = true
		searchButton.isEnabled = false
		searchJob = viewLifecycleOwner.lifecycleScope.launch {
			val items = try {
				search(query)
			} catch (cancelled: CancellationException) {
				throw cancelled
			} catch (_: Throwable) {
				if (dialog.isShowing) {
					progress.isVisible = false
					searchButton.isEnabled = true
					adapter.items = emptyList()
					status.setText(R.string.library_group_tracking_error)
					status.isVisible = true
					recyclerView.isVisible = false
					onError()
				}
				return@launch
			}
			if (!dialog.isShowing) return@launch
			progress.isVisible = false
			searchButton.isEnabled = true
			adapter.items = items
			status.setText(R.string.library_group_metadata_no_results)
			status.isVisible = items.isEmpty()
			recyclerView.isVisible = items.isNotEmpty()
		}
	}

	searchButton.setOnClickListener { runSearch() }
	input.setOnEditorActionListener { _, actionId, _ ->
		if (actionId == EditorInfo.IME_ACTION_SEARCH) {
			runSearch()
			true
		} else {
			false
		}
	}

	ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
		val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
		val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
		view.setPadding(
			horizontalPadding + bars.left,
			topPadding,
			horizontalPadding + bars.right,
			bottomPadding + maxOf(bars.bottom, ime.bottom),
		)
		insets
	}

	dialog.setContentView(root)
	dialog.setOnDismissListener { searchJob?.cancel() }
	dialog.show()
	dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
	dialog.behavior.apply {
		state = BottomSheetBehavior.STATE_EXPANDED
		skipCollapsed = true
	}
	dialog.findViewById<FrameLayout>(com.google.android.material.R.id.design_bottom_sheet)?.let { sheet ->
		sheet.layoutParams = sheet.layoutParams.apply {
			height = (resources.displayMetrics.heightPixels * 0.82f).toInt()
		}
		sheet.requestLayout()
	}
	ViewCompat.requestApplyInsets(root)

	// The group title is already a strong initial query, so show useful visual results immediately.
	root.post { runSearch() }
}

private class GroupTrackingSearchAdapter(
	onSelected: (ScrobblerManga) -> Unit,
) : BaseListAdapter<ListModel>() {

	init {
		addDelegate(
			ListItemType.MANGA_SCROBBLING,
			scrobblingMangaAD(OnListItemClickListener { item, _ -> onSelected(item) }),
		)
	}
}
