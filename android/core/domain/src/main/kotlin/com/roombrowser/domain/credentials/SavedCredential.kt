package com.roombrowser.domain.credentials

import kotlinx.serialization.Serializable

/**
 * One saved login of one profile's password manager.
 *
 * [password] is PLAINTEXT IN MEMORY ONLY: it exists decrypted solely for the
 * moments a fill, an edit dialog or an export needs it. Every persisted form
 * stores ciphertext sealed with the profile's vault key (Room stores
 * credentials.password_enc, produced by the per-profile AndroidKeyStore key —
 * see com.roombrowser.security.VaultCrypto), so a copied or leaked database
 * file yields no passwords.
 *
 * [domain] is the canonical host — lowercase, no scheme, no path, no trailing
 * dot (see [CredentialDomainMatcher.normalize]). Looking a login up for the
 * page being viewed is parent-domain aware: [CredentialDomainMatcher.matches].
 */
@Serializable
data class SavedCredential(
    /** Stable row id (UUID). Regenerated on import so PKs never collide. */
    val id: String,
    /** Owning profile (ProfileId.value). Credentials never cross profiles. */
    val profileId: String,
    /** Canonical host: lowercase, no scheme/path, no trailing dot. */
    val domain: String,
    val username: String,
    /** Plaintext ONLY in memory; persisted only as ciphertext. */
    val password: String,
    /** Optional user label (e.g. "Work mail"); null = no label. */
    val title: String? = null,
    /** Creation time, epoch ms. */
    val createdAt: Long,
    /** Last edit time, epoch ms; bumped on every save. */
    val updatedAt: Long
) {
    /**
     * Case-insensitive substring match over domain / username / title — the
     * search-box predicate. A blank query matches everything, which is the
     * "empty search shows all" convention and matches the DAO's
     * `LIKE '%' || q || '%'` behaviour.
     */
    fun matchesQuery(query: String): Boolean {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return true
        return domain.lowercase().contains(q) ||
            username.lowercase().contains(q) ||
            title?.lowercase()?.contains(q) == true
    }
}
