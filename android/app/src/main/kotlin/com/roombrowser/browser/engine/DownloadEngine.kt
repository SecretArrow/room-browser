package com.roombrowser.browser.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import com.roombrowser.R
import com.roombrowser.data.db.DownloadEntity
import com.roombrowser.data.repo.BrowserRepository
import com.roombrowser.data.repo.DownloadStatus
import com.roombrowser.domain.model.ProfileId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

/**
 * Profile-scoped download engine.
 *
 * Features: queue, pause, resume (HTTP Range), cancel, retry, open, share,
 * delete, duplicate filename handling, notifications, correct scoped
 * storage usage (MediaStore Downloads on API 29+, legacy public dir on 28).
 */
class DownloadEngine(
    private val context: Context,
    private val repo: BrowserRepository,
    private val client: OkHttpClient
) {

    companion object {
        const val CHANNEL_PROGRESS = "downloads_progress"
        const val CHANNEL_DONE = "downloads_done"
        const val MAX_PARALLEL = 2
        private const val NOTIF_TAG = "rb_dl"

        /** RFC 6266-ish filename extraction used by WebView download events. */
        fun guessFileName(url: String, contentDisposition: String?, mimeType: String): String {
            val fromDisposition = contentDisposition?.let { disposition ->
                Regex("filename\\*?=(?:UTF-8''|\"?)([^\";]+)\"?", RegexOption.IGNORE_CASE)
                    .find(disposition)?.groupValues?.get(1)
            }?.let { java.net.URLDecoder.decode(it, "UTF-8") }
            if (!fromDisposition.isNullOrBlank()) return sanitize(fromDisposition)
            val fromUrl = url.substringBefore('?').substringAfterLast('/').let {
                java.net.URLDecoder.decode(it, "UTF-8")
            }
            if (fromUrl.isNotBlank() && fromUrl.contains('.')) return sanitize(fromUrl)
            val ext = when (mimeType.substringBefore(';').lowercase()) {
                "text/html" -> ".html"
                "text/plain" -> ".txt"
                "image/png" -> ".png"
                "image/jpeg" -> ".jpg"
                "image/gif" -> ".gif"
                "image/webp" -> ".webp"
                "application/pdf" -> ".pdf"
                "application/zip" -> ".zip"
                "audio/mpeg" -> ".mp3"
                "video/mp4" -> ".mp4"
                else -> ""
            }
            return "download-${System.currentTimeMillis()}$ext"
        }

        private fun sanitize(name: String): String =
            name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
                .replace(Regex("\\.{2,}"), ".")
                .take(120).ifBlank { "download" }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = mutableMapOf<Long, Job>()
    private val cancelled = mutableSetOf<Long>()

    private val _events = MutableStateFlow<String?>(null)
    val events: StateFlow<String?> = _events

    fun ensureChannels() {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_PROGRESS, "Download progress", NotificationManager.IMPORTANCE_LOW)
            )
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_DONE, "Download finished", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
    }

    fun enqueue(profileId: ProfileId, url: String, suggestedName: String, mime: String, userAgent: String?) {
        scope.launch {
            val entity = DownloadEntity(
                profileId = profileId.value,
                url = url,
                fileName = dedupeName(profileId, suggestedName),
                mimeType = mime.ifBlank { "application/octet-stream" },
                destination = "",
                totalBytes = -1,
                downloadedBytes = 0,
                status = DownloadStatus.QUEUED.name,
                createdAt = System.currentTimeMillis()
            )
            val id = repo.insertDownload(entity)
            pump()
        }
    }

    fun pause(id: Long) {
        running.remove(id)?.cancel()
        scope.launch {
            repo.download(id)?.let {
                if (it.status == DownloadStatus.RUNNING.name || it.status == DownloadStatus.QUEUED.name) {
                    repo.updateDownload(it.copy(status = DownloadStatus.PAUSED.name))
                }
            }
        }
    }

    fun resume(id: Long) {
        scope.launch {
            repo.download(id)?.let {
                if (it.status == DownloadStatus.PAUSED.name || it.status == DownloadStatus.FAILED.name) {
                    repo.updateDownload(it.copy(status = DownloadStatus.QUEUED.name, error = null))
                    pump()
                }
            }
        }
    }

    fun cancel(id: Long) {
        cancelled += id
        running.remove(id)?.cancel()
        scope.launch {
            repo.download(id)?.let {
                repo.updateDownload(it.copy(status = DownloadStatus.CANCELLED.name))
            }
        }
    }

    fun retry(id: Long) = resume(id)

    fun delete(id: Long) {
        cancel(id)
        scope.launch {
            val dl = repo.download(id)
            // Remove file from MediaStore / filesystem when it is app-managed
            runCatching {
                dl?.destination?.takeIf { it.startsWith("file:") }?.let { File(Uri.parse(it).path!!).delete() }
            }
            repo.deleteDownload(id)
        }
    }

    /** Kick the queue after any state change. */
    fun pump() {
        scope.launch {
            val active = repo.activeDownloads()
            val runningCount = active.count { it.status == DownloadStatus.RUNNING.name }
            if (runningCount >= MAX_PARALLEL) return@launch
            active.filter { it.status == DownloadStatus.QUEUED.name }
                .take(MAX_PARALLEL - runningCount)
                .forEach { start(it) }
        }
    }

    private fun start(entity: DownloadEntity) {
        val job = scope.launch {
            repo.updateDownload(entity.copy(status = DownloadStatus.RUNNING.name))
            val partFile = File(context.cacheDir, "downloads/${entity.id}.part").apply {
                parentFile?.mkdirs()
            }
            runCatching {
                val resumeFrom = entity.downloadedBytes
                val request = Request.Builder()
                    .url(entity.url)
                    .apply {
                        if (resumeFrom > 0) header("Range", "bytes=$resumeFrom-")
                    }
                    .build()
                client.newBuilder().followRedirects(true).build().newCall(request).execute().use { response ->
                    if (!response.isSuccessful && response.code != 206) {
                        throw IOException("HTTP ${response.code}")
                    }
                    val total = response.body?.contentLength()?.let { if (it > 0) it + resumeFrom else -1 } ?: -1
                    val body = response.body ?: throw IOException("Empty body")
                    val output = partFile.outputStream().let { out ->
                        if (resumeFrom > 0 && response.code == 206) out else {
                            out.close(); partFile.outputStream() // truncate
                        }
                    }
                    output.use { out ->
                        val input = body.byteStream()
                        val buffer = ByteArray(16 * 1024)
                        var lastNotified = 0L
                        while (true) {
                            if (entity.id in cancelled) throw CancelledException()
                            val read = input.read(buffer)
                            if (read == -1) break
                            out.write(buffer, 0, read)
                            val downloaded = resumeFrom + partFile.length()
                            if (downloaded - lastNotified > 64 * 1024) {
                                lastNotified = downloaded
                                repo.updateDownload(
                                    entity.copy(
                                        downloadedBytes = downloaded,
                                        totalBytes = total,
                                        status = DownloadStatus.RUNNING.name
                                    )
                                )
                                notifyProgress(entity.id, entity.fileName, downloaded, total)
                            }
                        }
                    }
                }
                // Finalize: copy part file into public Downloads storage
                val destination = publish(entity, partFile)
                repo.updateDownload(
                    entity.copy(
                        downloadedBytes = partFile.length(),
                        totalBytes = partFile.length(),
                        status = DownloadStatus.COMPLETED.name,
                        destination = destination,
                        completedAt = System.currentTimeMillis()
                    )
                )
                notifyDone(entity.id, entity.fileName)
            }.onFailure { e ->
                val status = when (e) {
                    is CancelledException -> DownloadStatus.CANCELLED
                    else -> DownloadStatus.PAUSED.takeIf {
                        running[entity.id]?.isCancelled == true && e is kotlinx.coroutines.CancellationException
                    } ?: DownloadStatus.FAILED
                }
                repo.updateDownload(
                    entity.copy(
                        status = status.name,
                        error = if (status == DownloadStatus.FAILED) (e.message ?: "download failed") else null
                    )
                )
                if (status == DownloadStatus.FAILED) notifyFailure(entity.id, entity.fileName, e.message)
            }
            running.remove(entity.id)
            pump()
        }
        running[entity.id] = job
    }

    private class CancelledException : IOException("cancelled")

    /** Duplicate filename handling: append " (n)" before extension. */
    private suspend fun dedupeName(profileId: ProfileId, name: String): String {
        val existing = repo.downloadsFor(profileId).map { it.fileName }.toSet()
        if (name !in existing) return name
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while (true) {
            val candidate = "$base ($i)$ext"
            if (candidate !in existing) return candidate
            i++
        }
    }

    /**
     * Move the finished file into shared Downloads storage.
     * API 29+: MediaStore.Downloads with RELATIVE_PATH Download/<subfolder>.
     * API 28:  legacy public Downloads directory.
     */
    private fun publish(entity: DownloadEntity, partFile: File): String {
        val subfolder = entity.let { "RoomBrowser" }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, entity.fileName)
                put(MediaStore.Downloads.MIME_TYPE, entity.mimeType)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + subfolder)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("MediaStore insert failed")
            resolver.openOutputStream(uri)?.use { out ->
                partFile.inputStream().use { it.copyTo(out) }
            } ?: throw IOException("Could not open output stream")
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            partFile.delete()
            uri.toString()
        } else {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), subfolder)
            if (!dir.exists()) dir.mkdirs()
            val target = File(dir, entity.fileName)
            partFile.copyTo(target, overwrite = true)
            partFile.delete()
            Uri.fromFile(target).toString()
        }
    }

    fun open(id: Long) {
        scope.launch {
            val dl = repo.download(id) ?: return@launch
            if (dl.status != DownloadStatus.COMPLETED.name) return@launch
            val uri = Uri.parse(dl.destination)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, dl.mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { context.startActivity(Intent.createChooser(intent, dl.fileName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }

    fun share(id: Long) {
        scope.launch {
            val dl = repo.download(id) ?: return@launch
            if (dl.status != DownloadStatus.COMPLETED.name) return@launch
            val uri = Uri.parse(dl.destination)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = dl.mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { context.startActivity(Intent.createChooser(intent, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }

    private fun baseNotification(id: Long): NotificationCompat.Builder =
        NotificationCompat.Builder(context, CHANNEL_PROGRESS)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOnlyAlertOnce(true)
            .setOngoing(true)

    private fun notifyProgress(id: Long, name: String, downloaded: Long, total: Long) {
        ensureChannels()
        val builder = baseNotification(id)
            .setContentTitle(name)
            .setProgress(100, progressPercent(downloaded, total), total < 0)
            .addAction(android.R.drawable.ic_media_pause, "Pause", actionIntent(id, "pause"))
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", actionIntent(id, "cancel"))
        post(id, builder.build())
    }

    private fun progressPercent(downloaded: Long, total: Long): Int =
        if (total > 0) ((downloaded * 100) / total).toInt().coerceIn(0, 100) else 0

    private fun notifyDone(id: Long, name: String) {
        val builder = NotificationCompat.Builder(context, CHANNEL_DONE)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(name)
            .setContentText("Download complete")
            .setAutoCancel(true)
            .addAction(0, "Open", actionIntent(id, "open"))
        post(id, builder.build())
    }

    private fun notifyFailure(id: Long, name: String, message: String?) {
        val builder = NotificationCompat.Builder(context, CHANNEL_DONE)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(name)
            .setContentText("Download failed${message?.let { ": $it" } ?: ""}")
            .setAutoCancel(true)
            .addAction(0, "Retry", actionIntent(id, "retry"))
        post(id, builder.build())
    }

    private fun post(id: Long, notification: Notification) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        runCatching { nm.notify(NOTIF_TAG, id.toInt(), notification) }
    }

    private fun actionIntent(id: Long, action: String): PendingIntent = PendingIntent.getBroadcast(
        context,
        (id * 31 + action.hashCode()).toInt(),
        Intent("com.roombrowser.DOWNLOAD_ACTION").apply {
            setPackage(context.packageName)
            putExtra("id", id)
            putExtra("action", action)
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    fun shutdown() {
        scope.cancel()
    }
}
