package com.roombrowser.browser.engine

/**
 * One line of the identity self-check: what a site is told, and whether that
 * answer marks this browser as an embedded one.
 */
data class IdentityCheck(
    /** The surface being reported, in the words a person can search for. */
    val label: String,
    /** True when this surface says "a browser" rather than "an app". */
    val ok: Boolean,
    /** The answer, plus what to do about it when [ok] is false. */
    val detail: String
)

/**
 * The surfaces Google's sign-in reads, turned into rows a person can act on.
 *
 * WHY THIS EXISTS. Google answers an embedded WebView with "Sign in with a
 * supported browser", and that page lists four possible causes: JavaScript
 * off, an unsupported extension, automation, or being embedded in another
 * application. The list is boilerplate — it names everything Google might
 * object to, not the one thing it objected to — so a person who lands there
 * has no way to tell which applies. This app knows exactly which of the four
 * it can control, so it answers them one by one instead of leaving the guess
 * to whoever hit the wall.
 *
 * JAVASCRIPT IS NEVER THE CAUSE HERE, and that is worth stating rather than
 * assuming: `WebViewEngineSession.configure` writes
 * `settings.javascriptEnabled` on every configure pass, and that field's own
 * default is `true` — the platform default is `false`, which is why the
 * assignment is explicit. Only a profile toggle or a per-site override can
 * turn it off. So the JavaScript row reports the profile's real answer and
 * nothing else.
 *
 * THE OTHER THREE ARE PROPERTIES OF THE INSTALLED WEBVIEW PROVIDER, not of
 * this APK. That is the whole point of reporting them separately: the same
 * engine code produces a clean identity on a current Android System WebView
 * and an incomplete one on a provider that predates the APIs, and only the
 * provider's version can explain the difference.
 */
object IdentityDiagnostics {

    /**
     * Tokens a WebView stamps on itself, each read by a site as "embedded".
     * The three string forms are what older providers put in the User-Agent;
     * `Android WebView` is the brand a provider older than User-Agent-Client-
     * Hints puts in `Sec-CH-UA` instead of `Google Chrome`.
     */
    private val EMBEDDED_TOKENS = listOf("; wv", " wv)", "Version/4.0", "Android WebView")

    /**
     * The browser families a sign-in page recognises, one token each.
     *
     * DELIBERATELY NOT "Chrome/". The app ships Firefox, Edge, Opera, Samsung
     * Internet, Brave and DuckDuckGo identities as well, and a row that called
     * every one of them a failure would be wrong on most of the catalogue. What
     * this list answers is the question the row actually asks — "would a site
     * recognise this as a browser at all?" — and Google's own supported list
     * (Chrome, Safari, Firefox, Opera, Edge) is exactly what it covers.
     *
     * Chromium derivatives are covered twice over on purpose: Edge, Opera and
     * Samsung all send a `Chrome/` token underneath their own, so they would
     * pass on that alone, and naming them separately keeps the list readable as
     * "the browsers Google accepts" rather than as an accident of inheritance.
     */
    private val BROWSER_TOKENS = listOf(
        "Chrome/",
        "Firefox/",
        "Edg/",
        "EdgA/",
        "OPR/",
        "SamsungBrowser/",
        "Version/"
    )

    /**
     * The first Android System WebView release that offers each provider-owned
     * surface, from androidx.webkit's own version table.
     *
     * NAMED IN THE ROWS ON PURPOSE. "Updating Android System WebView is the
     * only fix" is true and useless: it gives the person no way to tell a
     * WebView that is merely behind from one that is current and still
     * refusing, and no target to check their Play Store version against. The
     * number is the target.
     */
    private const val REQUESTED_WITH_OPT_OUT_VERSION = "71"
    private const val CLIENT_HINTS_VERSION = "77"
    private const val DOCUMENT_START_VERSION = "81"

    /**
     * Every check, in the order Google reads them: the string first, then the
     * headers, then what the page's own JavaScript sees.
     *
     * The capability flags are passed in rather than looked up so this stays a
     * pure function a JVM test can walk; `ProfileEngine` is the only caller and
     * it answers each of them from the engine.
     */
    fun checks(
        userAgent: String?,
        javascriptEnabled: Boolean,
        suppressesRequestedWith: Boolean,
        setsClientHints: Boolean,
        installsDocumentStartScripts: Boolean
    ): List<IdentityCheck> = listOf(
        userAgentCheck(userAgent),
        IdentityCheck(
            label = "X-Requested-With header",
            ok = suppressesRequestedWith,
            detail = if (suppressesRequestedWith) {
                "Not sent. The engine can suppress the header WebView stamps on " +
                    "every request, so no site learns this app's package name from it."
            } else {
                "Sent on every request, naming this app to every site. The engine " +
                    "stamps this header itself, so only the engine can take it off, " +
                    "and that opt-out needs Android System WebView " +
                    "$REQUESTED_WITH_OPT_OUT_VERSION or newer. Updating Android " +
                    "System WebView is what removes it."
            }
        ),
        IdentityCheck(
            label = "Sec-CH-UA client hints",
            ok = setsClientHints,
            detail = if (setsClientHints) {
                "Sent, and derived from the same string as the User-Agent, so the " +
                    "two cannot describe different browsers."
            } else {
                "Not under this app's control. Replacing the provider's own brand " +
                    "list needs Android System WebView $CLIENT_HINTS_VERSION or " +
                    "newer; below that it still names Android WebView, and no " +
                    "app-side code can replace a header the engine builds itself."
            }
        ),
        IdentityCheck(
            label = "navigator.userAgentData",
            ok = installsDocumentStartScripts,
            detail = if (installsDocumentStartScripts) {
                "Present, installed by the page-side identity script."
            } else {
                "Absent. Installing it needs Android System WebView " +
                    "$DOCUMENT_START_VERSION or newer; below that there is no " +
                    "document-start hook, so the page-side half of the identity " +
                    "cannot be installed at all."
            }
        ),
        IdentityCheck(
            label = "JavaScript",
            ok = javascriptEnabled,
            detail = if (javascriptEnabled) {
                "On. Google's sign-in page lists \"JavaScript turned off\" as a " +
                    "possible cause; in this app it is off only when the profile's " +
                    "own toggle turned it off."
            } else {
                "Off for this profile, because the profile's own toggle turned it " +
                    "off. Google's sign-in needs it: turn it back on in this " +
                    "profile's settings."
            }
        )
    )

    private fun userAgentCheck(userAgent: String?): IdentityCheck {
        if (userAgent.isNullOrBlank()) {
            return IdentityCheck(
                label = "User-Agent",
                ok = false,
                detail = "The engine has not reported one yet."
            )
        }
        val token = EMBEDDED_TOKENS.firstOrNull { userAgent.contains(it) }
        return IdentityCheck(
            label = "User-Agent",
            ok = token == null && BROWSER_TOKENS.any { userAgent.contains(it) },
            detail = when {
                token != null ->
                    "Carries '$token', the token a site reads as an embedded browser."
                BROWSER_TOKENS.none { userAgent.contains(it) } ->
                    "Names no browser a sign-in page recognises, so it cannot be " +
                        "treated as one: $userAgent"
                else -> userAgent
            }
        )
    }
}
