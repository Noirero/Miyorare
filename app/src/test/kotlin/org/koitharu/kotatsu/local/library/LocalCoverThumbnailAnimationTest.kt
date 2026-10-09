package org.koitharu.kotatsu.local.library

import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

/** Tests container classification without invoking a bitmap or multi-frame decoder. */
class LocalCoverThumbnailAnimationTest {
    @Test fun oneFrameGifIsStaticEvenWhenItHasLoopAndDelayMetadata() {
        assertEquals(false, LocalCoverThumbnail.animation(singleGif))
        assertEquals(false, LocalCoverThumbnail.animation(Base64.getDecoder().decode(
            "R0lGODlhIAAwAIEAAP8AAAAAAAAAAAAAACH/C05FVFNDQVBFMi4wAwEAAAAh+QQAFAAAACwAAAAAIAAwAEAIQQABCBxIsKDBgwgTKlzIsKHDhxAjSpxIsaLFixgzatzIsaPHjyBDihxJsqTJkyhTqlzJsqXLlzBjypxJs6bNmQEBADs="
        )))
    }

    @Test fun twoGifFramesWithDifferentPalettesAreAnimated() {
        assertEquals(true, LocalCoverThumbnail.animation(Base64.getDecoder().decode(
            "R0lGODlhIAAwAIEAAP8AAAAAAAAAAAAAACH/C05FVFNDQVBFMi4wAwEAAAAh+QQAFAAAACwAAAAAIAAwAAAIQQABCBxIsKDBgwgTKlzIsKHDhxAjSpxIsaLFixgzatzIsaPHjyBDihxJsqTJkyhTqlzJsqXLlzBjypxJs6bNmQEBACH5BAEUAAEALAAAAAAgADAAgQAA/wAAAAAAAAAAAAhBAAEIHEiwoMGDCBMqXMiwocOHECNKnEixosWLGDNq3Mixo8ePIEOKHEmypMmTKFOqXMmypcuXMGPKnEmzps2ZAQEAOw=="
        )))
    }

    @Test fun gifCommentBytesAreNotCountedAsFramesAndTruncationIsUncertain() {
        val comment = byteArrayOf(0x21, 0xfe.toByte(), 4, 0x2c, 0x2c, 0x2c, 0x2c, 0, 0x3b)
        assertEquals(false, LocalCoverThumbnail.animation(singleGif.copyOf(singleGif.size - 1) + comment))
        assertNull(LocalCoverThumbnail.animation(singleGif.copyOf(singleGif.size - 1)))
        assertNull(LocalCoverThumbnail.animation(singleGif.copyOf(20)))
        // Do not flatten an unfamiliar rendering extension merely because one image was seen.
        assertNull(LocalCoverThumbnail.animation(singleGif.copyOf(singleGif.size - 1) + byteArrayOf(0x21, 1, 0, 0x3b)))
    }

    @Test fun webpAnimationUsesVp8xFlagRatherThanExtensionOrIncidentalAnimText() {
        val header = "RIFF".toByteArray() + ByteArray(4) + "WEBPVP8X".toByteArray() + byteArrayOf(10, 0, 0, 0, 0)
        assertEquals(false, LocalCoverThumbnail.animation(header + "ANIM".toByteArray()))
        assertEquals(true, LocalCoverThumbnail.animation(header.copyOf().apply { this[20] = 2 }))
        assertEquals(false, LocalCoverThumbnail.animation(header.copyOf(20)))
    }

    private val singleGif = Base64.getDecoder().decode(
        "R0lGODdhIAAwAIEAAP8AAAAAAAAAAAAAACwAAAAAIAAwAEAIQQABCBxIsKDBgwgTKlzIsKHDhxAjSpxIsaLFixgzatzIsaPHjyBDihxJsqTJkyhTqlzJsqXLlzBjypxJs6bNmQEBADs="
    )
}
