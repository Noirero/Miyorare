package org.koitharu.kotatsu.core.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import org.koitharu.kotatsu.favourites.domain.CategorySetIdentity

class Migration49To50 : Migration(49, 50) {
	override fun migrate(db: SupportSQLiteDatabase) {
		db.execSQL("CREATE TABLE IF NOT EXISTS favourite_category_sets (set_id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, sync_key TEXT NOT NULL, title TEXT NOT NULL, sort_key INTEGER NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, deleted_at INTEGER NOT NULL, space INTEGER NOT NULL)")
		db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_favourite_category_sets_sync_key ON favourite_category_sets(sync_key)")
		db.execSQL("CREATE INDEX IF NOT EXISTS index_favourite_category_sets_space ON favourite_category_sets(space)")
		db.execSQL("CREATE TABLE IF NOT EXISTS favourite_category_set_assignments (category_id INTEGER NOT NULL PRIMARY KEY, set_id INTEGER NOT NULL, category_sync_key TEXT NOT NULL, updated_at INTEGER NOT NULL, FOREIGN KEY(category_id) REFERENCES favourite_categories(category_id) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(set_id) REFERENCES favourite_category_sets(set_id) ON UPDATE NO ACTION ON DELETE RESTRICT)")
		db.execSQL("CREATE INDEX IF NOT EXISTS index_favourite_category_set_assignments_set_id ON favourite_category_set_assignments(set_id)")
		db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_favourite_category_set_assignments_category_sync_key ON favourite_category_set_assignments(category_sync_key)")
		CategorySetsSchema.initialize(db, migrateLegacy = true)
	}
}

/** Also used on fresh databases. SQL guards protect direct DAO/restore/sync writers. */
object CategorySetsSchema {
	fun initialize(db: SupportSQLiteDatabase, migrateLegacy: Boolean = false) {
		for (space in 0..1) {
			db.execSQL(
				"INSERT OR IGNORE INTO favourite_category_sets(sync_key,title,sort_key,created_at,updated_at,deleted_at,space) VALUES(?, 'Utama',0,0,0,0,?)",
				arrayOf<Any>(CategorySetIdentity.defaultSet(space), space),
			)
		}
		if (migrateLegacy) {
			val occurrences = HashMap<Pair<Int, String>, Int>()
			db.query("SELECT category_id,space,title,created_at,deleted_at FROM favourite_categories WHERE space IN (0,1) ORDER BY category_id").use { cursor ->
				while (cursor.moveToNext()) {
					val space = cursor.getInt(1)
					val title = cursor.getString(2)
					val key = space to CategorySetIdentity.normalizeTitle(title)
					val occurrence = occurrences[key] ?: 0
					occurrences[key] = occurrence + 1
					db.execSQL(
						"INSERT INTO favourite_category_set_assignments(category_id,set_id,category_sync_key,updated_at) SELECT ?,set_id,?,? FROM favourite_category_sets WHERE sync_key = ?",
						arrayOf<Any>(cursor.getLong(0), CategorySetIdentity.legacyCategory(space, title, occurrence), maxOf(cursor.getLong(3), cursor.getLong(4)), CategorySetIdentity.defaultSet(space)),
					)
				}
			}
		}
		// New categories get independent identities, including inserts by legacy importers.
		db.execSQL("""
			CREATE TRIGGER IF NOT EXISTS category_set_insert AFTER INSERT ON favourite_categories WHEN NEW.space IN (0,1) BEGIN
			INSERT OR IGNORE INTO favourite_category_sets(sync_key,title,sort_key,created_at,updated_at,deleted_at,space)
			SELECT 'miyorare:category-set:default:0','Utama',0,0,0,0,0 WHERE NOT EXISTS(SELECT 1 FROM favourite_category_sets WHERE space = 0 AND deleted_at = 0);
			INSERT OR IGNORE INTO favourite_category_sets(sync_key,title,sort_key,created_at,updated_at,deleted_at,space)
			SELECT 'miyorare:category-set:default:1','Utama',0,0,0,0,1 WHERE NOT EXISTS(SELECT 1 FROM favourite_category_sets WHERE space = 1 AND deleted_at = 0);
			INSERT INTO favourite_category_set_assignments(category_id,set_id,category_sync_key,updated_at)
			SELECT NEW.category_id,set_id,lower(hex(randomblob(16))),MAX(NEW.created_at,NEW.deleted_at)
			FROM favourite_category_sets WHERE space = NEW.space AND deleted_at = 0 ORDER BY sort_key,sync_key LIMIT 1;
			SELECT CASE WHEN NOT EXISTS(SELECT 1 FROM favourite_category_set_assignments WHERE category_id = NEW.category_id) THEN RAISE(ABORT,'Missing category Set') END;
			END
		""".trimIndent())
		for (operation in listOf("INSERT", "UPDATE")) db.execSQL("""
			CREATE TRIGGER IF NOT EXISTS category_set_guard_${operation.lowercase()} BEFORE $operation ON favourite_category_set_assignments BEGIN
			SELECT CASE WHEN NOT EXISTS(SELECT 1 FROM favourite_categories c JOIN favourite_category_sets s ON s.set_id = NEW.set_id WHERE c.category_id = NEW.category_id AND c.space = s.space AND (c.deleted_at != 0 OR s.deleted_at = 0)) THEN RAISE(ABORT,'Invalid category Set space or tombstone') END;
			END
		""".trimIndent())
		db.execSQL("""
			CREATE TRIGGER IF NOT EXISTS category_set_space_guard BEFORE UPDATE OF space,sync_key ON favourite_category_sets WHEN NEW.space != OLD.space OR NEW.sync_key != OLD.sync_key BEGIN
			SELECT RAISE(ABORT,'Set identity is immutable');
			END
		""".trimIndent())
		db.execSQL("""
			CREATE TRIGGER IF NOT EXISTS category_set_category_update AFTER UPDATE ON favourite_categories WHEN NEW.space IN (0,1) BEGIN
			UPDATE favourite_category_set_assignments SET updated_at = MAX(updated_at + 1, CAST((julianday('now') - 2440587.5)*86400000 AS INTEGER), NEW.deleted_at) WHERE category_id = NEW.category_id;
			END
		""".trimIndent())
		db.execSQL("""
			CREATE TRIGGER IF NOT EXISTS category_set_space_update BEFORE UPDATE OF space ON favourite_categories WHEN NEW.space != OLD.space BEGIN
			SELECT CASE WHEN EXISTS(SELECT 1 FROM favourite_category_set_assignments WHERE category_id = OLD.category_id) THEN RAISE(ABORT,'Category space is immutable') END;
			END
		""".trimIndent())
		db.execSQL("""
			CREATE TRIGGER IF NOT EXISTS category_set_delete_guard BEFORE UPDATE OF deleted_at ON favourite_category_sets WHEN NEW.deleted_at != 0 AND OLD.deleted_at = 0 BEGIN
			SELECT CASE WHEN (SELECT COUNT(*) FROM favourite_category_sets WHERE space = OLD.space AND deleted_at = 0) <= 1 THEN RAISE(ABORT,'Cannot delete last Set') END;
			SELECT CASE WHEN EXISTS(SELECT 1 FROM favourite_category_set_assignments a JOIN favourite_categories c ON c.category_id = a.category_id WHERE a.set_id = OLD.set_id AND c.deleted_at = 0) THEN RAISE(ABORT,'Set still contains categories') END;
			END
		""".trimIndent())
	}
}
