package org.koitharu.kotatsu.core.ui

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.DialogFragment
import org.koitharu.kotatsu.settings.compose.MiyorareTheme

/**
 * Base for dialogs that render an M3 Expressive [MiyorareTheme] Compose card instead of the legacy
 * [MaterialAlertDialog][com.google.android.material.dialog.MaterialAlertDialogBuilder] chrome. The
 * window is transparent and full-width, so [Content] draws its own rounded surface (typically an
 * [org.koitharu.kotatsu.core.ui.dialog.ExpressiveDialogCard]).
 *
 * Stays a [DialogFragment] on purpose: subclasses keep their `by viewModels()`, Hilt injection and
 * `registerForActivityResult` launchers, which a plain dialog could not host.
 */
abstract class ComposeAlertDialogFragment : DialogFragment() {

	override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
		return super.onCreateDialog(savedInstanceState).apply {
			window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
		}
	}

	final override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?,
	): View {
		val view = ComposeView(requireContext())
		// Fragment wires its viewLifecycleOwner onto this view, so Compose gets its tree owners.
		view.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
		// NB: not `ComposeView(...).apply { setContent { Content() } }` — inside `apply` the receiver
		// is the ComposeView, whose own `Content()` would shadow ours and recurse forever (StackOverflow).
		view.setContent {
			MiyorareTheme {
				this@ComposeAlertDialogFragment.Content()
			}
		}
		return view
	}

	override fun onStart() {
		super.onStart()
		// Match the plain expressive dialog: full-width window, height wraps the card.
		dialog?.window?.setLayout(
			ViewGroup.LayoutParams.MATCH_PARENT,
			ViewGroup.LayoutParams.WRAP_CONTENT,
		)
	}

	@Composable
	protected abstract fun Content()
}
