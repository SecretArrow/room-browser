package com.roombrowser.engine

/**
 * The per-ORIGIN cookie erasure plan: what to send to `CookieManager
 * .setCookie` so that ONE site's cookies die, and nothing else's.
 *
 * WHY THIS EXISTS. Android's CookieManager has `removeAllCookies` (the whole
 * profile's jar) and `removeSessionCookies` (every session cookie in it) and
 * nothing in between. Per-site erasure therefore has to be synthesised from
 * the one primitive left: `setCookie(url, header)` can overwrite a cookie with
 * an expired twin, and an expired cookie is gone. This object turns a page URL
 * plus the jar's own `getCookie(url)` answer into exactly the overwrite
 * requests that deletes that site's cookies.
 *
 * THE PARTS NO PUBLIC API COVERS, stated plainly rather than hidden:
 *
 *  - `getCookie(url)` returns every cookie the browser would ATTACH to a
 *    request to [url] — including cookies whose domain is the registrable
 *    parent (`accounts.google.com` sees a `.google.com` cookie). Each name
 *    must be expired against the domain it was SET on, so every host in
 *    [hostChain] gets its own overwrite for every name: the host itself, and
 *    the two-label parent a third-level host inherits from.
 *  - A host-only overwrite (no `Domain=`) reaches only cookies set host-only;
 *    a `Domain=` overwrite reaches the domain-wide ones. Both variants are
 *    emitted because `getCookie` cannot say which shape a name was set in.
 *  - `Max-Age=0` is the RFC 6268/RFC 6265 deletion directive; the 1970
 *    `Expires` travels with it because some providers were shipped before
 *    Max-Age parsing and have never been removed from the fleet.
 *
 * Everything here is pure string work so a JVM test can walk the whole plan:
 * the engine call site only forwards what this answers.
 */
object SiteCookieClearer {

    /** One `setCookie` request: expire a cookie at [url] carrying [header]. */
    data class Expiry(val url: String, val header: String)

    /**
     * The hosts a page at [host] can have cookies set on, itself first.
     *
     * A three-label host (`accounts.google.com`) inherits from its two-label
     * parent (`google.com`), so the parent travels; a two-label host
     * (`google.com`) is its own registrable domain and stands alone. Deliberately
     * a heuristic, not a public-suffix list: `co.uk` domains would gain a bogus
     * parent — which is harmless here, because an overwrite against a domain
     * that never set the cookie simply sets nothing.
     */
    fun hostChain(host: String): List<String> {
        val clean = host.trim().lowercase().trimEnd('.')
        if (clean.isEmpty()) return emptyList()
        val labels = clean.split('.')
        return if (labels.size >= 3) {
            val parent = labels.takeLast(2).joinToString(".")
            listOf(clean, parent).distinct()
        } else {
            listOf(clean)
        }
    }

    /** The cookie NAMES a `getCookie` answer carries, in order, deduplicated. */
    fun parseNames(cookieHeader: String?): List<String> {
        if (cookieHeader.isNullOrBlank()) return emptyList()
        return cookieHeader.split(';')
            .mapNotNull { part ->
                val name = part.substringBefore('=').trim()
                name.takeIf { it.isNotEmpty() }
            }
            .distinct()
    }

    /**
     * The full overwrite plan for one site: every name from [cookieHeader],
     * expired against every host in [hostChain]'s chain for the URL [url]
     * names, both cookie shapes. Empty when there is nothing to erase.
     */
    fun expiryRequests(url: String, cookieHeader: String?): List<Expiry> {
        val host = GoogleAuthNavigation.hostOf(url) ?: return emptyList()
        val names = parseNames(cookieHeader)
        if (names.isEmpty()) return emptyList()
        return hostChain(host).flatMap { domain ->
            val at = "https://$domain/"
            names.flatMap { name ->
                listOf(
                    Expiry(at, "$name=; Path=/; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT"),
                    Expiry(
                        at,
                        "$name=; Path=/; Domain=$domain; Max-Age=0; " +
                            "Expires=Thu, 01 Jan 1970 00:00:00 GMT"
                    )
                )
            }
        }
    }
}
