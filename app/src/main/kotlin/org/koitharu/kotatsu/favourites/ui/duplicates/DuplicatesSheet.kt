package org.koitharu.kotatsu.favourites.ui.duplicates

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import coil3.ImageLoader
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.shape.MaterialShapeDrawable
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.getTitle
import org.koitharu.kotatsu.core.model.parcelable.ParcelableManga
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.ui.sheet.BaseAdaptiveSheet
import org.koitharu.kotatsu.core.util.ext.consume
import org.koitharu.kotatsu.core.util.ext.getDisplayMessage
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.core.util.ext.observeEvent
import org.koitharu.kotatsu.databinding.SheetDuplicatesBinding
import org.koitharu.kotatsu.favourites.data.EXTRA_FAVOURITE_SPACE
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
import org.koitharu.kotatsu.favourites.ui.categories.select.FavoriteDialog
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.settings.compose.DropSauceTheme
import javax.inject.Inject
import com.google.android.material.R as materialR

/**
 * Warns that the manga about to be favourited already looks like something in the library, and
 * offers to replace it, add it anyway, or skip it. Shown before the category picker, one clashing
 * title at a time — see [DuplicatesViewModel] for how a batch is drained.
 */
@AndroidEntryPoint
class DuplicatesSheet : BaseAdaptiveSheet<SheetDuplicatesBinding>() {

	@Inject
	lateinit var coil: ImageLoader

	private val viewModel by viewModels<DuplicatesViewModel>()

	override fun onCreate(savedInstanceState: Bundle?) {
		// AppRouter historically omitted FavouriteSpace. Resolve it from the owning list/activity before
		// SavedStateHandle creates the ViewModel, so a Private action can never silently fall back to Normal.
		arguments?.let { args ->
			if (!args.containsKey(EXTRA_FAVOURITE_SPACE)) {
				args.putInt(EXTRA_FAVOURITE_SPACE, resolveFavouriteSpace().dbValue)
			}
		}
		super.onCreate(savedInstanceState)
	}

	override fun onCreateViewBinding(inflater: LayoutInflater, container: ViewGroup?): SheetDuplicatesBinding {
		return SheetDuplicatesBinding.inflate(inflater, container, false)
	}

	override fun onViewBindingCreated(binding: SheetDuplicatesBinding, savedInstanceState: Bundle?) {
		super.onViewBindingCreated(binding, savedInstanceState)
		binding.composeView.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
		binding.composeView.setContent {
			DropSauceTheme {
				val state by viewModel.state.collectAsState()
				if (state is DuplicatesState.Ask) {
					DuplicatesContent(
						state = state as DuplicatesState.Ask,
						imageLoader = coil,
						onSkip = viewModel::skip,
						onAddAnyway = viewModel::addAnyway,
						// Preview opens the real details screen; the sheet survives behind it and is
						// still waiting on this same card when the user comes back.
						onPreview = { router.openDetails(it.manga) },
						onReplace = { viewModel.replaceWith(it.manga) },
						onDisableCheck = viewModel::disableDuplicateCheck,
						onMigrateProgressChanged = viewModel::setProgressMigrated,
					)
				}
			}
		}

		setContentVisible(false)
		viewModel.state.observe(viewLifecycleOwner, ::onStateChanged)
		viewModel.onFinished.observeEvent(viewLifecycleOwner, ::onFinished)
		viewModel.onMigrated.observeEvent(viewLifecycleOwner, ::onMigrated)
		viewModel.onError.observeEvent(viewLifecycleOwner) { e ->
			Toast.makeText(binding.root.context, e.getDisplayMessage(resources), Toast.LENGTH_LONG).show()
		}
	}

	override fun onStart() {
		super.onStart()
		// The sheet's own view only exists from here on, so re-apply — onViewBindingCreated can't reach it.
		setContentVisible(viewModel.state.value !is DuplicatesState.Checking)
		(dialog as? BottomSheetDialog)?.let { sheetDialog ->
			// Open straight to the expanded state. `isFitToContents` stays on, so "expanded" still means
			// exactly the content's height for a short list — it just stops a long list opening at the
			// collapsed peek with the action button stranded below the fold.
			sheetDialog.behavior.skipCollapsed = true
			sheetDialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
			// Extra-rounded top, matching the app's other expressive pull-up surfaces. Applied to the
			// view rather than through a theme overlay: this fragment turns into a SideSheetDialog in
			// landscape, and a persisted bottom-sheet overlay makes that side sheet fail to inflate.
			val sheetView = sheetDialog.findViewById<View>(materialR.id.design_bottom_sheet)
			(sheetView?.background as? MaterialShapeDrawable)?.let { background ->
				val corner = resources.getDimension(R.dimen.sheet_corner_expressive)
				background.shapeAppearanceModel = background.shapeAppearanceModel.toBuilder()
					.setTopLeftCornerSize(corner)
					.setTopRightCornerSize(corner)
					.build()
			}
		}
	}

