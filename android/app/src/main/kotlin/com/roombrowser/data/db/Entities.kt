package com.roombrowser.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Profile identity + settings (settings serialized as JSON). */
@Entity(tableName = "profiles")
data class ProfileEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String, // immutable UUID
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "icon") val icon: String,
    @ColumnInfo(name = "color_argb") val colorArgb: Long,
    @ColumnInfo(name = "is_locked") val isLocked: Boolean,
    @ColumnInfo(name = "is_default") val isDefault: Boolean,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "last_active_at") val lastActiveAt: Long,
    @ColumnInfo(name = "settings_json") val settingsJson: String,
    /** Full per-profile theme snapshot (RoomThemeSpec JSON); "" = default. */
    @ColumnInfo(name = "theme_json", defaultValue = "") val themeJson: String = ""
)

@Entity(
    tableName = "tabs",
    indices = [Index("profile_id"), Index("profile_id", "position")]
)
data class TabEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "profile_id") val profileId: String,
    @ColumnInfo(name = "position") val position: Int,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "url") val url: String,
    @ColumnInfo(name = "is_private") val isPrivate: Boolean,
    @ColumnInfo(name = "is_pinned") val isPinned: Boolean = false,
    @ColumnInfo(name = "group_name") val groupName: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "last_viewed_at") val lastViewedAt: Long,
    @ColumnInfo(name = "closed_at") val closedAt: Long? = null // reopen-closed-tab support
)

@Entity(
    tableName = "bookmarks",
    indices = [Index("profile_id"), Index("profile_id", "folder")]
)
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "profile_id") val profileId: String,
    @ColumnInfo(name = "url") val url: String,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "folder") val folder: String? = null,
    @ColumnInfo(name = "position") val position: Int = 0,
    @ColumnInfo(name = "created_at") val createdAt: Long
)

@Entity(
    tableName = "history",
    indices = [Index("profile_id"), Index("profile_id", "visited_at")]
)
data class HistoryEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "profile_id") val profileId: String,
    @ColumnInfo(name = "url") val url: String,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "visited_at") val visitedAt: Long
)

@Entity(
    tableName = "downloads",
    indices = [Index("profile_id"), Index("status")]
)
data class DownloadEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "profile_id") val profileId: String,
    @ColumnInfo(name = "url") val url: String,
    @ColumnInfo(name = "file_name") val fileName: String,
    @ColumnInfo(name = "mime_type") val mimeType: String,
    @ColumnInfo(name = "destination") val destination: String, // content uri or file path
    @ColumnInfo(name = "total_bytes") val totalBytes: Long,
    @ColumnInfo(name = "downloaded_bytes") val downloadedBytes: Long,
    @ColumnInfo(name = "status") val status: String, // DownloadStatus.name
    @ColumnInfo(name = "error") val error: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "completed_at") val completedAt: Long? = null,
    /**
     * The User-Agent the transfer must present.
     *
     * Stored rather than held in memory because an interrupted download is
     * re-queued when the engine is rebuilt (a profile switch, or the app coming
     * back), and the profile it belongs to presents a device — a resume that
     * went out under a different UA than the original request would be two
     * identities fetching one file.
     */
    @ColumnInfo(name = "user_agent") val userAgent: String = ""
)

/** Per-profile, per-site permission decisions. */
@Entity(
    tableName = "site_permissions",
    primaryKeys = ["profile_id", "host", "permission"],
    indices = [Index("profile_id")]
)
data class SitePermissionEntity(
    @ColumnInfo(name = "profile_id") val profileId: String,
    @ColumnInfo(name = "host") val host: String,
    @ColumnInfo(name = "permission") val permission: String, // PermissionKind name
    @ColumnInfo(name = "decision") val decision: String // PermissionDecision name
)

