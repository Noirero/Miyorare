package org.koitharu.kotatsu.details.ui

data class TrackerVolumeUiState(
	val isRequested: Boolean = false,
	val isLoading: Boolean = false,
	val volume: Int? = null,
	val isError: Boolean = false,
)
