package com.roombrowser.domain.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The Ollama registry translation decides WHERE a catalog install downloads
 * from and WHICH bytes count as the model, so it is covered here rather than
 * only against the live registry: tag parsing, URL construction, and the
 * "pick the GGUF layer out of the Docker-v2 manifest" rule.
 */
class OllamaRegistryTest {

    /** The real manifest shape, trimmed to the fields that matter. */
    private fun manifest(vararg layers: String) =
        """{"schemaVersion":2,"mediaType":"application/vnd.docker.distribution.manifest.v2+json",""" +
            """"layers":[${layers.joinToString(",")}]}"""

    private fun layer(mediaType: String, digest: String, size: Long) =
        """{"mediaType":"$mediaType","digest":"$digest","size":$size}"""

    // --------------------------------------------------------------- splitTag

    @Test
    fun `a tagged preset splits into name and reference`() {
        assertEquals("qwen2.5" to "0.5b", OllamaRegistry.splitTag("qwen2.5:0.5b"))
    }

    @Test
    fun `a bare name gets the implicit latest reference`() {
        assertEquals("tinyllama" to "latest", OllamaRegistry.splitTag("tinyllama"))
    }

    @Test
    fun `a namespaced name keeps its namespace`() {
        assertEquals("user/model" to "tag", OllamaRegistry.splitTag("user/model:tag"))
    }

    @Test
    fun `a colon in the first position is not treated as a separator`() {
        // Malformed, but it must round-trip rather than crash or truncate.
        assertEquals(":weird" to "latest", OllamaRegistry.splitTag(":weird"))
    }

    @Test
    fun `surrounding whitespace is trimmed`() {
        assertEquals("qwen2.5" to "0.5b", OllamaRegistry.splitTag("  qwen2.5:0.5b "))
    }

    // ------------------------------------------------------------- manifestUrl

    @Test
    fun `an official preset maps into the library namespace`() {
        assertEquals(
            "https://registry.ollama.ai/v2/library/qwen2.5/manifests/0.5b",
            OllamaRegistry.manifestUrl("qwen2.5:0.5b")
        )
    }

    @Test
    fun `a bare name resolves the latest manifest`() {
        assertEquals(
            "https://registry.ollama.ai/v2/library/tinyllama/manifests/latest",
            OllamaRegistry.manifestUrl("tinyllama")
        )
    }

    @Test
    fun `a namespaced name is used verbatim, not double-prefixed`() {
        assertEquals(
            "https://registry.ollama.ai/v2/user/model/manifests/tag",
            OllamaRegistry.manifestUrl("user/model:tag")
        )
    }

    @Test
    fun `an injected host is used and a trailing slash is normalised`() {
        assertEquals(
            "http://127.0.0.1:8080/v2/library/qwen2.5/manifests/0.5b",
            OllamaRegistry.manifestUrl("qwen2.5:0.5b", host = "http://127.0.0.1:8080/")
        )
    }

    // ---------------------------------------------------------------- blobUrl

    @Test
    fun `a blob url addresses the manifest digest`() {
        assertEquals(
            "https://registry.ollama.ai/v2/library/qwen2.5/blobs/sha256:abc123",
            OllamaRegistry.blobUrl("qwen2.5:0.5b", "sha256:abc123")
        )
    }

    @Test
    fun `a blob url for a namespaced model keeps its namespace`() {
        assertEquals(
            "https://registry.ollama.ai/v2/user/model/blobs/sha256:abc123",
            OllamaRegistry.blobUrl("user/model:tag", "sha256:abc123")
        )
    }

    // --------------------------------------------------------------- modelBlob

    @Test
    fun `the GGUF layer is picked out by its media type`() {
        // The real qwen2.5:0.5b layout: model weights first, then metadata.
        val blob = OllamaRegistry.modelBlob(
            manifest(
                layer("application/vnd.ollama.image.model", "sha256:c5396e06", 397_807_936L),
                layer("application/vnd.ollama.image.system", "sha256:66b9ea09", 68L),
                layer("application/vnd.ollama.image.template", "sha256:eb440283", 1482L),
                layer("application/vnd.ollama.image.license", "sha256:832dd9e0", 11343L)
            )
        )
        assertEquals("sha256:c5396e06", blob?.digest)
        assertEquals(397_807_936L, blob?.sizeBytes)
    }

    @Test
    fun `the metadata layers are never mistaken for the model`() {
        // Order-independence: the model layer is LAST here.
        val blob = OllamaRegistry.modelBlob(
            manifest(
                layer("application/vnd.ollama.image.template", "sha256:aaaa", 1482L),
                layer("application/vnd.ollama.image.model", "sha256:bbbb", 123L)
            )
        )
        assertEquals("sha256:bbbb", blob?.digest)
    }

    @Test
    fun `a manifest with no model layer resolves to null`() {
        val blob = OllamaRegistry.modelBlob(
            manifest(layer("application/vnd.ollama.image.license", "sha256:aaaa", 10L))
        )
        assertNull(blob)
    }

    @Test
    fun `an empty layer list resolves to null`() {
        assertNull(OllamaRegistry.modelBlob(manifest()))
    }

    @Test
    fun `a garbage body resolves to null instead of throwing`() {
        assertNull(OllamaRegistry.modelBlob("<html>404</html>"))
        assertNull(OllamaRegistry.modelBlob(""))
    }

    @Test
    fun `a layer with no digest is skipped`() {
        val blob = OllamaRegistry.modelBlob(
            manifest(
                """{"mediaType":"${OllamaRegistry.MODEL_MEDIA_TYPE}","size":5}""",
                layer(OllamaRegistry.MODEL_MEDIA_TYPE, "sha256:good", 7L)
            )
        )
        assertEquals("sha256:good", blob?.digest)
    }

    @Test
    fun `a missing size degrades to zero rather than null`() {
        val blob = OllamaRegistry.modelBlob(
            manifest("""{"mediaType":"${OllamaRegistry.MODEL_MEDIA_TYPE}","digest":"sha256:x"}""")
        )
        assertEquals("sha256:x", blob?.digest)
        assertEquals(0L, blob?.sizeBytes)
    }

    // --------------------------------------------------------------- fileName

    @Test
    fun `a tagged preset becomes a dashed file name`() {
        assertEquals("qwen2.5-0.5b.gguf", OllamaRegistry.fileName("qwen2.5:0.5b"))
    }

    @Test
    fun `a bare name drops the implicit latest from the file name`() {
        assertEquals("tinyllama.gguf", OllamaRegistry.fileName("tinyllama"))
    }

    @Test
    fun `a namespace separator cannot survive into the file name`() {
        // '/' in a file name would escape the models directory.
        assertEquals("user-model-tag.gguf", OllamaRegistry.fileName("user/model:tag"))
    }

    // --------------------------------------------------------------- modelId

    @Test
    fun `the store id is the file name without its extension`() {
        // OnDeviceModelStore.list() reports ids with no ".gguf" and fileFor()
        // puts it back — comparing a raw file name against those ids would
        // never match, leaving installed models offering Install forever.
        assertEquals("qwen2.5-0.5b", OllamaRegistry.modelId("qwen2.5:0.5b"))
    }

    @Test
    fun `the store id never carries the gguf extension for any tag shape`() {
        listOf("qwen2.5:0.5b", "tinyllama", "user/model:tag", ":").forEach { tag ->
            val id = OllamaRegistry.modelId(tag)
            assertFalse("id '$id' must not end in .gguf", id.endsWith(".gguf"))
        }
    }
}
