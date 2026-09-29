package com.roombrowser.domain.engine

import com.roombrowser.domain.model.BrowserGlobalSettings
import com.roombrowser.domain.model.ConflictSeverity
import com.roombrowser.domain.model.NetworkRetention
import com.roombrowser.domain.model.ProfileId
import com.roombrowser.domain.model.WarningBehavior

/** One observed (profile, ip) association. */
data class IpAssociation(
    val profileId: ProfileId,
    val ip: String,
    val firstSeenAt: Long,
    val lastSeenAt: Long
)

/**
 * Profile Network Conflict Protection — pure decision logic.
 *
 * IMPORTANT (from the product spec):
 * A shared public IP does NOT prove that two profiles belong to the same
 * person. This is an informational, fully configurable warning only.
 * It never blocks access silently.
 */
class IpConflictDetector(private val clock: () -> Long = { System.currentTimeMillis() }) {

    data class Conflict(
        val currentProfileId: ProfileId,
        val currentIp: String,
        val previousProfileId: ProfileId,
        val previousProfileName: String,
        val lastSeenAt: Long
    )

    data class CheckResult(
        val conflict: Conflict?,
        val requiresConfirmation: Boolean,
        val shouldWarn: Boolean
    )

    /** Validate an IPv4 / IPv6 literal. */
    fun isValidIp(ip: String): Boolean {
        val value = ip.trim()
        if (value.isEmpty()) return false
        if (value.contains(':')) {
            // IPv6 (loose validation: hex groups and colons, optional embedded IPv4)
            return Regex("^([0-9a-fA-F]{0,4}:){1,7}([0-9a-fA-F]{0,4})?(\\.[0-9]{1,3}){0,2}$").matches(value) &&
                value.count { it == ':' } >= 2
        }
        val parts = value.split('.')
        if (parts.size != 4) return false
        return parts.all {
            it.isNotEmpty() && it.length <= 3 && it.all(Char::isDigit) && it.toInt() in 0..255
        }
    }

    /**
     * Check whether opening [currentProfileId] from [currentIp] conflicts with
     * an IP previously associated with another profile.
     */
    fun check(
        currentProfileId: ProfileId,
        currentIp: String,
        history: List<IpAssociation>,
        global: BrowserGlobalSettings,
        profileNetworkProtectionEnabled: Boolean = true,
        suppressedIps: Set<String> = emptySet(),
        suppressedThisSession: Set<String> = emptySet(),
        alreadyWarnedNetworks: Set<String> = emptySet()
    ): CheckResult {
        if (!global.networkProtectionEnabled || !profileNetworkProtectionEnabled) {
            return CheckResult(null, requiresConfirmation = false, shouldWarn = false)
        }
        if (global.warningBehavior == WarningBehavior.DONT_WARN) {
            return CheckResult(null, requiresConfirmation = false, shouldWarn = false)
        }
        val ip = currentIp.trim()
        if (!isValidIp(ip)) return CheckResult(null, false, false)
        if (ip in suppressedIps) return CheckResult(null, false, false)

        val candidates = history
            .filter { it.profileId != currentProfileId && it.ip == ip }
            .filterNot { it.profileId.value in suppressedThisSession }
            .filterNot { it.lastSeenAt < (global.retention.cutoff(clock()) ?: Long.MIN_VALUE) }

        val latest = candidates.maxByOrNull { it.lastSeenAt }
            ?: return CheckResult(null, false, false)

        if (global.warningBehavior == WarningBehavior.ONCE_PER_NETWORK && ip in alreadyWarnedNetworks) {
            return CheckResult(null, false, false)
        }

        val conflict = Conflict(
            currentProfileId = currentProfileId,
            currentIp = ip,
            previousProfileId = latest.profileId,
            previousProfileName = "",
            lastSeenAt = latest.lastSeenAt
        )
        return CheckResult(
            conflict = conflict,
            requiresConfirmation = global.conflictSeverity == ConflictSeverity.REQUIRE_CONFIRMATION,
            shouldWarn = true
        )
    }

    /** Records that this profile was seen from [ip]; returns the updated association. */
    fun record(
        profileId: ProfileId,
        ip: String,
        existing: IpAssociation?
    ): IpAssociation {
        val now = clock()
        return IpAssociation(
            profileId = profileId,
            ip = ip,
            firstSeenAt = existing?.firstSeenAt ?: now,
            lastSeenAt = now
        )
    }

    /** Purge records beyond the retention policy. Returns the purged records. */
    fun purgeExpired(history: List<IpAssociation>, retention: NetworkRetention): List<IpAssociation> {
        val cutoff = retention.cutoff(clock()) ?: return emptyList()
        return history.filter { it.lastSeenAt < cutoff }
    }
}
