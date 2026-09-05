package org.koitharu.kotatsu.details.ui

import android.app.assist.AssistContent
import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.Toast
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import coil3.ImageLoader
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNot
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.nav.ReaderIntent
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.os.AppShortcutManager
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.VisualEffectPreferences
import org.koitharu.kotatsu.core.ui.BaseActivity
import org.koitharu.kotatsu.core.ui.dialog.buildAlertDialog
import org.koitharu.kotatsu.core.ui.util.MenuInvalidator
import org.koitharu.kotatsu.core.ui.util.ReversibleActionObserver
import org.koitharu.kotatsu.core.util.ext.copyToClipboard
import org.koitharu.kotatsu.core.util.ext.getThemeColor
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.core.util.ext.observeEvent
import org.koitharu.kotatsu.core.util.ext.toUriOrNull
import org.koitharu.kotatsu.databinding.ActivityDetailsExpressiveBinding
import org.koitharu.kotatsu.details.service.MangaPrefetchService
import org.koitharu.kotatsu.details.ui.model.ChapterListItem
import org.koitharu.kotatsu.details.ui.pager.ChaptersPagesViewModel
import org.koitharu.kotatsu.download.ui.worker.DownloadStartedObserver
import org.koitharu.kotatsu.parsers.model.ContentRating
import org.koitharu.kotatsu.parsers.util.nullIfEmpty
import org.koitharu.kotatsu.reader.ui.ReaderState
import org.koitharu.kotatsu.reader.ui.showChapterJumpDialog
import org.koitharu.kotatsu.settings.compose.rememberBooleanPref
import org.koitharu.kotatsu.settings.compose.rememberDetailsBackdropBlurPref
import javax.inject.Inject

/**
 * Material 3 Expressive details screen. The chapter list is rendered inline by Compose using the
 * same DetailsViewModel state as the existing chapter management sheet, so there is no second
 * database/repository pipeline and advanced chapter controls remain available through Manage.
 */
