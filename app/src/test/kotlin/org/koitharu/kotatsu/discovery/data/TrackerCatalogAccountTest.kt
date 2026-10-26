package org.koitharu.kotatsu.discovery.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.koitharu.kotatsu.discovery.domain.CatalogTrackingEntry
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
		assertNull(
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
}
