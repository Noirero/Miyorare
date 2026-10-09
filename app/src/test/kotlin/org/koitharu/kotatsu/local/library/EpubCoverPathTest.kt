package org.koitharu.kotatsu.local.library

import org.junit.Assert.*
import org.junit.Test

class EpubCoverPathTest {
    @Test fun epub2And3MetadataSelectOnlyTheDeclaredCover() {
        assertEquals("OEBPS/book.opf", EpubCoverPath.opf("<container><rootfiles><rootfile full-path=\"OEBPS/book.opf\"/></rootfiles></container>".toByteArray()))
        assertEquals("OEBPS/Images/art.jpg", EpubCoverPath.cover("OEBPS/book.opf", """<package><metadata><meta name="cover" content="art"/></metadata><manifest><item id="first" href="wrong.jpg" media-type="image/jpeg"/><item id="art" href="Images/art.jpg" media-type="image/jpeg"/></manifest></package>""".toByteArray()))
        assertEquals("OEBPS/Images/art.jpg", EpubCoverPath.cover("OEBPS/book.opf", """<opf:package><opf:manifest><opf:item id="first" href="wrong.jpg" media-type="image/jpeg"/><opf:item id="art" href="Images/art.jpg" properties="nav cover-image" media-type="image/jpeg"/></opf:manifest></opf:package>""".toByteArray()))
    }

    @Test fun pathResolutionAllowsSafeRelativePathsButRejectsEscapesAndExternalContent() {
        assertEquals("Images/front cover+1.jpg", EpubCoverPath.resolve("OEBPS", "../Images/front%20cover+1.jpg"))
        for (path in listOf("../../outside.jpg", "https://host/image.jpg", "/absolute.jpg", "..%2f..%2foutside.jpg", "bad\\cover.jpg", "%00cover.jpg", "%GG")) {
            assertNull(path, EpubCoverPath.resolve("OEBPS", path))
        }
    }

    @Test fun missingOrBrokenMetadataKeepsGenericCoverFallbackAvailable() {
        assertNull(EpubCoverPath.opf("<container/>".toByteArray()))
        assertNull(EpubCoverPath.cover("book.opf", "<package><manifest/></package>".toByteArray()))
    }
}
