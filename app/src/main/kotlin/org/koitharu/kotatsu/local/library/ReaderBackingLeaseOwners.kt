package org.koitharu.kotatsu.local.library

/**
 * Ownership ledger only. The caller serializes access and maintains MaterializedCachePins.
 * A repeated page enumeration for the same owner/backing does not acquire another durable pin.
 */
internal class ReaderBackingLeaseOwners {
    private val filesByOwner = HashMap<Any, MutableSet<String>>()

    fun retain(owner: Any, name: String): Boolean =
        filesByOwner.getOrPut(owner) { HashSet() }.add(name)

    fun release(owner: Any): Set<String> =
        filesByOwner.remove(owner).orEmpty()
}
