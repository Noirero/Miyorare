package org.koitharu.kotatsu.favourites.ui.list

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FavouritesSelectionMenuRegressionTest {

	@Test
	fun `modern selection More item uses a valid AppCompat menu category`() {
		val source = source("org/koitharu/kotatsu/favourites/ui/list/FavouritesListFragment.kt")

		assertTrue(source.contains("MODERN_SELECTION_MORE_ORDER=0xFFFF"))
		assertTrue(
			source.contains(
				"menu.add(Menu.NONE,MODERN_SELECTION_MORE_ID,MODERN_SELECTION_MORE_ORDER,R.string.more)",
			),
		)
		assertFalse(
			"Int.MAX_VALUE sets invalid AppCompat menu category bits",
			source.contains("menu.add(Menu.NONE,MODERN_SELECTION_MORE_ID,Int.MAX_VALUE,R.string.more)"),
		)
	}

	private fun source(relativePath: String): String {
		return (
			sequenceOf(
				File("src/main/kotlin", relativePath),
				File("app/src/main/kotlin", relativePath),
			).firstOrNull(File::isFile)?.readText()
				?: error("Cannot find production source: $relativePath")
		)
			.replace(Regex("""//[^\r\n]*"""), "")
			.replace(Regex("""\s+"""), "")
	}
}
