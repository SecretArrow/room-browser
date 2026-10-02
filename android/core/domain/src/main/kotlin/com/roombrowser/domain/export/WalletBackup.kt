package com.roombrowser.domain.export

import com.roombrowser.domain.credentials.PasswordVaultCrypto
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The file is not a wallet-keys export, or is a version this build cannot read. */
class WalletBackupFormatException(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)

/**
 * Wallet keys export — what a user saves so their coins survive losing the
 * phone.
 *
 * ## Why a separate file, and not a field in [ProfileBackup]
 *
 * A profile export is a shareable thing: users send one to themselves, or to
 * a friend, to move bookmarks and settings around. Putting the seed in it
 * would mean every one of those files silently carries the keys to every
 * coin in the wallet. Keeping the two apart means the blast radius of a
 * carelessly shared profile backup is a set of bookmarks, and the file that
 * holds the money is one the user deliberately chose to write.
 *
 * ## Why the seed is never written in the clear
 *
 * The obvious reading of "export my seed phrase to a text file" is a
 * plaintext `.txt`. That file then lands in Downloads, gets picked up by
 * cloud backup, is readable by every app holding legacy storage permission,
 * and survives deletion on flash storage — for a secret that is
 * unrecoverable once seen. So the file this class writes is a small JSON
 * envelope whose ONLY payload is a [ProfileBackup.VaultBackup] blob, sealed
 * by [PasswordVaultCrypto] under a passphrase the user picks at export time
 * (PBKDF2-HMAC-SHA256, 210 000 iterations, then AES-256-GCM — authenticated,
 * so a wrong passphrase is a clean error rather than garbage).
 *
 * What the user gets after decrypting is exactly the readable keys document
 * they asked for: [render] is the plaintext, and it is the plaintext and
 * nothing else that goes inside the cipher.
 *
 * ## What is inside
 *
 * The recovery phrase restores every DERIVED account — they are re-derived
 * from it, so the phrase alone is a complete backup of them. Imported
 * private keys are not derived from anything, so they are carried
 * explicitly; a backup that omitted them would restore a wallet that looks
 * right and has no access to those funds.
 *
 * ## On [open]
 *
 * The read half exists now, before there is any import UI, because a backup
 * format that has never been read back has never been shown to work. The
 * export tests decrypt through this method rather than through the raw
 * cipher, so what they prove is that THIS file can be opened by THIS code.
 */
object WalletBackup {

    /** Set of a plaintext [Contents]; the plaintext is the artifact the user reads. */
    data class KeyEntry(
        val chain: String,
        val label: String,
        val address: String,
        /** BIP44 path for derived accounts; empty for imports. */
        val path: String,
        /** Present only for imported accounts — the phrase cannot re-derive these. */
        val privateKey: String? = null
    )

    /**
     * Everything a restore needs, already detached from the database.
     *
     * [mnemonic] is null for a wallet that was created by importing single
     * keys rather than by a phrase; such a wallet's only backup is its
     * imported keys.
     */
    data class Contents(
        val walletLabel: String,
        val createdAt: Long,
        val mnemonic: String?,
        val accounts: List<KeyEntry>
    ) {
        /** Nothing here could restore anything — sealing it would write a decoy. */
        val isEmpty: Boolean
            get() = mnemonic.isNullOrBlank() && accounts.none { !it.privateKey.isNullOrBlank() }
    }

    /** What the document says about itself. [profileLabel] is a NAME, never a profile id. */
    data class Header(val profileLabel: String, val exportedAt: Long)

    const val KIND = "room-browser-wallet-keys"
    const val FORMAT_VERSION = 1

