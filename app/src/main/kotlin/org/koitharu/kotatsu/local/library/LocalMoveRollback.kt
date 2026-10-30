package org.koitharu.kotatsu.local.library

/** Best-effort reverse-order cleanup; never hide the original operation failure. */
internal object LocalMoveRollback {
    fun <T> cleanup(created: List<T>, delete: (T) -> Unit): List<Throwable> {
        val failures = ArrayList<Throwable>()
        for (item in created.asReversed()) {
            try {
                delete(item)
            } catch (failure: Exception) {
                failures += failure
            }
        }
        return failures
    }
}
