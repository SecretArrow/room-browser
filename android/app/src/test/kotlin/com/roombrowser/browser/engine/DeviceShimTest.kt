package com.roombrowser.browser.engine

import com.google.common.truth.Truth.assertThat
import com.roombrowser.domain.model.Device
import com.roombrowser.domain.model.Devices
import org.junit.Test

/**
 * The shim's contract. The JavaScript itself is exercised against a browser
 * -shaped context outside the JVM; what a unit test can hold down is the
 * splicing: every placeholder filled, nothing left behind, and a value that
 * cannot break out of the string literal it is written into.
 */
class DeviceShimTest {

    private val device = Devices.all.first { it.formFactor == "phone" }
    private val tablet = Devices.all.first { it.formFactor == "tablet" }

    @Test
    fun `script fills every placeholder`() {
        val js = DeviceShim.scriptFor(device)
        // A leftover "__FOO__" would be a syntax error in the page, not a
        // silent no-op, so this is the check that matters most.
        assertThat(js).doesNotContain("__")
        assertThat(js).contains(device.userAgent)
        assertThat(js).contains("'${device.code}'")
        assertThat(js).contains(device.chromeVersion.substringBefore('.'))
        assertThat(js).contains("var MEMORY = ${device.deviceMemoryGb};")
        assertThat(js).contains("var CORES = ${device.hardwareConcurrency};")
        assertThat(js).contains(device.gpuVendor)
        assertThat(js).contains(device.gpuRenderer)
    }

    @Test
    fun `a tablet is not announced as a phone`() {
        // Chrome omits " Mobile" on a tablet, and so does the client-hint
        // form factor. Claiming otherwise on a real tablet is a tell.
        assertThat(DeviceShim.scriptFor(tablet)).contains("var MOBILE = false;")
        assertThat(DeviceShim.scriptFor(tablet)).contains("var FORM = 'Tablet';")
        assertThat(DeviceShim.scriptFor(device)).contains("var MOBILE = true;")
        assertThat(DeviceShim.scriptFor(device)).contains("var FORM = 'Mobile';")
    }

    @Test
    fun `the script never touches the screen`() {
        // The policy in one assertion: the page really is laid out on this
        // phone's screen, so a claimed viewport would render it wrong *and*
        // be the cheapest spoofing signal there is. See SECURITY.md.
        val js = DeviceShim.scriptFor(device)
        assertThat(js).doesNotContain("innerWidth")
        assertThat(js).doesNotContain("innerHeight")
        assertThat(js).doesNotContain("devicePixelRatio")
        assertThat(js).doesNotContain("screen.width")
        assertThat(js).doesNotContain("screen.height")
        assertThat(js).doesNotContain("availWidth")
    }

    @Test
    fun `different devices produce different scripts`() {
        val a = Devices.all[0]
        val b = Devices.all[1]
        assertThat(DeviceShim.scriptFor(a)).isNotEqualTo(DeviceShim.scriptFor(b))
        // Same device, same script — a fingerprint that moved between loads
        // would be worse than one that is simply unusual.
        assertThat(DeviceShim.scriptFor(a)).isEqualTo(DeviceShim.scriptFor(a))
    }

    @Test
    fun `a hostile value cannot break out of its string literal`() {
        val hostile = Device(
            id = "x", brand = "X", model = "M",
            code = "A');\nalert(1);//",
            year = 2024, androidVersion = "14", chromeVersion = "131.0.0.0",
            buildId = "B", userAgent = "UA'; alert('pwned'); //",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "V\\'", gpuRenderer = "R ", formFactor = "phone"
        )
        val js = DeviceShim.scriptFor(hostile)
        // The quote inside a value is escaped, so the payload never closes
        // the literal it sits in.
        assertThat(js).contains("\\'")
        assertThat(js).doesNotContain("alert('pwned')")
        // A raw newline would end the statement early; a U+2028 would too,
        // since JavaScript treats it as a line terminator.
        assertThat(js.lines().size).isEqualTo(DeviceShim.scriptFor(device).lines().size)
        assertThat(js).doesNotContain(" ")
        assertThat(js).contains("\\u2028")
    }

    @Test
    fun `every catalogued device produces a script`() {
        val expectedLines = DeviceShim.scriptFor(device).lines().size
        for (d in Devices.all) {
            val js = DeviceShim.scriptFor(d)
            assertThat(js).doesNotContain("__")
            assertThat(js).contains(d.userAgent)
            assertThat(js.trimStart()).startsWith("(function () {")
            assertThat(js.trimEnd()).endsWith("})();")
            // One line per statement: nothing in a device field leaked a
            // newline into the script.
            assertThat(js.lines().size).isEqualTo(expectedLines)
        }
    }
}
