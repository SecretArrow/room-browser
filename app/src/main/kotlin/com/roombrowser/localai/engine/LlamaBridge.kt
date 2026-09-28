package com.roombrowser.localai.engine

/** JNI bridge to the vendored llama.cpp (app/src/main/cpp). All external calls are BLOCKING —
 *  LlamaEngine owns threading and cancellation. Keep this class internal: only LlamaEngine talks to it. */
internal object LlamaBridge {
    val libraryLoaded: Boolean = runCatching { System.loadLibrary("llama_jni") }.isSuccess

    /** llama.cpp version string, e.g. "b4364". */
    external fun nativeVersion(): String

    /** Loads a GGUF model file; returns a handle (>0) or 0 on failure. contextTokens 0 = model default. */
    external fun nativeLoad(path: String, contextTokens: Int, threads: Int): Long

    /** Frees the model+context for a handle. Safe to call twice. */
    external fun nativeFree(handle: Long)

    /** Runs a raw completion. Returns null on ERROR (invalid handle/decode failure), "" when
     *  cancelled before the first token, otherwise the generated continuation (possibly partial after cancel). */
    external fun nativeGenerate(handle: Long, prompt: String, maxTokens: Int, temperature: Float, topP: Float): String?

    /** Aborts an in-flight nativeGenerate for this handle as soon as possible. */
    external fun nativeCancel(handle: Long)

    /** Applies the model's built-in chat template; null when the model has none (caller falls back to plain text). */
    external fun nativeApplyChatTemplate(handle: Long, roles: Array<String>, contents: Array<String>): String?
}
