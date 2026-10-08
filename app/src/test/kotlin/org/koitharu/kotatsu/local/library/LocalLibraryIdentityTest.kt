package org.koitharu.kotatsu.local.library

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalLibraryIdentityTest {
    @Test fun titleChapterAndCoverShareTheManagedRoutingBoundary() {
        assertTrue(isSmartLocalUri("smart-local://manga/42/book"))
        assertTrue(isSmartLocalUri("smart-local://manga/42/book.epub"))
        assertTrue(isSmartLocalUri("smart-local://chapter/43/volume.epub"))
        assertTrue(isSmartLocalUri("smart-local://cover/42"))
    }
    @Test fun physicalPathsAndOnlineIdentitiesNeverBecomeManagedRoutes() {
        for (url in listOf("/storage/books/book.epub", "file:///storage/books/book.epub",
            "content://provider/tree/books", "https://example.org/smart-local://manga/42", "smart-local")) {
            assertFalse(url, isSmartLocalUri(url))
        }
    }
}
