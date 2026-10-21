package org.miyorare.kotatsu.runtime;

import android.content.Context;

/** Dedicated runtime-pack fixture loaded from a separately installed APK by DexClassLoader. */
public final class KotatsuRuntimeEntrypoint {
    @SuppressWarnings("unused")
    private final Context context;

    public KotatsuRuntimeEntrypoint(Context context) {
        this.context = context;
    }

    public int protocolVersion() {
        return 1;
    }

    public boolean healthCheck() {
        try {
            ClassLoader runtimeLoader = getClass().getClassLoader();
            if (runtimeLoader == null || runtimeLoader.getParent() == null) {
                return false;
            }
            Class<?> runtimeParser = Class.forName(
                    "org.koitharu.kotatsu.parsers.MangaLoaderContext",
                    false,
                    runtimeLoader
            );
            Class<?> hostParser = Class.forName(
                    "org.koitharu.kotatsu.parsers.MangaLoaderContext",
                    false,
                    runtimeLoader.getParent()
            );
            Object marker = runtimeParser.getMethod("runtimeFixtureMarker").invoke(null);
            return runtimeParser != hostParser
                    && runtimeParser.getClassLoader() == runtimeLoader
                    && hostParser.getClassLoader() != runtimeLoader
                    && "kotatsu-runtime-fixture".equals(marker);
        } catch (Throwable error) {
            return false;
        }
    }

    public String catalogJson() {
        return "[{\"key\":\"FIXTURE\",\"title\":\"Runtime Fixture\",\"locale\":\"en\",\"contentType\":\"MANGA\",\"broken\":false}]";
    }

    public String getListJson(String source, int offset, String order, String filterJson) {
        return "[]";
    }

    public String getDetailsJson(String source, String mangaJson) {
        return mangaJson;
    }

    public String getPagesJson(String source, String chapterJson) {
        return "[]";
    }

    public String getPageUrl(String source, String pageJson) {
        return "https://example.invalid/page.jpg";
    }

    public String getRelatedJson(String source, String mangaJson) {
        return "[]";
    }

    public String sortOrdersJson(String source) {
        return "[\"POPULARITY\"]";
    }

    public String filterCapabilitiesJson(String source) {
        return "{\"isMultipleTagsSupported\":false,\"isTagsExclusionSupported\":false,\"isSearchSupported\":true,\"isSearchWithFiltersSupported\":false,\"isYearSupported\":false,\"isYearRangeSupported\":false,\"isOriginalLocaleSupported\":false,\"isAuthorSearchSupported\":false}";
    }

    public String filterOptionsJson(String source) {
        return "{}";
    }

    public String sourceConfigJson(String source) {
        return "{\"items\":[]}";
    }

    public String setSourceConfigJson(String source, String valuesJson) {
        return sourceConfigJson(source);
    }

    public String requestHeadersJson(String source) {
        return "{}";
    }
}
