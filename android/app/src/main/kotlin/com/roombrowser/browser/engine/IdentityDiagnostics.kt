package com.roombrowser.browser.engine

import com.roombrowser.engine.EmbeddedHeaderObservation

/**
 * One line of the identity self-check: what a site is told, and whether that
 * answer marks this browser as an embedded one.
 */
data class IdentityCheck(
    /** The surface being reported, in the words a person can search for. */
    val label: String,
    /**
     * True when this surface says "a browser", false when it says "an app",
     * and NULL when nothing has been measured yet.
     *
     * The third state is not a nicety: the header is decided per request, so
     * before a page has loaded there is no answer to give. Reporting an
     * unmeasured surface as a pass would reassure somebody on a device where
     * sign-in cannot work, and reporting it as a failure is the mistake this
     * screen already made once -- telling people to update a WebView that was
     * current because a feature flag said false for the opposite reason.
     */
    val ok: Boolean?,
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
 * TWO OF THE REMAINING THREE ARE PROPERTIES OF THE INSTALLED WEBVIEW PROVIDER,
 * not of this APK — the client hints and the document-start hook. That is the
 * whole point of reporting them separately: the same engine code produces a
 * clean identity on a current Android System WebView and an incomplete one on
 * a provider that predates the APIs, and only the provider's version can
 * explain the difference.
 *
 * THE HEADER IS NOT ONE OF THEM ANY MORE, and that distinction is the whole
 * reason [requestedWithCheck] exists. Chromium discontinued `X-Requested-With`
 * and deprecated the opt-out API alongside it, so the provider's feature flag
 * no longer answers "is it sent" — it answers "does this provider still have
 * the thing to suppress", which is false at both ends of the version range.
 * The header is therefore reported from what the engine OBSERVED on a real
 * request, and is reported as unmeasured when nothing has been sent yet.
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
        requestedWith: EmbeddedHeaderObservation,
        setsClientHints: Boolean,
        installsDocumentStartScripts: Boolean
    ): List<IdentityCheck> = listOf(
        userAgentCheck(userAgent),
        requestedWithCheck(requestedWith),
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

    /**
     * The header, from what the engine actually saw on a request.
     *
     * THE OLD ROW ASKED THE WRONG QUESTION. It read the provider's opt-out
     * feature flag and, when that was false, said the header "is sent on every
     * request" and that the device's WebView was "older than the opt-out". Both
     * halves were wrong on a current device: Chromium discontinued the header
     * in late 2022 and deprecated the opt-out with it, so the flag now reads
     * false on a provider that adds no header at all -- the same answer an
     * ancient provider gives for the opposite reason. People were sent to
     * update a WebView that was already current, and the real state of the
     * request stayed unknown.
     *
     * THE OBSERVATION THIS ROW ANSWERS FROM IS THE MAIN-FRAME SIGHTING (the
     * caller passes [EmbeddedHeaderObservation] from
     * `embeddedBrowserHeaderMainFrameObservation`), because that is the
     * request the refusal reads and the request the session's empty-header
     * override targets.
     */
    private fun requestedWithCheck(observed: EmbeddedHeaderObservation): IdentityCheck =
        IdentityCheck(
            label = "X-Requested-With header (page request)",
            ok = when (observed) {
                EmbeddedHeaderObservation.ABSENT -> true
                EmbeddedHeaderObservation.PRESENT -> false
                EmbeddedHeaderObservation.UNOBSERVED -> null
            },
            detail = when (observed) {
                EmbeddedHeaderObservation.ABSENT ->
                    "Not sent. No main-document request this app has made carried " +
                        "it: every main-frame load is issued with an empty " +
                        "X-Requested-With override (loadUrl's additional headers, " +
                        "the lever the provider has honoured since WebView 71), and " +
                        "no site learns this app's package name from the page " +
                        "request. Google's sign-in refusal reads exactly this " +
                        "request, so a clean row here is the sign-in half done."
                EmbeddedHeaderObservation.PRESENT ->
                    "Sent, naming this app, on the main-document request. Every " +
                        "main-frame load is issued with an empty X-Requested-With " +
                        "override and page-started navigations into Google's " +
                        "sign-in hosts are re-issued the same way, so this row " +
                        "means the provider stamped the header anyway -- it ignored " +
                        "the override, or the document was reached on a " +
                        "cross-origin redirect hop the engine issues itself, the " +
                        "one navigation kind no app header survives. Updating the " +
                        "WebView (Android System WebView in Play/system settings) " +
                        "is the real remedy on the devices that show this row."
                EmbeddedHeaderObservation.UNOBSERVED ->
                    "Not measured yet. The header is decided on each request, so " +
                        "open a page and read this row again -- a browser that has " +
                        "loaded nothing has nothing to report here."
            }
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
