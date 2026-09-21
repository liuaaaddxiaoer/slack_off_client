package com.slackoff.app.support

import android.content.Context
import android.content.SharedPreferences
import com.slackoff.app.model.NovelChapterItem
import com.slackoff.app.model.NovelChapterList
import com.slackoff.app.model.novelKey
import com.slackoff.app.network.ApiResult
import com.slackoff.app.network.NovelService
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class NovelCacheRecord(
    val source: String,
    val bookId: String,
    val title: String,
    val author: String? = null,
    val category: String? = null,
    val cachedCount: Int = 0,
    val totalCount: Int = 0,
    val isPaused: Boolean = false,
    val failedCount: Int = 0,
    val updatedAt: Long = 0,
) {
    val key: String get() = novelKey(source, bookId)
    val isComplete: Boolean get() = totalCount > 0 && cachedCount >= totalCount
}

data class NovelCacheDownloadState(
    val cachedCount: Int = 0,
    val totalCount: Int = 0,
    val isCaching: Boolean = false,
    val isPaused: Boolean = false,
    val failedCount: Int = 0,
) {
    val isComplete: Boolean get() = totalCount > 0 && cachedCount >= totalCount
    val message: String?
        get() = when {
            isCaching -> null
            isComplete -> "全书缓存完成"
            isPaused -> "缓存已暂停"
            failedCount > 0 -> "缓存完成，$failedCount 章失败，可继续重试"
            else -> null
        }
}

/** App 级整本缓存任务与缓存列表记录。 */
object NovelCacheDownloadManager {
    private const val PREFS_NAME = "com.slackoff.novel-cache-downloads"
    private const val KEY_RECORDS = "records"

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()
    private var prefs: SharedPreferences? = null
    private var recordsByKey = emptyMap<String, NovelCacheRecord>()

    private val _states = MutableStateFlow<Map<String, NovelCacheDownloadState>>(emptyMap())
    val states: StateFlow<Map<String, NovelCacheDownloadState>> = _states.asStateFlow()
    private val _records = MutableStateFlow<List<NovelCacheRecord>>(emptyList())
    val records: StateFlow<List<NovelCacheRecord>> = _records.asStateFlow()

    fun init(context: Context) {
        if (prefs != null) return
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val restored = try {
            json.decodeFromString<List<NovelCacheRecord>>(prefs?.getString(KEY_RECORDS, "[]") ?: "[]")
        } catch (_: Exception) {
            emptyList()
        }
        recordsByKey = restored.associateBy { it.key }
        publishRecords()
        _states.value = restored.associate { record ->
            record.key to NovelCacheDownloadState(
                cachedCount = record.cachedCount,
                totalCount = record.totalCount,
                isPaused = record.isPaused || (!record.isComplete && record.cachedCount > 0),
                failedCount = record.failedCount,
            )
        }
    }

    fun refresh(source: String, bookId: String, totalCount: Int) {
        val key = novelKey(source, bookId)
        if (jobs[key]?.isActive == true) return
        scope.launch {
            val cached = NovelChapterCache.cachedChapterCount(source, bookId).coerceAtMost(totalCount)
            updateState(key) { it.copy(cachedCount = cached, totalCount = totalCount) }
            recordsByKey[key]?.let { updateRecord(it.copy(cachedCount = cached, totalCount = totalCount)) }
        }
    }

    fun start(
        source: String,
        bookId: String,
        title: String,
        author: String?,
        category: String?,
        chapters: List<NovelChapterItem>,
    ) {
        if (chapters.isEmpty()) return
        val key = novelKey(source, bookId)
        if (jobs[key]?.isActive == true) return
        updateRecord(
            (recordsByKey[key] ?: NovelCacheRecord(source, bookId, title, author, category)).copy(
                title = title,
                author = author,
                category = category,
                totalCount = chapters.size,
                isPaused = false,
                failedCount = 0,
                updatedAt = System.currentTimeMillis(),
            )
        )
        scope.launch {
            NovelChapterCache.putCatalog(
                source,
                bookId,
                NovelChapterList(source, bookId, title, chapters.size, 0, 0, chapters.size, chapters),
            )
        }
        launchDownload(key, source, bookId, chapters)
    }

    fun pause(source: String, bookId: String) {
        val key = novelKey(source, bookId)
        jobs[key]?.cancel()
        updateState(key) { it.copy(isCaching = false, isPaused = true) }
        recordsByKey[key]?.let { updateRecord(it.copy(isPaused = true, updatedAt = System.currentTimeMillis())) }
    }

    fun resume(record: NovelCacheRecord) {
        if (jobs[record.key]?.isActive == true) return
        scope.launch {
            val catalog = NovelChapterCache.catalog(record.source, record.bookId) ?: return@launch
            updateRecord(record.copy(isPaused = false, failedCount = 0, updatedAt = System.currentTimeMillis()))
            launchDownload(record.key, record.source, record.bookId, catalog.items)
        }
    }

    private fun launchDownload(key: String, source: String, bookId: String, chapters: List<NovelChapterItem>) {
        val job = scope.launch {
            try {
                val missing = chapters.filter { NovelChapterCache.chapter(source, bookId, it.chapterId) == null }
                val initialCached = chapters.size - missing.size
                updateState(key) { NovelCacheDownloadState(initialCached, chapters.size, missing.isNotEmpty()) }
                recordsByKey[key]?.let { updateRecord(it.copy(cachedCount = initialCached, totalCount = chapters.size, isPaused = false)) }
                if (missing.isEmpty()) return@launch

                val failures = AtomicInteger(0)
                val semaphore = Semaphore(4)
                missing.map { chapter ->
                    async {
                        semaphore.withPermit {
                            when (val result = NovelService.chapter(source, bookId, chapter.chapterId)) {
                                is ApiResult.Success -> {
                                    NovelChapterCache.putChapter(source, bookId, result.data)
                                    updateState(key) { current -> current.copy(cachedCount = current.cachedCount + 1) }
                                    val cached = _states.value[key]?.cachedCount ?: initialCached
                                    recordsByKey[key]?.let { record ->
                                        updateRecord(record.copy(cachedCount = cached, updatedAt = System.currentTimeMillis()))
                                    }
                                }
                                is ApiResult.Error -> failures.incrementAndGet()
                            }
                        }
                    }
                }.awaitAll()
                val cached = NovelChapterCache.cachedChapterCount(source, bookId).coerceAtMost(chapters.size)
                updateState(key) { NovelCacheDownloadState(cached, chapters.size, failedCount = failures.get()) }
                recordsByKey[key]?.let {
                    updateRecord(it.copy(cachedCount = cached, totalCount = chapters.size, failedCount = failures.get(), updatedAt = System.currentTimeMillis()))
                }
            } catch (_: CancellationException) {
                throw CancellationException()
            }
        }
        jobs[key] = job
        job.invokeOnCompletion { jobs.remove(key, job) }
    }

    private fun updateState(key: String, transform: (NovelCacheDownloadState) -> NovelCacheDownloadState) {
        _states.update { current -> current + (key to transform(current[key] ?: NovelCacheDownloadState())) }
    }

    @Synchronized
    private fun updateRecord(record: NovelCacheRecord) {
        recordsByKey = recordsByKey + (record.key to record)
        publishRecords()
        prefs?.edit()?.putString(KEY_RECORDS, json.encodeToString(_records.value))?.apply()
    }

    private fun publishRecords() {
        _records.value = recordsByKey.values.sortedByDescending { it.updatedAt }
    }
}
