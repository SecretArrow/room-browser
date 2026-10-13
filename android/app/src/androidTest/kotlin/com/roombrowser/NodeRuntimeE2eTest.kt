package com.roombrowser

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.roombrowser.node.NodeClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The embedded Node runtime, in the APK that ships it: the runtime's process is
 * started, the runtime boots, and it reports the identity it was handed.
 *
 * This is the only proof that libnode.so loads on this device at all. The
 * packaged release ships arm64-v8a, armeabi-v7a and x86_64 (this emulator is
 * x86_64) and no 32-bit x86, so on that ABI the runtime answers "unavailable"
 * rather than failing — which is why nothing here asserts on a device it was
 * never built for.
 */
@RunWith(AndroidJUnit4::class)
class NodeRuntimeE2eTest {

    private val targetContext = InstrumentationRegistry.getInstrumentation().targetContext

    private val profileUserAgent =
        "Mozilla/5.0 (Linux; Android 11; Pixel 5) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    @Test
    fun the_runtime_boots_in_this_apk_and_echoes_the_profile_identity() {
        NodeClient.connect(targetContext).use { client ->
            val status = client.status(profileUserAgent)
            assertTrue("the runtime reported ${status.node}", status.node.startsWith("v18."))
            // nodejs-mobile is built with --dest-os=android, so the platform is
            // its own name rather than the "linux" the same source reports on a
            // desktop host.
            assertEquals("android", status.platform)
            assertEquals(profileUserAgent, status.userAgent)
            assertTrue("the user-agent rule was not installed", status.userAgentEnforced)
        }
    }
}
