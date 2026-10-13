package com.roombrowser.node

import android.content.Context
import android.content.Intent
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Process
import java.io.BufferedReader
import java.io.Closeable
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.JsonObject

/**
 * The app side of the bridge: it starts the runtime's process, connects to it
 * and exchanges one line of JSON per command.
 *
 * The runtime LISTENS and this side connects, so readiness is a completed
 * connection rather than a guess. The socket lives in the app's process, so it
 * dies with that process — which is also what stops the runtime, since the
 * runtime exits when its only connection closes.
 */
class NodeClient private constructor(
    private val socket: LocalSocket,
    private val token: String
) : Closeable {

    private val out = OutputStreamWriter(socket.outputStream, Charsets.UTF_8)
    private val input = BufferedReader(InputStreamReader(socket.inputStream, Charsets.UTF_8))
    private var nextId = 0

    /**
     * [userAgent] travels with every command rather than being fixed at
     * start-up, because the profile that owns this runtime can change under it
     * and the runtime must never fall back to an identity of its own.
     */
    @Synchronized
    fun status(userAgent: String?): NodeStatus = call("status", userAgent, NodeProtocol::statusOf)

    @Synchronized
    private fun <T> call(cmd: String, userAgent: String?, read: (JsonObject) -> T): T {
        val id = ++nextId
        out.write(NodeProtocol.request(id, cmd, token, userAgent))
        out.write("\n")
        out.flush()
        val line = input.readLine() ?: throw IOException("the Node runtime closed the connection")
        return read(NodeProtocol.result(line, id))
    }

    @Synchronized
    override fun close() {
        runCatching { socket.close() }
    }

    companion object {

        private const val CONNECT_TIMEOUT_MS = 20_000L
        private const val ATTEMPT_TIMEOUT_MS = 250
        private const val RETRY_PAUSE_MS = 50L

        private val counter = AtomicInteger()
        private val random = SecureRandom()

        /**
         * Starts the runtime if it is not running and connects to it.
         *
         * Blocking, and bounded: the runtime is a process start plus a Node
         * boot, so the caller waits on a worker thread and gets an exception
         * naming the wait rather than an unbounded hang.
         */
        fun connect(context: Context, timeoutMillis: Long = CONNECT_TIMEOUT_MS): NodeClient {
            val name = "roombrowser-node-${Process.myPid()}-${counter.incrementAndGet()}"
            val token = ByteArray(24).also(random::nextBytes).joinToString("") { "%02x".format(it) }
            val requested = runCatching {
                context.startService(
                    Intent(context, NodeService::class.java)
                        .putExtra(NodeRuntime.SOCKET_EXTRA, name)
                        .putExtra(NodeRuntime.TOKEN_EXTRA, token)
                )
            }.isSuccess
            if (!requested) {
                throw IOException("the system would not start the Node runtime's process")
            }
            val deadline = System.currentTimeMillis() + timeoutMillis
            while (true) {
                val socket = LocalSocket()
                try {
                    socket.connect(
                        LocalSocketAddress(name, LocalSocketAddress.Namespace.ABSTRACT),
                        ATTEMPT_TIMEOUT_MS
                    )
                    return NodeClient(socket, token)
                } catch (err: IOException) {
                    runCatching { socket.close() }
                    if (System.currentTimeMillis() >= deadline) {
                        throw IOException(
                            "the Node runtime did not start within ${timeoutMillis / 1000}s — " +
                                "this build carries one for arm64-v8a, armeabi-v7a and x86_64"
                        )
                    }
                    Thread.sleep(RETRY_PAUSE_MS)
                }
            }
        }
    }
}
