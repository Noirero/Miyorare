package org.koitharu.kotatsu.list.ui.model

/** A full-width row of up to two virtual group cards. Manga cover models remain untouched. */
data class SmartLocalGroupRowModel(
    val first: Group,
    val second: Group?,
) : ListModel {
    data class Group(val name: String, val count: Int)
    override fun areItemsTheSame(other: ListModel): Boolean =
        other is SmartLocalGroupRowModel && first.name == other.first.name && second?.name == other.second?.name

    companion object {
        const val COLLAPSED_LIMIT = 4

        fun rows(groups: List<Group>, expanded: Boolean): List<SmartLocalGroupRowModel> {
            val visible = if (expanded) groups else groups.take(COLLAPSED_LIMIT)
            return visible.chunked(2).map { pair ->
                SmartLocalGroupRowModel(pair[0], pair.getOrNull(1))
            }
        }
    }
}
