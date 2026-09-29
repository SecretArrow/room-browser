package com.roombrowser.domain.agent

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * The PUBLIC Ollama registry (`registry.ollama.ai`) — the place `ollama pull`
 * actually fetches model bytes from. Resolving a catalog tag against it turns
 * "install this model" into a plain HTTPS download of the real GGUF, so the
 * built-in on-device engine can be fed WITHOUT a local Ollama server: on a
 * phone with no Termux, `http://localhost:11434` is simply not there.
 *
 * The registry speaks the Docker Registry v2 protocol, which means two hops:
 *
 *  1. `GET /v2/library/{name}/manifests/{tag}` → a JSON manifest whose
 *     `layers` array describes the blobs. The layer with
 *     [MODEL_MEDIA_TYPE] is the GGUF weights; the others are metadata
 *     (system prompt, chat template, license) that the on-device engine does
 *     not need.
 *  2. `GET /v2/library/{name}/blobs/{digest}` → the bytes, addressed by the
 *     `sha256:` digest the manifest just gave us.
 *
 * Everything here is pure (no HTTP, no Android), so tag parsing, URL
 * construction and manifest parsing are all covered by the core unit tests;
 * [com.roombrowser.agent.OllamaRegistryClient] is only the plumbing.
 *
 * The blob digest is also an integrity guarantee: the caller knows the
 * expected `sha256` BEFORE downloading, so a truncated or substituted file is
 * detectable rather than silently loaded into the engine.
 */
object OllamaRegistry {

    /** The public registry host. */
    const val HOST: String = "https://registry.ollama.ai"

    /** Registry protocol version prefix (Docker Registry v2). */
    private const val API: String = "v2"

    /** Media type of the layer that carries the GGUF weights. */
    const val MODEL_MEDIA_TYPE: String = "application/vnd.ollama.image.model"

    /** Implicit namespace for the official (non-namespaced) model library. */
    private const val LIBRARY_NAMESPACE: String = "library"

    /** Extension every model blob lands with — the store keys ids off it. */
    private const val GGUF_SUFFIX: String = ".gguf"

    /**
     * The downloadable GGUF layer of a manifest: its `sha256:` digest and the
     * exact byte count the registry will serve.
     */
    data class ModelBlob(val digest: String, val sizeBytes: Long)

    /**
     * Splits an ollama tag into `(name, reference)`.
     *
     * `"qwen2.5:0.5b"` → `"qwen2.5"` to `"0.5b"`; a bare `"tinyllama"` gets
     * the implicit `latest` reference, which is what `ollama pull tinyllama`
     * resolves to. A colon at index 0 is not a separator (the tag is malformed
     * but must still round-trip rather than crash), and a namespaced name like
     * `"user/model:tag"` keeps its namespace in the name half.
     */
    fun splitTag(tag: String): Pair<String, String> {
        val t = tag.trim()
        val i = t.lastIndexOf(':')
        return if (i <= 0) t to "latest" else t.substring(0, i) to t.substring(i + 1)
    }

    /**
     * The registry path namespace for [name]: a name that already carries a
     * namespace (`"user/model"`) is used verbatim; a bare official name gets
     * the implicit `library/` prefix, which is what the public registry
     * expects for curated models.
     */
    private fun namespaceOf(name: String): String =
        if (name.contains('/')) name else "$LIBRARY_NAMESPACE/$name"

    /**
     * `GET` URL of the manifest for [tag]. [host] is injectable so e2e tests
     * can point this at a MockWebServer instead of the real registry.
     */
    fun manifestUrl(tag: String, host: String = HOST): String {
        val (name, reference) = splitTag(tag)
        return "${host.trim().trimEnd('/')}/$API/${namespaceOf(name)}/manifests/$reference"
    }

    /**
     * `GET` URL of the blob addressed by [digest] (already `"sha256:…"`, as the
     * manifest supplies it) for the model named in [tag].
     */
    fun blobUrl(tag: String, digest: String, host: String = HOST): String {
        val (name, _) = splitTag(tag)
        return "${host.trim().trimEnd('/')}/$API/${namespaceOf(name)}/blobs/$digest"
    }

    /**
     * Parses a manifest body and returns the GGUF layer, or `null` when the
     * body is garbage or carries no model layer (an embedding-only or
     * metadata-only manifest). A malformed body is NOT an exception: the
     * caller shows an honest "could not resolve" message instead of crashing,
     * matching how the rest of the agent layer degrades.
     */
    fun modelBlob(manifestJson: String): ModelBlob? = runCatching {
        val layers = AgentJson.parseToJsonElement(manifestJson.trim())
            .jsonObject["layers"]?.jsonArray ?: return@runCatching null
        for (layer in layers) {
            val obj = layer as? JsonObject ?: continue
            if (obj.string("mediaType") != MODEL_MEDIA_TYPE) continue
            val digest = obj.string("digest") ?: continue
            return@runCatching ModelBlob(digest, obj["size"]?.jsonPrimitive?.longOrNull ?: 0L)
        }
        null
    }.getOrNull()

    /**
     * Local file name for a downloaded model, e.g. `"qwen2.5-0.5b.gguf"`. A
     * `latest` reference is dropped (`"tinyllama.gguf"`) so the common case
     * stays short; a namespace separator becomes a dash so the name can never
     * escape the models directory.
     */
    fun fileName(tag: String): String {
        val (name, reference) = splitTag(tag)
        val flat = name.replace('/', '-')
        val suffix = if (reference == "latest") "" else "-$reference"
        return "$flat$GGUF_SUFFIX"
    }

    /**
     * The id [fileName] maps to in the ON-DEVICE store — the file name without
     * its extension, which is what `OnDeviceModelStore.list()` reports and what
     * its `fileFor` takes back.
     *
     * This exists so "is this catalog tag already installed?" compares like
     * with like: the store's ids never carry `.gguf`, so testing a raw
     * [fileName] against them would silently never match and every installed
     * model would keep offering Install.
     */
    fun modelId(tag: String): String = fileName(tag).removeSuffix(GGUF_SUFFIX)

    private fun JsonObject.string(key: String): String? =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
}