    /**
     * The file, as written to disk. [vault] is non-null by construction: an
     * export with nothing to seal is refused by [seal] rather than written
     * as a file that restores nothing.
     *
     * [kind] deliberately has NO default. A profile export carries a `vault`
     * block too, so `kind` is the only thing separating the two formats — and
     * a defaulted discriminator is no discriminator at all: a file that
     * simply omits the field would decode to the default and be accepted,
     * which for an import means reading a bookmark list as a seed phrase.
     */
    @Serializable
    data class KeyFile(
        val kind: String,
        val formatVersion: Int = FORMAT_VERSION,
        val vault: ProfileBackup.VaultBackup
    )

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * The decrypted document: what the user reads once they open the file
     * with the passphrase they set.
     */
    fun render(contents: Contents, header: Header): String = buildString {
        appendLine("Room Browser - wallet keys")
        appendLine("Wallet: ${contents.walletLabel}")
        appendLine("Profile: ${header.profileLabel}")
        appendLine("Created: ${stamp(contents.createdAt)}")
        appendLine("Exported: ${stamp(header.exportedAt)}")
        appendLine()
        appendLine("KEEP THIS FILE OFFLINE. Anyone who can read it can take every coin")
        appendLine("in this wallet. Room Browser cannot recover it for you and cannot")
        appendLine("reset the password it is sealed with.")

        val words = contents.mnemonic?.trim()?.split(Regex("\\s+"))?.filter { it.isNotEmpty() }
        if (words.isNullOrEmpty()) {
            appendLine()
            appendLine("Recovery phrase: none - this wallet was created by importing keys,")
            appendLine("so the imported keys below are its only backup.")
        } else {
            appendLine()
            appendLine("Recovery phrase (${words.size} words) - restores every derived account")
            words.forEachIndexed { index, word ->
                appendLine("  ${(index + 1).toString().padStart(2)}. $word")
            }
        }

        val derived = contents.accounts.filter { it.privateKey.isNullOrBlank() }
        val imported = contents.accounts.filter { !it.privateKey.isNullOrBlank() }

        appendLine()
        if (derived.isEmpty()) {
            appendLine("Derived accounts: none")
        } else {
            appendLine("Derived accounts - re-derived from the phrase above, listed for reference")
            derived.forEach { entry ->
                appendLine("  ${entry.chain}  ${entry.label}  ${entry.address}  ${entry.path}")
            }
        }

        if (imported.isNotEmpty()) {
            appendLine()
            appendLine("Imported keys - NOT restored by the recovery phrase")
            imported.forEach { entry ->
                appendLine("  ${entry.chain}  ${entry.label}  ${entry.address}")
                appendLine("    private key: ${entry.privateKey}")
            }
        }
    }

    /**
     * Seals [contents] under [passphrase] and returns the file text.
     *
     * @throws IllegalArgumentException when [contents] holds nothing that
     *   could restore a wallet — see [Contents.isEmpty].
     */
    fun seal(contents: Contents, header: Header, passphrase: CharArray): String {
        require(passphrase.isNotEmpty()) { "A wallet backup needs a passphrase" }
        require(!contents.isEmpty) {
            "This wallet has no recovery phrase and no imported keys to back up"
        }
        val sealed = PasswordVaultCrypto.encrypt(render(contents, header), passphrase)
        return json.encodeToString(
            KeyFile.serializer(),
            KeyFile(kind = KIND, vault = ProfileBackup.VaultBackup.from(sealed))
        )
    }

    /**
     * Decrypts a file written by [seal].
     *
     * @throws WalletBackupFormatException when [text] is not one of our files.
     * @throws com.roombrowser.domain.credentials.VaultAuthException when the
     *   passphrase is wrong or the ciphertext was tampered with.
     */
    fun open(text: String, passphrase: CharArray): String {
        if (text.isBlank()) throw WalletBackupFormatException("the file is empty")
        val file = try {
            json.decodeFromString(KeyFile.serializer(), text)
        } catch (e: SerializationException) {
            // kotlinx messages can be multi-line; the first line is the useful one.
            throw WalletBackupFormatException(
                e.message?.lineSequence()?.firstOrNull() ?: "not a wallet keys file",
                e
            )
        }
        if (file.kind != KIND) {
            throw WalletBackupFormatException("not a wallet keys file (kind=${file.kind})")
        }
        if (file.formatVersion > FORMAT_VERSION) {
            throw WalletBackupFormatException(
                "this file was written by a newer Room Browser (format ${file.formatVersion})"
            )
        }
        return PasswordVaultCrypto.decrypt(file.vault.toCipherData(), passphrase)
    }

    /**
     * A filename that sorts by date and survives every filesystem: no spaces,
     * no colons, ASCII only. Carries the wallet's own label so two wallets
     * backed up on the same day do not collide in a Downloads folder, and the
     * time so two exports of one wallet do not either.
     */
    fun fileName(walletLabel: String, at: Long): String {
        val slug = walletLabel.lowercase(Locale.US)
            .map { if (it.isLetterOrDigit() && it.code < 128) it else '-' }
            .joinToString("")
            .trim('-')
            .replace(Regex("-+"), "-")
            .take(24)
        val stem = if (slug.isEmpty()) "wallet" else slug
        return "room-browser-wallet-keys-$stem-${fileStamp(at)}.txt"
    }

    /** Fixed shape and locale: the file is read on a machine that is not this phone. */
    private fun stamp(at: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(at))

    private fun fileStamp(at: Long): String =
        SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(at))
}
