package org.koitharu.kotatsu.list.ui.adapter

/** Render-only state for one Normal Favourites grid holder. */
internal class FavouritesCoverBorderState {
	private var coverUrl: String? = null
	private var currentBinding: Any? = null
	private var activeRequest: Any? = null

	val requestBindingIdentity: Any?
		get() = currentBinding

	var isPending: Boolean = false
		private set

	fun bind(url: String?) {
		if (url == coverUrl) return
		coverUrl = url
		// Recycled holders must reject completions from an earlier binding,
		// including when a previously used URL is bound again.
		currentBinding = if (url != null) Any() else null
		activeRequest = null
		isPending = url != null
	}

	fun start(data: Any, binding: Any?, request: Any?) {
		if (data != coverUrl || binding == null || binding !== currentBinding || request == null) return
		activeRequest = request
		isPending = true
	}

	fun finish(request: Any?) {
		if (request == null || activeRequest !== request) return
		activeRequest = null
		isPending = false
	}

	fun cancel(request: Any?) = finish(request)
}
