package org.koitharu.kotatsu.download.ui.worker

import android.content.Intent
import android.view.View
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.flow.FlowCollector
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.util.ext.findActivity
import org.koitharu.kotatsu.download.ui.list.DownloadsActivity
import org.koitharu.kotatsu.favourites.data.EXTRA_FAVOURITE_SPACE
import org.koitharu.kotatsu.favourites.data.FavouriteSpace
import org.koitharu.kotatsu.main.ui.owners.BottomNavOwner

class DownloadStartedObserver(
	private val snackbarHost: View,
) : FlowCollector<Unit> {

	override suspend fun emit(value: Unit) {
		val snackbar = Snackbar.make(snackbarHost, R.string.download_started, Snackbar.LENGTH_LONG)
		val hostActivity = snackbarHost.context.findActivity()
		(hostActivity as? BottomNavOwner)?.let {
			snackbar.anchorView = it.bottomNav
		}
		val favouriteSpace = FavouriteSpace.fromArgument(
			hostActivity?.intent?.getIntExtra(
				EXTRA_FAVOURITE_SPACE,
				FavouriteSpace.NORMAL.dbValue,
			) ?: FavouriteSpace.NORMAL.dbValue,
		)
		if (hostActivity != null) {
			// Preserve the originating FavouriteSpace. The public Downloads queue deliberately hides
			// Private-only work, so dropping this scope made the snackbar's Details action open an empty
			// list even though the Private chapter was actively downloading.
			snackbar.setAction(R.string.details) {
				hostActivity.startActivity(
					Intent(hostActivity, DownloadsActivity::class.java)
						.putExtra(EXTRA_FAVOURITE_SPACE, favouriteSpace.dbValue),
				)
			}
		} else {
			AppRouter.from(snackbarHost)?.let { router ->
				snackbar.setAction(R.string.details) { router.openDownloads() }
			}
		}
		snackbar.show()
	}
}
