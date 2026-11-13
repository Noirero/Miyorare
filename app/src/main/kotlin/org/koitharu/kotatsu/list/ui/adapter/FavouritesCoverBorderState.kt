package org.koitharu.kotatsu.list.ui.adapter

/** Render-only state for one Normal Favourites grid holder. Keys mirror Coil's URL deduplication. */
internal class FavouritesCoverBorderState {
	private var coverUrl: String? = null
	private var activeRequest: Any? = null

	var isPending: Boolean = false
		private set

	fun bind(url: String?) {
		if (url == coverUrl) return
		coverUrl = url
		activeRequest = null
		// A missing URL uses the normal fallback rather than waiting for a cover.
		isPending = url != null
	}

	fun start(data: Any, request: Any) {
		if (data != coverUrl) return
		activeRequest = request
		isPending = true
	}

	fun finish(request: Any) {
		if (activeRequest !== request) return
		activeRequest = null
		isPending = false
	}
}
