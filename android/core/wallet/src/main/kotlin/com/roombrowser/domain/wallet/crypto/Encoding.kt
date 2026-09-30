package com.roombrowser.domain.wallet.crypto

/**
 * Base58 (Bitcoin alphabet) — used by Solana addresses/signatures and TRON's
 * base58check addresses.
 */
object Base58 {

    private const val ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
    private val LOOKUP = IntArray(128) { -1 }.also {
        ALPHABET.forEachIndexed { i, c -> it[c.code] = i }
    }

    fun encode(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        var zeros = 0
        while (zeros < bytes.size && bytes[zeros] == 0.toByte()) zeros++
        // log(256)/log(58) rounded up == 138/100 + 1
        val size = (bytes.size - zeros) * 138 / 100 + 1
        val b58 = ByteArray(size)
        var length = 0
        for (i in zeros until bytes.size) {
            var carry = bytes[i].toInt() and 0xff
            var j = 0
            var k = size - 1
            while (k >= 0 && (carry != 0 || j < length)) {
                carry += 256 * (b58[k].toInt() and 0xff)
                b58[k] = (carry % 58).toByte()
                carry /= 58
                k--
                j++
            }
            length = j
        }
        val sb = StringBuilder()
        repeat(zeros) { sb.append('1') }
        var start = size - length
        while (start < size && b58[start] == 0.toByte()) start++
        for (i in start until size) sb.append(ALPHABET[b58[i].toInt() and 0xff])
        return sb.toString()
    }

    fun decode(text: String): ByteArray {
        require(text.isNotEmpty()) { "Empty base58 string" }
        val zeros = text.takeWhile { it == '1' }.length
        val size = (text.length - zeros) * 733 / 1000 + 1 // log(58)/log(256) rounded up
        val bytes = ByteArray(size)
        var length = 0
        for (i in zeros until text.length) {
            val c = text[i].code
            require(c < 128 && LOOKUP[c] >= 0) { "Invalid base58 character '${text[i]}'" }
            var carry = LOOKUP[c]
            var j = 0
            var k = size - 1
            while (k >= 0 && (carry != 0 || j < length)) {
                carry += 58 * (bytes[k].toInt() and 0xff)
                bytes[k] = (carry % 256).toByte()
                carry /= 256
                k--
                j++
            }
            length = j
        }
        val out = ByteArray(zeros + length)
        var start = size - length
        while (start < size && bytes[start] == 0.toByte()) start++
        System.arraycopy(bytes, start, out, zeros, size - start)
        return out
    }

    fun decodeOrNull(text: String): ByteArray? = try {
        decode(text)
    } catch (_: IllegalArgumentException) {
        null
    }
}

/**
 * Generic Bech32 (BIP-173) encoder/decoder with 5-bit payload converters,
 * plus Bech32m (BIP-350) constant. Used for Cosmos (bech32 hrp + raw 5-bit
 * address bytes) and Bitcoin segwit addresses (witness version + program).
 */
object Bech32 {

    private const val CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
    private const val BECH32M_CONST = 0x2bc830a3

