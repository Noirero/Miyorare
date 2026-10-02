package org.koitharu.kotatsu.sync.library

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.time.Instant

class LibrarySyncMappingStore(context: Context) : SQLiteOpenHelper(context, "library-sync.db", null, 1) {
	override fun onCreate(db: SQLiteDatabase) {
		db.execSQL("CREATE TABLE mappings(service TEXT NOT NULL, external_id TEXT NOT NULL, local_manga_id INTEGER NOT NULL, updated_at INTEGER NOT NULL, PRIMARY KEY(service, external_id))")
		db.execSQL("CREATE INDEX mappings_local ON mappings(local_manga_id)")
	}
	override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

	fun put(service: LibrarySyncServiceId, externalId: String, localMangaId: Long, updatedAt: Instant = Instant.now()) {
		writableDatabase.execSQL(
			"INSERT OR REPLACE INTO mappings(service, external_id, local_manga_id, updated_at) VALUES(?,?,?,?)",
			arrayOf(service.name, externalId, localMangaId, updatedAt.toEpochMilli()),
		)
	}

	fun localId(service: LibrarySyncServiceId, externalId: String): Long? =
		readableDatabase.rawQuery(
			"SELECT local_manga_id FROM mappings WHERE service=? AND external_id=?",
			arrayOf(service.name, externalId),
		).use { if (it.moveToFirst()) it.getLong(0) else null }

	fun externalId(service: LibrarySyncServiceId, localMangaId: Long): String? =
		readableDatabase.rawQuery(
			"SELECT external_id FROM mappings WHERE service=? AND local_manga_id=? ORDER BY updated_at DESC LIMIT 1",
			arrayOf(service.name, localMangaId.toString()),
		).use { if (it.moveToFirst()) it.getString(0) else null }
}
