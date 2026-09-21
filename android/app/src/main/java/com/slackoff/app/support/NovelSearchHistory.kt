package com.slackoff.app.support

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 搜索历史（最近 10 条），与 iOS 端同一个 key 语义。 */
object NovelSearchHistory {
    private const val PREFS_NAME = "com.slackoff.novel-search-history"
    private const val KEY = "terms"
    private const val LIMIT = 10

    private var prefs: SharedPreferences? = null
    private val _terms = MutableStateFlow<List<String>>(emptyList())
    val terms: StateFlow<List<String>> = _terms.asStateFlow()

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs = p
        // 不用 StringSet：它是无序集合，历史记录的先后顺序会丢
        _terms.value = p.getString(KEY, null)?.split("\u0001")?.filter { it.isNotEmpty() } ?: emptyList()
    }

    fun remember(term: String) {
        val trimmed = term.trim()
        if (trimmed.isEmpty()) return
        // 最新的排在最前
        val updated = (listOf(trimmed) + _terms.value.filterNot { it == trimmed }).take(LIMIT)
        _terms.value = updated
        prefs?.edit()?.putString(KEY, updated.joinToString("\u0001"))?.apply()
    }

    fun clear() {
        _terms.value = emptyList()
        prefs?.edit()?.remove(KEY)?.apply()
    }
}