    fun polymod(values: IntArray): Int {
        var chk = 1
        val gen = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)
        for (v in values) {
            val b = chk shr 25
            chk = ((chk and 0x1ffffff) shl 5) xor v
            for (i in 0..4) {
                if (((b shr i) and 1) == 1) chk = chk xor gen[i]
            }
        }
        return chk
    }

    fun hrpExpand(hrp: String): IntArray {
        val hrpLen = hrp.length
        val out = IntArray(hrpLen * 2 + 1)
        for (i in 0 until hrpLen) {
            out[i] = hrp[i].code shr 5
            out[i + hrpLen + 1] = hrp[i].code and 31
        }
        return out
    }

    fun encode(hrp: String, data5bit: IntArray, version: Int = 1): String {
        val checksumConst = when (version) {
            1 -> 1
            else -> BECH32M_CONST
        }
        val values = IntArray(data5bit.size + 6)
        System.arraycopy(data5bit, 0, values, 0, data5bit.size)
        val expanded = hrpExpand(hrp)
        val combined = IntArray(expanded.size + values.size)
        System.arraycopy(expanded, 0, combined, 0, expanded.size)
        System.arraycopy(values, 0, combined, expanded.size, values.size)
        val mod = polymod(combined) xor checksumConst
        // Checksum characters are appended highest-5-bits first (BIP173;
        // verified against the official bc1qw508d6... vector).
        for (i in 0..5) {
            values[data5bit.size + i] = (mod shr (5 * (5 - i))) and 31
        }
        val sb = StringBuilder(hrp).append('1')
        for (v in data5bit) sb.append(CHARSET[v])
        for (i in 0..5) sb.append(CHARSET[values[data5bit.size + i]])
        return sb.toString()
    }

    /**
     * Raw convertbits 8→5 (zero-padded) for bech32 payloads that carry no
     * witness version (Cosmos addresses are bech32(hrp, convertbits(hash160))).
     */
    fun convertBits(bytes: ByteArray): IntArray {
        val out = ArrayList<Int>(bytes.size * 8 / 5 + 1)
        var buffer = 0
        var bits = 0
        for (b in bytes) {
            buffer = (buffer shl 8) or (b.toInt() and 0xff)
            bits += 8
            while (bits >= 5) {
                out.add((buffer shr (bits - 5)) and 31)
                bits -= 5
            }
        }
        if (bits > 0) out.add((buffer shl (5 - bits)) and 31)
        return out.toIntArray()
    }

    /**
     * Witness-program conversion (BIP173 segwit): the first byte is the
     * witness version and becomes a SINGLE 5-bit symbol, the remaining bytes
     * are convertbits'd separately — the version byte must never be merged
     * into the program's bit stream.
     */
    fun to5bit(witnessVersionAndProgram: ByteArray): IntArray {
        require(witnessVersionAndProgram.isNotEmpty()) { "Empty witness program" }
        val version = witnessVersionAndProgram[0].toInt() and 0xff
        require(version in 0..31) { "Witness version $version does not fit a 5-bit symbol" }
        val program = convertBits(witnessVersionAndProgram.copyOfRange(1, witnessVersionAndProgram.size))
        return intArrayOf(version) + program
    }

    /** Convert 5-bit groups back to bytes; returns null when padding is invalid. */
    fun from5bit(data: IntArray): ByteArray? {
        var buffer = 0
        var bits = 0
        val out = ArrayList<Byte>(data.size * 5 / 8)
        for (v in data) {
            require(v in 0..31) { "Invalid 5-bit value $v" }
            buffer = (buffer shl 5) or v
            bits += 5
            if (bits >= 8) {
                out.add(((buffer shr (bits - 8)) and 0xff).toByte())
                bits -= 8
            }
        }
        if (bits in 1..4 && ((buffer shl (8 - bits)) and 0xff) != 0) return null
        if (bits >= 5) return null
        return out.toByteArray()
    }

    /** Decode a bech32 string into (hrp, 5-bit data); null on failure. */
    fun decode(text: String): Pair<String, IntArray>? {
        if (text.length < 8 || text.length > 90) return null
        val hasUpper = text.any { it.isUpperCase() }
        val hasLower = text.any { it.isLowerCase() }
        if (hasUpper && hasLower) return null
        val lowered = text.lowercase()
        val sep = lowered.lastIndexOf('1')
        if (sep < 1 || sep + 7 > lowered.length) return null
        val hrp = lowered.substring(0, sep)
        if (hrp.any { it.code !in 33..126 }) return null
        val data = IntArray(lowered.length - sep - 1)
        for (i in data.indices) {
            val c = lowered[sep + 1 + i]
            val idx = CHARSET.indexOf(c)
            if (idx < 0) return null
            data[i] = idx
        }
        val expanded = hrpExpand(hrp)
        val values = data.copyOf()
        val combined = IntArray(expanded.size + values.size)
        System.arraycopy(expanded, 0, combined, 0, expanded.size)
        System.arraycopy(values, 0, combined, expanded.size, values.size)
        val chk = polymod(combined)
        if (chk != 1 && chk != BECH32M_CONST) return null
        return hrp to data.copyOfRange(0, data.size - 6)
    }
}
