package org.koitharu.kotatsu.details.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.ui.dialog.buildAlertDialog
import org.koitharu.kotatsu.details.ui.pager.ChaptersPagesViewModel
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
import org.koitharu.kotatsu.reader.ui.ReaderActivity
import org.koitharu.kotatsu.sources.compat.EhentaiSourceFamily
import java.io.File

/**
 * ExHentai Main/Gekko: the owner explicitly approves unlinking the displayed CBZ.
 * The approval is a physical-file snapshot; the domain revalidates it under the manga lock.
 * Other source deletion menus keep their existing behaviour.
 */
internal fun showExhentaiPhysicalDeleteConfirmation(
	context: Context,
	scope: CoroutineScope,
	viewModel: ChaptersPagesViewModel,
): Boolean {
	val details = viewModel.mangaDetails.value ?: return false
	val source = details.sourceManga.source.name
	if (details.local == null || (source != EhentaiSourceFamily.OFFICIAL_SOURCE_NAME &&
		source != "TSUKI:GEKKOUSHI:gekkoushi:EXHENTAI")) return false
	// This call must never fall back to legacy unconfirmed deletion for a locked vault.
	val host = context.findExhentaiDeleteHost()
	if (host == null || !host.canShowExhentaiFileDetails(viewModel.favouriteSpace)) return true
	scope.launch {
		val approval = withContext(Dispatchers.IO) { viewModel.previewExhentaiPhysicalDeletion() }
		if (!host.canShowExhentaiFileDetails(viewModel.favouriteSpace)) return@launch
		if (approval == null || viewModel.mangaDetails.value?.id != details.id) {
			Toast.makeText(context, R.string.exhentai_delete_target_unavailable, Toast.LENGTH_LONG).show()
			return@launch
		}
		val path = approval.canonicalPath
		val message = buildString {
			append(context.getString(
				R.string.exhentai_delete_target_description,
				details.sourceManga.title, File(path).name, path,
			))
			if (approval.sharedWithOtherSpace) {
				append("\n\n")
				append(context.getString(R.string.exhentai_delete_shared_space_warning))
			}
		}
		val confirmationDialog = buildAlertDialog(context) {
			setTitle(R.string.exhentai_delete_physical_title)
			setMessage(message)
			setPositiveButton(R.string.delete) { _, _ ->
				if (host.canShowExhentaiFileDetails(viewModel.favouriteSpace)) viewModel.deleteLocal(approval)
			}
			setNegativeButton(android.R.string.cancel, null)
		}.show()
		host.trackSensitiveExhentaiDeleteDialog(confirmationDialog)
	}
	return true
}

/** Readers and Details use the same exact-file confirmation and vault policy. */
private fun Activity.canShowExhentaiFileDetails(space: FavouriteSpace): Boolean = when (this) {
	is DetailsExpressiveActivity -> canShowExhentaiFileDetails()
	is ReaderActivity -> canShowExhentaiFileDetails(space)
	else -> false
}

private fun Activity.trackSensitiveExhentaiDeleteDialog(dialog: androidx.appcompat.app.AlertDialog) {
	when (this) {
		is DetailsExpressiveActivity -> trackExhentaiDeleteDialog(dialog)
		is ReaderActivity -> trackExhentaiDeleteDialog(dialog)
		else -> dialog.dismiss()
	}
}

/** ContextThemeWrapper may wrap a Reader or Details activity for a themed action mode. */
private fun Context.findExhentaiDeleteHost(): Activity? {
	var candidate: Context? = this
	while (candidate is ContextWrapper) {
		if (candidate is DetailsExpressiveActivity || candidate is ReaderActivity) return candidate
		candidate = candidate.baseContext
	}
	return null
}
