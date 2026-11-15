package org.koitharu.kotatsu.favourites.ui

import android.content.res.Configuration
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.koitharu.kotatsu.favourites.domain.DOWNLOADED_FAVOURITES_CATEGORY_ID
import org.koitharu.kotatsu.favourites.domain.LOCAL_FAVOURITES_CATEGORY_ID
import org.koitharu.kotatsu.favourites.domain.PRIVATE_COMPLETED_CATEGORY_ID
import org.koitharu.kotatsu.favourites.domain.PRIVATE_IN_PROGRESS_CATEGORY_ID
import java.util.Locale

class SystemFavouriteCategoryLabelTest {
	@Test
	fun systemLabelsFollowTheUiContextWithoutRenamingCustomCategories() {
		val base = InstrumentationRegistry.getInstrumentation().targetContext
		for ((locale, expected) in listOf(
			Locale.UK to listOf("Local", "Downloaded", "In progress", "Completed"),
			Locale.forLanguageTag("id-ID") to listOf("Lokal", "Diunduh", "Belum Selesai", "Selesai"),
		)) {
			val config = Configuration(base.resources.configuration).apply { setLocale(locale) }
			val localized = base.createConfigurationContext(config)
			val ids = listOf(LOCAL_FAVOURITES_CATEGORY_ID, DOWNLOADED_FAVOURITES_CATEGORY_ID, PRIVATE_IN_PROGRESS_CATEGORY_ID, PRIVATE_COMPLETED_CATEGORY_ID)
			assertEquals(expected, ids.map { localized.systemFavouriteCategoryLabel(it) })
			assertNull(localized.systemFavouriteCategoryLabel(42L))
		}
	}
}
