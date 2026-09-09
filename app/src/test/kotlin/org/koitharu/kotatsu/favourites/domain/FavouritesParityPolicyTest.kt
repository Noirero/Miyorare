package org.koitharu.kotatsu.favourites.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.favourites.data.FavouriteSpace

class FavouritesParityPolicyTest {

	@Test
	fun `every parity capability is available in Normal and Private`() {
		for (capability in FavouritesParityPolicy.parityCapabilities) {
			assertTrue(FavouritesParityPolicy.isUserFacingAvailable(capability, FavouriteSpace.NORMAL))
			assertTrue(FavouritesParityPolicy.isUserFacingAvailable(capability, FavouriteSpace.PRIVATE))
		}
	}

	@Test
	fun `public integrations never inherit into Private by default`() {
		val public = FavouritesCapability.entries.filter {
			FavouritesParityPolicy.classification(it) == FavouritesCapabilityClass.PUBLIC_INTEGRATION
		}
		for (capability in public) {
			assertTrue(FavouritesParityPolicy.isUserFacingAvailable(capability, FavouriteSpace.NORMAL))
			assertFalse(FavouritesParityPolicy.isUserFacingAvailable(capability, FavouriteSpace.PRIVATE))
		}
	}

	@Test
	fun `private security stays Private only`() {
		val security = FavouritesCapability.entries.filter {
			FavouritesParityPolicy.classification(it) == FavouritesCapabilityClass.PRIVATE_SECURITY
		}
		for (capability in security) {
			assertFalse(FavouritesParityPolicy.isUserFacingAvailable(capability, FavouriteSpace.NORMAL))
			assertTrue(FavouritesParityPolicy.isUserFacingAvailable(capability, FavouriteSpace.PRIVATE))
		}
	}

	@Test
	fun `classification partitions every capability exactly once`() {
		val classified = FavouritesCapability.entries.groupBy(FavouritesParityPolicy::classification)
		assertEquals(FavouritesCapability.entries.size, classified.values.sumOf { it.size })
	}

	@Test
	fun `privacy sensitive parity uses Private safe adapters`() {
		val expected = setOf(
			FavouritesCapability.LOCAL_SHELF,
			FavouritesCapability.DUPLICATE_HANDLING,
			FavouritesCapability.LIBRARY_GROUPS,
			FavouritesCapability.LOCAL_REFRESH,
			FavouritesCapability.DETAILS_READER_NAVIGATION,
			FavouritesCapability.NEW_CHAPTERS,
			FavouritesCapability.AUTO_DOWNLOAD_NEW_CHAPTERS,
		)
		val actual = FavouritesCapability.entries.filterTo(linkedSetOf()) {
			FavouritesParityPolicy.classification(it) == FavouritesCapabilityClass.PRIVATE_SAFE_ADAPTER
		}
		assertEquals(expected, actual)
	}
}
