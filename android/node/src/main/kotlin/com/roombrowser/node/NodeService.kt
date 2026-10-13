package com.roombrowser.node

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.Process
import kotlin.concurrent.thread

/**
 * The `:node` process's entry point: it starts the runtime and does nothing else.
 *
 * The service exists so the runtime has a process of its own, which is what
 * makes a Node crash survivable and the runtime killable. It is NOT a security
 * boundary — the process shares the app's UID and data directory, so a package
 * that could read the app's files could read them either way.
 */
class NodeService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val socket = intent?.getStringExtra(NodeRuntime.SOCKET_EXTRA)
        val token = intent?.getStringExtra(NodeRuntime.TOKEN_EXTRA)
        // A build with no runtime for this ABI has nothing to serve and nothing
        // to wait for, so it releases the process rather than holding one open
        // while the caller times out and reports the absence.
        if (socket.isNullOrBlank() || token.isNullOrBlank() || !NodeRuntime.available) {
            stopSelf()
            return START_NOT_STICKY
        }
        thread(name = "room-node") {
            // A throw leaves the runtime's state unknown, so the process goes
            // with it exactly as it would if the runtime had exited.
            val owned = runCatching { NodeRuntime.run(applicationContext, socket, token) }
                .getOrElse { true }
            // Only the call that ran the runtime may end this process. A second
            // start command returns without owning it, and killing there would
            // take down the runtime the first one is serving.
            if (!owned) return@thread
            // The runtime cannot be started twice in one process, so the process
            // that ran it must not outlive it: a later connection then gets a
            // fresh one rather than a half-torn-down V8.
            stopSelf()
            Process.killProcess(Process.myPid())
        }
        return START_NOT_STICKY
    }
}
