package org.koitharu.kotatsu.local.library

/** Same scheme boundary as the routing repository; ownership/containment stay in the index. */
fun isSmartLocalUri(url: String): Boolean = url.substringBefore(':', "") == LOCAL_LIBRARY_SCHEME
