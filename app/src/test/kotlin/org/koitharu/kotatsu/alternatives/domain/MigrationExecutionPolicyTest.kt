package org.koitharu.kotatsu.alternatives.domain

import org.junit.Assert.*
import org.junit.Test
import org.koitharu.kotatsu.core.model.MangaSource
import org.koitharu.kotatsu.parsers.model.Manga

class MigrationExecutionPolicyTest {
	private val matcher = MigrationCandidateMatcher()
	private fun manga(id: Long, source: String, authors: Set<String> = setOf("Writer")) = Manga(
		id = id, title = "Shared Story", altTitles = emptySet(), url = "/$id", publicUrl = "https://example.org/$id",
		rating = 0f, contentRating = null, coverUrl = null, tags = emptySet(), state = null,
		authors = authors, source = MangaSource(source), chapters = null, description = null,
	)
	private val seed = manga(1, "OLD")
	private val first = manga(2, "FIRST")
	private val second = manga(3, "SECOND")
	private fun preview(vararg candidates: Manga) = MigrationDiscovery(seed,
		matcher.assess(seed, candidates.map { MigrationCandidateObservation(it) }))

	@Test fun readyRequiresExactCurrentWinner() {
		assertEquals(first, MigrationExecutionPolicy.select(preview(first), MigrationSelection.of(first), false))
		assertNull(MigrationExecutionPolicy.select(preview(first), MigrationSelection.of(second), false))
	}
	@Test fun reviewNeverRunsUnattendedButExplicitChoiceIsLosslessEligible() {
		val p = preview(first, second)
		assertEquals(MigrationMatchState.NEEDS_REVIEW, p.assessment?.state)
		assertNull(MigrationExecutionPolicy.select(p, MigrationSelection.of(first), false))
		assertEquals(second, MigrationExecutionPolicy.select(p, MigrationSelection.of(second), true))
	}
	@Test fun titleAloneNeedsDeliberateReview() {
		val candidate = first.copy(authors = emptySet())
		val p = preview(candidate)
		assertNull(MigrationExecutionPolicy.select(p, MigrationSelection.of(candidate), false))
		assertEquals(candidate, MigrationExecutionPolicy.select(p, MigrationSelection.of(candidate), true))
	}
	@Test fun incompleteSearchCannotBecomeUnattendedPermission() {
		val p = preview(first).copy(incomplete = true)
		assertNull(MigrationExecutionPolicy.select(p, MigrationSelection.of(first), false))
		assertEquals(first, MigrationExecutionPolicy.select(p, MigrationSelection.of(first), true))
	}
	@Test fun missingOrHealthySourceNeverMigratesWithoutExplicitReadableOverride() {
		assertNull(MigrationExecutionPolicy.select(preview(), MigrationSelection.of(first), true))
		assertNull(MigrationExecutionPolicy.select(preview(first).copy(sourceHealthy = true), MigrationSelection.of(first), true))
	}
	@Test fun readableSourceCanMigrateOnlyThroughExplicitSingleMangaOverride() {
		val readable = preview(first).copy(sourceHealthy = true)
		assertNull(MigrationExecutionPolicy.select(readable, MigrationSelection.of(first), false))
		assertEquals(
			first,
			MigrationExecutionPolicy.select(
				readable,
				MigrationSelection.of(first),
				reviewed = false,
				allowReadableSource = true,
			),
		)
	}
	@Test fun candidateChangesRequireAnotherReviewInsteadOfTrustingAnOldId() {
		val prior = MigrationSelection.of(first)
		for (changed in listOf(first.copy(title = "Shared Story II"), first.copy(authors = setOf("Other Writer")),
			first.copy(publicUrl = "https://example.org/reused-id"), first.copy(source = MangaSource("OTHER")))) {
			assertNull(MigrationExecutionPolicy.select(preview(changed), prior, true))
		}
	}
	@Test fun absentAndUnrelatedCandidateCannotBeInjectedByManualSelection() {
		val unrelated = first.copy(title = "Unrelated Work", authors = emptySet())
		assertNull(MigrationExecutionPolicy.select(preview(unrelated), MigrationSelection.of(unrelated), true))
	}
}
