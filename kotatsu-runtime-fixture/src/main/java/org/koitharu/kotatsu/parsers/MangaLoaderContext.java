package org.koitharu.kotatsu.parsers;

/** Deliberately collides with the host parser ABI to prove class-loader isolation on Android. */
public final class MangaLoaderContext {
    private MangaLoaderContext() {
    }

    public static String runtimeFixtureMarker() {
        return "kotatsu-runtime-fixture";
    }
}
