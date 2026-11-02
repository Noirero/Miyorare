package org.koitharu.kotatsu.local.library

/** Group membership uses stable document keys, not volatile list positions. */
internal object LocalGroupVisibility {
    fun visible(selectedGroup: String?, groups: Map<String, Set<String>>, documentKey: String): Boolean =
        selectedGroup == null || groups[selectedGroup]?.contains(documentKey) == true

    fun count(groups: Map<String, Set<String>>, group: String, availableKeys: Set<String>): Int =
        groups[group].orEmpty().count { it in availableKeys }
}
