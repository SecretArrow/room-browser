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
        if (socket.isNullOrBlank() || token.isNullOrBlank()) {
            stopSelf()
            return START_NOT_STICKY
        }
        thread(name = "room-node") {
            NodeRuntime.run(applicationContext, socket, token)
            // The runtime cannot be started twice in one process, so the process
            // that ran it must not outlive it: a later connection then gets a
            // fresh one rather than a half-torn-down V8.
            stopSelf()
            Process.killProcess(Process.myPid())
        }
        return START_NOT_STICKY
    }
}