@AndroidEntryPoint
class DetailsExpressiveActivity :
	BaseActivity<ActivityDetailsExpressiveBinding>() {

	@Inject lateinit var coil: ImageLoader
	@Inject lateinit var settings: AppSettings
	@Inject lateinit var shortcutManager: AppShortcutManager
	@Inject lateinit var visualEffectPreferences: VisualEffectPreferences

	private val viewModel: DetailsViewModel by viewModels()
	private lateinit var menuProvider: DetailsMenuProvider

	private val topInset = mutableIntStateOf(0)
	private val bottomInset = mutableIntStateOf(0)
	private val mangaNote = mutableStateOf<String?>(null)
	private val notesPreferences by lazy { getSharedPreferences(NOTES_PREFERENCES, Context.MODE_PRIVATE) }
	private var isDarkTheme = false

	private var contentAtTop = true

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(ActivityDetailsExpressiveBinding.inflate(layoutInflater))
		WindowCompat.setDecorFitsSystemWindows(window, false)
		isDarkTheme = ColorUtils.calculateLuminance(getThemeColor(android.R.attr.colorBackground)) <= 0.5
		WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = !isDarkTheme
		setDisplayHomeAsUp(isEnabled = true, showUpAsClose = false)
		supportActionBar?.setDisplayShowTitleEnabled(false)
		mangaNote.value = loadNote()
		setupContent()
		setupSwipeRefresh()

		menuProvider = DetailsMenuProvider(
			activity = this,
			viewModel = viewModel,
			snackbarHost = viewBinding.composeView,
			appShortcutManager = shortcutManager,
			onNoteClick = ::showNoteDialog,
		)
		addMenuProvider(menuProvider)

		val menuInvalidator = MenuInvalidator(this)
		viewModel.isStatsAvailable.observe(this, menuInvalidator)
		viewModel.remoteManga.observe(this, menuInvalidator)
		viewModel.mangaDetails.observe(this) {
			it?.let { d -> title = d.toManga().title }
			invalidateOptionsMenu()
		}
		viewModel.onTrackingProgressSynced.observeEvent(this) { chapter ->
			Toast.makeText(
				this,
				getString(R.string.tracking_progress_synced, chapter),
				Toast.LENGTH_SHORT,
			).show()
		}
		viewModel.onActionDone
			.filterNot { router.isChapterPagesSheetShown() }
			.observeEvent(this, ReversibleActionObserver(viewBinding.composeView))
		viewModel.onError
			.filterNot { router.isChapterPagesSheetShown() }
			.observeEvent(
				this,
				DetailsErrorObserver(
					activity = this,
					snackbarHost = viewBinding.composeView,
					bottomSheet = null,
					viewModel = viewModel,
					resolver = exceptionResolver,
				),
			)
		viewModel.onMangaRemoved.observeEvent(this) { finishAfterTransition() }
		viewModel.onDownloadStarted
			.filterNot { router.isChapterPagesSheetShown() }
			.observeEvent(this, DownloadStartedObserver(viewBinding.composeView))
		viewModel.chapters.observe(this, PrefetchObserver(this))
	}

	override fun onProvideAssistContent(outContent: AssistContent) {
		super.onProvideAssistContent(outContent)
		viewModel.getMangaOrNull()?.publicUrl?.toUriOrNull()?.let { outContent.webUri = it }
	}

	override fun isNsfwContent(): Flow<Boolean> =
		viewModel.manga.map { it?.contentRating == ContentRating.ADULT }

	private fun setupContent() {
		val actions = DetailsExpressiveActions(
			onCoverClick = { manga ->
				val url = viewModel.coverUrl.value ?: return@DetailsExpressiveActions
				router.openImage(url = url, source = manga.source, manga = manga)
			},
			onTitleClick = { title -> showTitleDialog(title) },
			onSourceClick = { manga -> router.openList(manga.source, null, null) },
			onLocalClick = { manga -> router.showLocalInfoDialog(manga) },
			onFavoriteClick = { manga -> router.showFavoriteDialog(manga, null) },
			onAuthorClick = { author ->
				router.showAuthorDialog(author, viewModel.getMangaOrNull()?.source ?: return@DetailsExpressiveActions)
			},
			onTagClick = { tag -> router.showTagDialog(tag) },
			onScrobblingMore = {
				router.showScrobblingSelectorSheet(
					manga = viewModel.getMangaOrNull() ?: return@DetailsExpressiveActions,
					scrobblerService = viewModel.scrobblingInfo.value.firstOrNull()?.scrobbler,
				)
			},
			onScrobblingCardClick = { index -> router.showScrobblingInfoSheet(index) },
			onRelatedMore = { manga -> router.openRelated(manga) },
			onRelatedClick = { item -> router.openDetails(item.toMangaWithOverride()) },
			onReadClick = { openReader(isIncognitoMode = false) },
			onIncognitoClick = { openReader(isIncognitoMode = true) },
			onForgetHistoryClick = { viewModel.removeFromHistory() },
			onChaptersClick = { router.showChapterPagesSheet() },
			onChapterClick = ::openChapter,
			onChapterDownloadClick = { item ->
				router.askForDownloadOverMeteredNetwork { allowMeteredNetwork ->
					viewModel.download(setOf(item.chapter.id), allowMeteredNetwork)
				}
			},
		)
		viewBinding.composeView.setViewCompositionStrategy(
			ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed,
		)
		viewBinding.composeView.setContent {
			org.koitharu.kotatsu.settings.compose.DropSauceTheme {
				val density = androidx.compose.ui.platform.LocalDensity.current
				val details by viewModel.mangaDetails.collectAsState()
				val history by viewModel.historyInfo.collectAsState()
				val chapters by viewModel.chapters.collectAsState()
				val loading by viewModel.isLoading.collectAsState()
				val favs by viewModel.favouriteCategories.collectAsState()
				val scrob by viewModel.scrobblingInfo.collectAsState()
				val related by viewModel.relatedManga.collectAsState()
				val localSize by viewModel.localSize.collectAsState()
				val srcTitle by viewModel.cachedSourceTitle.collectAsState()
				val coverUrl by viewModel.coverUrl.collectAsState()
				val backdropUrl by viewModel.backdropUrl.collectAsState()
				val tags by viewModel.tags.collectAsState()
				val visualEffectLevel by visualEffectPreferences.level.collectAsState()
				val favLabel = favs.takeIf { it.isNotEmpty() }?.joinToString { it.title }

				val isBackdropEnabled by rememberBooleanPref(AppSettings.KEY_DETAILS_BACKDROP, true)
				val backdropBlurAmount by rememberDetailsBackdropBlurPref(AppSettings.KEY_DETAILS_BACKDROP_BLUR_AMOUNT, 2)

				DetailsExpressiveScreen(
					details = details,
					note = mangaNote.value,
					tags = tags,
					historyInfo = history,
					chapters = chapters,
					isLoading = loading,
					favouriteCount = favs.size,
					favouriteLabel = favLabel,
					scrobblings = scrob,
					related = related,
					localSize = localSize,
					sourceTitle = srcTitle,
					imageLoader = coil,
					coverUrl = coverUrl,
					backdropUrl = backdropUrl,
					isBackdropEnabled = isBackdropEnabled,
					backdropBlurAmount = backdropBlurAmount,
					visualEffectLevel = visualEffectLevel,
					style = settings.detailsUiMode,
					topInset = with(density) { topInset.intValue.toDp() },
					bottomContentPadding = with(density) { bottomInset.intValue.toDp() },
					onScroll = ::onContentScroll,
					actions = actions,
				)
			}
		}
	}

	private fun setupSwipeRefresh() {
		val swipeRefresh = viewBinding.swipeRefreshLayout
		swipeRefresh.setOnRefreshListener { viewModel.reload() }
		viewModel.isLoading.observe(this) { swipeRefresh.isRefreshing = it }
		updateSwipeRefreshEnabled()
	}

	private fun updateSwipeRefreshEnabled() {
		viewBinding.swipeRefreshLayout.isEnabled = contentAtTop
	}

	private fun openReader(isIncognitoMode: Boolean) {
		val manga = viewModel.getMangaOrNull() ?: return
		if (viewModel.historyInfo.value.isChapterMissing) {
			Snackbar.make(viewBinding.composeView, R.string.chapter_is_missing, Snackbar.LENGTH_SHORT).show()
			return
		}
		val intentBuilder = ReaderIntent.Builder(this)
			.manga(manga)
			.branch(viewModel.selectedBranchValue)
		if (isIncognitoMode) {
			intentBuilder.incognito()
		}
		router.openReader(intentBuilder.build())
		if (isIncognitoMode) {
			Toast.makeText(this, R.string.incognito_mode, Toast.LENGTH_SHORT).show()
		}
	}

	private fun openChapter(item: ChapterListItem) {
		val manga = viewModel.getMangaOrNull() ?: return
		val state = if (item.isCurrent && viewModel.readingState.value?.chapterId == item.chapter.id) {
			viewModel.readingState.value!!
		} else {
			ReaderState(item.chapter.id, 0, 0)
		}
		lifecycleScope.launch {
			val openReader = { peek: Boolean ->
				val builder = ReaderIntent.Builder(this@DetailsExpressiveActivity)
					.manga(manga)
					.branch(viewModel.selectedBranchValue)
					.state(state)
				if (peek) builder.peek()
				router.openReader(builder.build())
			}
			when (viewModel.getChapterOpenMode(item.chapter.id)) {
				ChaptersPagesViewModel.ChapterOpenMode.NORMAL -> openReader(false)
				ChaptersPagesViewModel.ChapterOpenMode.ASK -> showChapterJumpDialog(
					activity = this@DetailsExpressiveActivity,
					onPeek = { openReader(true) },
					onMoveProgress = { openReader(false) },
					onDisable = { viewModel.disableChapterJumpDialog() },
				)
			}
		}
	}

	private fun onContentScroll(scrollY: Int) {
		contentAtTop = scrollY <= 0
		updateSwipeRefreshEnabled()
	}

	override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
		val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
		topInset.intValue = bars.top
		bottomInset.intValue = bars.bottom
		viewBinding.appbar.updatePadding(top = bars.top)
		viewBinding.swipeRefreshLayout.setProgressViewOffset(false, bars.top, bars.top + 180)
		return insets
	}

	private fun showTitleDialog(title: String) {
		val text = title.nullIfEmpty() ?: return
		buildAlertDialog(this) {
			setMessage(text)
			setNegativeButton(R.string.close, null)
			setPositiveButton(androidx.preference.R.string.copy) { _, _ ->
				copyToClipboard(getString(R.string.content_type_manga), text)
			}
		}.show()
	}

	private fun loadNote(): String? = notesPreferences
		.getString(viewModel.mangaId.toString(), null)
		?.trim()
		?.takeIf { it.isNotEmpty() }

	private fun saveNote(value: String?) {
		val note = value?.trim()?.takeIf { it.isNotEmpty() }
		notesPreferences.edit().apply {
			if (note == null) {
				remove(viewModel.mangaId.toString())
			} else {
				putString(viewModel.mangaId.toString(), note)
			}
		}.apply()
		mangaNote.value = note
	}

	private fun showNoteDialog() {
		val input = EditText(this).apply {
			setText(mangaNote.value.orEmpty())
			setSelection(text.length)
			minLines = 3
			maxLines = 8
			gravity = Gravity.TOP or Gravity.START
			inputType = InputType.TYPE_CLASS_TEXT or
				InputType.TYPE_TEXT_FLAG_MULTI_LINE or
				InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
			setHorizontallyScrolling(false)
		}
		val dialog = buildAlertDialog(this) {
			setTitle("Note")
			setView(input)
			setPositiveButton(android.R.string.ok) { _, _ -> saveNote(input.text?.toString()) }
			setNegativeButton(android.R.string.cancel, null)
			if (!mangaNote.value.isNullOrBlank()) {
				setNeutralButton(R.string.delete) { _, _ -> saveNote(null) }
			}
		}
		dialog.show()
		input.requestFocus()
		dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
	}

	private class PrefetchObserver(
		private val context: android.content.Context,
	) : kotlinx.coroutines.flow.FlowCollector<List<ChapterListItem>?> {
		private var isCalled = false
		override suspend fun emit(value: List<ChapterListItem>?) {
			if (value.isNullOrEmpty() || isCalled) return
			isCalled = true
			val item = value.find { it.isCurrent } ?: value.first()
			MangaPrefetchService.prefetchPages(context, item.chapter)
		}
	}

	private companion object {
		const val NOTES_PREFERENCES = "manga_notes"
	}
}
