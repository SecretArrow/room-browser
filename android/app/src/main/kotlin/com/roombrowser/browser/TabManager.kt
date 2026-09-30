package com.roombrowser.browser

import android.graphics.Bitmap
import android.os.Bundle
import android.webkit.WebView
import com.roombrowser.data.db.TabEntity

/** In-memory tab model bound to a WebView slot. */
data class TabSession(
    val entity: TabEntity,
    val webView: WebView?,
    val thumbnail: Bitmap?,
    val desktopMode: Boolean = false,
    /** Monotonic recency stamp — drives LRU eviction of live WebViews. */
    val lastUsedAt: Long = 0L
) {
    val id: String get() = entity.id
    val url: String get() = entity.url
    val title: String get() = entity.title.ifBlank { entity.url }
}

/**
 * Tab session manager for ONE profile (the profile bound to this process).
 * Lazy restoration: only the active tab owns a live WebView; background
 * tabs hold persisted state (URL/title), optional thumbnails and the
 * back/forward bundle captured when their engine was evicted or closed.
 *
 * Every open tab has a SESSION by construction (see [ensureSession]) — a
 * tab whose live engine is not tracked here is invisible to the LRU
 * budget, never gets destroyed on close, and loses its page on reselect.
 */
class TabManager {

    private val sessions = linkedMapOf<String, TabSession>()
    private val thumbnails = HashMap<String, Bitmap>()

    /**
     * Back/forward state bundles saved right before an engine is destroyed
     * (LRU eviction or tab close). Keyed by tab id, so a bundle can never
     * be restored into a DIFFERENT tab; survives [remove] so a closed tab
     * that is reopened gets its history back; dies with [clear] (profile
     * switch — another profile's tabs must never receive this history).
     */
    private val savedStates = HashMap<String, Bundle>()

    fun restore(entities: List<TabEntity>) {
        entities.forEach { sessions[it.id] = TabSession(it, null, thumbnails[it.id]) }
    }

    fun add(entity: TabEntity, webView: WebView?): TabSession {
        val session = TabSession(entity, webView, thumbnails[entity.id])
        sessions[entity.id] = session
        return session
    }

    /**
     * The session for this tab, created on demand and refreshed from the
     * latest entity — keeps any live engine/thumbnail the session already
     * holds. Called on every selectTab so "open tab ⇒ session" always holds.
     */
    fun ensureSession(entity: TabEntity): TabSession {
        val existing = sessions[entity.id]
        if (existing != null) {
            val refreshed = existing.copy(entity = entity)
            sessions[entity.id] = refreshed
            return refreshed
        }
        val created = TabSession(entity, null, thumbnails[entity.id])
        sessions[entity.id] = created
        return created
    }

    fun get(id: String): TabSession? = sessions[id]

    fun all(): List<TabSession> = sessions.values.toList()

    fun updateEntity(entity: TabEntity) {
        sessions[entity.id]?.let { sessions[entity.id] = it.copy(entity = entity) }
            ?: run { sessions[entity.id] = TabSession(entity, null, thumbnails[entity.id]) }
    }

    fun attachWebView(id: String, webView: WebView?) {
        sessions[id]?.let { sessions[id] = it.copy(webView = webView, lastUsedAt = android.os.SystemClock.elapsedRealtime()) }
    }

    /** Drops the engine reference from whichever session holds [webView]
     *  (per-tab engines are owned by exactly ONE session). */
    fun detachWebView(webView: WebView?) {
        if (webView == null) return
        for ((id, session) in sessions) {
            if (session.webView === webView) {
                sessions[id] = session.copy(webView = null)
            }
        }
    }

    /** Stores the engine's back/forward bundle under [id] (pre-destroy). */
    fun saveEngineState(id: String, bundle: Bundle) {
        if (bundle.isEmpty) return
        savedStates[id] = bundle
        if (savedStates.size > MAX_SAVED_STATES) {
            savedStates.keys.firstOrNull()?.let { savedStates.remove(it) }
        }
    }

    /** The saved engine state for [id] (null when this tab has none). */
    fun engineState(id: String): Bundle? = savedStates[id]

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

    /** Sessions that currently hold a LIVE WebView (the active one included). */
    fun liveWebViewSessions(): List<TabSession> =
        sessions.values.filter { it.webView != null }

    /**
     * LRU eviction candidates: background sessions (never [keepId]) holding
     * live WebViews, OLDEST first. The caller destroys as many as needed to
     * stay under the live-WebView budget — evicted tabs gracefully fall back
     * to lazy re-creation (entity + thumbnail + saved state bundle survive).
     */
    fun lruVictims(keepId: String?): List<TabSession> =
        sessions.values
            .filter { it.webView != null && it.id != keepId }
            .sortedBy { it.lastUsedAt }

    fun clear() {
        sessions.clear()
        thumbnails.clear()
        // Engine-state bundles die with the profile context: the next
        // profile's tabs must never receive this profile's history.
        savedStates.clear()
    }

    companion object {
        const val MAX_THUMBS = 8
        const val MAX_SAVED_STATES = 8
    }
}
