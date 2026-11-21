package org.koitharu.kotatsu.settings.journey

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.viewModels
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.settings.compose.BaseComposeSettingsFragment
import org.koitharu.kotatsu.settings.compose.MiyorareTheme

/**
 * Reader Journey panel reached by swiping right on the root Settings screen.
 *
 * The panel is a compact, read-only summary of the same Journey data shown by the full
 * Journey screen; its visual model (Malam Anggun, Ungu Fajar, Paspor Baca,
 * Kartu Perpustakaan) is selectable inside the panel and persisted in AppSettings.
 */
@AndroidEntryPoint
class SettingsJourneyPanelFragment : BaseComposeSettingsFragment(R.string.reader_journey) {

	private val viewModel by viewModels<SettingsJourneyPanelViewModel>()

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?,
	): View = ComposeView(requireContext()).apply {
		setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
		setContent {
			MiyorareTheme {
				val stats by viewModel.stats.collectAsState()
				val profile by viewModel.profile.collectAsState()
				val model by viewModel.model.collectAsState()

				SettingsJourneyPanelScreen(
					stats = stats,
					profile = profile,
					model = model,
					onModelChange = viewModel::setModel,
				)
			}
		}
	}
}
