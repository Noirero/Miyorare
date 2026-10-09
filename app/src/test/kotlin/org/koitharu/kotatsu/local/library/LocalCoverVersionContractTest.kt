package org.koitharu.kotatsu.local.library

import org.junit.Assert.*
import org.junit.Test
import org.koitharu.kotatsu.local.library.LocalTreeScanner.Node

/** Discovery is the freshness authority; reading a warm cover must not inspect source content. */
class LocalCoverVersionContractTest {
    private fun node(name: String, modified: Long = 1, size: Long = 100, key: String = name) =
        Node(key, "content://fixture/$key", name, false, size, modified)
    private fun plan(vararg nodes: Node, scan: Long = 1) = LocalCoverPlan("root", nodes.toList(), scan)

    @Test fun unchangedKnownMetadataSurvivesRefreshWhileUnknownModifiedHasExplicitScanFallback() {
        val known = node("book.pdf")
        assertEquals(plan(known, scan = 1).fingerprint, plan(known, scan = 2).fingerprint)
        val unknown = node("book.pdf", modified = 0)
        assertNotEquals(plan(unknown, scan = 1).fingerprint, plan(unknown, scan = 2).fingerprint)
        assertEquals(plan(unknown, scan = 1).fingerprint, plan(unknown, scan = 1).fingerprint)
    }

    @Test fun sameSizeReplacementNeedsVersionEvidenceAndDoesNotPretendContentWasHashed() {
        assertNotEquals(plan(node("book.pdf", modified = 1)).fingerprint, plan(node("book.pdf", modified = 2)).fingerprint)
        // Same size AND same modified cannot prove replacement without opening content. This
        // deliberate contract is reported to owners rather than introducing per-bind source reads.
        assertEquals(plan(node("book.pdf", modified = 1)).fingerprint, plan(node("book.pdf", modified = 1)).fingerprint)
    }

    @Test fun renamedCandidateIsOneScopedInvalidationAndSourceIdentityRemainsProviderOwned() {
        val original = node("book.pdf", key = "stable-document-id")
        val renamed = node("renamed.pdf", key = "stable-document-id")
        assertEquals(original.key, renamed.key); assertEquals(original.uri, renamed.uri)
        // Display names also participate in extension/sidecar priority; do not silently erase them.
        assertNotEquals(plan(original).fingerprint, plan(renamed).fingerprint)
        val unrelated = node("other.pdf")
        assertEquals(plan(unrelated).fingerprint, plan(unrelated).fingerprint)
    }

    @Test fun higherPrioritySidecarTransitionsInvalidateFallbackButLaterChapterDoesNot() {
        val first = node("book.pdf")
        val sidecar = node("cover.png")
        val broken = plan(sidecar, first)
        assertNotEquals(plan(first).fingerprintThrough(0), broken.fingerprintThrough(1))
        assertNotEquals(broken.fingerprintThrough(1), plan(node("cover.png", modified = 2), first).fingerprintThrough(1))
        assertEquals(plan(first).fingerprintThrough(0), plan(first, node("later.pdf")).fingerprintThrough(0))
    }
}
