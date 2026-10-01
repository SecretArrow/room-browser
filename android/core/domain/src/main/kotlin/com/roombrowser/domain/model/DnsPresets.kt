package com.roombrowser.domain.model

/**
 * One curated DNS resolver.
 *
 * The resolver's addresses and its DNS-over-HTTPS endpoint, exactly as the
 * operator publishes them. All four address fields are plain literals (no
 * scheme, no port) and [dohUrl] is an https URL that satisfies
 * `DnsValidator.validateDohUrl` — that validator is the gate the app applies
 * before a DoH URL is ever used, so every preset must clear it by
 * construction.
 *
 * How a preset maps onto the existing [DnsMode]:
 *  - **SYSTEM** — the reset/default state. No preset is applied; the profile
 *    (or the browser-wide setting) resolves DNS the way the OS does.
 *  - **DOH + [dohUrl]** — the preset applied: the profile's `dnsMode` becomes
 *    [DnsMode.DOH] and its `dohUrl` becomes the preset's endpoint. The four
 *    address fields are the same resolver's own DNS servers: the settings UI
 *    shows them ([primaryIpv4] in the picker title, all four in the subtitle),
 *    and `DnsMonitor` passes them to OkHttp as the DoH bootstrap hint so the
 *    endpoint hostname is resolved by the resolver itself instead of being
 *    leaked to the OS resolver.
 *  - **custom** — the user's own DoH URL: still [DnsMode.DOH], but with a URL
 *    that is not any preset's `dohUrl`. A preset is a shortcut for the common
 *    choices, not the boundary of what can be entered.
 */
data class DnsPreset(
    val id: String,
    val label: String,
    val primaryIpv4: String,
    val secondaryIpv4: String,
    val primaryIpv6: String,
    val secondaryIpv6: String,
    val dohUrl: String
)

/** Curated DNS presets (Cloudflare, Google, OpenDNS). */
object DnsPresets {

    private val CLOUDFLARE = DnsPreset(
        id = "cloudflare",
        label = "Cloudflare DNS",
        primaryIpv4 = "1.1.1.1",
        secondaryIpv4 = "1.0.0.1",
        primaryIpv6 = "2606:4700:4700::1111",
        secondaryIpv6 = "2606:4700:4700::1001",
        dohUrl = "https://cloudflare-dns.com/dns-query"
    )

    private val GOOGLE = DnsPreset(
        id = "google",
        label = "Google DNS",
        primaryIpv4 = "8.8.8.8",
        secondaryIpv4 = "8.8.4.4",
        primaryIpv6 = "2001:4860:4860::8888",
        secondaryIpv6 = "2001:4860:4860::8844",
        dohUrl = "https://dns.google/dns-query"
    )

    private val OPENDNS = DnsPreset(
        id = "opendns",
        label = "OpenDNS",
        primaryIpv4 = "208.67.222.222",
        secondaryIpv4 = "208.67.220.220",
        primaryIpv6 = "2620:119:35::35",
        secondaryIpv6 = "2620:119:53::53",
        dohUrl = "https://doh.opendns.com/dns-query"
    )

    val all: List<DnsPreset> = listOf(CLOUDFLARE, GOOGLE, OPENDNS)

    fun byId(id: String): DnsPreset? = all.firstOrNull { it.id == id }
}
