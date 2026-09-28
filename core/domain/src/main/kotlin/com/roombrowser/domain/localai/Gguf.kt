package com.roombrowser.domain.localai

import java.io.EOFException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Parsed GGUF header metadata (https://github.com/ggml-org/ggml/blob/master/docs/gguf.md).
 *
 * Only the HEADER is described here — tensor infos and tensor data are never
 * touched, so parsing a multi-GB model costs a few KB of I/O (the model list
 * parses every file on disk on every refresh).
 */
data class GgufMeta(
    val version: Int,
    /** general.name — human-readable model name, null when absent. */
    val name: String?,
    /** general.architecture — "llama", "qwen2", "gemma3", … null when absent. */
    val architecture: String?,
    /** general.file_type mapped to a human label ("F16", "Q4_K_M", …); "FT n" for unknown codes. */
    val quantization: String?,
    /** `<arch>.context_length` (accepts uint32 or uint64 encodings), null when absent. */
    val contextLength: Long?,
    /** Raw header tensor_count (uint64). */
    val tensorCount: Long,
    /**
     * Approximate parameter count parsed from general.size_label when present
     * (e.g. "1.5B" → 1_500_000_000), else null. This is a label written by the
     * quantizer, NOT a measured count — honest enough for a size hint in the UI,
     * never for anything computed.
     */
    val parameterCountApprox: Long?
)

/** Sentinel for anything malformed — caught once at the [Gguf.parse] boundary. */
private class GgufMalformedException(message: String) : Exception(message)

/**
 * GGUF header parser, ported faithfully (header only) from the llama.cpp that
 * is vendored in this repo (`app/src/main/cpp/llamacpp/ggml/src/gguf.cpp`,
 * `gguf_init_from_reader`). Porting from the vendored C++ — not from the
 * online spec — guarantees the Kotlin parser and the engine that consumes the
 * file agree on what a header is.
 *
 * Wire format (all integers LITTLE-ENDIAN, packed — the modern gguf.cpp applies
 * NO alignment padding inside the KV section; alignment only governs the tensor
 * DATA section which this parser never reads):
 *
 *  - magic    "GGUF"          (4 bytes)
 *  - version  uint32          — gguf.cpp rejects 0, rejects 1 ("GGUFv1 is no
 *    longer supported"), rejects anything above 3, and rejects a version whose
 *    low 16 bits are zero (that is how an endianness mismatch shows up: a
 *    big-endian-written 3 reads as 0x03000000). We mirror all four checks:
 *    v1 files cannot be loaded by the embedded engine either, so accepting
 *    them here would only paint a "loadable" label on a file that will fail.
 *  - tensor_count uint64, kv_count uint64
 *  - per KV: key string (uint64 len + UTF-8 bytes), type uint32; ARRAY adds an
 *    element-type uint32 + element-count uint64 before the elements; every
 *    other type is a fixed-width scalar (BOOL is 1 byte).
 *
 * Robustness contract: [parse] returns null for ANY malformed input — bad
 * magic, unsupported version, truncated stream, absurd lengths (> 64 MiB per
 * string), invalid type codes — and never throws and never closes the stream
 * (the caller owns it).
 */
object Gguf {

    private const val SUPPORTED_VERSION = 3L

    /** Mirrors GGUF_MAX_ARRAY_ELEMENTS in gguf.cpp (1 Gi elements). */
    private const val MAX_ARRAY_ELEMENTS = 1L shl 30

    /**
     * Sanity cap for one KV string. gguf.cpp allows up to 1 GiB (it also knows
     * the remaining file size, which a stream does not); 64 MiB is far above
     * any real header string (keys, names, chat templates) while keeping a
     * corrupt length field from dragging the parser through gigabytes.
     */
    private const val MAX_STRING_BYTES = 64L * 1024 * 1024

    // gguf_type enum values (gguf.h) — the type is written as a 4-byte enum.
    private const val T_UINT8 = 0L
    private const val T_INT8 = 1L
    private const val T_UINT16 = 2L
    private const val T_INT16 = 3L
    private const val T_UINT32 = 4L
    private const val T_INT32 = 5L
    private const val T_FLOAT32 = 6L
    private const val T_BOOL = 7L
    private const val T_STRING = 8L
    private const val T_ARRAY = 9L
    private const val T_UINT64 = 10L
    private const val T_INT64 = 11L
    private const val T_FLOAT64 = 12L

    /**
     * general.file_type → human quantization label. Values follow the vendored
     * llama.h `enum llama_ftype` (0…41); gaps (5/6 removed types, 33…35 removed
     * repack types) intentionally have no entry and render as "FT n".
     */
    private val FILE_TYPE_LABELS: Map<Long, String> = mapOf(
        0L to "F32",
        1L to "F16",
        2L to "Q4_0",
        3L to "Q4_1",
        4L to "Q4_1_SOME_F16",
        7L to "Q8_0",
        8L to "Q5_0",
        9L to "Q5_1",
        10L to "Q2_K",
        11L to "Q3_K_S",
        12L to "Q3_K_M",
        13L to "Q3_K_L",
        14L to "Q4_K_S",
        15L to "Q4_K_M",
        16L to "Q5_K_S",
        17L to "Q5_K_M",
        18L to "Q6_K",
        19L to "IQ2_XXS",
        20L to "IQ2_XS",
        21L to "Q2_K_S",
        22L to "IQ3_XS",
        23L to "IQ3_XXS",
        24L to "IQ1_S",
        25L to "IQ4_NL",
        26L to "IQ3_S",
        27L to "IQ3_M",
        28L to "IQ2_S",
        29L to "IQ2_M",
        30L to "IQ4_XS",
        31L to "IQ1_M",
        32L to "BF16",
        36L to "TQ1_0",
        37L to "TQ2_0",
        38L to "MXFP4",
        39L to "NVFP4",
        40L to "Q1_0",
        41L to "Q2_0"
    )

    /** "1.5B", "350M", "2.06B", "1,5B" → 1500000000, 350000000, 2060000000, … */
    private val SIZE_LABEL = Regex("(\\d+(?:[.,]\\d+)?)\\s*([KkMmBbTt])")

    /**
     * Reads ONLY the header of a GGUF stream. Returns null when the magic bytes
     * don't match or the header is malformed. Consumes at most the header — the
     * caller may keep using the stream afterwards (tensor data follows): the
     * reader pulls EXACTLY the declared bytes, never a buffered block more
     * (which is why [InputStream] is not wrapped in a BufferedInputStream).
     */
    fun parse(input: InputStream): GgufMeta? =
        runCatching { readHeader(ExactReader(input, MAX_STRING_BYTES)) }.getOrNull()

    // ------------------------------------------------------------------ header

    private fun readHeader(r: ExactReader): GgufMeta {
        // magic — compared per byte like gguf_init_from_reader does.
        val magic = r.bytes(4)
        if (magic[0] != 'G'.code.toByte() || magic[1] != 'G'.code.toByte() ||
            magic[2] != 'U'.code.toByte() || magic[3] != 'F'.code.toByte()
        ) {
            throw GgufMalformedException("not a GGUF file")
        }

        val version = r.u32()
        if (version == 0L || version == 1L || version > SUPPORTED_VERSION) {
            // gguf.cpp: "bad GGUF version" / "GGUFv1 is no longer supported" /
            // "this GGUF file is version n but this software only supports up to 3".
            throw GgufMalformedException("unsupported GGUF version $version")
        }
        if (version and 0xFFFFL == 0L) {
            // gguf.cpp endianness guard: a host-endian 3 read from a
            // byte-swapped file lands here (e.g. 0x03000000).
            throw GgufMalformedException("GGUF version looks byte-swapped (endianness mismatch)")
        }

        val tensorCount = r.u64()
        val kvCount = r.u64()

        // Scalar/string KVs we may want AFTER the loop (architecture usually
        // comes first in real files, but ordering is not guaranteed by the
        // format, so extraction happens once every KV is consumed).
        val values = HashMap<String, Any?>()
        val seenKeys = HashSet<String>()
        for (i in 0 until kvCount) {
            val key = r.string()
            // gguf.cpp rejects empty and duplicate keys.
            if (key.isEmpty() || !seenKeys.add(key)) {
                throw GgufMalformedException("bad KV key at index $i")
            }
            val type = r.u32()
            if (type == T_ARRAY) {
                val elementType = r.u32()
                val count = r.u64()
                skipArray(r, elementType, count)
            } else {
                values[key] = readScalar(r, type)
            }
        }

        val architecture = values["general.architecture"] as? String
        return GgufMeta(
            version = version.toInt(),
            name = (values["general.name"] as? String)?.takeIf { it.isNotBlank() },
            architecture = architecture,
            quantization = (values["general.file_type"] as? Long)
                ?.let { FILE_TYPE_LABELS[it] ?: "FT $it" },
            contextLength = architecture
                ?.let { values["$it.context_length"] as? Long }
                ?.takeIf { it > 0 },
            tensorCount = tensorCount,
            parameterCountApprox = (values["general.size_label"] as? String)
                ?.let(::parseSizeLabel)
        )
    }

    // ------------------------------------------------------------------ values

    /**
     * Consumes one scalar KV of [type]. Integer types are captured as [Long]
     * (unsigned read; header values are small enough that wrapping never
     * happens in practice), BOOL as [Boolean], STRING as [String]; float types
     * are consumed but not captured — nothing in the metadata we expose needs
     * them, and skipping keeps the extraction map small.
     */
    private fun readScalar(r: ExactReader, type: Long): Any? = when (type) {
        T_UINT8 -> r.u8()
        T_INT8 -> r.bytes(1)[0].toLong()
        T_UINT16 -> r.u16()
        T_INT16 -> r.le(2).short.toLong()
        T_UINT32 -> r.u32()
        T_INT32 -> r.le(4).int.toLong()
        T_FLOAT32 -> { r.bytes(4); null }
        T_BOOL -> r.bytes(1)[0] != 0.toByte()
        T_STRING -> r.string()
        T_UINT64 -> r.u64()
        T_INT64 -> r.u64()
        T_FLOAT64 -> { r.bytes(8); null }
        else -> throw GgufMalformedException("invalid KV type $type")
    }

    /**
     * Consumes an ARRAY KV. Arrays are skipped, not captured — tokenizer arrays
     * can hold a few hundred thousand elements and none of them are needed for
     * the model list. They still must be WALKED byte-faithfully (strings keep
     * their per-element length prefix) so later KVs keep parsing.
     */
    private fun skipArray(r: ExactReader, elementType: Long, count: Long) {
        if (count < 0 || count > MAX_ARRAY_ELEMENTS) {
            throw GgufMalformedException("array element count out of range")
        }
        when (elementType) {
            T_STRING -> {
                // String arrays keep their per-element length prefix — walk them.
                var remaining = count
                while (remaining > 0) {
                    r.string()
                    remaining--
                }
            }
            T_UINT8, T_INT8, T_BOOL -> skipBytes(r, count)
            T_UINT16, T_INT16 -> skipBytes(r, count * 2)
            T_UINT32, T_INT32, T_FLOAT32 -> skipBytes(r, count * 4)
            T_UINT64, T_INT64, T_FLOAT64 -> skipBytes(r, count * 8)
            else -> throw GgufMalformedException("invalid array element type $elementType")
        }
    }

    /** Bulk-discard of [n] bytes in ≤64 KiB chunks (a hostile count dies on EOF). */
    private fun skipBytes(r: ExactReader, n: Long) {
        val chunk = ByteArray(64 * 1024)
        var remaining = n
        while (remaining > 0) {
            val take = minOf(remaining, chunk.size.toLong()).toInt()
            r.bytes(take)
            remaining -= take
        }
    }

    /** "1.5B" → 1_500_000_000. First number+suffix wins; null when unparseable. */
    internal fun parseSizeLabel(label: String): Long? {
        val match = SIZE_LABEL.find(label) ?: return null
        val number = match.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
        val multiplier = when (match.groupValues[2].lowercase()) {
            "k" -> 1e3
            "m" -> 1e6
            "b" -> 1e9
            "t" -> 1e12
            else -> return null
        }
        return kotlin.math.round(number * multiplier).toLong()
    }

    /**
     * Stream reader that returns EXACTLY the requested bytes or throws
     * [EOFException] — no read-ahead buffering, so parsing leaves the
     * underlying stream positioned right after the header. All multi-byte
     * values are decoded LITTLE-ENDIAN via [ByteBuffer].
     */
    private class ExactReader(
        private val input: InputStream,
        private val maxStringBytes: Long
    ) {

        fun bytes(n: Int): ByteArray {
            val buf = ByteArray(n)
            var off = 0
            while (off < n) {
                val read = input.read(buf, off, n - off)
                if (read < 0) throw EOFException("GGUF header truncated")
                off += read
            }
            return buf
        }

        // Not private: the outer object's readScalar() decodes through it
        // (Kotlin forbids outer classes from touching nested-class privates;
        // ExactReader itself is private, so this stays Gguf-scoped anyway).
        fun le(size: Int): ByteBuffer =
            ByteBuffer.wrap(bytes(size)).order(ByteOrder.LITTLE_ENDIAN)

        fun u8(): Long = bytes(1)[0].toLong() and 0xFF

        fun u16(): Long = le(2).short.toLong() and 0xFFFF

        fun u32(): Long = le(4).int.toLong() and 0xFFFFFFFFL

        fun u64(): Long = le(8).long

        fun string(): String {
            val len = u64()
            if (len < 0 || len > maxStringBytes) {
                throw GgufMalformedException("string length $len out of range")
            }
            return String(bytes(len.toInt()), Charsets.UTF_8)
        }
    }
}
