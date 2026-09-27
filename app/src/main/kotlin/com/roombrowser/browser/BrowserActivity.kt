package com.roombrowser.browser

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.roombrowser.browser.engine.ProfileEngine
import com.roombrowser.browser.ui.BrowserScreen
import com.roombrowser.domain.model.ProfileId
import com.roombrowser.security.BiometricGate
import com.roombrowser.ui.common.RoomBrowserTheme
import kotlinx.coroutines.launch

/**
 * Browser engine activity — runs in the dedicated ':browser' process.
 *
 * CONTRACT (see PROFILE_ISOLATION.md):
 *  1. ProfileEngine.bindProcessToProfile(profileId) is called in onCreate
 *     BEFORE any WebView exists — this selects the per-profile WebView data
 *     directory suffix (cookies/localStorage/IndexedDB/cache/service
 *     workers are therefore physically separated per profile).
 *  2. If the process is already bound to a DIFFERENT profile (should not
 *     normally happen — the switch executor restarts the process), the
 *     activity schedules a proper restart instead of mixing profiles.
 *  3. The activity is recreate-safe (process death, config changes) — the
 *     active profile is re-read from Room (app_state) when the intent extra
 *     is absent.
 */
class BrowserActivity : FragmentActivity() {

    private var boundProfileId: ProfileId? = null
    private var initialUrl: String? = null
    private var pendingSwitch = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Edge-to-edge with runtime insets: the UI applies WindowInsets
        // padding itself, so nothing ever overlaps the 3-button navigation
        // bar (Back / Home / Recents) or the status bar.
        enableEdgeToEdge()

        val fromIntent = intent.getStringExtra(EXTRA_PROFILE_ID)
        var profileIdString = fromIntent
        if (profileIdString == null) {
            // Cold restore path: read the persisted active profile
            val graph = (application as com.roombrowser.RoomBrowserApp).graph
            profileIdString = kotlinx.coroutines.runBlocking {
                graph.appState.activeProfileIdSnapshot()
            }
        }
        if (profileIdString == null) {
            // No profile selected → back to the picker
            startActivity(
                Intent(this, com.roombrowser.main.MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            )
            finish()
            return
        }

        val profileId = ProfileId(profileIdString)
        initialUrl = intent.getStringExtra(EXTRA_INITIAL_URL)
            ?: savedInstanceState?.getString(EXTRA_INITIAL_URL)

        // THE isolation-critical step: bind this process to the profile.
        val bound = ProfileEngine.bindProcessToProfile(profileId)
        if (!bound) {
            // Process already bound to another profile → restart cleanly.
            scheduleSelfRestart(profileId, initialUrl)
            return
        }
        boundProfileId = profileId

        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                return BrowserViewModel(application, profileId) as T
            }
        }
        val viewModel = ViewModelProvider(this, factory)[BrowserViewModel::class.java]
        setContent {
            RoomBrowserTheme(profileAccent = viewModel.profile.colorArgb) {
                BrowserScreen(
                    activity = this,
                    viewModel = viewModel,
                    initialUrl = initialUrl,
                    onSwitchProfile = { targetProfileId -> switchProfile(targetProfileId) }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        initialUrl?.let { outState.putString(EXTRA_INITIAL_URL, it) }
    }

    /**
     * Profile switch through the executor: runs the 7-step security
     * protocol and restarts this process bound to the new profile.
     */
    private fun switchProfile(
        targetProfileId: ProfileId,
        extras: Intent.() -> Unit = {}
    ) {
        if (pendingSwitch) return
        pendingSwitch = true
        val current = boundProfileId ?: return
        val viewModel = ViewModelProvider(this, browserFactory(boundProfileId!!))[BrowserViewModel::class.java]

        val executor = ProfileSwitchExecutor(this)
        executor.switch(
            from = current,
            to = targetProfileId,
            restartIntentExtras = extras,
            host = object : ProfileSwitchExecutor.Host {
                override fun stopNavigation() {
                    viewModel.stopLoading()
                    viewModel.activeWebView?.stopLoading()
                }

                override suspend fun saveTabState() {
                    viewModel.captureThumbnail()
                    viewModel.persistActiveTabNow()
                }

                override fun destroyBrowserContext() {
                    viewModel.activeWebView?.apply {
                        stopLoading()
                        loadUrl("about:blank")
                        destroy()
                    }
                    viewModel.detachWebView()
                }

                override fun flushProfileState() {
                    android.webkit.CookieManager.getInstance().flush()
                }

                override fun releaseProfileResources() {
                    viewModel.clearInMemoryState()
                }

                override fun cleanupPrivateTabs() {
                    ProfileEngine.clearSessionArtifacts(application)
                }

                override val restartActivityClass: Class<*> = BrowserActivity::class.java
            }
        )
    }

    private fun scheduleSelfRestart(profileId: ProfileId, url: String?) {
        val intent = Intent(this, BrowserActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra(EXTRA_PROFILE_ID, profileId.value)
            url?.let { putExtra(EXTRA_INITIAL_URL, it) }
        }
        val pending = android.app.PendingIntent.getActivity(
            this, 4242, intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        val alarm = getSystemService(ALARM_SERVICE) as android.app.AlarmManager
        runCatching {
            alarm.setExactAndAllowWhileIdle(
                android.app.AlarmManager.ELAPSED_REALTIME,
                android.os.SystemClock.elapsedRealtime() + 350,
                pending
            )
        }.onFailure {
            alarm.set(android.app.AlarmManager.ELAPSED_REALTIME, android.os.SystemClock.elapsedRealtime() + 350, pending)
        }
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    /** Biometric gate for locked profiles (called from the UI). */
    fun gateProfile(profileName: String, onUnlocked: () -> Unit, onLocked: () -> Unit) {
        BiometricGate.unlock(this, profileName, onUnlocked, onLocked)
    }

    private fun browserFactory(profileId: ProfileId): ViewModelProvider.Factory =
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                return BrowserViewModel(application, profileId) as T
            }
        }

    companion object {
        const val EXTRA_PROFILE_ID = "com.roombrowser.extra.PROFILE_ID"
        const val EXTRA_INITIAL_URL = "com.roombrowser.extra.INITIAL_URL"
    }
}
