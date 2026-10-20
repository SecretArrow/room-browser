package com.roombrowser

import android.content.Context
import android.content.Intent
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import com.roombrowser.data.db.AppDatabase
import com.roombrowser.di.AppGraph
import com.roombrowser.domain.model.ProfileId
import com.roombrowser.domain.model.ProfileSettings
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.ConcurrentHashMap

@RunWith(AndroidJUnit4::class)
class DesktopModeE2eTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device: UiDevice = UiDevice.getInstance(instrumentation)
    private val targetContext: Context = instrumentation.targetContext

    private lateinit var server: MockWebServer

    private val userAgentByPath = ConcurrentHashMap<String, String>()

    private val tag = (System.currentTimeMillis() % 100000).toString()
    private val profileName = "E2EDesktop$tag"
    private val switchTitle = "Request desktop site by default"
    private val desktopMarker = "Windows NT 10.0"

    @Before
    fun setUp() {
        E2eDeterminism.suppressOrganicNetworkWarnings()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = (request.path ?: "").substringBefore('?')
                userAgentByPath[path] = request.getHeader("User-Agent").orEmpty()
                return MockResponse()
                    .setHeader("Content-Type", "text/html; charset=utf-8")
                    .setBody("<!DOCTYPE html><html><body><h1>ROOM-DESKTOP-MODE-E2E</h1></body></html>")
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    private fun launchMainActivity() {
        val intent = targetContext.packageManager.getLaunchIntentForPackage(targetContext.packageName)
            ?: Intent(Intent.ACTION_MAIN).apply {
                setClassName(targetContext.packageName, "com.roombrowser.main.MainActivity")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
        targetContext.startActivity(intent)
    }

    private fun launchEngineAt(url: String, profileId: String) {
        targetContext.startActivity(
            Intent()
                .setClassName(targetContext.packageName, "com.roombrowser.browser.BrowserActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                .putExtra("com.roombrowser.extra.PROFILE_ID", profileId)
                .putExtra("com.roombrowser.extra.INITIAL_URL", url)
        )
    }

    private fun hasText(text: String, timeoutMs: Long): Boolean =
        device.wait(Until.hasObject(By.text(text)), timeoutMs)

    private fun hasDesc(desc: String, timeoutMs: Long): Boolean =
        device.wait(Until.hasObject(By.desc(desc)), timeoutMs)

    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            try {
                Thread.sleep(250)
            } catch (_: InterruptedException) {
            }
        }
        return condition()
    }

    private fun waitGone(text: String, timeoutMs: Long): Boolean =
        waitUntil(timeoutMs) { device.findObjects(By.text(text)).isEmpty() }

    private fun clickCenter(node: UiObject2): Boolean = try {
        val b = node.visibleBounds
        device.executeShellCommand("input tap ${b.centerX()} ${b.centerY()}")
        device.waitForIdle(1_000)
        true
    } catch (_: Exception) {
        false
    }

    private fun clickSmart(node: UiObject2): Boolean {
        var current: UiObject2? = node
        var hops = 0
        while (current != null && hops < 8) {
            val clickable = try {
                current.isClickable
            } catch (_: Exception) {
                false
            }
            if (clickable) {
                val b = runCatching { current.visibleBounds }.getOrNull()
                if (b != null && b.width() > 0) {
                    device.executeShellCommand("input tap ${b.centerX()} ${b.centerY()}")
                    device.waitForIdle(1_000)
                    return true
                }
            }
            current = try {
                current.parent
            } catch (_: Exception) {
                null
            }
            hops++
        }
        return clickCenter(node)
    }

    private fun clickText(text: String, timeoutMs: Long): Boolean {
        val node = device.wait(Until.findObject(By.text(text)), timeoutMs) ?: return false
        return clickSmart(node)
    }

    private fun clickDesc(desc: String, timeoutMs: Long): Boolean {
        val node = device.wait(Until.findObject(By.desc(desc)), timeoutMs) ?: return false
        return clickSmart(node)
    }

    private fun dragUpQuarter() {
        device.swipe(
            device.displayWidth / 2, device.displayHeight * 3 / 4,
            device.displayWidth / 2, device.displayHeight / 4, 100
        )
        device.waitForIdle(600)
        try {
            Thread.sleep(300)
        } catch (_: InterruptedException) {
        }
    }

    private fun engineUiUp(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (hasDesc("Address bar", 400)) return true
            if (hasText("Search or type URL", 400)) return true
            if (hasText("Privacy Dashboard", 400)) return true
            try {
                Thread.sleep(250)
            } catch (_: InterruptedException) {
            }
        }
        return hasDesc("Address bar", 500)
    }

    private fun uiTree(): String {
        // BOUNDED, and the reason is a measured hang: the walk re-reads every
        // node through the accessibility connection of the window it started
        // in, and while that window is being recreated each re-read costs the
        // full cross-process timeout — a crawl, not a crash, that once took a
        // DevTools search test 104 minutes to finish. A dedicated walker with a
        // 4-second join is the ceiling: a live window is walked whole, a dying
        // one is abandoned, and the returned message says which happened. A
        // fresh thread per walk (never a shared pool) means a window that dies
        // mid-walk parks one daemon instead of starving every later probe.
        val walked = java.util.concurrent.atomic.AtomicReference<String?>(null)
        val walker = Thread {
            try {
                walked.set(
                    run {
                        val texts = runCatching {
                            device.findObjects(By.textContains("")).mapNotNull { it.text }.distinct().take(70)
                        }.getOrDefault(emptyList())
                        "TEXTS: $texts"
                    }
                )
            } catch (t: Throwable) {
                walked.set("probe dump failed: $t")
            }
        }
        walker.name = "e2e-tree-walker"
        walker.isDaemon = true
        walker.start()
        walker.join(4_000)
        return walked.get()
            ?: "tree probe timed out — the window was mid-transition, so the "
            "walk was abandoned at 4s rather than crawled"
    }

    private fun clickCreateScrollAware(): Boolean {
        for (i in 1..12) {
            if (clickText("Create Profile", 1_500)) return true
            dragUpQuarter()
        }
        return false
    }

    private fun bootstrapFreshProfile(): Boolean {
        device.pressHome()
        launchMainActivity()
        device.waitForIdle(2_000)
        assertTrue(
            "Profile list or first-run state must appear",
            hasText("Your profiles", 90_000) || hasText("Create Profile", 90_000)
        )
        assertTrue("Create Profile affordance must be reachable", clickCreateScrollAware())
        assertTrue("Create-profile dialog should open", hasText("Cancel", 8_000))
        val field = device.wait(Until.findObject(By.clazz("android.widget.EditText")), 8_000)
        assertTrue("Name text field must be visible", field != null)
        clickCenter(field!!)
        device.executeShellCommand("input text $profileName")
        device.waitForIdle(1_000)
        val cancel = device.findObjects(By.text("Cancel")).minByOrNull { it.visibleBounds.top }
        val confirm = device.findObjects(By.text("Create Profile"))
            .filter { c ->
                cancel != null && kotlin.math.abs(
                    c.visibleBounds.centerY() - cancel.visibleBounds.centerY()
                ) < 200
            }
            .maxByOrNull { c -> c.visibleBounds.centerX() }
        assertTrue("Dialog confirm button must be found", confirm != null)
        clickCenter(confirm!!)
        assertTrue("Create dialog should close after confirm", waitGone("Cancel", 10_000))
        return engineUiUp(40_000)
    }

    private fun openSheetEntry(label: String, verify: () -> Boolean): Boolean {
        for (round in 1..3) {
            if (!hasDesc("Page actions and settings", 1_500)) {
                device.pressBack()
                device.waitForIdle(1_000)
            }
            if (!clickDesc("Page actions and settings", 6_000)) continue
            for (attempt in 1..12) {
                val node = device.wait(Until.findObject(By.desc(label)), 2_000)
                if (node != null) {
                    clickSmart(node)
                    if (verify()) return true
                    runCatching { clickCenter(node) }
                    if (verify()) return true
                }
                dragUpQuarter()
            }
        }
        return false
    }

    private fun openProfileSettings(): Boolean =
        openSheetEntry("Profile settings") {
            device.wait(Until.hasObject(By.textContains("Profile Settings —")), 8_000)
        }

    private fun flipSwitch(title: String, wantOn: Boolean): Boolean {
        val want = "$title switch, ${if (wantOn) "on" else "off"}"
        val from = "$title switch, ${if (wantOn) "off" else "on"}"
        for (i in 1..40) {
            if (hasDesc(want, 500)) return true
            val node = device.wait(Until.findObject(By.desc(from)), 1_500)
            if (node != null) {
                clickSmart(node)
                if (hasDesc(want, 4_000)) return true
                runCatching { clickCenter(node) }
                if (hasDesc(want, 4_000)) return true
            }
            dragUpQuarter()
        }
        return hasDesc(want, 2_000)
    }

    private fun readStoredProfile(): Pair<String, ProfileSettings>? {
        val db = Room.databaseBuilder(targetContext, AppDatabase::class.java, AppDatabase.NAME)
            .allowMainThreadQueries()
            .build()
        try {
            val row = runBlocking {
                db.profileDao().all().firstOrNull { it.name == profileName }
            } ?: return null
            val settings = runCatching {
                Json { ignoreUnknownKeys = true }
                    .decodeFromString(ProfileSettings.serializer(), row.settingsJson)
            }.getOrNull() ?: return null
            return Pair(row.id, settings)
        } finally {
            db.close()
        }
    }

    private fun desktopModeStored(): Boolean = runCatching {
        readStoredProfile()?.second?.desktopModeDefault == true
    }.getOrDefault(false)

    /**
     * The switch is a persisted profile setting and the suite shares one process,
     * so a profile left in desktop mode would send the desktop UA in every later
     * suite's pages. This profile is the test's own, so its default goes back off.
     */
    @After
    fun restoreDesktopMode() {
        val id = runCatching { readStoredProfile()?.first }.getOrNull() ?: return
        runCatching {
            runBlocking<Unit> {
                val graph: AppGraph = (targetContext.applicationContext as RoomBrowserApp).graph
                val current = graph.profileRepo.getProfile(ProfileId(id))?.settings
                    ?: return@runBlocking
                graph.profileManager.updateSettings(
                    ProfileId(id),
                    current.copy(desktopModeDefault = false)
                )
            }
        }
    }

    @Test
    fun desktop_mode_default_sends_the_desktop_user_agent_on_a_new_session() {
        val base = server.url("/").toString().trimEnd('/')

        assertTrue("Engine must come up on a fresh profile", bootstrapFreshProfile())

        val read = readStoredProfile()
        assertTrue(
            "The freshly created profile must be readable from the database\n${uiTree()}",
            read != null
        )
        val stored = read!!
        val profileId = stored.first
        assertTrue(
            "A fresh profile must not default to desktop mode",
            !stored.second.desktopModeDefault
        )

        launchEngineAt("$base/before", profileId)
        assertTrue(
            "The server must receive the pre-toggle request\n${uiTree()}",
            waitUntil(40_000) { userAgentByPath.containsKey("/before") }
        )
        val beforeUa = userAgentByPath["/before"].orEmpty()
        assertTrue(
            "The pre-toggle request must carry the profile's mobile identity (saw '$beforeUa')",
            beforeUa.contains("Android")
        )
        assertTrue(
            "Desktop mode is off, so no Windows identity may be sent (saw '$beforeUa')",
            !beforeUa.contains(desktopMarker)
        )

        assertTrue("Profile settings must open\n${uiTree()}", openProfileSettings())
        assertTrue(
            "The desktop-mode switch must be reachable and off\n${uiTree()}",
            flipSwitch(switchTitle, true)
        )
        assertTrue(
            "The desktop-mode default must reach storage\n${uiTree()}",
            waitUntil(15_000) { desktopModeStored() }
        )

        launchEngineAt("$base/after", profileId)
        assertTrue(
            "The server must receive the post-toggle request\n${uiTree()}",
            waitUntil(40_000) { userAgentByPath.containsKey("/after") }
        )
        val afterUa = userAgentByPath["/after"].orEmpty()
        assertTrue(
            "A session created for a desktop-mode-default profile must send the " +
                "desktop user agent (saw '$afterUa')",
            afterUa.contains(desktopMarker)
        )
        assertTrue(
            "The desktop identity must replace the Android one (saw '$afterUa')",
            !afterUa.contains("Android")
        )
        device.waitForIdle(200)
    }
}
