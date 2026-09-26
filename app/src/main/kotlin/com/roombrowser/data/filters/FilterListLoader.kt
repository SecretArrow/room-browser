package com.roombrowser.data.filters

import android.content.Context
import com.roombrowser.domain.engine.FilterEngine

/**
 * Loads the bundled, offline filter lists (assets/filters/hosts.txt) and
 * builds the FilterEngine. The list ships inside the APK — it never
 * phones home and no URLs are logged.
 */
object FilterListLoader {

    fun load(context: Context): FilterEngine {
        val ads = mutableSetOf<String>()
        val trackers = mutableSetOf<String>()
        val malicious = mutableSetOf<String>()
        runCatching {
            context.assets.open("filters/hosts.txt").bufferedReader().useLines { lines ->
                for (line in lines) {
                    val trimmed = line.trim()
                    if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
                    val parts = trimmed.split("|", limit = 2)
                    if (parts.size != 2) continue
                    val host = parts[1].trim().lowercase()
                    if (host.isEmpty()) continue
                    when (parts[0].trim().lowercase()) {
                        "ad" -> ads += host
                        "tracker" -> trackers += host
                        "malicious" -> malicious += host
                    }
                }
            }
        }
        return FilterEngine(ads, trackers, malicious)
    }
}