/** Per-profile, per-site content settings. Null value = inherit profile setting. */
@Entity(
    tableName = "site_settings",
    primaryKeys = ["profile_id", "host"],
    indices = [Index("profile_id")]
)
data class SiteSettingEntity(
    @ColumnInfo(name = "profile_id") val profileId: String,
    @ColumnInfo(name = "host") val host: String,
    @ColumnInfo(name = "shields_disabled") val shieldsDisabled: Boolean? = null,
    @ColumnInfo(name = "js_enabled") val jsEnabled: Boolean? = null,
    @ColumnInfo(name = "cookies_blocked") val cookiesBlocked: Boolean? = null,
    @ColumnInfo(name = "desktop_mode") val desktopMode: Boolean? = null,
    @ColumnInfo(name = "autoplay_blocked") val autoplayBlocked: Boolean? = null,
    @ColumnInfo(name = "popup_blocked") val popupBlocked: Boolean? = null
)

/** profile_network_history (spec section 6 / 74). */
@Entity(
    tableName = "ip_history",
    indices = [Index("profile_id"), Index("ip"), Index("last_seen_at")]
)
data class IpHistoryEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "profile_id") val profileId: String,
    @ColumnInfo(name = "ip") val ip: String,
    @ColumnInfo(name = "first_seen_at") val firstSeenAt: Long,
    @ColumnInfo(name = "last_seen_at") val lastSeenAt: Long
)

/** Real blocking events power the privacy dashboard (no fake statistics). */
@Entity(
    tableName = "block_events",
    indices = [Index("profile_id", "ts"), Index("profile_id", "host")]
)
data class BlockEventEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "profile_id") val profileId: String,
    @ColumnInfo(name = "host") val host: String, // host only — never full URLs
    @ColumnInfo(name = "category") val category: String,
    @ColumnInfo(name = "ts") val ts: Long
)

/** Cross-process app state KV (active profile, global settings JSON, ...). */
@Entity(tableName = "app_state")
data class AppStateEntity(
    @PrimaryKey @ColumnInfo(name = "key") val key: String,
    @ColumnInfo(name = "value") val value: String
)

// =========================================================================
// PER-PROFILE THEME SYSTEM — schema v4
// =========================================================================

/**
 * A user-saved custom theme in the local gallery ("My themes"). Themes
 * actually applied to profiles are full snapshots on the profile row
 * (profiles.theme_json) — the gallery is only a picker source, so editing
 * one profile never mutates another profile's look.
 */
@Entity(tableName = "themes")
data class CustomThemeEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "spec_json") val specJson: String,
    @ColumnInfo(name = "created_at") val createdAt: Long
)

// =========================================================================
// AI AGENT (autonomous browsing assistant) — schema v2
// =========================================================================

/**
 * A user-configured AI agent provider. Five protocols are supported:
 *  - [PROTOCOL_OPENAI] — any OpenAI-compatible chat/completions API
 *    (Z.ai, OpenAI, OpenRouter, Groq, DeepSeek, Ollama, LM Studio, custom...)
 *  - [PROTOCOL_OPENCODE] — an `opencode serve` server (session-based REST
 *    API on its own machine, bridged by OpenCodeAgentGateway)
 *  - [PROTOCOL_OLLAMA] — a native Ollama server (/api/chat + /api/tags;
 *    managed by the Local AI screen, bridged by OllamaAgentGateway)
 *  - [PROTOCOL_LOCAL] — the embedded on-device llama.cpp engine (no server,
 *    no network; models are .gguf files managed in Local AI)
 *  - [PROTOCOL_ANTHROPIC] — the Anthropic Messages API shape (POST /messages,
 *    x-api-key + anthropic-version), spoken by aggregators such as AgentRouter
 *    through the @ai-sdk/anthropic SDK and bridged by AnthropicAgentGateway,
 *    which probes once and falls back to the OpenAI shape when the endpoint is
 *    not Anthropic-shaped
 *
 * The API key is stored ENCRYPTED with an AndroidKeyStore AES-GCM key.
 *
 * `protocol` is a plain String column (defaultValue "OPENAI"), so adding a new
 * protocol value needs NO Room migration: existing rows keep their value and
 * only new/edited Anthropic providers ever store "ANTHROPIC".
 */
