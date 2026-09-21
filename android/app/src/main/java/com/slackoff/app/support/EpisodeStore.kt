package com.slackoff.app.support

import android.content.Context
import android.content.SharedPreferences

object EpisodeStore {
    private const val PREFS_NAME = "com.slackoff.episode-selection"
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun selectedEpisode(slug: String): Int? {
        val p = prefs ?: return null
        val value = p.getInt(slug, -1)
        return if (value >= 0) value else null
    }

    fun remember(episode: Int, slug: String) {
        if (slug.isEmpty()) return
        val p = prefs ?: return
        if (p.getInt(slug, -1) == episode) return
        p.edit().putInt(slug, episode).apply()
    }
}