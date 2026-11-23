package org.koitharu.kotatsu.details.ui

import android.content.Context
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.ui.dialog.buildAlertDialog
import org.koitharu.kotatsu.details.ui.pager.ChaptersPagesViewModel
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
	scope.launch {
		val approval = withContext(Dispatchers.IO) { viewModel.previewExhentaiPhysicalDeletion() }
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
		buildAlertDialog(context) {
			setTitle(R.string.exhentai_delete_physical_title)
			setMessage(message)
			setPositiveButton(R.string.delete) { _, _ ->
				viewModel.deleteLocal(approval)
			}
			setNegativeButton(android.R.string.cancel, null)
		}.show()
	}
	return true
}
