package org.koitharu.kotatsu.favourites.vault

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** In-memory unlock state. Process death or app backgrounding returns protected Private to locked. */
@Singleton
class PrivateFavouritesSession @Inject constructor(
	private val security: PrivateFavouritesSecurityStore,
) : DefaultLifecycleObserver {
	private val mutableUnlocked = MutableStateFlow(security.protection == PrivateFavouritesProtection.NONE)
	val isUnlocked = mutableUnlocked.asStateFlow()

	init {
		ProcessLifecycleOwner.get().lifecycle.addObserver(this)
	}

	fun unlock() {
		mutableUnlocked.value = true
	}

	fun lock() {
		mutableUnlocked.value = false
	}

	override fun onStop(owner: LifecycleOwner) {
		if (security.protection == PrivateFavouritesProtection.NONE) {
			unlock()
		} else {
			lock()
		}
	}
}
