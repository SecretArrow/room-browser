package com.roombrowser.engine.webview

import com.google.common.truth.Truth.assertThat
import com.roombrowser.domain.model.Devices
import org.junit.Test

/**
 * The page-world half of a device profile: claimed RAM, cores and GPU
 * strings, derived from the SAME catalogue device the wire UA is tuned from.
 * The consistency sweep over the whole catalogue is the test that matters --
 * a device whose script values disagree with its own wire UA is the
 * split-identity bug this subsystem exists to keep dead.
 */
class DeviceIdentityScriptTest {

    private val rogPhone = Devices.all.first { it.id == "asus-asusai2501a" }

    @Test
    fun `the script claims the device's ram cores and gpu`() {
        val script = DeviceIdentityScript.scriptFor(
            deviceMemoryGb = rogPhone.deviceMemoryGb,
            hardwareConcurrency = rogPhone.hardwareConcurrency,
            gpuVendor = rogPhone.gpuVendor,
            gpuRenderer = rogPhone.gpuRenderer
        )
        assertThat(script).contains("var MEMORY = ${rogPhone.deviceMemoryGb};")
        assertThat(script).contains("var CORES = ${rogPhone.hardwareConcurrency};")
        assertThat(script).contains("'${rogPhone.gpuVendor}'")
        assertThat(script).contains("'${rogPhone.gpuRenderer}'")
    }

    @Test
    fun `the shims target exactly the three linkage surfaces`() {
        val script = DeviceIdentityScript.scriptFor(8, 8, "Qualcomm", "Adreno (TM) 830")
        assertThat(script).contains("define(Navigator.prototype, 'deviceMemory'")
        assertThat(script).contains("define(Navigator.prototype, 'hardwareConcurrency'")
        assertThat(script).contains("if (pname === 37445) return GPU_VENDOR;")
        assertThat(script).contains("if (pname === 37446) return GPU_RENDERER;")
        // The tell the pre-191 shim carried and this one must never carry
        // again: a hand-built navigator.userAgent / userAgentData. The engine
        // answers those natively from the wire UA now; a synthesized one has
        // a microtask-shaped seam a page can catch.
        assertThat(script).doesNotContain("userAgentData")
        assertThat(script).doesNotContain("getHighEntropyValues")
        assertThat(script).doesNotContain("define(Navigator.prototype, 'userAgent'")
        assertThat(script).doesNotContain("'brands'")
    }

    @Test
    fun `gpu strings with quotes and backslashes survive the splice`() {
        val script = DeviceIdentityScript.scriptFor(4, 4, "O'Brien \\ Mali", "G-57 'test'")
        assertThat(script).contains("'O\\'Brien \\\\ Mali'")
        assertThat(script).contains("'G-57 \\'test\\''")
    }

    @Test
    fun `the script is one self-contained iife`() {
        val script = DeviceIdentityScript.scriptFor(8, 8, "Qualcomm", "Adreno (TM) 830")
        assertThat(script.trim()).startsWith("(function () {")
        assertThat(script.trim()).endsWith("})()")
        // Balanced braces: the wrapper composes on the OUTSIDE, so a broken
        // frame here would silently break every other script on the page.
        val opening = script.count { it == '{' }
        val closing = script.count { it == '}' }
        assertThat(opening).isEqualTo(closing)
    }

    @Test
    fun `every catalogue device gets a script consistent with its own wire ua`() {
        // THE consistency contract, swept over the whole catalogue: the same
        // device the facade tunes the wire UA from must be the device the
        // page world claims, so no device may ship empty identity fields and
        // every script must carry exactly its own values.
        assertThat(Devices.all.isNotEmpty()).isTrue()
        for (device in Devices.all) {
            val script = DeviceIdentityScript.scriptFor(
                deviceMemoryGb = device.deviceMemoryGb,
                hardwareConcurrency = device.hardwareConcurrency,
                gpuVendor = device.gpuVendor,
                gpuRenderer = device.gpuRenderer
            )
            assertThat(script).contains("var MEMORY = ${device.deviceMemoryGb};")
            assertThat(script).contains("var CORES = ${device.hardwareConcurrency};")
            assertThat(script).contains(device.gpuVendor)
            assertThat(script).contains(device.gpuRenderer)
            // The wire half: the device's own UA carries its model code, the
            // one slot the engine tuning keeps from the catalogue. A device
            // whose UA lost the model is a wire/page split by construction.
            assertThat(device.userAgent).contains(device.code)
        }
    }
}
