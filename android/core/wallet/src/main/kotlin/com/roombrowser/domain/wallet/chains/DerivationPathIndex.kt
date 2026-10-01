package com.roombrowser.domain.wallet.chains

/**
 * A chain adapter's knowledge of where its own account index sits in the
 * derivation paths it emits.
 *
 * There is deliberately no shared "chain adapter" supertype in this package:
 * each adapter is a standalone class with its own `deriveAccount` signature.
 * This interface is the one thing they can usefully share, because every one
 * of them builds a BIP32-style path out of an index — so each adapter, and
 * only that adapter, knows which level of its own path carries the index.
 *
 * Why this exists: the persistence layer hands out the next free account
 * index for a chain by reading the paths already stored for it. Reading the
 * LAST level happens to work for every chain except Solana (m/44'/501'/i'/0'),
 * where the index is the ACCOUNT level and the final level is the fixed
 * change `0'` — so "last level + 1" stopped advancing after the second
 * account and a third collided with the second under the UNIQUE
 * (wallet_id, chain_type, address) index. Asking the adapter that wrote the
 * path removes the guesswork instead of teaching one layer another chain's
 * shape.
 *
 * Contract: an implementation must NEVER throw. A path that does not match
 * the chain's shape — a hand-edited row, a path written by an older build,
 * another chain's path that reached the wrong bucket — returns null, and the
 * caller treats that account as uncounted while keeping its own fallback.
 */
interface DerivationPathIndex {

    /**
     * The account index [path] encodes, or null when [path] is not a path
     * this chain's adapter would emit: wrong prefix, wrong depth, a
     * non-numeric index level, and so on.
     */
    fun derivationIndexOf(path: String): Int?
}

/**
 * Shared parsing for [DerivationPathIndex] implementations.
 *
 * The root ("m/") is dropped, so a shape check compares levels only and
 * "m/44'/60'/0'/0/3" arrives as ["44'", "60'", "0'", "0'", "3"].
 *
 * Hardening markers are stripped exactly the way the BIP32 parser in
 * crypto/HdKeys.kt strips them ("'", "h" and "H" all mean hardened), so the
 * two path readers agree on what counts as a numeric level. Nothing here
 * throws: a level that is not a number reads as null.
 */
internal object DerivationPathParsing {

    /**
     * The levels of an absolute BIP32 [path], or null when it has no "m/"
     * root. An empty path and a bare "m/" are both rejected — neither is a
     * path any adapter emits.
     */
    fun levels(path: String): List<String>? {
        val body = when {
            path.startsWith("m/") || path.startsWith("M/") -> path.substring(2)
            else -> return null
        }
        return if (body.isEmpty()) null else body.split('/')
    }

    /** The number a single BIP32 level encodes, or null when it is not one. */
    fun levelValue(level: String): Int? = level.trimEnd('\'', 'h', 'H').toIntOrNull()

    /** True when [level] encodes [expected], any hardening marker ignored. */
    fun isLevel(level: String, expected: Int): Boolean = levelValue(level) == expected
}
