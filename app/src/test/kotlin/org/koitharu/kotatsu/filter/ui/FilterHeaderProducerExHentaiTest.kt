package org.koitharu.kotatsu.filter.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.sources.compat.EhentaiSourceFamily

class FilterHeaderProducerExHentaiTest {

    @Test
    fun `official ExHentai keeps dynamic filter details out of toolbar chips`() {
        assertFalse(shouldExposeDynamicFilterChips(EhentaiSourceFamily.OFFICIAL_SOURCE_NAME))
    }

    @Test
    fun `other dynamic sources keep their toolbar chips`() {
        assertTrue(shouldExposeDynamicFilterChips("MIHON_123456789"))
    }
}
