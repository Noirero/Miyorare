package org.koitharu.kotatsu.favourites.domain

import org.koitharu.kotatsu.favourites.data.FavouriteSpace

/**
 * Source of truth for Normal/Private Favourites parity.
 *
 * Every general Favourites capability is SHARED_BY_DEFAULT. A feature may differ only when it needs
 * a Private-safe implementation, is an explicit cross-space operation, protects the Private session,
 * or would publish Private-only data to an external/public surface.
 *
 * Keep [classification] as an exhaustive `when`: adding a capability here without deciding its
 * parity class is a compile error instead of silently creating a Normal-only feature.
 */
enum class FavouritesCapability {
	CONTENT_TYPE_SWITCH,
	ALL_FAVOURITES,
	DOWNLOADED_SHELF,
	LOCAL_SHELF,
	CATEGORY_TABS,
	CATEGORY_PICKER,
	CATEGORY_COUNTS,
	CATEGORY_MANAGEMENT,
	CATEGORY_ASSIGNMENT,
	SEARCH,
	QUICK_FILTERS,
	ADVANCED_FILTERS,
	SOURCE_FILTER,
	SORTING,
	DISPLAY_MODES,
	GRID_CONFIGURATION,
	ITEM_BADGES,
	CONTINUE_READING,
	PAGINATION,
	GO_TO_TOP_BOTTOM,
	SELECT_ALL,
	PINNING,
	SHARE,
	REMOVE,
	SAVE,
	FIX,
	EDIT_OVERRIDE,
	MARK_COMPLETED,
	DUPLICATE_HANDLING,
	LIBRARY_GROUPS,
	LOCAL_REFRESH,
	DETAILS_READER_NAVIGATION,
	NEW_CHAPTERS,
	AUTO_DOWNLOAD_NEW_CHAPTERS,
	CROSS_SPACE_TRANSFER,
	PRIVATE_AUTHENTICATION,
	PRIVATE_SCREEN_PROTECTION,
	EXTERNAL_TRACKING,
	PUBLIC_NOTIFICATIONS,
	PUBLIC_BACKUP_EXPORT,
}

enum class FavouritesCapabilityClass {
	/** Same UI and behavior; FavouriteSpace only selects the data source. */
	SHARED,

	/** Same user-facing capability, but implementation must enforce the Private privacy boundary. */
	PRIVATE_SAFE_ADAPTER,

	/** Deliberately different because the operation itself moves data between Normal and Private. */
	SPACE_SPECIFIC,

	/** Private-only security/session behavior, not a Normal feature to mirror. */
	PRIVATE_SECURITY,

	/** Public/external integration: Private-only data must never inherit it blindly. */
	PUBLIC_INTEGRATION,
}

object FavouritesParityPolicy {

	fun classification(capability: FavouritesCapability): FavouritesCapabilityClass = when (capability) {
		FavouritesCapability.CONTENT_TYPE_SWITCH,
		FavouritesCapability.ALL_FAVOURITES,
		FavouritesCapability.DOWNLOADED_SHELF,
		FavouritesCapability.CATEGORY_TABS,
		FavouritesCapability.CATEGORY_PICKER,
		FavouritesCapability.CATEGORY_COUNTS,
		FavouritesCapability.CATEGORY_MANAGEMENT,
		FavouritesCapability.CATEGORY_ASSIGNMENT,
		FavouritesCapability.SEARCH,
		FavouritesCapability.QUICK_FILTERS,
		FavouritesCapability.ADVANCED_FILTERS,
		FavouritesCapability.SOURCE_FILTER,
		FavouritesCapability.SORTING,
		FavouritesCapability.DISPLAY_MODES,
		FavouritesCapability.GRID_CONFIGURATION,
		FavouritesCapability.ITEM_BADGES,
		FavouritesCapability.CONTINUE_READING,
		FavouritesCapability.PAGINATION,
		FavouritesCapability.GO_TO_TOP_BOTTOM,
		FavouritesCapability.SELECT_ALL,
		FavouritesCapability.PINNING,
		FavouritesCapability.SHARE,
		FavouritesCapability.REMOVE,
		FavouritesCapability.SAVE,
		FavouritesCapability.FIX,
		FavouritesCapability.EDIT_OVERRIDE,
		FavouritesCapability.MARK_COMPLETED,
		-> FavouritesCapabilityClass.SHARED

		FavouritesCapability.LOCAL_SHELF,
		FavouritesCapability.DUPLICATE_HANDLING,
		FavouritesCapability.LIBRARY_GROUPS,
		FavouritesCapability.LOCAL_REFRESH,
		FavouritesCapability.DETAILS_READER_NAVIGATION,
		FavouritesCapability.NEW_CHAPTERS,
		FavouritesCapability.AUTO_DOWNLOAD_NEW_CHAPTERS,
		-> FavouritesCapabilityClass.PRIVATE_SAFE_ADAPTER

		FavouritesCapability.CROSS_SPACE_TRANSFER -> FavouritesCapabilityClass.SPACE_SPECIFIC

		FavouritesCapability.PRIVATE_AUTHENTICATION,
		FavouritesCapability.PRIVATE_SCREEN_PROTECTION,
		-> FavouritesCapabilityClass.PRIVATE_SECURITY

		FavouritesCapability.EXTERNAL_TRACKING,
		FavouritesCapability.PUBLIC_NOTIFICATIONS,
		FavouritesCapability.PUBLIC_BACKUP_EXPORT,
		-> FavouritesCapabilityClass.PUBLIC_INTEGRATION
	}

	/** Capabilities that must be present in both Normal and Private from the user's point of view. */
	val parityCapabilities: Set<FavouritesCapability> = FavouritesCapability.entries
		.filterTo(linkedSetOf()) {
			classification(it) == FavouritesCapabilityClass.SHARED ||
				classification(it) == FavouritesCapabilityClass.PRIVATE_SAFE_ADAPTER
		}

	fun isUserFacingAvailable(capability: FavouritesCapability, space: FavouriteSpace): Boolean = when (
		classification(capability)
	) {
		FavouritesCapabilityClass.SHARED,
		FavouritesCapabilityClass.PRIVATE_SAFE_ADAPTER,
		-> true

		FavouritesCapabilityClass.SPACE_SPECIFIC -> true
		FavouritesCapabilityClass.PRIVATE_SECURITY -> space == FavouriteSpace.PRIVATE
		FavouritesCapabilityClass.PUBLIC_INTEGRATION -> space == FavouriteSpace.NORMAL
	}
}
