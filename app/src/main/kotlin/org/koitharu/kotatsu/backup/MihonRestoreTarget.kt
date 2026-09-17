package org.koitharu.kotatsu.backup

/**
 * Destination workspace for collection data restored from a Mihon/Tachiyomi-compatible backup.
 * The target is consumed by MihonBackupManager before its Room transaction starts, so Normal and
 * Private memberships/categories are never staged through one another.
 */
enum class MihonRestoreTarget {
    NORMAL,
    PRIVATE,
}
