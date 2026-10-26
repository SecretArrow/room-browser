package com.roombrowser.engine

import com.roombrowser.domain.oct.OctUri

/**
 * Which URI schemes the engine renders and which it hands to the operating
 * system.
 *
 * THE APP'S NAVIGATION POLICY IS ASKED FIRST (see
 * [EngineSessionListener.onNavigationRequest]): the engine consults this
 * classifier only for a navigation that policy let through, so a scheme the
 * app claims for itself can never reach a handoff. The in-app set below is
 * the engine's own answer, kept total and self-documenting:
 *
 *  - http/https are documents. The engine renders them and MUST NEVER hand
 *    them off -- a handoff would offer every web link to whatever handler the
 *    OS picks;
 *  - about/blob/data/javascript are engine-internal schemes WebView has its
 *    own answers for (the start page, in-page byte stores, in-page scripts);
 *  - file is local-device content, renderable in-app;
 *  - `oct://` is the app's own circle-reader scheme. It is served by the app
 *    from its node -- never by the OS and never by the engine. It is matched
 *    through [OctUri.PREFIX], the same constant the app's own classifier
 *    (`OctNavigation.classify`) uses, so the two can never drift apart.
 *
 * Everything else -- `tel:`, `sms:`, `mailto:`, `geo:`, `market://`,
 * `intent://`, a messaging app's custom scheme -- names an ACTION, not a
 * document. No engine can render it; attempting to renders the platform's
 * `ERR_UNKNOWN_URL_SCHEME` error page, which is exactly the dead end this
 * classifier exists to prevent. The handoff is reported through
 * [EngineSessionListener.onExternalSchemeRequested]; building the Intent and
 * launching it is app policy and lives above the facade.
 *
 * PURE: string logic only, no Android types, so the decision is provable in a
 * JVM test.
 */
object ExternalUrlPolicy {

    /** Schemes the engine (or the app's own surfaces) render in-app. */
    private val IN_APP_SCHEMES = setOf(
        "http",
        "https",
        "about",
        "blob",
        "data",
        "javascript",
        "file"
    )

    /**
     * True when [uri] carries a scheme the engine cannot render and the OS
     * should be asked to open instead. A URI with no scheme, or with a
     * malformed one, is never a handoff target: it is not something this
     * class claims to know.
     */
    fun isHandoffTarget(uri: String): Boolean {
        val scheme = schemeOf(uri) ?: return false
        if (scheme in IN_APP_SCHEMES) return false
        // The circle reader's own scheme, spelled by the app's own constant.
        if (uri.startsWith(OctUri.PREFIX, ignoreCase = true)) return false
        return true
    }

    /**
     * The URI's scheme, lowercase, when [uri] actually carries one -- the
     * RFC 3986 shape: a letter first, then letters/digits/`+`/`-`/`.` up to
     * the first colon. `mailto:` is a scheme; `1http://`, `:rest` and
     * `example.com/page` are not, and answer null.
     */
    private fun schemeOf(uri: String): String? {
        val colon = uri.indexOf(':')
        if (colon <= 0) return null
        val candidate = uri.substring(0, colon)
        if (!candidate[0].isLetter()) return null
        if (!candidate.all { it.isLetterOrDigit() || it == '+' || it == '-' || it == '.' }) {
            return null
        }
        return candidate.lowercase()
    }
}
