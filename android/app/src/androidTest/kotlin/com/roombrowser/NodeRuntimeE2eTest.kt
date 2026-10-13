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

    @Test
    fun a_script_runs_and_its_value_comes_back() {
        NodeClient.connect(targetContext).use { client ->
            val run = client.run("return { answer: 42 };", profileUserAgent, BUDGET_MS)
            assertTrue("the script did not return: ${run.output}", run.ok)
            assertTrue("the value was not in the reply: ${run.output}", run.output.contains("42"))
        }
    }

    @Test
    fun every_request_a_script_makes_carries_the_profile_user_agent() {
        // A server inside the script itself, so this is hermetic: no network
        // beyond loopback, and what it reports is the header the runtime
        // actually put on the wire rather than the identity it was handed.
        val script = """
            const http = require('http');
            const seen = await new Promise((resolve, reject) => {
              const server = http.createServer((req, res) => {
                res.end('ok');
                resolve(req.headers['user-agent']);
                server.close();
              });
              server.on('error', reject);
              server.listen(0, '127.0.0.1', () => {
                http.get(
                  { host: '127.0.0.1', port: server.address().port, path: '/' },
                  (res) => res.resume()
                );
              });
            });
            return seen;
        """.trimIndent()
        NodeClient.connect(targetContext).use { client ->
            val run = client.run(script, profileUserAgent, BUDGET_MS)
            assertTrue("the request never arrived: ${run.output}", run.ok)
            assertTrue(
                "the request did not carry the profile identity: ${run.output}",
                run.output.contains("Pixel 5")
            )
        }
    }

    @Test
    fun a_script_that_throws_is_reported_rather_than_breaking_the_runtime() {
        NodeClient.connect(targetContext).use { client ->
            val run = client.run("throw new Error('boom');", profileUserAgent, BUDGET_MS)
            assertTrue("a throw was reported as a success: ${run.output}", !run.ok)
            assertTrue("the throw's own words were lost: ${run.output}", run.output.contains("boom"))
        }
    }

    @Test
    fun a_script_that_outlives_its_budget_is_stopped_and_says_so() {
        // The runtime's own timer, not the socket's: the caller is given the
        // number it was working to rather than a connection that went quiet.
        val budget = 2_000L
        NodeClient.connect(targetContext).use { client ->
            val run = client.run("await new Promise(() => {});", profileUserAgent, budget)
            assertTrue("a script that never finished was reported as done", !run.ok)
            assertTrue(
                "the stop was not reported in the runtime's words: ${run.output}",
                run.output.contains("$budget")
            )
        }
    }

    private companion object {
        /** The budget these scripts get; every one of them is meant to finish. */
        const val BUDGET_MS = 20_000L
    }
}
