package org.koitharu.kotatsu.local.ui

import android.content.Intent
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.util.ext.getDisplayMessage
import org.koitharu.kotatsu.local.data.importer.EpubImportPreview
import org.koitharu.kotatsu.local.data.importer.SingleMangaImporter
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import javax.inject.Inject

/**
 * Lightweight exported entry point for Android's "Open with" flow.
 *
 * It deliberately stays separate from MainActivity: external EPUB validation/import should not
 * initialize the main navigation stack just to ask what the user wants to do with one book.
 */
@AndroidEntryPoint
class ExternalEpubImportActivity : AppCompatActivity() {

	@Inject
	lateinit var importer: SingleMangaImporter

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		if (intent.action != Intent.ACTION_VIEW) {
			finish()
			return
		}
		val uri = intent.data
		if (uri == null) {
			finish()
			return
		}
		persistReadPermissionIfOffered(uri)
		loadPreview(uri)
	}

	private fun loadPreview(uri: Uri) {
		val progress = MaterialAlertDialogBuilder(this)
			.setTitle(R.string.external_epub_import_title)
			.setMessage(R.string.external_epub_preparing)
			.setCancelable(false)
			.create()
		progress.show()

		lifecycleScope.launch {
			val result = runCatchingCancellable { importer.previewEpub(uri) }
			if (isFinishing || isDestroyed) return@launch
			progress.dismiss()
			result.fold(
				onSuccess = { preview -> showPreview(uri, preview) },
				onFailure = ::showError,
			)
		}
	}

	private fun showPreview(uri: Uri, preview: EpubImportPreview) {
		val authorText = preview.authors
			.takeIf { it.isNotEmpty() }
			?.joinToString()
			?: getString(R.string.external_epub_unknown_author)
		val message = buildString {
			append(preview.fileName)
			append('\n')
			append(authorText)
			append('\n')
			append(getString(R.string.external_epub_sections, preview.sectionCount))
			preview.description?.trim()?.takeIf { it.isNotEmpty() }?.let { description ->
				append("\n\n")
				append(description.take(MAX_PREVIEW_DESCRIPTION_LENGTH))
			}
		}

		val builder = MaterialAlertDialogBuilder(this)
			.setTitle(preview.title)
			.setMessage(message)
			.setOnCancelListener { finish() }
		preview.cover?.let { cover ->
			builder.setIcon(BitmapDrawable(resources, cover))
		}

		val existing = preview.existingManga
		if (existing != null) {
			builder
				.setMessage(
					buildString {
						append(getString(R.string.external_epub_already_imported))
						append("\n\n")
						append(message)
					},
				)
				.setPositiveButton(R.string.read) { _, _ -> open(existing, read = true) }
				.setNeutralButton(R.string.external_epub_open_details) { _, _ -> open(existing, read = false) }
				.setNegativeButton(R.string.external_epub_reimport) { _, _ ->
					importAndOpen(uri, read = false)
				}
		} else {
			builder
				.setPositiveButton(R.string.external_epub_import_and_read) { _, _ ->
					importAndOpen(uri, read = true)
				}
				.setNeutralButton(R.string.external_epub_import_only) { _, _ ->
					importAndOpen(uri, read = false)
				}
				.setNegativeButton(android.R.string.cancel) { _, _ -> finish() }
		}
		builder.show()
	}

	private fun importAndOpen(uri: Uri, read: Boolean) {
		val progress = MaterialAlertDialogBuilder(this)
			.setTitle(R.string.external_epub_import_title)
			.setMessage(R.string.external_epub_importing)
			.setCancelable(false)
			.create()
		progress.show()

		lifecycleScope.launch {
			val result = runCatchingCancellable { importer.import(uri).manga }
			if (isFinishing || isDestroyed) return@launch
			progress.dismiss()
			result.fold(
				onSuccess = { manga -> open(manga, read) },
				onFailure = ::showError,
			)
		}
	}

	private fun open(manga: Manga, read: Boolean) {
		val router = AppRouter(this)
		if (read) {
			router.openReader(manga)
		} else {
			router.openDetails(manga)
		}
		finish()
	}

	private fun showError(error: Throwable) {
		MaterialAlertDialogBuilder(this)
			.setTitle(R.string.error_occurred)
			.setMessage(error.getDisplayMessage(resources))
			.setPositiveButton(android.R.string.ok) { _, _ -> finish() }
			.setOnCancelListener { finish() }
			.show()
	}

	private fun persistReadPermissionIfOffered(uri: Uri) {
		val hasReadGrant = intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0
		val hasPersistableGrant = intent.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0
		if (!hasReadGrant || !hasPersistableGrant) return
		runCatching {
			contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
		}
	}

	private companion object {
		private const val MAX_PREVIEW_DESCRIPTION_LENGTH = 500
	}
}
