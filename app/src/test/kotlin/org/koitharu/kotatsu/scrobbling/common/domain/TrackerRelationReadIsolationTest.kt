package org.koitharu.kotatsu.scrobbling.common.domain

import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService
import org.koitharu.kotatsu.scrobbling.common.domain.model.TrackerContent
import org.koitharu.kotatsu.scrobbling.common.domain.model.TrackerMediaType
import org.koitharu.kotatsu.scrobbling.common.domain.model.TrackerPage
import org.koitharu.kotatsu.scrobbling.common.domain.model.TrackerPerson
import org.koitharu.kotatsu.scrobbling.common.domain.model.TrackerRelation
import org.koitharu.kotatsu.scrobbling.common.domain.model.TrackerRelationType
import org.koitharu.kotatsu.scrobbling.common.domain.model.TrackerResult
import org.koitharu.kotatsu.scrobbling.common.domain.model.TrackerTarget

class TrackerRelationReadIsolationTest {
	private val target = TrackerTarget(ScrobblerService.ANILIST, "42")
	private val relation = TrackerRelation(
		target = TrackerTarget(ScrobblerService.ANILIST, "7", "https://anilist.co/manga/7"),
		title = "Related",
		image = null,
		relation = TrackerRelationType.SEQUEL,
		mediaType = TrackerMediaType.MANGA,
	)

	@Test fun `relation success is independent from people and recommendations`() = runTest {
		val result = readAssociatedTrackerDetails(
			target = target,
			provider = FakeProvider(),
			requested = mapOf(TrackerContent.CHARACTERS to TrackerPage(), TrackerContent.RELATIONS to TrackerPage()),
			loadRelations = { _, _ -> TrackerResult.Success(listOf(relation)) },
		)

		assertTrue(result.characters is TrackerResult.Success)
		assertEquals(relation, (result.relations as TrackerResult.Success).items.single())
		assertTrue(result.recommendations is TrackerResult.NotRequested)
	}

	@Test fun `relation failure never removes successful people metadata`() = runTest {
		val result = readAssociatedTrackerDetails(
			target = target,
			provider = FakeProvider(),
			requested = mapOf(TrackerContent.CHARACTERS to TrackerPage(), TrackerContent.RELATIONS to TrackerPage()),
			loadRelations = { _, _ -> throw IOException("relations offline") },
		)

		assertEquals("Character", (result.characters as TrackerResult.Success).items.single().name)
		assertTrue(result.relations is TrackerResult.Error)
	}

	private class FakeProvider : TrackerDetailsProvider {
		override val detailsService = ScrobblerService.ANILIST
		override val detailsCapabilities = setOf(TrackerContent.CHARACTERS, TrackerContent.STAFF)
		override val isAuthorized = true
		override suspend fun loadCharacters(target: TrackerTarget, page: TrackerPage): TrackerResult<TrackerPerson> =
			TrackerResult.Success(listOf(TrackerPerson("1", "Character", null, listOf("MAIN"))))
		override suspend fun loadStaff(target: TrackerTarget, page: TrackerPage): TrackerResult<TrackerPerson> = TrackerResult.Empty()
	}
}
