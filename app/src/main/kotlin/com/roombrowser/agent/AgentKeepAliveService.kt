package com.roombrowser.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.roombrowser.browser.BrowserActivity

/**
 * Foreground (dataSync) service that keeps the ':browser' process alive and
 * the CPU awake while an AI-agent turn is executing, so agent tasks keep
 * running when the user leaves the app or turns the screen off.
 *
 * The service is intentionally tiny: the agent loop itself lives in
 * [BrowserAgentController]; this class only holds a partial wake lock and
 * mirrors the turn's progress into an ongoing notification (with a Stop
 * action that cancels the current turn).
 *
 * Lifecycle (driven via [AgentForeground]):
 *  - begin(context)      → startForegroundService while the app is foreground
 *                          (send() is a user action, so this always holds)
 *  - status(text)        → update the ongoing notification
 *  - finish()            → release the wake lock and stop
 */
class AgentKeepAliveService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var lastStatus: String = "Working…"

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        AgentForeground.instance = this
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                AgentForeground.stopCurrentTurn?.invoke()
                requestStop()
            }
            else -> {
                intent?.getStringExtra(EXTRA_STATUS)?.let { lastStatus = it }
                goForeground(lastStatus)
            }
        }
        return START_NOT_STICKY
    }

    /** Called (same process) by [AgentForeground.status]. */
    fun updateStatus(status: String?) {
        if (status.isNullOrBlank()) return
        lastStatus = status
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        try {
            nm.notify(NOTIFICATION_ID, buildNotification(lastStatus))
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS not granted — the service still runs.
        }
    }

    /** Called (same process) by [AgentForeground.finish]. */
    fun requestStop() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
        if (AgentForeground.instance === this) AgentForeground.instance = null
        super.onDestroy()
    }

    // ------------------------------------------------------------ internals

    private fun goForeground(status: String) {
        acquireWakeLock()
        val type = if (Build.VERSION.SDK_INT >= 29) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(status), type)
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        runCatching {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "roombrowser:agent").apply {
                setReferenceCounted(false)
                acquire(WAKE_TIMEOUT_MS)
            }
        }
    }

    private fun buildNotification(status: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, BrowserActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, AgentKeepAliveService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("Room Agent")
            .setContentText(status)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .build()
    }

    private fun createChannel() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Agent progress", NotificationManager.IMPORTANCE_LOW)
        )
    }

    companion object {
        const val ACTION_START = "com.roombrowser.agent.START"
        const val ACTION_STOP = "com.roombrowser.agent.STOP"
        const val EXTRA_STATUS = "status"
        private const val CHANNEL_ID = "agent_progress"
        private const val NOTIFICATION_ID = 402
        private const val WAKE_TIMEOUT_MS = 30 * 60 * 1000L
    }
}

/**
 * Process-local bus between [BrowserAgentController] and the keep-alive
 * service — both live in the ':browser' process, so direct references are
 * safe and no IPC is needed.
 */
object AgentForeground {

    /** Set while a turn runs — the notification's Stop action invokes it. */
    @Volatile
    var stopCurrentTurn: (() -> Unit)? = null

    @Volatile
    internal var instance: AgentKeepAliveService? = null

    /** Starts the keep-alive service (must be called from the foreground). */
    fun begin(context: Context) {
        runCatching {
            ContextCompat.startForegroundService(
                context,
                Intent(context, AgentKeepAliveService::class.java)
                    .setAction(AgentKeepAliveService.ACTION_START)
            )
        }
    }

    /** Mirrors a progress line into the ongoing notification. */
    fun status(text: String?) {
        instance?.updateStatus(text)
    }

    /** Stops the service when the turn is over. */
    fun finish() {
        instance?.requestStop()
    }
}
