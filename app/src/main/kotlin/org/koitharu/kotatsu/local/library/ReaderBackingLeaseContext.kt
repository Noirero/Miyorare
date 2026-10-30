package org.koitharu.kotatsu.local.library

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Identifies a single page-enumeration attempt. The Reader owns the token after a successful
 * publication; unsuccessful attempts release it without invalidating any other Reader's URLs.
 */
class ReaderBackingLeaseContext(val token: Any) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ReaderBackingLeaseContext>
}
