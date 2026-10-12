package org.koitharu.kotatsu.details.ui.pager

import android.content.Context
import android.content.ContextWrapper
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import org.koitharu.kotatsu.SampleData
import org.koitharu.kotatsu.bookmarks.domain.BookmarksRepository
import org.koitharu.kotatsu.core.model.MangaSource
import org.koitharu.kotatsu.core.parser.MangaDataRepository
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.details.data.ChapterPersonalRepository
import org.koitharu.kotatsu.details.data.MangaDetails
import org.koitharu.kotatsu.details.domain.DetailsInteractor
import org.koitharu.kotatsu.download.domain.DownloadDestinationStore
import org.koitharu.kotatsu.download.ui.worker.DownloadWorker
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
import org.koitharu.kotatsu.history.data.HistoryRepository
import org.koitharu.kotatsu.local.domain.DeleteLocalMangaUseCase
import org.koitharu.kotatsu.local.domain.model.LocalManga
import java.util.UUID
import javax.inject.Inject

/** Real shared ViewModel mutations and Android preferences, isolated from other runtime tests. */
class ChapterSortDirectionTestFixture @Inject constructor(
	private val interactor: DetailsInteractor,
	private val bookmarksRepository: BookmarksRepository,
	private val historyRepository: HistoryRepository,
	private val scheduler: DownloadWorker.Scheduler,
	private val destinations: DownloadDestinationStore,
	private val deleteLocal: DeleteLocalMangaUseCase,
	private val mangaDataRepository: MangaDataRepository,
	private val repositories: MangaRepository.Factory,
	private val personalRepository: ChapterPersonalRepository,
) {
	suspend fun withIsolatedPreferences(block: suspend Session.() -> Unit) {
		val target = InstrumentationRegistry.getInstrumentation().targetContext
		val prefs = target.getSharedPreferences("chapter_sort_test_${UUID.randomUUID()}", Context.MODE_PRIVATE)
		val context = object : ContextWrapper(target) {
			override fun getSharedPreferences(name: String?, mode: Int) = prefs
		}
		val session = Session(context)
		try {
			session.block()
		} finally {
			session.clearViewModels()
			check(prefs.edit().clear().commit())
		}
	}

	inner class Session(private val context: Context) {
		val settings = AppSettings(context)
		val store = ChapterListOptionsStore(context, settings)
		private val viewModels = ViewModelStore()
		private var nextId = 0

		init {
			store.setDefault(FavouriteSpace.NORMAL, false, ChapterListOptions(
				unreadOnly = true, titleMode = ChapterTitleMode.NUMBER, grid = true,
			))
			store.setGroupingMode(FavouriteSpace.NORMAL, false, ChapterGroupingMode.CHAPTERS_25)
		}

		suspend fun create(detailsDefaults: Boolean = true): ChaptersPagesViewModel {
			// Reconstruct settings/store as a reopened consumer does, using the same persisted prefs.
			val restoredSettings = AppSettings(context)
			val restoredStore = if (detailsDefaults) ChapterListOptionsStore(context, restoredSettings) else null
			// SampleData's LOCAL source has a remote-style locator; it is not a local file fixture.
			val source = MangaSource("MIHON_424242")
			val manga = SampleData.mangaDetails.copy(
				id = 906L, source = source,
				url = "https://example.invalid/manga/chapter-sort",
				publicUrl = "https://example.invalid/manga/chapter-sort",
				chapters = SampleData.mangaDetails.chapters?.map { chapter ->
					chapter.copy(source = source, url = "https://example.invalid/chapter/${chapter.id}")
				},
			)
			lateinit var result: ChaptersPagesViewModel
			onMain {
				result = object : ChaptersPagesViewModel(
					settings = restoredSettings,
					interactor = interactor,
					bookmarksRepository = bookmarksRepository,
					historyRepository = historyRepository,
					downloadScheduler = scheduler,
					downloadDestinationStore = destinations,
					favouriteSpace = FavouriteSpace.NORMAL,
					deleteLocalMangaUseCase = deleteLocal,
					localStorageChanges = MutableSharedFlow<LocalManga?>(),
					mangaDataRepository = mangaDataRepository,
					mangaRepositoryFactory = repositories,
					chapterListOptionsStore = restoredStore,
					chapterPersonalRepository = personalRepository,
				) {
					override fun reload() = Unit
				}
				viewModels.put("sort-${nextId++}", result)
				result.mangaDetails.value = MangaDetails(manga)
			}
			if (restoredStore != null) {
				val expected = restoredStore.getDefault(FavouriteSpace.NORMAL, false)
				withTimeout(5_000L) { result.chapterListOptions.first { it == expected } }
			}
			return result
		}

		fun restoredDefault(): ChapterListOptions =
			ChapterListOptionsStore(context, AppSettings(context)).getDefault(FavouriteSpace.NORMAL, false)

		fun onMain(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

		fun clearViewModels() = onMain { viewModels.clear() }
	}
}
