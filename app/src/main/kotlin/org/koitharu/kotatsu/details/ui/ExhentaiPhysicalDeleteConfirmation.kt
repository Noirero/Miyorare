package org.koitharu.kotatsu.details.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.widget.Toast
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
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
	if (source != EhentaiSourceFamily.OFFICIAL_SOURCE_NAME &&
		source != "TSUKI:GEKKOUSHI:gekkoushi:EXHENTAI") return false
	// Never fall back to legacy unconfirmed deletion for an ambiguous or locked title.
	val host = context.findExhentaiDeleteHost() ?: return true
	fun canShow(): Boolean =
		!host.isFinishing && !host.isDestroyed &&
			(host as? LifecycleOwner)?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.STARTED) == true &&
			host.canShowExhentaiFileDetails(viewModel.favouriteSpace) &&
			viewModel.mangaDetails.value?.sourceManga?.let { it.id == details.id && it.source.name == source } == true
	if (!canShow()) return true

	fun unavailable() {
		Toast.makeText(context, R.string.exhentai_delete_target_unavailable, Toast.LENGTH_LONG).show()
	}
	fun confirm(file: File) {
		scope.launch {
			if (!canShow()) return@launch
			val approval = withContext(Dispatchers.IO) { viewModel.previewExhentaiPhysicalDeletion(file) }
			if (!canShow()) return@launch
			if (approval == null) {
				unavailable()
				return@launch
			}
			val path = approval.canonicalPath
			val message = buildString {
				append(context.getString(
					R.string.exhentai_delete_target_description,
					details.sourceManga.title, File(path).name, path,
				))
				append("\n\n")
				append(context.getString(R.string.exhentai_delete_shared_source_warning))
				if (approval.sharedWithOtherSpace) {
					append("\n\n")
					append(context.getString(R.string.exhentai_delete_shared_space_warning))
				}
			}
			val confirmationDialog = buildAlertDialog(context) {
				setTitle(R.string.exhentai_delete_physical_title)
				setMessage(message)
				setPositiveButton(R.string.delete) { _, _ ->
					if (canShow()) viewModel.deleteLocal(approval)
				}
				setNegativeButton(android.R.string.cancel, null)
			}
			confirmationDialog.show()
			host.trackSensitiveExhentaiDeleteDialog(confirmationDialog)
		}
	}
	scope.launch {
		val targets = withContext(Dispatchers.IO) { viewModel.listExhentaiPhysicalDeletionTargets() }
		if (!canShow()) return@launch
		when (targets.size) {
			0 -> unavailable()
			1 -> confirm(targets.single())
			else -> {
				val picker = buildAlertDialog(context) {
					setTitle(R.string.exhentai_delete_choose_target)
					setItems(targets.map { it.path }.toTypedArray()) { _, index ->
						if (canShow()) confirm(targets[index])
					}
					setNegativeButton(android.R.string.cancel, null)
				}
				picker.show()
				host.trackSensitiveExhentaiDeleteDialog(picker)
			}
		}
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
