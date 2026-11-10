package org.koitharu.kotatsu.list.ui.adapter

/**
 * Keeps the debug-only History A/B probe separate from the host's actual Modern cover policy.
 * Cinematic Local and all non-opted-in screens retain their configured overlay.
 */
internal fun resolveHistoryCoverOverlay(
	configured: Boolean,
	modern: Boolean,
	probeEnabled: Boolean,
	bypassRequested: Boolean,
): Boolean = configured && !(modern && probeEnabled && bypassRequested)
