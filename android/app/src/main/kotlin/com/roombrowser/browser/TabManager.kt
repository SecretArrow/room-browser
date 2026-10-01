package com.roombrowser.browser

import android.graphics.Bitmap
import android.os.Bundle
import android.webkit.WebView
import com.roombrowser.data.db.TabEntity
import java.util.concurrent.ConcurrentHashMap

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

    /**
     * Reverse index over live engines: engine -> (owning tab id, that tab's
     * last known page URL).
     *
     * WHY IT EXISTS: engine callbacks carry the WebView that fired, and both
     * questions asked of them — "which tab owns this engine?" and "which page
     * is THIS engine showing?" — are answered from the firing view.
     * [sessions] cannot answer either from a background thread.
     *
     * WHY CONCURRENT: [pageUrlFor] is read by shouldInterceptRequest, which
     * runs on a WebView BACKGROUND thread for every sub-resource of every
     * engine; [sessions] is main-thread state and iterating it from there
     * could see a resize mid-flight. WebView does not override equals or
     * hashCode, so the keys are engine IDENTITY — exactly the
     * `session.webView === webView` semantics the old loops had.
     *
     * Kept in lockstep with [sessions] through the single [store] funnel. A
     * STALE entry is worse than a missing one: a missing entry falls back to
     * the pre-fix behaviour, a stale one would route an engine's callback to
     * a tab that no longer owns it.
     */
    private val engineIndex = ConcurrentHashMap<WebView, EngineEntry>()

    /** Immutable index value, replaced wholesale and never mutated, so a
     *  reader can never see a tab id and a URL from different tabs. */
    private data class EngineEntry(val id: String, val url: String)

    /**
     * THE only place a session's engine reference is written: updates
     * [sessions] and [engineIndex] together, so the reverse index can never
     * disagree with the map it indexes. Main thread only.
     */
    private fun store(session: TabSession): TabSession {
        val previous = sessions[session.id]?.webView
        val engine = session.webView
        if (previous != null && previous !== engine) engineIndex.remove(previous)
        sessions[session.id] = session
        if (engine != null) engineIndex[engine] = EngineEntry(session.id, session.url)
        return session
    }

    fun restore(entities: List<TabEntity>) {
        entities.forEach { store(TabSession(it, null, thumbnails[it.id])) }
    }

    fun add(entity: TabEntity, webView: WebView?): TabSession =
        store(TabSession(entity, webView, thumbnails[entity.id]))

    /**
     * The session for this tab, created on demand and refreshed from the
     * latest entity — keeps any live engine/thumbnail the session already
     * holds. Called on every selectTab so "open tab ⇒ session" always holds.
     */
    fun ensureSession(entity: TabEntity): TabSession {
        val existing = sessions[entity.id]
        if (existing != null) return store(existing.copy(entity = entity))
        return store(TabSession(entity, null, thumbnails[entity.id]))
    }

    fun get(id: String): TabSession? = sessions[id]

    fun all(): List<TabSession> = sessions.values.toList()

    fun updateEntity(entity: TabEntity) {
        val existing = sessions[entity.id]
        if (existing != null) {
            store(existing.copy(entity = entity))
        } else {
            store(TabSession(entity, null, thumbnails[entity.id]))
        }
    }

    fun attachWebView(id: String, webView: WebView?) {
        sessions[id]?.let {
            store(it.copy(webView = webView, lastUsedAt = android.os.SystemClock.elapsedRealtime()))
        }
    }

    /** Drops the engine reference from whichever session holds [webView]
     *  (per-tab engines are owned by exactly ONE session). */
    fun detachWebView(webView: WebView?) {
        if (webView == null) return
        sessions.values.filter { it.webView === webView }
            .forEach { store(it.copy(webView = null)) }
    }

    /** Reverse of [attachWebView]: the tab OWNING [webView] (per-tab engines
     *  belong to exactly ONE session), or null when no session holds it —
     *  a destroyed/never-tracked engine. Used to route an engine's callbacks
     *  to its own tab instead of whichever tab happens to be active.
     *  Thread-safe: reads the concurrent [engineIndex] only. */
    fun idFor(webView: WebView?): String? {
        if (webView == null) return null
        return engineIndex[webView]?.id
    }

    /** Page URL of the tab OWNING [webView], or null when no session holds it
     *  (destroyed or mid-teardown engine). Safe from ANY thread: this is the
     *  one lookup shouldInterceptRequest may perform, and it must never touch
     *  [sessions].
     *
     *  A null answer is the caller's cue to fall back — it is NOT a licence
     *  to judge the engine against some other tab's page. */
    fun pageUrlFor(webView: WebView?): String? {
        if (webView == null) return null
        return engineIndex[webView]?.url
    }

    /** Records [url] as this tab's current page URL, engine untouched. Called
     *  from the navigation funnel so a BACKGROUND engine's page host — the one
     *  shouldInterceptRequest judges its sub-resources against — is as fresh
     *  as the row being persisted, rather than as stale as the last switch to
     *  that tab. Main thread only; no-op for a tab with no session (a late
     *  callback after the tab closed must not resurrect an index entry). */
    fun setPageUrl(id: String, url: String) {
        val session = sessions[id] ?: return
        if (session.url == url) return
        store(session.copy(entity = session.entity.copy(url = url)))
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
        sessions[id]?.let { store(it.copy(thumbnail = bitmap)) }
    }

    fun remove(id: String): TabSession? {
        val removed = sessions.remove(id) ?: return null
        removed.webView?.let { engineIndex.remove(it) }
        return removed
    }

    fun setDesktopMode(id: String, enabled: Boolean) {
        sessions[id]?.let { store(it.copy(desktopMode = enabled)) }
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
        // The engine index is a view of `sessions`; it dies with them.
        engineIndex.clear()
    }

    companion object {
        const val MAX_THUMBS = 8
        const val MAX_SAVED_STATES = 8
    }
}
