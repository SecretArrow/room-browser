package com.roombrowser.domain.agent

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class OllamaDtosTest {

    // ---------- OllamaTagsParser ----------

    @Test
    fun `tags parser maps a real ollama body`() {
        val body = """
            {"models":[
              {"name":"llama3.2:1b","model":"llama3.2:1b",
               "modified_at":"2024-11-25T16:23:53.811067+07:00","size":1328238021,
               "digest":"b5f7b0db1c4a2d3e6b0f0e1d2c3b4a5f6e7d8c9b0a1f2e3d4c5b6a7f8e9d0c1b",
               "details":{"parent_model":"","format":"gguf","family":"llama","families":["llama"],
                          "parameter_size":"1.2B","quantization_level":"Q4_K_M"}},
              {"name":"gemma3:1b","model":"gemma3:1b",
               "modified_at":"2025-03-12T09:11:14.819042Z","size":815578992,
               "digest":"a70b3f6ff4a1d2c3e4f5a6b7c8d9e0f1a2b3c4d5e6f7a8b9c0d1e2f3a4b5c6",
               "details":{"parent_model":"","format":"gguf","family":"gemma3","families":["gemma3"],
                          "parameter_size":"1.0B","quantization_level":"Q4_0"}}
            ]}
        """.trimIndent()
        val models = OllamaTagsParser.parse(body)
        assertThat(models).hasSize(2)
        val llama = models[0]
        assertThat(llama.name).isEqualTo("llama3.2:1b")
        assertThat(llama.model).isEqualTo("llama3.2:1b")
        assertThat(llama.sizeBytes).isEqualTo(1328238021L)
        assertThat(llama.digest).startsWith("b5f7b0db")
        assertThat(llama.modifiedAt).isEqualTo("2024-11-25T16:23:53.811067+07:00")
        assertThat(llama.family).isEqualTo("llama")
        assertThat(llama.parameterSize).isEqualTo("1.2B")
        assertThat(llama.quantizationLevel).isEqualTo("Q4_K_M")
        val gemma = models[1]
        assertThat(gemma.name).isEqualTo("gemma3:1b")
        assertThat(gemma.sizeBytes).isEqualTo(815578992L)
        assertThat(gemma.family).isEqualTo("gemma3")
        assertThat(gemma.quantizationLevel).isEqualTo("Q4_0")
    }

    @Test
    fun `tags parser accepts a bare array body`() {
        val body = """[{"name":"tinyllama:1b","size":608000000,
            "details":{"family":"llama","parameter_size":"1.1B","quantization_level":"Q4_K_M"}}]"""
        val models = OllamaTagsParser.parse(body)
        assertThat(models).hasSize(1)
        assertThat(models[0].name).isEqualTo("tinyllama:1b")
        assertThat(models[0].model).isEmpty() // mirror field absent on this shape
        assertThat(models[0].family).isEqualTo("llama")
        assertThat(models[0].parameterSize).isEqualTo("1.1B")
    }

    @Test
    fun `tags parser tolerates entries without details`() {
        val models = OllamaTagsParser.parse("""{"models":[{"name":"qwen2.5:0.5b","size":397}]}""")
        assertThat(models).hasSize(1)
        val m = models[0]
        assertThat(m.family).isEmpty()
        assertThat(m.parameterSize).isEmpty()
        assertThat(m.quantizationLevel).isEmpty()
        assertThat(m.digest).isEmpty()
        assertThat(m.modifiedAt).isEmpty()
        assertThat(m.model).isEmpty()
    }

    @Test
    fun `tags parser returns empty when models key is missing`() {
        assertThat(OllamaTagsParser.parse("""{"error":"not found"}""")).isEmpty()
        assertThat(OllamaTagsParser.parse("""{"data":[]}""")).isEmpty()
    }

    @Test
    fun `tags parser garbage and empty bodies yield empty`() {
        assertThat(OllamaTagsParser.parse("<html>Termux proxy error</html>")).isEmpty()
        assertThat(OllamaTagsParser.parse("")).isEmpty()
        assertThat(OllamaTagsParser.parse("   ")).isEmpty()
    }

    // ---------- OllamaPullParser ----------

    @Test
    fun `pull line pulling manifest has no digest or byte counters`() {
        val e = OllamaPullParser.parseLine("""{"status":"pulling manifest"}""")!!
        assertThat(e.status).isEqualTo("pulling manifest")
        assertThat(e.digest).isNull()
        assertThat(e.completed).isNull()
        assertThat(e.total).isNull()
        assertThat(e.isTerminal).isFalse()
        assertThat(e.isDownloading).isFalse()
    }

    @Test
    fun `pull line downloading carries digest and layer byte counters`() {
        val line = """{"status":"downloading","digest":"sha256:b5f7b0db1c4a","completed":1234567,"total":815578992}"""
        val e = OllamaPullParser.parseLine(line)!!
        assertThat(e.status).isEqualTo("downloading")
        assertThat(e.digest).startsWith("sha256:")
        assertThat(e.completed!!).isEqualTo(1234567L)
        assertThat(e.total!!).isEqualTo(815578992L)
        assertThat(e.isDownloading).isTrue()
        assertThat(e.isTerminal).isFalse()
    }

    @Test
    fun `pull line success is terminal`() {
        val e = OllamaPullParser.parseLine("""{"status":"success"}""")!!
        assertThat(e.isTerminal).isTrue()
        assertThat(e.isDownloading).isFalse()
    }

    @Test
    fun `pull line blank yields null`() {
        assertThat(OllamaPullParser.parseLine("")).isNull()
        assertThat(OllamaPullParser.parseLine("   ")).isNull()
    }

    @Test
    fun `pull line garbage yields null`() {
        assertThat(OllamaPullParser.parseLine("downloading 45%")).isNull()
        assertThat(OllamaPullParser.parseLine("{not json")).isNull()
    }

    @Test
    fun `pull line with zero total is not downloading`() {
        val e = OllamaPullParser.parseLine("""{"status":"downloading","completed":0,"total":0}""")!!
        assertThat(e.isDownloading).isFalse()
    }

    @Test
    fun `pull error line surfaces as non terminal status text`() {
        val e = OllamaPullParser.parseLine("""{"error":"pull model manifest: file does not exist"}""")!!
        assertThat(e.status).contains("file does not exist")
        assertThat(e.isTerminal).isFalse()
        assertThat(e.isDownloading).isFalse()
    }

    // ---------- OllamaModelPresets ----------

    @Test
    fun `presets list the 13 curated tags in order`() {
        assertThat(OllamaModelPresets.PRESETS).hasSize(13)
        assertThat(OllamaModelPresets.PRESETS.map { it.tag }).containsExactly(
            "smollm2:360m", "qwen2.5:0.5b", "tinyllama", "gemma3:1b",
            "qwen2.5:1.5b", "deepseek-r1:1.5b", "llama3.2:1b", "gemma2:2b",
            "qwen2.5:3b", "llama3.2:3b", "phi3.5",
            "qwen2.5:7b", "llama3.1:8b"
        ).inOrder()
    }

    @Test
    fun `preset tags are distinct non blank and space free`() {
        val tags = OllamaModelPresets.PRESETS.map { it.tag }
        assertThat(tags.distinct()).hasSize(tags.size)
        OllamaModelPresets.PRESETS.forEach { p ->
            assertThat(p.tag.isBlank()).isFalse()
            assertThat(p.tag).doesNotContain(" ")
            assertThat(p.label).isNotEmpty()
            assertThat(p.strengths).isNotEmpty()
        }
    }

    @Test
    fun `preset numeric fields match the curation table`() {
        // tag -> (sizeMb, minRamGb, contextTokens)
        val expected = mapOf(
            "smollm2:360m" to Triple(269, 3, 4096),
            "qwen2.5:0.5b" to Triple(397, 3, 32768),
            "tinyllama" to Triple(608, 3, 2048),
            "gemma3:1b" to Triple(815, 3, 32768),
            "qwen2.5:1.5b" to Triple(986, 4, 32768),
            "deepseek-r1:1.5b" to Triple(1113, 4, 32768),
            "llama3.2:1b" to Triple(1328, 4, 131072),
            "gemma2:2b" to Triple(1612, 4, 8192),
            "qwen2.5:3b" to Triple(1900, 6, 32768),
            "llama3.2:3b" to Triple(2010, 6, 131072),
            "phi3.5" to Triple(2163, 6, 131072),
            "qwen2.5:7b" to Triple(4720, 8, 32768),
            "llama3.1:8b" to Triple(4930, 8, 131072)
        )
        assertThat(OllamaModelPresets.PRESETS).hasSize(expected.size)
        OllamaModelPresets.PRESETS.forEach { p ->
            val (sizeMb, minRamGb, contextTokens) = expected.getValue(p.tag)
            assertThat(p.sizeMb).isEqualTo(sizeMb)
            assertThat(p.minRamGb).isEqualTo(minRamGb)
            assertThat(p.contextTokens).isEqualTo(contextTokens)
            assertThat(p.sizeMb).isGreaterThan(0)
            assertThat(p.minRamGb).isAtLeast(3)
            assertThat(p.minRamGb).isAtMost(8)
        }
    }

    @Test
    fun `preset tiers params and language flags match the curation`() {
        val byTag = OllamaModelPresets.PRESETS.associateBy { it.tag }
        assertThat(byTag.getValue("smollm2:360m").tier).isEqualTo(OllamaPresetTier.ULTRALIGHT)
        assertThat(byTag.getValue("gemma3:1b").tier).isEqualTo(OllamaPresetTier.ULTRALIGHT)
        assertThat(byTag.getValue("gemma2:2b").tier).isEqualTo(OllamaPresetTier.LIGHT)
        assertThat(byTag.getValue("phi3.5").tier).isEqualTo(OllamaPresetTier.BALANCED)
        assertThat(byTag.getValue("llama3.1:8b").tier).isEqualTo(OllamaPresetTier.HEAVY)
        assertThat(byTag.getValue("tinyllama").params).isEqualTo("1.1B")
        assertThat(byTag.getValue("gemma2:2b").params).isEqualTo("2.6B")
        assertThat(byTag.getValue("llama3.2:3b").params).isEqualTo("3.2B")
        assertThat(byTag.getValue("qwen2.5:7b").params).isEqualTo("7.1B")
        assertThat(byTag.getValue("smollm2:360m").label).isEqualTo("SmolLM 2 · 360M")
        assertThat(byTag.getValue("deepseek-r1:1.5b").label).isEqualTo("DeepSeek R1 · 1.5B")
        assertThat(byTag.getValue("phi3.5").label).isEqualTo("Phi 3.5 · Mini")
        assertThat(byTag.getValue("smollm2:360m").indonesianFriendly).isFalse()
        assertThat(byTag.getValue("tinyllama").indonesianFriendly).isFalse()
        assertThat(byTag.getValue("qwen2.5:0.5b").indonesianFriendly).isTrue()
        assertThat(byTag.getValue("llama3.2:1b").indonesianFriendly).isTrue()
    }

    @Test
    fun `exactly one recommended preset per tier`() {
        OllamaPresetTier.entries.forEach { tier ->
            val inTier = OllamaModelPresets.byTier(tier)
            assertThat(inTier).isNotEmpty()
            assertThat(inTier.filter { it.recommended }).hasSize(1)
        }
        assertThat(OllamaModelPresets.byTier(OllamaPresetTier.ULTRALIGHT)).hasSize(4)
        assertThat(OllamaModelPresets.byTier(OllamaPresetTier.LIGHT)).hasSize(4)
        assertThat(OllamaModelPresets.byTier(OllamaPresetTier.BALANCED)).hasSize(3)
        assertThat(OllamaModelPresets.byTier(OllamaPresetTier.HEAVY)).hasSize(2)
    }

    @Test
    fun `recommended flags match the curation`() {
        val recommended = OllamaModelPresets.PRESETS.filter { it.recommended }.map { it.tag }
        assertThat(recommended).containsExactly(
            "gemma3:1b", "qwen2.5:1.5b", "qwen2.5:3b", "llama3.1:8b"
        ).inOrder()
    }

    @Test
    fun `formatSizeMb renders MB below 1000 and one decimal GB above`() {
        assertThat(OllamaModelPresets.formatSizeMb(397)).isEqualTo("397 MB")
        assertThat(OllamaModelPresets.formatSizeMb(999)).isEqualTo("999 MB")
        assertThat(OllamaModelPresets.formatSizeMb(1328)).isEqualTo("1.3 GB")
        assertThat(OllamaModelPresets.formatSizeMb(4720)).isEqualTo("4.7 GB")
    }

    @Test
    fun `find resolves presets by exact tag`() {
        val qwen = OllamaModelPresets.find("qwen2.5:3b")!!
        assertThat(qwen.params).isEqualTo("3.1B")
        assertThat(qwen.tier).isEqualTo(OllamaPresetTier.BALANCED)
        assertThat(qwen.recommended).isTrue()
        assertThat(qwen.indonesianFriendly).isTrue()
        assertThat(qwen.strengths).contains("tool calling")
        assertThat(OllamaModelPresets.find("nope")).isNull()
        assertThat(OllamaModelPresets.find("QWEN2.5:3B")).isNull() // tags are case-sensitive
    }

    @Test
    fun `suggestedTierForRam maps ram bands to tiers`() {
        assertThat(OllamaModelPresets.suggestedTierForRam(3)).isEqualTo(OllamaPresetTier.ULTRALIGHT)
        assertThat(OllamaModelPresets.suggestedTierForRam(5)).isEqualTo(OllamaPresetTier.LIGHT)
        assertThat(OllamaModelPresets.suggestedTierForRam(7)).isEqualTo(OllamaPresetTier.BALANCED)
        assertThat(OllamaModelPresets.suggestedTierForRam(12)).isEqualTo(OllamaPresetTier.HEAVY)
    }

    // ---------- LocalAiTuning ----------

    @Test
    fun `tuning defaults leave the server in control`() {
        val t = LocalAiTuning()
        assertThat(t.host).isEqualTo("http://localhost:11434")
        assertThat(t.gpuLayers).isNull()
        assertThat(t.cpuThreads).isNull()
        assertThat(t.contextWindow).isEqualTo(2048)
        assertThat(t.keepAliveMinutes).isEqualTo(5)
        assertThat(t.clampToSanity()).isEqualTo(t) // defaults are already sane
    }

    @Test
    fun `clampToSanity clamps low bounds and trims host`() {
        val t = LocalAiTuning(
            host = "  http://192.168.1.7:11434  ",
            gpuLayers = 999,
            cpuThreads = 0,
            contextWindow = 100,
            keepAliveMinutes = 999
        ).clampToSanity()
        assertThat(t.host).isEqualTo("http://192.168.1.7:11434")
        assertThat(t.gpuLayers!!).isEqualTo(99)
        assertThat(t.cpuThreads!!).isEqualTo(1)
        assertThat(t.contextWindow).isEqualTo(512)
        assertThat(t.keepAliveMinutes).isEqualTo(60)
    }

    @Test
    fun `clampToSanity clamps upper bounds and keeps AUTO null`() {
        val t = LocalAiTuning(
            host = "http://localhost:11434",
            gpuLayers = null,
            cpuThreads = null,
            contextWindow = 999999,
            keepAliveMinutes = -5
        ).clampToSanity()
        assertThat(t.gpuLayers).isNull()
        assertThat(t.cpuThreads).isNull()
        assertThat(t.contextWindow).isEqualTo(16384)
        assertThat(t.keepAliveMinutes).isEqualTo(0)
    }

    // ---------- LocalAiBackup ----------

    @Test
    fun `backup round trip preserves host tuning and models`() {
        val manifest = LocalAiBackupManifest(
            host = "http://192.168.1.7:11434",
            tuning = LocalAiTuning(
                host = "http://192.168.1.7:11434",
                gpuLayers = 27,
                cpuThreads = 4,
                contextWindow = 8192,
                keepAliveMinutes = 30
            ),
            models = listOf("llama3.2:1b", "qwen2.5:3b", "gemma3:1b"),
            exportedAtEpochMs = 1730000000000L
        )
        val decoded = LocalAiBackup.decode(LocalAiBackup.encode(manifest))
        assertThat(decoded).isNotNull()
        assertThat(decoded!!.host).isEqualTo(manifest.host)
        assertThat(decoded.tuning).isEqualTo(manifest.tuning)
        assertThat(decoded.models).isEqualTo(manifest.models)
        assertThat(decoded.exportedAtEpochMs).isEqualTo(manifest.exportedAtEpochMs)
        assertThat(decoded.kind).isEqualTo("room-browser-local-ai")
        assertThat(decoded.version).isEqualTo(1)
    }

    @Test
    fun `backup defaults round trip with kind and version on the wire`() {
        val manifest = LocalAiBackupManifest(
            host = "http://localhost:11434",
            tuning = LocalAiTuning(),
            models = emptyList(),
            exportedAtEpochMs = 1L
        )
        val encoded = LocalAiBackup.encode(manifest)
        assertThat(encoded).contains("\"kind\":\"room-browser-local-ai\"")
        assertThat(encoded).contains("\"version\":1")
        assertThat(LocalAiBackup.decode(encoded)).isEqualTo(manifest)
    }

    @Test
    fun `backup decode rejects arbitrary json`() {
        assertThat(LocalAiBackup.decode("""{"data":1}""")).isNull()
        assertThat(LocalAiBackup.decode("""[]""")).isNull()
    }

    @Test
    fun `backup decode rejects a wrong kind`() {
        val bad = """{"kind":"other-app-backup","version":1,"host":"http://x:11434",
            "tuning":{},"models":[],"exportedAtEpochMs":1}"""
        assertThat(LocalAiBackup.decode(bad)).isNull()
    }

    @Test
    fun `backup decode rejects an unknown version`() {
        val bad = """{"kind":"room-browser-local-ai","version":2,"host":"http://x:11434",
            "tuning":{},"models":[],"exportedAtEpochMs":1}"""
        assertThat(LocalAiBackup.decode(bad)).isNull()
    }

    @Test
    fun `backup decode rejects corrupt json`() {
        assertThat(LocalAiBackup.decode("{\"kind\":\"room-browser-local-ai\",")).isNull()
        assertThat(LocalAiBackup.decode("not a backup")).isNull()
    }
}
