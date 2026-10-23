package org.koitharu.kotatsu.download.domain.maintenance

import org.junit.Assert.*
import org.junit.Test
import org.koitharu.kotatsu.favourites.data.FavouriteSpace

class DownloadMaintenancePolicyTest {
	private val normal = FavouriteSpace.NORMAL
	private val privateSpace = FavouriteSpace.PRIVATE
	private val both = setOf(normal, privateSpace)

	@Test fun dedicatedRootProvesItsSpaceDespiteStaleMembership() {
		assertEquals(setOf(normal), DownloadMaintenanceOwnership.resolve(setOf(normal), setOf(privateSpace), setOf(privateSpace)))
		assertEquals(setOf(privateSpace), DownloadMaintenanceOwnership.resolve(setOf(privateSpace), both, emptySet()))
	}
	@Test fun sharedRootWithTwoMembershipsNeedsEvidence() {
		assertNull(DownloadMaintenanceOwnership.resolve(both, both, emptySet()))
		assertNull(DownloadMaintenanceOwnership.resolve(both, both, both))
		assertNull(DownloadMaintenanceOwnership.resolve(both, emptySet(), emptySet()))
	}
	@Test fun sharedRootPreservesAnExplicitOwner() {
		assertEquals(setOf(privateSpace), DownloadMaintenanceOwnership.resolve(both, both, setOf(privateSpace)))
	}
	@Test fun oneSpaceMembershipCanRepairLegacySharedRoot() {
		assertEquals(setOf(normal), DownloadMaintenanceOwnership.resolve(both, setOf(normal), both))
	}
	@Test fun anUnconfiguredRootCannotInventOwnership() {
		assertNull(DownloadMaintenanceOwnership.resolve(emptySet(), setOf(normal), emptySet()))
	}
	@Test fun sourceIdentityUsesVerifiedRegistryAndNeverSimilarNames() {
		assertTrue(DownloadMaintenanceUseCase.sameSource("MIHON_7422099479605463706", "TSUKI:MIYORARE:miyorare-en:BATCAVE"))
		assertFalse(DownloadMaintenanceUseCase.sameSource("SOURCE", "SOURCE_PLUS"))
		assertFalse(DownloadMaintenanceUseCase.sameSource("TSUKI:UMA:one:COMIC", "TSUKI:UMA:two:COMIC"))
	}
	@Test fun summaryDistinguishesHealthyFromZeroRepairsAndUnavailableStorage() {
		val result = DownloadMaintenanceResult(DownloadMaintenanceStatus.entries.map { DownloadMaintenanceItem("x", it, DownloadMaintenanceReason.VERIFIED) })
		assertEquals(6, result.checked)
		assertEquals(1, result.repaired)
		assertEquals(2, result.healthy)
		assertEquals(3, result.attention)
		assertEquals(1, result.failed)
	}
}
