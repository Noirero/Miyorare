// SPDX-License-Identifier: GPL-3.0-only
// Derived from InvalidDavid/UMA at 2462a1f88f277f83c20758e682b7c8882f5dad9f; maintained in Miyorare.
package tsuki.site.id.voratoon

import tsuki.MangaLoaderContext

import kotlinx.coroutines.delay
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import kotlin.time.Duration.Companion.milliseconds

/**
 * Solves a Cloudflare Turnstile challenge headlessly and returns the token.
 *
 * Loads a minimal Turnstile page under the same origin as [url] (Turnstile only validates the
 * origin, the path is irrelevant), then renders the widget and polls for the token. The WebView
 * is never shown; if Turnstile requires interactive user input, the call fails fast with a clear
 * error instead of hanging until the timeout.
 *
 * @param context   the parser's MangaLoaderContext
 * @param url       any URL on the site whose origin is allowed by the Turnstile widget
 * @param siteKey   the Turnstile site key
 * @param action    optional Turnstile action (must match the site's expected action, if any)
 * @param cData     optional Turnstile customer data
 * @param theme     widget theme: "auto", "light", or "dark"
 * @param timeoutMs total time to wait for the token
 * @throws IllegalStateException on timeout, widget error, or interaction requirement
 */
suspend fun getTurnstileToken(
    context: MangaLoaderContext,
    url: String,
    siteKey: String,
    action: String? = null,
    cData: String? = null,
    theme: String = "auto",
    timeoutMs: Long = 120_000L,
): String {
    val options = buildString {
        append("{")
        append("sitekey:").append(JSONObject.quote(siteKey))
        append(",theme:").append(JSONObject.quote(theme))
        append(",appearance:'always'")
        action?.let { append(",action:").append(JSONObject.quote(it)) }
        cData?.let { append(",cData:").append(JSONObject.quote(it)) }
        append(",callback:function(t){window.__turnstileToken=t;}")
        append(",'error-callback':function(c){window.__turnstileError='error:'+c;}")
        append(",'expired-callback':function(){window.__turnstileError='error:expired';}")
        append(",'timeout-callback':function(){window.__turnstileError='error:timeout';}")
        append(",'unsupported-callback':function(){window.__turnstileError='error:unsupported';}")
        append(",'before-interactive-callback':function(){window.__turnstileInteractive=true;}")
        append(",'after-interactive-callback':function(){window.__turnstileInteractive=false;}")
        append("}")
    }

    val html = """
        <!DOCTYPE html>
        <html>
        <head>
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <style>html,body{margin:0;padding:0;background:transparent;}</style>
        </head>
        <body>
            <div id="c"></div>
            <script>
                window.__turnstileToken = null;
                window.__turnstileError = null;
                window.__turnstileInteractive = false;
                function onload() { turnstile.render('#c', $options); }
            </script>
            <script
                src="https://challenges.cloudflare.com/turnstile/v0/api.js?render=explicit&onload=onload"
                onerror="window.__turnstileError='error:script_load_failed'"></script>
        </body>
        </html>
    """.trimIndent()

    val origin = runCatching {
        url.toHttpUrl().let {
            if (it.port == HttpUrl.defaultPort(it.scheme)) "${it.scheme}://${it.host}/"
            else "${it.scheme}://${it.host}:${it.port}/"
        }
    }.getOrDefault(url)

    val loaded = context.evaluateJs(
        origin,
        "document.open(); document.write(${JSONObject.quote(html)}); document.close(); 1",
    )
    if (loaded == null) {
        throw IllegalStateException("Failed to load Turnstile page at $origin")
    }

    // Let api.js start downloading before the first poll.
    delay(300.milliseconds)

    val deadline = System.currentTimeMillis() + timeoutMs
    var interactiveSince = 0L

    while (System.currentTimeMillis() < deadline) {
        val result = context.evaluateJs(
            "",
            "(window.__turnstileToken)" +
                    "||(window.__turnstileError)" +
                    "||(window.__turnstileInteractive?'__interactive':'')",
        )

        when {
            result.isNullOrBlank() -> interactiveSince = 0L
            result == "__interactive" -> {
                if (interactiveSince == 0L) interactiveSince = System.currentTimeMillis()
                // Turnstile can auto-resolve after briefly flashing the widget
                // only fail if it stays interactive. Headless WebViews have no way to click the checkbox.
                if (System.currentTimeMillis() - interactiveSince > 15_000L) {
                    throw IllegalStateException(
                        "Turnstile requires user interaction; no interactive WebView is available",
                    )
                }
            }
            result.startsWith("error:") -> {
                val code = result.removePrefix("error:")
                throw IllegalStateException(
                    "Turnstile failed: ${turnstileErrorMessage(code)} (code: $code)",
                )
            }
            else -> return result
        }

        delay(500.milliseconds)
    }

    throw IllegalStateException("Turnstile timed out after ${timeoutMs}ms")
}

private fun turnstileErrorMessage(code: String): String = when {
    code == "expired" -> "Expired"
    code == "timeout" -> "Timed out"
    code == "unsupported" -> "WebView doesn't support captcha"
    code == "script_load_failed" -> "Script not loaded"
    code == "110100" || code == "110110" || code == "400020" -> "Sitekey rejected"
    code == "110200" -> "Not authorized for this domain"
    code.startsWith("1106") -> "Timed out"
    code.startsWith("100") -> "Failed to initialize"
    code.startsWith("102") -> "Invalid parameters"
    code == "200100" -> "Wrong Device clock"
    code == "200500" -> "Captcha didn't load"
    code.startsWith("120") -> "Internal Cloudflare error"
    code.startsWith("300") -> "Challenge failed in this browser"
    code.startsWith("600") -> "Challenge not solved"
    else -> "Unknown error"
}
