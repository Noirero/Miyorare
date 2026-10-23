package org.koitharu.kotatsu.download.domain.maintenance

enum class DownloadMaintenanceStatus { HEALTHY, REPAIRED, NO_CHANGE_REQUIRED, AMBIGUOUS, UNAVAILABLE_STORAGE, UNRESOLVED, FAILED }
enum class DownloadMaintenancePhase { PREPARING, SCANNING, IDENTIFYING, VERIFYING, REPAIRING, REFRESHING, FINALIZING, COMPLETED }
enum class DownloadMaintenanceReason {
	VERIFIED, LOCAL_CONTENT, IDENTITY_UNKNOWN, SOURCE_MISMATCH, DUPLICATE_CONTAINER,
	PARTIAL_MATCH, CORRUPT_INDEX, UNSUPPORTED_ARTIFACT, STORAGE_UNAVAILABLE,
	PROVIDER_UNAVAILABLE, METADATA_UNAVAILABLE, OWNERSHIP_UNKNOWN, STORAGE_CHANGED, CANCELLED, IO_ERROR,
}

data class DownloadMaintenanceItem(
	val path: String,
	val status: DownloadMaintenanceStatus,
	val reason: DownloadMaintenanceReason,
	val recognizedChapters: Int = 0,
	val physicalChapters: Int = 0,
	val isPrivate: Boolean = false,
)

data class DownloadMaintenanceResult(val items: List<DownloadMaintenanceItem>) {
	val checked: Int get() = items.count { it.status != DownloadMaintenanceStatus.UNAVAILABLE_STORAGE }
	val repaired: Int get() = items.count { it.status == DownloadMaintenanceStatus.REPAIRED }
	val healthy: Int get() = items.count { it.status == DownloadMaintenanceStatus.HEALTHY || it.status == DownloadMaintenanceStatus.NO_CHANGE_REQUIRED }
	val failed: Int get() = items.count { it.status == DownloadMaintenanceStatus.FAILED }
	val attention: Int get() = items.size - repaired - healthy - failed
}

data class DownloadMaintenanceState(
	val running: Boolean = false,
	val phase: DownloadMaintenancePhase = DownloadMaintenancePhase.PREPARING,
	val scanned: Int = 0,
	val total: Int? = null,
	val result: DownloadMaintenanceResult? = null,
)
