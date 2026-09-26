package com.roombrowser.browser

import android.graphics.Bitmap
import android.webkit.WebView
import com.roombrowser.data.db.TabEntity

/** In-memory tab model bound to a WebView slot. */
data class TabSession(
    val entity: TabEntity,
    val webView: WebView?,
    val thumbnail: Bitmap?,
    val desktopMode: Boolean = false
) {
    val id: String get() = entity.id
    val url: String get() = entity.url
    val title: String get() = entity.title.ifBlank { entity.url }
}

/**
 * Tab session manager for ONE profile (the profile bound to this process).
 * Lazy restoration: only the active tab owns a live WebView; background
 * tabs hold persisted state (URL/title) and optional thumbnails.
 */
class TabManager {

    private val sessions = linkedMapOf<String, TabSession>()
    private val thumbnails = HashMap<String, Bitmap>()

    fun restore(entities: List<TabEntity>) {
        entities.forEach { sessions[it.id] = TabSession(it, null, thumbnails[it.id]) }
    }

    fun add(entity: TabEntity, webView: WebView?): TabSession {
        val session = TabSession(entity, webView, thumbnails[entity.id])
        sessions[entity.id] = session
        return session
    }

    fun get(id: String): TabSession? = sessions[id]

    fun all(): List<TabSession> = sessions.values.toList()

    fun updateEntity(entity: TabEntity) {
        sessions[entity.id]?.let { sessions[entity.id] = it.copy(entity = entity) }
            ?: run { sessions[entity.id] = TabSession(entity, null, thumbnails[entity.id]) }
    }

    fun attachWebView(id: String, webView: WebView?) {
        sessions[id]?.let { sessions[id] = it.copy(webView = webView) }
    }

    fun captureThumbnail(id: String, bitmap: Bitmap?) {
        if (bitmap == null) return
        thumbnails[id] = bitmap
        if (thumbnails.size > MAX_THUMBS) {
            val oldest = thumbnails.keys.firstOrNull()
            oldest?.let { thumbnails.remove(it) }
        }
        sessions[id]?.let { sessions[id] = it.copy(thumbnail = bitmap) }
    }

    fun remove(id: String): TabSession? = sessions.remove(id)

    fun setDesktopMode(id: String, enabled: Boolean) {
        sessions[id]?.let { sessions[id] = it.copy(desktopMode = enabled) }
    }

    fun privateTabs(): List<TabSession> = sessions.values.filter { it.entity.isPrivate }

    fun clear() {
        sessions.clear()
        thumbnails.clear()
    }

    companion object {
        const val MAX_THUMBS = 8
    }
}
