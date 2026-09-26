package com.roombrowser.browser.engine

import com.roombrowser.data.repo.AppStateRepository
import com.roombrowser.data.repo.BrowserRepository
import com.roombrowser.data.repo.IpCache
import com.roombrowser.domain.engine.IpAssociation
import com.roombrowser.domain.engine.IpConflictDetector
import com.roombrowser.domain.model.Profile
import com.roombrowser.domain.model.WarningBehavior
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Network identity: fetches the observed public IP (privacy-respecting,
 * configurable endpoints), caches results with a cooldown, and runs the
 * profile IP conflict check (spec sections 6 / 74).
 *
 * The public IP is fetched ONLY when network protection or network
 * diagnostics are enabled. All IP data stays in local Room storage —
 * it is never sent anywhere (see PRIVACY.md).
 */
class NetworkIdentity(
    private val appState: AppStateRepository,
    private val browserRepo: BrowserRepository,
    private val detector: IpConflictDetector
) {

    /** Plain HTTPS endpoints returning the IP in the response body. */
    private val endpoints = listOf(
        "https://api.ipify.org",
        "https://icanhazip.com",
        "https://checkip.amazonaws.com"
    )

    sealed interface NetState {
        data object Idle : NetState
        data object Checking : NetState
        data class Known(val ip: String) : NetState
        data class Conflict(
            val currentProfileId: String,
            val currentIp: String,
            val previousProfileId: String,
            val previousProfileName: String,
            val lastSeenAt: Long
        ) : NetState
        data class Error(val message: String) : NetState
    }

    private val _netState = MutableStateFlow<NetState>(NetState.Idle)
    val netState: StateFlow<NetState> = _netState

    private val suppressedThisSession = mutableSetOf<String>()

    /**
     * Fetch the current public IP with caching + cooldown (spec 74.5:
     * "Do not repeatedly query the network unnecessarily").
     */
    suspend fun currentPublicIp(client: OkHttpClient, force: Boolean = false): String? {
        val cached = appState.ipCache()
        val now = System.currentTimeMillis()
        if (!force && cached?.ip != null && now - cached.checkedAt < COOLDOWN_MS) {
            return cached.ip
        }
        _netState.value = NetState.Checking
        val ip = withContext(Dispatchers.IO) {
            endpoints.firstNotNullOfOrNull { endpoint ->
                runCatching {
                    client.newBuilder()
                        .callTimeout(8, TimeUnit.SECONDS)
                        .build()
                        .newCall(Request.Builder().url(endpoint).build())
                        .execute()
                        .use { response ->
                            if (response.isSuccessful) {
                                response.body?.string()?.trim()
                                    ?.takeIf { detector.isValidIp(it) }
                            } else null
                        }
                }.getOrNull()
            }
        }
        return if (ip != null) {
            appState.setIpCache(IpCache(ip, now))
            _netState.value = NetState.Known(ip)
            ip
        } else {
            _netState.value = NetState.Error("Could not determine public IP")
            null
        }
    }

    /**
     * Full conflict check performed when [profile] is opened.
     */
    suspend fun checkOnOpen(
        client: OkHttpClient,
        profile: Profile,
        global: com.roombrowser.domain.model.BrowserGlobalSettings,
        profiles: List<Profile>
    ) {
        val settings = profile.settings
        val enabledForProfile =
            if (settings.networkProtectionUseGlobal) global.networkProtectionEnabled
            else settings.networkProtectionEnabled
        if (!global.networkProtectionEnabled || !enabledForProfile) return

        val ip = currentPublicIp(client) ?: return
        val history = browserRepo.allIpHistory().map {
            IpAssociation(
                profileId = com.roombrowser.domain.model.ProfileId(it.profileId),
                ip = it.ip,
                firstSeenAt = it.firstSeenAt,
                lastSeenAt = it.lastSeenAt
            )
        }
        val result = detector.check(
            currentProfileId = profile.id,
            currentIp = ip,
            history = history,
            global = global,
            profileNetworkProtectionEnabled = enabledForProfile,
            suppressedIps = appState.suppressedIps(),
            suppressedThisSession = suppressedThisSession,
            alreadyWarnedNetworks = appState.warnedNetworks()
        )
        val conflict = result.conflict
        if (conflict != null && result.shouldWarn) {
            val previousName = profiles.firstOrNull { it.id == conflict.previousProfileId }?.name
                ?: "another profile"
            if (global.warningBehavior == WarningBehavior.ONCE_PER_NETWORK ||
                global.warningBehavior == WarningBehavior.ASK_EVERY_TIME
            ) {
                appState.addWarnedNetwork(ip)
            }
            _netState.value = NetState.Conflict(
                currentProfileId = profile.id.value,
                currentIp = ip,
                previousProfileId = conflict.previousProfileId.value,
                previousProfileName = previousName,
                lastSeenAt = conflict.lastSeenAt
            )
        }
        recordAssociation(profile.id, ip)
    }

    /** Record / refresh the (profile, ip) association in Room. */
    private suspend fun recordAssociation(
        profileId: com.roombrowser.domain.model.ProfileId,
        ip: String
    ) {
        val existing = browserRepo.findIpAssociation(profileId, ip)
        val recorded = detector.record(profileId, ip, existing?.let {
            IpAssociation(
                profileId = com.roombrowser.domain.model.ProfileId(it.profileId),
                ip = it.ip,
                firstSeenAt = it.firstSeenAt,
                lastSeenAt = it.lastSeenAt
            )
        })
        browserRepo.upsertIpHistory(
            com.roombrowser.data.db.IpHistoryEntity(
                id = existing?.id ?: 0,
                profileId = recorded.profileId.value,
                ip = recorded.ip,
                firstSeenAt = recorded.firstSeenAt,
                lastSeenAt = recorded.lastSeenAt
            )
        )
    }

    /** "Don't warn again for this IP". */
    suspend fun suppressCurrentIp() {
        val ip = (netState.value as? NetState.Known)?.ip
            ?: (netState.value as? NetState.Conflict)?.currentIp
        if (ip != null) {
            appState.suppressIp(ip)
            suppressedThisSession += ip
            _netState.value = NetState.Known(ip)
        }
    }

    fun dismissWarning() {
        val ip = (netState.value as? NetState.Conflict)?.currentIp
            ?: (netState.value as? NetState.Known)?.ip
        _netState.value = if (ip != null) NetState.Known(ip) else NetState.Idle
    }

    /** Re-check after a network change (spec 74.6). */
    suspend fun recheck(
        client: OkHttpClient,
        profile: Profile,
        global: com.roombrowser.domain.model.BrowserGlobalSettings,
        profiles: List<Profile>
    ) {
        currentPublicIp(client, force = true)
        checkOnOpen(client, profile, global, profiles)
    }

    companion object {
        const val COOLDOWN_MS = 10 * 60 * 1000L // 10 minutes
    }
}
