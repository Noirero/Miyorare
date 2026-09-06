package org.koitharu.kotatsu.core.util.ext

import android.content.SharedPreferences
import androidx.collection.ArraySet
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import org.json.JSONArray

fun <E : Enum<E>> SharedPreferences.getEnumValue(key: String, enumClass: Class<E>): E? {
	val stringValue = getString(key, null) ?: return null
	return enumClass.enumConstants?.find {
		it.name == stringValue
	}
}

fun <E : Enum<E>> SharedPreferences.getEnumValue(key: String, defaultValue: E): E {
	return getEnumValue(key, defaultValue.javaClass) ?: defaultValue
}

fun <E : Enum<E>> SharedPreferences.Editor.putEnumValue(key: String, value: E?) {
	putString(key, value?.name)
}

fun SharedPreferences.observeChanges(): Flow<String?> = callbackFlow {
	val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
		// SharedPreferences can notify listeners on the main thread. Never block UI dispatch
		// while a downstream collector is temporarily busy; keep pending keys queued instead.
		trySend(key)
	}
	registerOnSharedPreferenceChangeListener(listener)
	awaitClose {
		unregisterOnSharedPreferenceChangeListener(listener)
	}
}.buffer(Channel.UNLIMITED)

fun <T> SharedPreferences.observe(key: String, valueProducer: suspend () -> T): Flow<T> = flow {
	emit(valueProducer())
	observeChanges().collect { upstreamKey ->
		if (upstreamKey == key) {
			emit(valueProducer())
		}
	}
}.distinctUntilChanged()

fun SharedPreferences.Editor.putAll(values: Map<String, *>) {
	values.forEach { e ->
		when (val v = e.value) {
			is Boolean -> putBoolean(e.key, v)
			is Int -> putInt(e.key, v)
			is Long -> putLong(e.key, v)
			is Float -> putFloat(e.key, v)
			is String -> putString(e.key, v)
			is JSONArray -> putStringSet(e.key, v.toStringSet())
			is Set<*> -> putStringSet(e.key, v.filterIsInstance<String>().toSet())
		}
	}
}

private fun JSONArray.toStringSet(): Set<String> {
	val len = length()
	val result = ArraySet<String>(len)
	for (i in 0 until len) {
		result.add(getString(i))
	}
	return result
}
