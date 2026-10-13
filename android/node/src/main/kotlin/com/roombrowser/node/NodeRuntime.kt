package com.roombrowser.node

import android.content.Context
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The runtime side of the bridge — what runs in the `:node` process.
 *
 * The entry script is copied out of the APK before it runs: an archive is not a
 * file system, and the runtime resolves its entry point by path. The copy is
 * rewritten whenever the packaged one differs, so a new APK never runs the
 * previous version's script.
 */
object NodeRuntime {

    const val SOCKET_EXTRA = "com.roombrowser.node.socket"
    const val TOKEN_EXTRA = "com.roombrowser.node.token"

    /** The runtime exited because this build carries none for this ABI. */
    const val NOT_AVAILABLE = -1

    /** A runtime already runs in this process; the runtime is a process-wide singleton. */
    const val ALREADY_RUNNING = -2

    private const val SCRIPT_NAME = "bridge.js"

    /** False on an ABI this APK carries no libnode for. */
    val available: Boolean get() = NodeBridge.available

    private val started = AtomicBoolean(false)

    /** argv as the runtime reads it: argv[0] is the program name, not a path. */
    internal fun argvFor(script: File, socketName: String, token: String): Array<String> =
        arrayOf("node", script.absolutePath, socketName, token)

    internal fun scriptFile(context: Context): File {
        val target = File(File(context.filesDir, "node"), SCRIPT_NAME)
        target.parentFile?.mkdirs()
        val packaged = context.assets.open("node/$SCRIPT_NAME").use { it.readBytes() }
        if (!target.exists() || !target.readBytes().contentEquals(packaged)) {
            target.writeBytes(packaged)
        }
        return target
    }

    /**
     * Runs the runtime on the calling thread until it exits.
     *
     * The flag guards two runtimes at once, not two in a row: `node::Start` is a
     * process-wide singleton that cannot be started a second time in the same
     * process, which is why the service that calls this kills its process when
     * this returns (see [NodeService]).
     */
    fun run(context: Context, socketName: String, token: String): Int {
        if (!available) return NOT_AVAILABLE
        if (!started.compareAndSet(false, true)) return ALREADY_RUNNING
        try {
            return NodeBridge.startNode(argvFor(scriptFile(context), socketName, token))
        } finally {
            started.set(false)
        }
    }
}
