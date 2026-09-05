package org.koitharu.kotatsu.details.ui

import android.app.Activity
import android.net.Uri
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.view.MenuProvider
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.browser.BrowserActivity
import org.koitharu.kotatsu.core.exceptions.InteractiveActionRequiredException
import org.koitharu.kotatsu.core.model.LocalMangaSource
import org.koitharu.kotatsu.core.model.isNovelSource
import org.koitharu.kotatsu.core.model.unwrap
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.nav.AppRouterEntryPoint
import org.koitharu.kotatsu.core.nav.ReaderIntent
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.os.AppShortcutManager
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.ui.dialog.buildAlertDialog
import org.koitharu.kotatsu.core.util.ext.isHttpUrl
import org.koitharu.kotatsu.core.util.ext.toFileNameSafe
import org.koitharu.kotatsu.local.data.isEpub
import org.koitharu.kotatsu.mihon.model.MihonMangaSource

class DetailsMenuProvider(
	private val activity: FragmentActivity,
	private val viewModel: DetailsViewModel,
	private val snackbarHost: View,
	private val appShortcutManager: AppShortcutManager,
	private val onNoteClick: (() -> Unit)? = null,
) : MenuProvider, ActivityResultCallback<ActivityResult> {

	private val activityForResultLauncher = activity.registerForActivityResult(
		ActivityResultContracts.StartActivityForResult(),
		this,
	)

	/** Registered eagerly alongside [activityForResultLauncher] — both must exist before STARTED. */
	private val exportEpubLauncher = activity.registerForActivityResult(
		ActivityResultContracts.CreateDocument(MIME_EPUB),
	) { uri -> if (uri != null) exportEpubTo(uri) }

	/** Mihon's source WebView doubles as its login/token resolver, so return through the same contract. */
	private val mihonBrowserLauncher = activity.registerForActivityResult(
		BrowserActivity.Contract(),
	) { success ->
		if (success) viewModel.reload()
	}

	private val router: AppRouter
		get() = activity.router

	private val settings: AppSettings by lazy {
		EntryPointAccessors.fromApplication<AppRouterEntryPoint>(activity.applicationContext).settings
	}

	override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
		menuInflater.inflate(R.menu.opt_details, menu)
	}

	override fun onPrepareMenu(menu: Menu) {
		val manga = viewModel.manga.value
		menu.findItem(R.id.action_share).isVisible = manga != null && AppRouter.isShareSupported(manga)
		menu.findItem(R.id.action_save).isVisible = manga?.source != null && manga.source != LocalMangaSource
		menu.findItem(R.id.action_delete).isVisible = manga?.source == LocalMangaSource
		menu.findItem(R.id.action_browser).isVisible = manga?.publicUrl?.isHttpUrl() == true
		menu.findItem(R.id.action_alternatives).isVisible = manga?.source != LocalMangaSource
		menu.findItem(R.id.action_shortcut).isVisible = ShortcutManagerCompat.isRequestPinShortcutSupported(activity)
		menu.findItem(R.id.action_scrobbling).isVisible = viewModel.isScrobblingAvailable
		menu.findItem(R.id.action_online).isVisible = viewModel.remoteManga.value != null
		menu.findItem(R.id.action_stats).isVisible = viewModel.isStatsAvailable.value
		menu.findItem(R.id.action_note).isVisible = manga != null && onNoteClick != null

		// Keep the same availability rules that the old Read FAB submenu used for incognito mode.
		val historyInfo = viewModel.historyInfo.value
		val isChaptersLoading = viewModel.isLoading.value &&
			(historyInfo.totalChapters <= 0 || historyInfo.isChapterMissing)
		val isReadActionReady = !isChaptersLoading && historyInfo.isValid
		menu.findItem(R.id.action_incognito).isVisible =
			manga != null && isReadActionReady && !historyInfo.isIncognitoMode

		// Novels and local books only — there is nothing to put in an epub for an image manga.
		menu.findItem(R.id.action_export_epub).isVisible = manga?.isEpub == true
	}

	override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
		val manga = viewModel.getMangaOrNull() ?: return false
		when (menuItem.itemId) {
			R.id.action_share -> {
				router.showShareDialog(manga)
			}

			R.id.action_delete -> {
				buildAlertDialog(activity) {
					setTitle(R.string.delete_manga)
					setMessage(activity.getString(R.string.text_delete_local_manga, manga.title))
					setPositiveButton(R.string.delete) { _, _ -> viewModel.deleteLocal() }
					setNegativeButton(android.R.string.cancel, null)
				}.show()
			}

			R.id.action_save -> {
				router.showDownloadDialog(manga, snackbarHost)
			}

			R.id.action_browser -> {
				if (manga.source.unwrap() is MihonMangaSource) {
					mihonBrowserLauncher.launch(
						InteractiveActionRequiredException(
							source = manga.source,
							url = manga.publicUrl,
						),
					)
				} else {
					router.openBrowser(url = manga.publicUrl, source = manga.source, title = manga.title)
				}
			}

			R.id.action_online -> {
				router.openDetails(viewModel.remoteManga.value ?: return false)
			}

			R.id.action_related -> {
				// "Find similar" must follow the content type of the title that launched it. Without
				// this, global search can inherit the last Novel/Manga scope used elsewhere in the app.
				settings.isGlobalSearchNovelScope = manga.source.isNovelSource
				router.openSearch(manga.title)
			}

			R.id.action_alternatives -> {
				router.openAlternatives(manga)
			}

			R.id.action_stats -> {
				router.showStatisticSheet(manga)
			}

			R.id.action_scrobbling -> {
				router.showScrobblingSelectorSheet(manga, null)
			}

			R.id.action_shortcut -> {
				activity.lifecycleScope.launch {
					if (!appShortcutManager.requestPinShortcut(manga)) {
						Snackbar.make(snackbarHost, R.string.operation_not_supported, Snackbar.LENGTH_SHORT)
							.show()
					}
				}
			}

			R.id.action_incognito -> {
				// Re-check availability at click time in case state changed while the menu was open.
				val historyInfo = viewModel.historyInfo.value
				val isChaptersLoading = viewModel.isLoading.value &&
					(historyInfo.totalChapters <= 0 || historyInfo.isChapterMissing)
				if (historyInfo.isIncognitoMode || isChaptersLoading || !historyInfo.isValid) {
					return true
				}
				if (historyInfo.isChapterMissing) {
					Snackbar.make(snackbarHost, R.string.chapter_is_missing, Snackbar.LENGTH_SHORT).show()
					return true
				}
				val readerIntent = ReaderIntent.Builder(activity)
					.manga(manga)
					.branch(viewModel.selectedBranchValue)
					.incognito()
					.build()
				router.openReader(readerIntent)
				Toast.makeText(activity, R.string.incognito_mode, Toast.LENGTH_SHORT).show()
			}

			R.id.action_export_epub -> {
				if (viewModel.getLocalEpubFile() == null) {
					Snackbar.make(snackbarHost, R.string.export_epub_nothing, Snackbar.LENGTH_LONG).show()
				} else {
					exportEpubLauncher.launch("${manga.title.toFileNameSafe()}.epub")
				}
			}

			R.id.action_edit_override -> {
				// Pass the pristine source manga so the editor always shows the true original
				// title/cover, independent of any previously saved override.
				val original = viewModel.getSourceMangaOrNull() ?: manga
				val intent = AppRouter.overrideEditIntent(activity, original)
				activityForResultLauncher.launch(intent)
			}

			R.id.action_note -> {
				onNoteClick?.invoke() ?: return false
			}

			else -> return false
		}
		return true
	}

	/**
	 * A downloaded novel is already a valid EPUB (written by `LocalNovelEpubOutput`), so exporting is a
	 * stream copy into whatever the user picked — no rebuild, and nothing is re-fetched.
	 */
	private fun exportEpubTo(destination: Uri) {
		val source = viewModel.getLocalEpubFile() ?: return
		activity.lifecycleScope.launch {
			val result = runCatching {
				withContext(Dispatchers.IO) {
					checkNotNull(activity.contentResolver.openOutputStream(destination)) {
						"Cannot open $destination for writing"
					}.use { output -> source.inputStream().use { it.copyTo(output) } }
				}
			}
			val message = if (result.isSuccess) R.string.export_epub_done else R.string.export_epub_failed
			Snackbar.make(snackbarHost, message, Snackbar.LENGTH_SHORT).show()
		}
	}

	override fun onActivityResult(result: ActivityResult) {
		if (result.resultCode == Activity.RESULT_OK) {
			viewModel.reload()
		}
	}

	private companion object {
		const val MIME_EPUB = "application/epub+zip"
	}
}
