package org.koitharu.kotatsu.core.db

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.koitharu.kotatsu.SampleData
import org.koitharu.kotatsu.core.db.entity.toEntity
import org.koitharu.kotatsu.core.db.migrations.Migration49To50
import org.koitharu.kotatsu.core.db.migrations.Migration50To51
import org.koitharu.kotatsu.favourites.data.*
import org.koitharu.kotatsu.favourites.domain.CategorySetIdentity

@RunWith(AndroidJUnit4::class)
class CategorySetsMigrationTest {
	private val context = InstrumentationRegistry.getInstrumentation().targetContext

	@Test fun upgradePreservesRowsMembershipsDuplicatesAndRestart() = runBlocking {
		val name = "category-sets-upgrade-test"
		context.deleteDatabase(name)
		val categories = listOf(
			category(41, 0, "Favorite", 7), category(42, 0, " favorite ", 9),
			category(43, 1, "Private", 2), category(44, 0, "Deleted", 11).copy(deletedAt = 55),
		)
		val contentTypes = org.koitharu.kotatsu.favourites.domain.FavouriteContentTypeStore(context)
		contentTypes.setCategoryType(42, org.koitharu.kotatsu.favourites.domain.FavouriteContentType.NOVEL)
		val manga = SampleData.manga.toEntity()
		val normal = listOf(FavouriteEntity(manga.id, 41, 2, true, 123, 0), FavouriteEntity(manga.id, 42, 3, false, 124, 0), FavouriteEntity(manga.id, 44, 4, false, 125, 55))
		val private = PrivateFavouriteEntity(manga.id, 43, 8, true, 126, 0)
		try {
			val initial = Room.databaseBuilder(context, MangaDatabase::class.java, name).build()
			initial.getMangaDao().upsert(manga, emptyList())
			for (c in categories) initial.getFavouriteCategoriesDao().insert(c)
			initial.getFavouritesDao().insert(normal)
			initial.getPrivateFavouritesDao().insert(listOf(private))
			// Category/membership schemas are unchanged by v50. Remove exactly the new tables to
			// obtain a complete v49 database, including all unrelated Room tables and legacy rows.
			initial.openHelper.writableDatabase.apply {
				execSQL("DROP TABLE favourite_category_set_assignments")
				execSQL("DROP TABLE favourite_category_sets")
				execSQL("DROP TABLE room_master_table")
				version = 49
			}
			initial.close()
			repeat(2) {
				val migrated = Room.databaseBuilder(context, MangaDatabase::class.java, name)
					.addMigrations(Migration49To50(), Migration50To51())
					.build()
				try {
					assertTrue(migrated.openHelper.writableDatabase.isOpen) // Room validates the migrated schema.
					assertEquals(categories, (migrated.getFavouriteCategoriesDao().findAllForSync() + migrated.getFavouriteCategoriesDao().findAllInSpace(1)).sortedBy { it.categoryId })
					assertEquals(normal.toSet(), migrated.getFavouritesDao().findAllForSync().toSet())
					assertEquals(55L, migrated.getFavouriteCategoriesDao().findAllForSync().first { it.categoryId == 44 }.deletedAt)
					assertTrue(contentTypes.isCategoryForType(42, org.koitharu.kotatsu.favourites.domain.FavouriteContentType.NOVEL))
					assertEquals(listOf(private), migrated.getPrivateFavouritesDao().findAll(43).map { it.favourite })
					val dao = migrated.getFavouriteCategorySetsDao()
					for (space in 0..1) {
						val sets = dao.getSets(space)
						assertEquals(1, sets.size)
						assertEquals(CategorySetIdentity.defaultSet(space), sets.single().syncKey)
						assertEquals(categories.filter { it.space == space }.map { it.categoryId.toLong() }.toSet(), dao.getAssignments(space).map { it.categoryId }.toSet())
						assertTrue(dao.getAssignments(space).all { a -> a.setId == sets.single().setId })
					}
					assertEquals(CategorySetIdentity.legacyCategory(0, "Favorite"), dao.getAssignment(41)?.categorySyncKey)
					assertEquals(CategorySetIdentity.legacyCategory(0, "Favorite", 1), dao.getAssignment(42)?.categorySyncKey)
				} finally { migrated.close() }
			}
		} finally { context.deleteDatabase(name); contentTypes.removeCategories(listOf(42)) }
	}

	@Test fun emptyUpgradeCreatesBothDefaultSets() {
		val db = Room.inMemoryDatabaseBuilder(context, MangaDatabase::class.java).build()
		try {
			val sql = db.openHelper.writableDatabase
			sql.execSQL("DROP TABLE favourite_category_set_assignments")
			sql.execSQL("DROP TABLE favourite_category_sets")
			Migration49To50().migrate(sql)
			runBlocking { assertEquals(2, db.getFavouriteCategorySetsDao().getSets(0).size + db.getFavouriteCategorySetsDao().getSets(1).size) }
		} finally { db.close() }
	}

	private fun category(id: Int, space: Int, title: String, order: Int) = FavouriteCategoryEntity(id, 123, order, title, "NEWEST", true, true, true, 0, space)
}
