package org.koitharu.kotatsu.list.ui.model

/** A full-width row of up to two virtual group cards. Manga cover models remain untouched. */
data class SmartLocalGroupRowModel(
    val first: Group,
    val second: Group?,
) : ListModel {
    data class Group(val name: String, val count: Int)
    override fun areItemsTheSame(other: ListModel): Boolean =
        other is SmartLocalGroupRowModel && first.name == other.first.name && second?.name == other.second?.name
}
