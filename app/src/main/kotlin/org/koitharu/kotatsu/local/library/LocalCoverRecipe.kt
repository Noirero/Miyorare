package org.koitharu.kotatsu.local.library

/** Stable grid representation, independent of RecyclerView layout, density changes and Reader. */
internal object LocalCoverRecipe {
    const val VERSION = 3
    // The normal list grid prefers 120dp-wide portrait cards; 512px covers its common density
    // range without retaining the 768px extraction representation. Large/custom grids may upscale.
    const val STATIC_MAX_EDGE = 512
    const val JPEG_QUALITY = 82
    // Animation remains byte-for-byte and retains the pre-existing eligibility policy.
    const val ANIMATION_MAX_EDGE = 768
    const val IDENTITY = "v3:grid512:opaque-jpeg82:alpha-png:exif1:animation768-4MiB-passthrough:archive-index1:epub-opf1"

    fun sampleSize(width: Int, height: Int): Int {
        var sample = 1
        while (maxOf(width, height).toLong() / sample > STATIC_MAX_EDGE * 2L) sample *= 2
        return sample
    }
}
