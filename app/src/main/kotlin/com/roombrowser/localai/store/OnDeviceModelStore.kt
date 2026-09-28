package com.roombrowser.localai.store

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.roombrowser.domain.localai.Gguf
import com.roombrowser.domain.localai.GgufMeta
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream

/** One *.gguf weight file in the on-device model store, as shown by [OnDeviceModelStore.list]. */
data class OnDeviceModel(
    /** File name without the .gguf extension — the model id used everywhere else. */
    val id: String,
    val file: File,
    val sizeBytes: Long,
    /** Header metadata, or null for a corrupt/non-GGUF file — the file still lists;
     *  the engine will surface an honest load error when the user tries to use it. */
    val meta: GgufMeta?,
    val lastModified: Long
)

/**
 * On-device GGUF model store backing the embedded llama.cpp engine (subagent
 * 13-a): the engine is handed plain file paths, this store owns the directory
 * those paths live in.
 *
 * WHY `noBackupFilesDir`: model weights are re-downloadable multi-GB private
 * data — Android's backup agent would try to drag them through cloud backups
 * (and usually fail halfway) if they sat in `filesDir`. `noBackupFilesDir` is
 * the documented escape hatch and keeps models out of every backup rule.
 *
 * Honesty rules:
 *  - importing NEVER validates the content: a wrong file still lands on disk
 *    and simply shows `meta = null`; the engine's own loader produces the real
 *    error message when the user actually tries to load it.
 *  - [importFromUri] throws [IllegalStateException] with a plain-English
 *    message on stream failures (the UI shows it directly); [exportToUri]
 *    returns false instead — an export can silently be retried, a failed
 *    import must be explained.
 */
class OnDeviceModelStore(private val context: Context) {

    /** `<noBackupFilesDir>/on_device_models`, created eagerly in [init]. */
    val modelsDir: File = File(context.noBackupFilesDir, "on_device_models")

    init {
        // mkdirs (not mkdir): noBackupFilesDir itself may not exist yet on a
        // fresh install, and this runs once, before any import/download.
        modelsDir.mkdirs()
    }

    // ------------------------------------------------------------------ listing

    /**
     * All *.gguf files, newest first. Header metadata is parsed per file, per
     * call — no cache, because imports/exports/downloads may all mutate the
     * directory between two list()s. Parsing is header-only, so this is a few
     * KB of I/O per model. Do the call from Dispatchers.IO (or the controller
     * scope) as with every other disk-touching API here.
     */
    fun list(): List<OnDeviceModel> {
        val files = modelsDir.listFiles { f -> f.isFile && f.name.endsWith(".gguf") }
            ?.sortedByDescending { it.lastModified() }
            ?: return emptyList()
        return files.map { file ->
            OnDeviceModel(
                id = file.name.removeSuffix(".gguf"),
                file = file,
                sizeBytes = file.length(),
                meta = runCatching {
                    FileInputStream(file).use { Gguf.parse(it) }
                }.getOrNull(),
                lastModified = file.lastModified()
            )
        }
    }

    /**
     * Resolves a model id to its file, or null when it does not exist. The id
     * is a plain file name — anything resembling a path ("/", "\\", "..") is
     * refused so a malicious id can never escape [modelsDir].
     */
    fun fileFor(id: String): File? {
        if (id.isBlank() || id.contains('/') || id.contains('\\') || id.contains("..")) return null
        val file = File(modelsDir, "$id.gguf")
        return if (file.exists()) file else null
    }

    /** Deletes the model; true when a file was actually removed. */
    fun delete(id: String): Boolean = fileFor(id)?.delete() == true

    // ------------------------------------------------------------------- SAF

    /**
     * Copies a model WEIGHT file picked via SAF into the store and returns the
     * new model id (the display name without .gguf). The name is resolved the
     * same way the agent attachment panel does (OpenableColumns.DISPLAY_NAME,
     * last path segment as fallback, timestamped fallback when the provider
     * reveals nothing).
     *
     * The copy goes through a `.part` sibling first and is renamed into place
     * only when complete, so a killed import never leaves a half-written
     * `.gguf` that would parade as a model in [list]. Content is NOT validated
     * (see class KDoc) — importing a non-GGUF file is allowed and simply lists
     * with `meta = null`.
     */
    suspend fun importFromUri(uri: Uri): String = withContext(Dispatchers.IO) {
        modelsDir.mkdirs()
        val fileName = sanitizeFileName(resolveDisplayName(uri))
        val target = File(modelsDir, fileName)
        val part = File(modelsDir, "$fileName.part")

        val source = context.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("Import failed: the selected file could not be opened.")
        try {
            part.outputStream().use { out ->
                source.use { input -> input.copyTo(out, IMPORT_BUFFER_BYTES) }
            }
        } catch (e: Exception) {
            part.delete()
            throw IllegalStateException(
                "Import failed: ${e.message ?: e.javaClass.simpleName}"
            )
        }
        // Replace an existing model of the same name only AFTER the new bytes
        // are fully on disk (see KDoc: never expose a half-written .gguf).
        target.delete()
        if (!part.renameTo(target)) {
            part.delete()
            throw IllegalStateException("Import failed: could not finalize $fileName on disk.")
        }
        fileName.removeSuffix(".gguf")
    }

    /**
     * Copies a model out through SAF (user-picked target). False when the
     * model id is unknown OR the copy failed for any reason (stream refused,
     * disk full, provider error) — an export is retryable, so a boolean beats
     * an exception here.
     */
    suspend fun exportToUri(id: String, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        val source = fileFor(id) ?: return@withContext false
        runCatching {
            context.contentResolver.openOutputStream(uri, "w")?.use { out ->
                source.inputStream().use { input ->
                    input.copyTo(out, IMPORT_BUFFER_BYTES)
                    out.flush()
                }
            } ?: error("the destination could not be opened for writing")
        }.isSuccess
    }

    // ---------------------------------------------------------------- storage

    /** Total bytes the store occupies: finished models plus in-flight `.part` files. */
    fun storageBytes(): Long =
        modelsDir.listFiles { f -> f.isFile && (f.name.endsWith(".gguf") || f.name.endsWith(".part")) }
            ?.sumOf { it.length() }
            ?: 0L

    // ---------------------------------------------------------------- helpers

    /** DISPLAY_NAME → last path segment → "imported-&lt;millis&gt;.gguf". */
    private fun resolveDisplayName(uri: Uri): String {
        val queried = runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIdx >= 0 && cursor.moveToFirst()) cursor.getString(nameIdx) else null
            }
        }.getOrNull()
        return queried?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: "imported-${System.currentTimeMillis()}.gguf"
    }

    /**
     * Strips anything path-like (a hostile provider can hand out display names
     * containing separators) and guarantees the .gguf extension, so the file
     * lands in [modelsDir] and shows up in [list].
     */
    private fun sanitizeFileName(raw: String): String {
        val cleaned = raw.substringAfterLast('/').substringAfterLast('\\').replace("..", "_")
        val withExt = if (cleaned.endsWith(".gguf", ignoreCase = true)) {
            // Normalize an odd-case extension: the id is always name-minus-".gguf".
            cleaned.dropLast(5) + ".gguf"
        } else {
            "$cleaned.gguf"
        }
        return withExt.ifBlank { "imported-${System.currentTimeMillis()}.gguf" }
    }

    private companion object {
        private const val IMPORT_BUFFER_BYTES = 8 * 1024
    }
}
