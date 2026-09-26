package com.roombrowser.domain.engine

import com.roombrowser.domain.model.DnsMode

/**
 * DNS configuration validation (DoH URL / DoT hostname) and effective-mode
 * resolution (profile overrides global).
 */
object DnsValidator {

    data class EffectiveDns(
        val mode: DnsMode,
        val dohUrl: String?,
        val dotHostname: String?,
        /** Human-readable status shown in the DNS panel. */
        val status: DnsStatus
    )

    enum class DnsStatus { SYSTEM, PROTECTED_DOH, PROTECTED_DOT, MISCONFIGURED }

    fun validateDohUrl(url: String): Boolean {
        val u = url.trim()
        if (!u.startsWith("https://", ignoreCase = true)) return false
        return runCatching { java.net.URI(u).host?.isNotBlank() == true }.getOrDefault(false)
    }

    fun validateDotHostname(host: String): Boolean {
        val h = host.trim()
        if (h.isEmpty() || h.length > 253) return false
        if (h.contains(' ') || h.contains('/')) return false
        // allow host or host:port
        val hostPart = h.substringBefore(':')
        return hostPart.split('.').all { label ->
            label.isNotEmpty() && label.length <= 63 &&
                Regex("^[a-zA-Z0-9_-]+$").matches(label)
        }
    }

    /**
     * Resolve the DNS mode for a profile given global settings.
     * Per-profile DNS preference: SYSTEM / global / DoH / DoT.
     */
    fun effective(
        globalMode: DnsMode,
        globalDohUrl: String?,
        globalDotHostname: String?,
        profileMode: DnsMode,
        profileDohUrl: String?,
        profileDotHostname: String?
    ): EffectiveDns {
        val mode = when (profileMode) {
            DnsMode.SYSTEM -> return EffectiveDns(DnsMode.SYSTEM, null, null, DnsStatus.SYSTEM)
            // Profile "AUTO" means: use the global browser setting
            DnsMode.AUTO -> globalMode
            else -> profileMode
        }
        val doh = when (mode) {
            DnsMode.DOH -> (profileDohUrl?.takeIf { profileMode == DnsMode.DOH } ?: globalDohUrl)
            else -> null
        }
        val dot = when (mode) {
            DnsMode.DOT -> (profileDotHostname?.takeIf { profileMode == DnsMode.DOT } ?: globalDotHostname)
            else -> null
        }
        val status = when (mode) {
            DnsMode.SYSTEM -> DnsStatus.SYSTEM
            DnsMode.AUTO -> DnsStatus.SYSTEM
            DnsMode.DOH -> if (doh != null && validateDohUrl(doh)) DnsStatus.PROTECTED_DOH else DnsStatus.MISCONFIGURED
            DnsMode.DOT -> if (dot != null && validateDotHostname(dot)) DnsStatus.PROTECTED_DOT else DnsStatus.MISCONFIGURED
        }
        return EffectiveDns(mode, doh, dot, status)
    }
}
