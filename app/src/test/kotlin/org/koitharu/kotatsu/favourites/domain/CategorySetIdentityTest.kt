package org.koitharu.kotatsu.favourites.domain

import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class CategorySetIdentityTest {
	@Test fun independentUpgradesConvergeDespiteLocalIdsAndLocale() {
		val previous = Locale.getDefault()
		try {
			Locale.setDefault(Locale.forLanguageTag("tr"))
			assertEquals(CategorySetIdentity.legacyCategory(0, "FAVORITE"), CategorySetIdentity.legacyCategory(0, " favorite "))
			assertNotEquals(CategorySetIdentity.legacyCategory(0, "Favorite"), CategorySetIdentity.legacyCategory(1, "Favorite"))
			assertNotEquals(CategorySetIdentity.legacyCategory(0, "Favorite"), CategorySetIdentity.legacyCategory(0, "Favorite", 1))
			assertNotEquals(CategorySetIdentity.defaultSet(0), CategorySetIdentity.defaultSet(1))
		} finally { Locale.setDefault(previous) }
	}
}
