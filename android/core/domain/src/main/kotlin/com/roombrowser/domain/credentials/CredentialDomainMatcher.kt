package com.roombrowser.domain.credentials

/**
 * Parent-domain-aware host matching for credential lookup.
 *
 * A login saved for google.com must be offered on accounts.google.com — and
 * one saved on accounts.google.com must be offered on google.com — because
 * sites deliberately spread their login flows across subdomains. A match is
 * therefore TRUE when one host equals the other, or when one ends with
 * "." + the other. The dot boundary is the whole point: "notevil.com" shares
 * a suffix with "evil.com" but is a different site and must NOT match.
 */
object CredentialDomainMatcher {

    /**
     * Canonical host form: trimmed, lowercased, trailing dot removed
     * ("Example.COM." → "example.com"). Hosts are compared canonically so a
     * capitalized or FQDN-dot form saved by one flow still matches the
     * lowercase host a later page reports.
     */
    fun normalize(host: String): String = host.trim().lowercase().trimEnd('.')

    /**
     * True when [savedDomain] and [pageHost] name the same site: equal, or
     * one is a subdomain of the other. Empty (or blank) inputs never match —
     * an empty host is a parsing failure, not a wildcard.
     */
    fun matches(savedDomain: String, pageHost: String): Boolean {
        val saved = normalize(savedDomain)
        val page = normalize(pageHost)
        if (saved.isEmpty() || page.isEmpty()) return false
        return saved == page ||
            page.endsWith(".$saved") ||
            saved.endsWith(".$page")
    }
}
