package org.koitharu.kotatsu.discovery.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.discovery.domain.CatalogTrackingEntry
import org.koitharu.kotatsu.discovery.domain.catalogSupportedTrackingStatuses
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerUser
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblingStatus

class TrackerCatalogAccountTest {
	@Test
	fun `provider statuses normalize without crossing provider vocabularies`() {
		assertEquals(ScrobblingStatus.PLANNED, catalogTrackingStatus(ScrobblerService.ANILIST, "PLANNING"))
		assertEquals(ScrobblingStatus.RE_READING, catalogTrackingStatus(ScrobblerService.ANILIST, "REPEATING"))
		assertEquals(ScrobblingStatus.PLANNED, catalogTrackingStatus(ScrobblerService.MAL, "plan_to_read"))
		assertEquals(ScrobblingStatus.ON_HOLD, catalogTrackingStatus(ScrobblerService.MAL, "on_hold"))
		assertEquals(ScrobblingStatus.READING, catalogTrackingStatus(ScrobblerService.KITSU, "current"))
		assertEquals(ScrobblingStatus.DROPPED, catalogTrackingStatus(ScrobblerService.KITSU, "dropped"))
		assertEquals(ScrobblingStatus.PLANNED, catalogTrackingStatus(ScrobblerService.MANGABAKA, "plan_to_read"))
		assertEquals(ScrobblingStatus.RE_READING, catalogTrackingStatus(ScrobblerService.MANGABAKA, "rereading"))
		assertEquals(ScrobblingStatus.ON_HOLD, catalogTrackingStatus(ScrobblerService.MANGABAKA, "paused"))
		assertNull(catalogTrackingStatus(ScrobblerService.MAL, "PLANNING"))
		assertNull(catalogTrackingStatus(ScrobblerService.KITSU, "unknown"))
		assertNull(catalogTrackingStatus(ScrobblerService.MANGABAKA, "planning"))
	}

	@Test
	fun `outbound statuses preserve each provider vocabulary`() {
		assertEquals("PLANNING", catalogTrackingStatusRaw(ScrobblerService.ANILIST, ScrobblingStatus.PLANNED))
		assertEquals("REPEATING", catalogTrackingStatusRaw(ScrobblerService.ANILIST, ScrobblingStatus.RE_READING))
		assertEquals("plan_to_read", catalogTrackingStatusRaw(ScrobblerService.MAL, ScrobblingStatus.PLANNED))
		assertNull(catalogTrackingStatusRaw(ScrobblerService.MAL, ScrobblingStatus.RE_READING))
		assertEquals("planned", catalogTrackingStatusRaw(ScrobblerService.KITSU, ScrobblingStatus.PLANNED))
		assertNull(catalogTrackingStatusRaw(ScrobblerService.KITSU, ScrobblingStatus.RE_READING))
		assertEquals("rereading", catalogTrackingStatusRaw(ScrobblerService.MANGABAKA, ScrobblingStatus.RE_READING))
	}

	@Test
	fun `editor only exposes statuses supported by the selected provider`() {
		assertTrue(ScrobblingStatus.RE_READING in catalogSupportedTrackingStatuses(ScrobblerService.ANILIST))
		assertTrue(ScrobblingStatus.RE_READING in catalogSupportedTrackingStatuses(ScrobblerService.MANGABAKA))
		assertFalse(ScrobblingStatus.RE_READING in catalogSupportedTrackingStatuses(ScrobblerService.MAL))
		assertFalse(ScrobblingStatus.RE_READING in catalogSupportedTrackingStatuses(ScrobblerService.KITSU))
	}

	@Test
	fun `account profile URLs retain provider identity`() {
		assertEquals(
			"https://anilist.co/user/Reader%20Name",
			catalogAccountProfileUrl(ScrobblerUser(1, "Reader Name", null, ScrobblerService.ANILIST)),
		)
		assertEquals(
			"https://myanimelist.net/profile/Reader%20Name",
			catalogAccountProfileUrl(ScrobblerUser(2, "Reader Name", null, ScrobblerService.MAL)),
		)
		assertEquals(
			"https://kitsu.app/users/42",
			catalogAccountProfileUrl(ScrobblerUser(42, "Reader", null, ScrobblerService.KITSU)),
		)
		assertEquals(
			"https://mangabaka.org/my/settings/profile",
			catalogAccountProfileUrl(ScrobblerUser(5, "Reader", null, ScrobblerService.MANGABAKA)),
		)
	}

	@Test(expected = IllegalArgumentException::class)
	fun `tracking entry rejects non normalized rating`() {
		CatalogTrackingEntry(
			service = ScrobblerService.ANILIST,
			targetId = 1,
			rating = 1.1f,
		)
	}

	@Test(expected = IllegalArgumentException::class)
	fun `tracking entry rejects invalid provider entry id`() {
		CatalogTrackingEntry(
			service = ScrobblerService.KITSU,
			targetId = 1,
			entryId = 0,
		)
	}
}