	override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
		val typeMask = WindowInsetsCompat.Type.systemBars()
		viewBinding?.root?.updatePadding(bottom = insets.getInsets(typeMask).bottom)
		return insets.consume(v, typeMask, bottom = true)
	}

	private fun onStateChanged(state: DuplicatesState) {
		when (state) {
			is DuplicatesState.Checking -> setContentVisible(false)

			is DuplicatesState.Ask -> {
				// Dismissing mid-migration would strand a half-answered batch.
				isCancelable = !state.isMigrating
				setContentVisible(true)
			}
		}
	}

	private fun onFinished(manga: List<Manga>) {
		// Hand over through the activity: this fragment is about to be gone, and its own router
		// would have no fragment manager left to show the category dialog with.
		val activity = activity ?: return
		val router = activity.router
		val accentColor = arguments?.let {
			if (it.containsKey(AppRouter.KEY_ACCENT_COLOR)) it.getInt(AppRouter.KEY_ACCENT_COLOR) else null
		}
		val favouriteSpace = FavouriteSpace.fromArgument(
			arguments?.getInt(EXTRA_FAVOURITE_SPACE, FavouriteSpace.NORMAL.dbValue),
		)
		dismiss()
		if (manga.isEmpty()) return

		if (favouriteSpace == FavouriteSpace.NORMAL) {
			router.showFavoriteCategoriesDialog(manga, accentColor)
			return
		}

		FavoriteDialog().apply {
			arguments = Bundle().apply {
				putParcelableArrayList(
					AppRouter.KEY_MANGA_LIST,
					manga.mapTo(ArrayList(manga.size)) { ParcelableManga(it, withDescription = false) },
				)
				putInt(EXTRA_FAVOURITE_SPACE, FavouriteSpace.PRIVATE.dbValue)
				if (accentColor != null) putInt(AppRouter.KEY_ACCENT_COLOR, accentColor)
			}
		}.show(activity.supportFragmentManager, FavoriteDialog::class.java.name)
	}

	private fun onMigrated(result: MigrationResult) {
		val context = context ?: return
		Toast.makeText(
			context,
			getString(
				R.string.duplicates_replaced,
				result.title,
				result.fromSource.getTitle(context),
				result.toSource.getTitle(context),
			),
			Toast.LENGTH_LONG,
		).show()
	}

	private fun resolveFavouriteSpace(): FavouriteSpace {
		var owner: Fragment? = parentFragment
		while (owner != null) {
			val args = owner.arguments
			if (args != null && args.containsKey(EXTRA_FAVOURITE_SPACE)) {
				return FavouriteSpace.fromArgument(args.getInt(EXTRA_FAVOURITE_SPACE))
			}
			owner = owner.parentFragment
		}
		return FavouriteSpace.fromArgument(
			activity?.intent?.getIntExtra(EXTRA_FAVOURITE_SPACE, FavouriteSpace.NORMAL.dbValue),
		)
	}

	/**
	 * The sheet starts transparent while its cheap duplicate query resolves. If the check ever grows
	 * slow enough to be visible here, move it in front of the sheet instead of adding a spinner.
	 */
	private fun setContentVisible(isVisible: Boolean) {
		val alpha = if (isVisible) 1f else 0f
		viewBinding?.root?.alpha = alpha
		dialog?.findViewById<View>(materialR.id.design_bottom_sheet)?.alpha = alpha
		dialog?.findViewById<View>(materialR.id.m3_side_sheet)?.alpha = alpha
		dialog?.window?.setDimAmount(if (isVisible) DEFAULT_DIM else 0f)
	}

	private companion object {
		const val DEFAULT_DIM = 0.32f
	}
}