@Entity(tableName = "agent_providers")
data class AgentProviderEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "base_url") val baseUrl: String,
    @ColumnInfo(name = "api_key_enc") val apiKeyEnc: String, // "" = no key (local servers)
    @ColumnInfo(name = "default_model") val defaultModel: String,
    @ColumnInfo(name = "protocol", defaultValue = "OPENAI") val protocol: String = PROTOCOL_OPENAI,
    /**
     * How this provider is asked to call tools — a [com.roombrowser.domain.agent.ToolMode]
     * name, "AUTO" when unset. Stored as a name so a new mode needs no
     * migration; legacy rows read as AUTO (see `ToolMode.fromStored`).
     */
    @ColumnInfo(name = "tool_mode", defaultValue = "AUTO") val toolMode: String = TOOL_MODE_DEFAULT,
    @ColumnInfo(name = "created_at") val createdAt: Long
) {
    companion object {
        const val PROTOCOL_OPENAI = "OPENAI"
        const val PROTOCOL_OPENCODE = "OPENCODE"
        const val PROTOCOL_OLLAMA = "OLLAMA"
        const val PROTOCOL_LOCAL = "LOCAL"
        const val PROTOCOL_ANTHROPIC = "ANTHROPIC"

        /** Mirrors `ToolMode.DEFAULT.name` without a domain import here. */
        const val TOOL_MODE_DEFAULT = "AUTO"
    }
}

/** One agent chat session, scoped to a profile. */
@Entity(
    tableName = "agent_sessions",
    indices = [Index("profile_id")]
)
data class AgentSessionEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "profile_id") val profileId: String,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "provider_id") val providerId: Long,
    @ColumnInfo(name = "model") val model: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long
)

/**
 * One message row of an agent session. Roles: "user", "assistant", "tool".
 * Tool rows carry the tool name/args/result for display in the chat UI;
 * on session continuation only user/assistant rows are replayed to the
 * provider (tool-call linkage is only valid within a single turn).
 */
@Entity(
    tableName = "agent_messages",
    indices = [Index("session_id")]
)
data class AgentMessageEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "session_id") val sessionId: Long,
    @ColumnInfo(name = "role") val role: String,
    @ColumnInfo(name = "content") val content: String,
    @ColumnInfo(name = "tool_name") val toolName: String? = null,
    @ColumnInfo(name = "tool_args") val toolArgs: String? = null,
    @ColumnInfo(name = "tool_result") val toolResult: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long
)

// =========================================================================
// PASSWORD MANAGER (per-profile credential vault) — schema v7
// =========================================================================

/**
 * One saved login of a profile's password manager.
 *
 * The password is stored ONLY as ciphertext: password_enc is the
 * AndroidKeyStore AES-256-GCM blob produced by the profile's vault key
 * (alias roomvault-<safeSuffix>, see com.roombrowser.security.VaultCrypto).
 * A plaintext password never reaches disk in any form — a copied database
 * file yields no secrets, and deleting the profile deletes its key, which
 * makes any surviving blob permanently undecryptable.
 */
@Entity(
    tableName = "credentials",
    indices = [Index("profile_id"), Index("profile_id", "domain")]
)
data class CredentialEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String, // UUID; fresh on import
    @ColumnInfo(name = "profile_id") val profileId: String,
    /** Canonical host: lowercase, no scheme/path, no trailing dot. */
    @ColumnInfo(name = "domain") val domain: String,
    @ColumnInfo(name = "username") val username: String,
    /** base64(iv||ciphertext+tag) under the profile's vault key. */
    @ColumnInfo(name = "password_enc") val passwordEnc: String,
    /** Optional user label; null = no label. */
    @ColumnInfo(name = "title") val title: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long
)
