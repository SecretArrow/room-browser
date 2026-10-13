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
     * Runs the runtime on the calling thread until it exits, and answers whether
     * this call was the one that owned it.
     *
     * `false` means nothing ran here — this build carries no runtime for this
     * ABI, or another thread in this process got there first — and the caller
     * must then leave the process alone, because killing it would take down a
     * runtime that is serving somebody. `node::Start` is a process-wide
     * singleton and cannot be started twice, which is why whoever owned it is
     * also whoever ends the process (see [NodeService]).
     */
    fun run(context: Context, socketName: String, token: String): Boolean {
        if (!available) return false
        if (!started.compareAndSet(false, true)) return false
        try {
            NodeBridge.startNode(argvFor(scriptFile(context), socketName, token))
            return true
        } finally {
            started.set(false)
        }
    }
}
