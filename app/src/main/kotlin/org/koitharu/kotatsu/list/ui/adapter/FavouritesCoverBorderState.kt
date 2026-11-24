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

	fun bind(url: String?): Boolean {
		if (url == coverUrl) return false
		coverUrl = url
		// Recycled holders must reject completions from an earlier binding,
		// including when a previously used URL is bound again.
		currentBinding = if (url != null) Any() else null
		activeRequest = null
		isPending = url != null
		return true
	}

	fun start(data: Any, binding: Any?, request: Any?): Boolean {
		if (data != coverUrl || binding == null || binding !== currentBinding || request == null) return false
		activeRequest = request
		isPending = true
		return true
	}

	fun finish(request: Any?): Boolean {
		if (request == null || activeRequest !== request) return false
		activeRequest = null
		isPending = false
		return true
	}

	fun cancel(request: Any?) = finish(request)
}
