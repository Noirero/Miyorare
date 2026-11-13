package org.koitharu.kotatsu.search.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MangaListActivityInitializationTest {

	@Test
	fun `nsfw check does not depend on source initialized after super onCreate`() {
		val isNsfwContent = source()
			.substringAfter("overridefunisNsfwContent():Flow<Boolean>=")
			.substringBefore("overridefunonApplyWindowInsets(")

		assertEquals(
			"flowOf(MangaSource(intent.getStringExtra(AppRouter.KEY_SOURCE)).isNsfw())",
			isNsfwContent,
		)
	}

	@Test
	fun `shelf session restores selected sort and filter instead of replaying launch sort`() {
		val code = source()
		val restore = code.substringAfter("if(existingFragmentisFilterCoordinator.Owner){").substringBefore("}else{")
		assertFalse(restore.contains("setSessionSortOrder"))
		assertTrue(code.contains("restoredShelf?.sortOrder?:intent.getSerializableExtraCompat"))
		assertTrue(code.contains("outState.putString(STATE_SHELF_SORT,snapshot.sortOrder.name)"))
		assertTrue(code.contains("outState.putParcelable(STATE_SHELF_FILTER,ParcelableMangaListFilter(snapshot.listFilter))"))
		assertTrue(code.contains("restoreShelfSession(fragment.filterCoordinator,shelf)"))
	}

	private fun source(): String {
		val relativePath = "org/koitharu/kotatsu/search/ui/MangaListActivity.kt"
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
