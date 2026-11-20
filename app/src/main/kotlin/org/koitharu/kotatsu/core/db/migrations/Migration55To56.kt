package org.koitharu.kotatsu.core.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import org.koitharu.kotatsu.favourites.domain.CategorySetIdentity

/**
 * Adds Set ownership without rebuilding library_groups, whose children have cascading FKs.
 * Room invokes migrations inside an upgrade transaction; failed checks abort the upgrade.
 */
class Migration55To56 : Migration(55, 56) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE library_groups ADD COLUMN set_id INTEGER NOT NULL DEFAULT 0")
        val activeSets = HashMap<Int, MutableList<ActiveSet>>()
        db.query(
            "SELECT set_id, sync_key, space FROM favourite_category_sets " +
                "WHERE deleted_at = 0 ORDER BY space, sort_key, sync_key",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val set = ActiveSet(cursor.getLong(0), cursor.getString(1))
                activeSets.getOrPut(cursor.getInt(2)) { ArrayList() }.add(set)
            }
        }
        val groups = ArrayList<Pair<Long, Int>>()
        db.query("SELECT group_id, space FROM library_groups ORDER BY group_id").use { cursor ->
            while (cursor.moveToNext()) groups += cursor.getLong(0) to cursor.getInt(1)
        }
        for ((groupId, space) in groups) {
            val available = requireNotNull(activeSets[space]?.takeIf { it.isNotEmpty() }) {
                "No active Set for library group $groupId in space $space"
            }
            val placementOwners = LinkedHashSet<Long>()
            db.query(
                "SELECT DISTINCT a.set_id FROM library_group_categories gc " +
                    "JOIN favourite_categories c ON c.category_id = gc.category_id " +
                    "JOIN favourite_category_set_assignments a ON a.category_id = c.category_id " +
                    "JOIN favourite_category_sets s ON s.set_id = a.set_id " +
                    "WHERE gc.group_id = ? AND c.space = ? AND c.deleted_at = 0 " +
                    "AND s.space = c.space AND s.deleted_at = 0",
                arrayOf<Any>(groupId, space),
            ).use { cursor ->
                while (cursor.moveToNext()) placementOwners += cursor.getLong(0)
            }
            val owner = placementOwners.singleOrNull()
                ?.takeIf { candidate -> available.any { it.id == candidate } }
                ?: (available.firstOrNull { it.syncKey == CategorySetIdentity.defaultSet(space) }
                    ?: available.first()).id
            db.execSQL(
                "UPDATE library_groups SET set_id = ? WHERE group_id = ?",
                arrayOf<Any>(owner, groupId),
            )
        }
        require(count(db,
            "SELECT COUNT(*) FROM library_groups g LEFT JOIN favourite_category_sets s ON s.set_id = g.set_id " +
                "WHERE g.set_id = 0 OR s.set_id IS NULL OR s.space != g.space OR s.deleted_at != 0",
        ) == 0L) { "Invalid library group Set ownership after migration" }
        require(count(db,
            "SELECT COUNT(*) FROM (SELECT g.space, g.set_id, m.manga_id " +
                "FROM library_group_members m JOIN library_groups g ON g.group_id = m.group_id " +
                "GROUP BY g.space, g.set_id, m.manga_id HAVING COUNT(*) > 1)",
        ) == 0L) { "Conflicting library group memberships after Set backfill" }
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_library_groups_space_set_id " +
                "ON library_groups(space, set_id)",
        )
        LibraryGroupSetSchema.installTriggers(db)
    }

    private fun count(db: SupportSQLiteDatabase, sql: String): Long =
        db.query(sql).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getLong(0)
        }

    private data class ActiveSet(val id: Long, val syncKey: String)
}

/** Same custom triggers must be installed both during upgrade and on fresh installation. */
object LibraryGroupSetSchema {
    fun installTriggers(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TRIGGER IF NOT EXISTS library_group_owner_insert
            BEFORE INSERT ON library_groups BEGIN
                SELECT CASE WHEN NOT EXISTS (
                    SELECT 1 FROM favourite_category_sets s
                    WHERE s.set_id = NEW.set_id AND s.space = NEW.space AND s.deleted_at = 0
                ) THEN RAISE(ABORT, 'Invalid library group Set owner') END;
            END
        """.trimIndent())
        db.execSQL("""
            CREATE TRIGGER IF NOT EXISTS library_group_owner_update
            BEFORE UPDATE OF set_id, space ON library_groups BEGIN
                SELECT CASE WHEN NOT EXISTS (
                    SELECT 1 FROM favourite_category_sets s
                    WHERE s.set_id = NEW.set_id AND s.space = NEW.space AND s.deleted_at = 0
                ) THEN RAISE(ABORT, 'Invalid library group Set owner') END;
                SELECT CASE WHEN EXISTS (
                    SELECT 1 FROM library_group_members owned
                    JOIN library_group_members other ON other.manga_id = owned.manga_id
                    JOIN library_groups owner ON owner.group_id = other.group_id
                    WHERE owned.group_id = OLD.group_id AND other.group_id != OLD.group_id
                      AND owner.space = NEW.space AND owner.set_id = NEW.set_id
                ) THEN RAISE(ABORT, 'Library group Set membership conflict') END;
            END
        """.trimIndent())
        for (operation in listOf("INSERT", "UPDATE OF group_id, manga_id")) {
            val suffix = if (operation == "INSERT") "insert" else "update"
            db.execSQL("""
                CREATE TRIGGER IF NOT EXISTS library_group_member_$suffix
                BEFORE $operation ON library_group_members BEGIN
                    SELECT CASE WHEN EXISTS (
                        SELECT 1 FROM library_groups target
                        JOIN library_group_members other ON other.manga_id = NEW.manga_id
                        JOIN library_groups owner ON owner.group_id = other.group_id
                        WHERE target.group_id = NEW.group_id AND other.group_id != NEW.group_id
                          AND owner.space = target.space AND owner.set_id = target.set_id
                    ) THEN RAISE(ABORT, 'Manga already grouped in this Set') END;
                END
            """.trimIndent())
        }
        db.execSQL("""
            CREATE TRIGGER IF NOT EXISTS library_group_set_tombstone
            BEFORE UPDATE OF deleted_at ON favourite_category_sets
            WHEN NEW.deleted_at != 0 AND OLD.deleted_at = 0 BEGIN
                SELECT CASE WHEN EXISTS (
                    SELECT 1 FROM library_groups WHERE set_id = OLD.set_id
                ) THEN RAISE(ABORT, 'Set still owns library groups') END;
            END
        """.trimIndent())
        db.execSQL("""
            CREATE TRIGGER IF NOT EXISTS library_group_set_delete
            BEFORE DELETE ON favourite_category_sets BEGIN
                SELECT CASE WHEN EXISTS (
                    SELECT 1 FROM library_groups WHERE set_id = OLD.set_id
                ) THEN RAISE(ABORT, 'Set still owns library groups') END;
            END
        """.trimIndent())
    }
}
