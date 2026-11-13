package org.koitharu.kotatsu.favourites.groups.ui

/**
 * Shared slot order for Library Group grid and details collage.
 * Preserve the grid's existing first-four, repeating-member behavior.
 */
internal fun <T> collageCoverSlots(members: List<T>, slotCount: Int = 4): List<T> =
    if (members.isEmpty() || slotCount <= 0) emptyList()
    else List(slotCount) { index -> members[index % members.size] }
