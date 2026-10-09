package org.koitharu.kotatsu.local.library

import org.junit.Assert.*
import org.junit.Test

class LocalCoverRecipeTest {
    @Test fun boundsSamplingAndRecipeIdentityAreStableAcrossGridSizes() {
        assertEquals(1, LocalCoverRecipe.sampleSize(512, 768))
        assertEquals(2, LocalCoverRecipe.sampleSize(2048, 1024))
        assertTrue(LocalCoverRecipe.sampleSize(Int.MAX_VALUE, Int.MAX_VALUE) > 0)
        assertEquals(3, LocalCoverPlan.THUMBNAIL_VERSION)
        assertTrue(LocalCoverRecipe.IDENTITY.contains("grid512"))
        assertTrue(LocalCoverRecipe.IDENTITY.contains("opaque-jpeg82"))
        assertTrue(LocalCoverRecipe.IDENTITY.contains("alpha-png"))
        assertEquals(768, LocalCoverRecipe.ANIMATION_MAX_EDGE)
    }
}
