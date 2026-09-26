package com.roombrowser.main

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModelProvider
import com.roombrowser.browser.BrowserActivity
import com.roombrowser.security.BiometricGate
import com.roombrowser.ui.common.RoomBrowserTheme
import com.roombrowser.main.ui.MainScreen

/**
 * Launcher activity (default process): profile selector, first-run flow,
 * profile CRUD, external link routing and share-sheet receiving.
 *
 * This process NEVER hosts a WebView — that is exclusive to the ':browser'
 * process so per-profile storage isolation is guaranteed.
 */
class MainActivity : FragmentActivity() {

    private lateinit var viewModel: MainViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel = ViewModelProvider(this)[MainViewModel::class.java]

        // Route external links (VIEW intent from other apps) through the
        // "Open with profile" chooser — never silently open the wrong profile.
        intent?.dataString?.let { handleExternalUrl(it) }
        intent?.takeIf { it.action == Intent.ACTION_SEND }
            ?.getStringExtra(Intent.EXTRA_TEXT)
            ?.let { handleExternalUrl(it) }

        setContent {
            RoomBrowserTheme {
                MainScreen(
                    activity = this,
                    viewModel = viewModel,
                    onOpenProfile = { profileId, url ->
                        openBrowser(profileId, url)
                    }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.dataString?.let { handleExternalUrl(it) }
        intent.takeIf { it.action == Intent.ACTION_SEND }
            ?.getStringExtra(Intent.EXTRA_TEXT)
            ?.let { handleExternalUrl(it) }
    }

    private fun handleExternalUrl(raw: String) {
        val url = raw.trim()
        if (url.startsWith("http://") || url.startsWith("https://")) {
            viewModel.submitExternalUrl(url)
        }
    }

    /** Launch the browser process for the selected profile. */
    private fun openBrowser(profileId: String, url: String?) {
        val intent = Intent(this, com.roombrowser.browser.BrowserActivity::class.java).apply {
            putExtra(BrowserActivity.EXTRA_PROFILE_ID, profileId)
            url?.let { putExtra(BrowserActivity.EXTRA_INITIAL_URL, it) }
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(intent)
    }

    /** Biometric gate for locked profiles (called from the UI). */
    fun gateProfile(profileName: String, onUnlocked: () -> Unit) {
        BiometricGate.unlock(this, profileName, onUnlocked, onUnlocked)
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}
