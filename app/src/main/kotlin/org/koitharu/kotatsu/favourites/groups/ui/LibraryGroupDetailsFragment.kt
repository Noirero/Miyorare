package org.koitharu.kotatsu.favourites.groups.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.viewModels
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.core.nav.ReaderIntent
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.ui.BaseFragment
import org.koitharu.kotatsu.databinding.FragmentLibraryGroupDetailsBinding
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.reader.ui.ReaderState
import org.koitharu.kotatsu.settings.compose.DropSauceTheme

@AndroidEntryPoint
class LibraryGroupDetailsFragment : BaseFragment<FragmentLibraryGroupDetailsBinding>() {

	private val viewModel by viewModels<LibraryGroupDetailsViewModel>()

	override fun onCreateViewBinding(
		inflater: LayoutInflater,
		container: ViewGroup?,
	) = FragmentLibraryGroupDetailsBinding.inflate(inflater, container, false)

	override fun onViewBindingCreated(
		binding: FragmentLibraryGroupDetailsBinding,
		savedInstanceState: android.os.Bundle?,
	) {
		binding.composeView.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
		binding.composeView.setContent {
			DropSauceTheme {
				val state by viewModel.state.collectAsState()
				LaunchedEffect(state.group?.title) {
					state.group?.title?.let { title -> requireActivity().title = title }
				}
				LibraryGroupDetailsScreen(
					state = state,
					onRetry = viewModel::reload,
					onToggleMember = viewModel::toggleMember,
					onRefreshMember = viewModel::refreshMember,
					onOpenMember = { member -> router.openDetails(member.manga) },
					onChapterClick = ::openChapter,
				)
			}
		}
	}

	override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
		val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
		requireViewBinding().composeView.updatePadding(
			left = bars.left,
			right = bars.right,
			bottom = bars.bottom,
		)
		return insets
	}

	private fun openChapter(member: LibraryGroupDetailsMemberUi, chapter: MangaChapter) {
		val manga = member.manga
		val intent = ReaderIntent.Builder(requireContext())
			.manga(manga)
			.branch(chapter.branch)
			.state(
				ReaderState(
					chapterId = chapter.id,
					page = 0,
					scroll = 0,
				),
			)
			.libraryGroup(viewModel.groupId)
			.build()
		router.openReader(intent)
	}
}
