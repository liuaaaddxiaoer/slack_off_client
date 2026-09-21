package com.slackoff.app.novel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.slackoff.app.model.NovelBook
import com.slackoff.app.model.NovelChapterBody
import com.slackoff.app.model.NovelChapterItem
import com.slackoff.app.network.ApiResult
import com.slackoff.app.network.NovelService
import com.slackoff.app.support.BookshelfStore
import com.slackoff.app.support.NovelChapterCache
import com.slackoff.app.support.NovelSettingsStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 跨章页引用（章下标, 章内页序），仿真翻页要在章之间连续翻。 */
data class NovelPageRef(val chapterIndex: Int, val pageIndex: Int)

/**
 * 阅读器状态机：目录、正文、分页、跨章导航、相邻章预取、进度落盘。
 * 与 iOS 端 NovelReaderModel.swift 一一对应。
 */
class NovelReaderViewModel(application: Application) : AndroidViewModel(application) {

    data class UiState(
        val source: String = "",
        val bookId: String = "",
        val bookTitle: String = "",
        val bookAuthor: String? = null,
        val bookCategory: String? = null,
        val chapters: List<NovelChapterItem> = emptyList(),
        val chapterIndex: Int = 0,
        val body: NovelChapterBody? = null,
        val layout: NovelLayout? = null,
        val currentPage: Int = 0,
        val isLoadingChapter: Boolean = false,
        val isLoadingCatalog: Boolean = false,
        val errorMessage: String? = null,
        val edgeHint: String? = null,
        val pageWidthPx: Float = 0f,
        val pageHeightPx: Float = 0f,
        val safeTopPx: Float = 0f,
        val safeBottomPx: Float = 0f,
    ) {
        val totalChapters: Int get() = chapters.size
        val pageCount: Int get() = layout?.pageCount ?: 0
        val currentChapterId: String get() = body?.chapterId ?: chapters.getOrNull(chapterIndex)?.chapterId ?: ""

        val overallProgress: Double
            get() {
                if (totalChapters <= 0) return 0.0
                val chapterProgress = layout?.progressAtPage(currentPage) ?: 0.0
                return ((chapterIndex + chapterProgress) / totalChapters).coerceIn(0.0, 1.0)
            }
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val bodyCache = HashMap<String, NovelChapterBody>()
    private val layoutCache = HashMap<Int, NovelLayout>()
    private var resumeProgress: Double? = null
    private var persistJob: Job? = null
    private var lastPersistedSignature = ""
    private var started = false

    /** 进入阅读器。chapterId 为 null 时用书架进度，再退回第一章。 */
    fun start(
        source: String,
        bookId: String,
        title: String,
        author: String?,
        category: String?,
        chapterId: String?,
    ) {
        val current = _state.value
        if (current.bookId == bookId && current.chapters.isNotEmpty()) return

        _state.value = current.copy(
            source = source,
            bookId = bookId,
            bookTitle = title,
            bookAuthor = author,
            bookCategory = category,
        )

        viewModelScope.launch {
            _state.value = _state.value.copy(isLoadingCatalog = true)
            val cachedCatalog = NovelChapterCache.catalog(source, bookId)
            val result = if (cachedCatalog != null) {
                ApiResult.Success(cachedCatalog)
            } else {
                NovelService.chapters(source, bookId).also { networkResult ->
                    if (networkResult is ApiResult.Success) {
                        NovelChapterCache.putCatalog(source, bookId, networkResult.data)
                    }
                }
            }
            when (result) {
                is ApiResult.Success -> {
                    val list = result.data
                    var next = _state.value.copy(
                        chapters = list.items,
                        isLoadingCatalog = false,
                        bookTitle = _state.value.bookTitle.ifEmpty { list.title.orEmpty() },
                    )
                    if (list.items.isEmpty()) {
                        _state.value = next.copy(errorMessage = "这本书的目录是空的")
                        return@launch
                    }

                    val shelfBook = BookshelfStore.book(source, bookId)
                    if (next.bookAuthor == null) next = next.copy(bookAuthor = shelfBook?.author)

                    var targetIndex = 0
                    when {
                        chapterId != null -> {
                            targetIndex = list.items.indexOfFirst { it.chapterId == chapterId }.coerceAtLeast(0)
                        }
                        shelfBook != null && shelfBook.lastChapterId.isNotEmpty() -> {
                            val found = list.items.indexOfFirst { it.chapterId == shelfBook.lastChapterId }
                            if (found >= 0) {
                                targetIndex = found
                                resumeProgress = shelfBook.positionInChapter
                            }
                        }
                    }
                    _state.value = next
                    loadChapter(targetIndex, resumeProgress)
                    resumeProgress = null
                    started = true
                    persistProgress(force = true)
                    startPeriodicPersist()
                }
                is ApiResult.Error -> {
                    _state.value = _state.value.copy(
                        isLoadingCatalog = false,
                        errorMessage = result.message,
                    )
                }
            }
        }
    }

    /** 容器尺寸 / 安全区变化时调用；会按原字符位置重排。 */
    fun updateMetrics(widthPx: Float, heightPx: Float, safeTopPx: Float, safeBottomPx: Float) {
        val current = _state.value
        if (current.pageWidthPx == widthPx && current.pageHeightPx == heightPx &&
            current.safeTopPx == safeTopPx && current.safeBottomPx == safeBottomPx
        ) return
        _state.value = current.copy(
            pageWidthPx = widthPx,
            pageHeightPx = heightPx,
            safeTopPx = safeTopPx,
            safeBottomPx = safeBottomPx,
        )
        relayout()
    }

    /** 字号 / 行距改了之后重排，保持读者所在字符位置。 */
    fun onTypographyChanged() = relayout()

    private fun relayout() {
        val offset = _state.value.layout?.charOffsetOfPage(_state.value.currentPage)
        layoutCache.clear()
        buildLayout(restoreCharOffset = offset)
    }

    private fun currentTypography(): NovelTypography {
        val density = getApplication<Application>().resources.displayMetrics.density
        val settings = NovelSettingsStore.settings.value
        val state = _state.value
        return NovelTypography.from(settings, density).copy(
            topInsetPx = (40f * density).coerceAtLeast(state.safeTopPx + 18f * density),
            bottomInsetPx = (40f * density).coerceAtLeast(state.safeBottomPx + 26f * density),
        )
    }

    private fun loadChapter(index: Int, restoreProgress: Double? = null) {
        val state = _state.value
        val item = state.chapters.getOrNull(index) ?: return
        _state.value = state.copy(chapterIndex = index, isLoadingChapter = true)

        val cached = bodyCache[item.chapterId]
        if (cached != null) {
            _state.value = _state.value.copy(body = cached, isLoadingChapter = false, errorMessage = null)
            buildLayout(restoreProgress = restoreProgress)
            prefetchNeighbors()
            persistProgress(force = false)
            return
        }

        viewModelScope.launch {
            val diskCached = NovelChapterCache.chapter(state.source, state.bookId, item.chapterId)
            val result = if (diskCached != null) {
                ApiResult.Success(diskCached)
            } else {
                NovelService.chapter(state.source, state.bookId, item.chapterId).also { networkResult ->
                    if (networkResult is ApiResult.Success) {
                        NovelChapterCache.putChapter(state.source, state.bookId, networkResult.data)
                    }
                }
            }
            when (result) {
                is ApiResult.Success -> {
                    bodyCache[item.chapterId] = result.data
                    _state.value = _state.value.copy(
                        body = result.data,
                        isLoadingChapter = false,
                        errorMessage = null,
                    )
                    buildLayout(restoreProgress = restoreProgress)
                    prefetchNeighbors()
                    persistProgress(force = false)
                }
                // 正文失败只在页内占位重试，不清空已有内容
                is ApiResult.Error -> {
                    _state.value = _state.value.copy(
                        isLoadingChapter = false,
                        errorMessage = result.message,
                    )
                }
            }
        }
    }

    fun retryCurrentChapter() {
        val state = _state.value
        val item = state.chapters.getOrNull(state.chapterIndex) ?: return
        bodyCache.remove(item.chapterId)
        loadChapter(state.chapterIndex)
    }

    private fun buildLayout(restoreProgress: Double? = null, restoreCharOffset: Int? = null) {
        val state = _state.value
        val body = state.body ?: return
        if (state.pageWidthPx <= 0f || state.pageHeightPx <= 0f) return

        val layout = TextPaginator.paginate(
            title = body.title,
            paragraphs = body.paragraphs,
            typography = currentTypography(),
            pageWidthPx = state.pageWidthPx,
            pageHeightPx = state.pageHeightPx,
        )
        layoutCache[state.chapterIndex] = layout

        val page = when {
            restoreCharOffset != null -> layout.pageIndexForCharOffset(restoreCharOffset)
            restoreProgress != null && layout.pageCount > 1 ->
                (restoreProgress * (layout.pageCount - 1)).toInt().coerceIn(0, layout.pageCount - 1)
            else -> 0
        }
        _state.value = _state.value.copy(
            layout = layout,
            currentPage = page.coerceIn(0, (layout.pageCount - 1).coerceAtLeast(0)),
        )
    }

    /** 后台预取上一章 / 下一章正文，失败静默（翻过去时会重新拉）。 */
    private fun prefetchNeighbors() {
        val state = _state.value
        listOf(1, -1).forEach { delta ->
            val index = state.chapterIndex + delta
            val item = state.chapters.getOrNull(index) ?: return@forEach
            if (bodyCache.containsKey(item.chapterId)) return@forEach
            viewModelScope.launch {
                val diskCached = NovelChapterCache.chapter(state.source, state.bookId, item.chapterId)
                if (diskCached != null) {
                    bodyCache[item.chapterId] = diskCached
                    return@launch
                }
                when (val result = NovelService.chapter(state.source, state.bookId, item.chapterId)) {
                    is ApiResult.Success -> {
                        bodyCache[item.chapterId] = result.data
                        NovelChapterCache.putChapter(state.source, state.bookId, result.data)
                    }
                    is ApiResult.Error -> {}
                }
            }
        }
    }

    // MARK: 翻页

    fun goToPage(index: Int) {
        val layout = _state.value.layout ?: return
        val page = index.coerceIn(0, (layout.pageCount - 1).coerceAtLeast(0))
        if (page == _state.value.currentPage) return
        _state.value = _state.value.copy(currentPage = page)
        persistProgress(force = false)
    }

    fun advance() {
        val state = _state.value
        val layout = state.layout ?: return
        if (state.currentPage + 1 < layout.pageCount) {
            goToPage(state.currentPage + 1)
        } else if (state.chapterIndex + 1 < state.totalChapters) {
            loadChapter(state.chapterIndex + 1)
        } else {
            flashHint("已经是最后一章了")
        }
    }

    fun retreat() {
        val state = _state.value
        if (state.currentPage > 0) {
            goToPage(state.currentPage - 1)
        } else if (state.chapterIndex > 0) {
            loadChapter(state.chapterIndex - 1, restoreProgress = 1.0)
        } else {
            flashHint("已经是第一章了")
        }
    }

    fun goToChapter(index: Int) {
        val state = _state.value
        if (index !in state.chapters.indices || index == state.chapterIndex) return
        loadChapter(index)
    }

    // MARK: 跨章页取用（仿真翻页用）

    fun refBefore(ref: NovelPageRef): NovelPageRef? {
        if (ref.pageIndex > 0) return ref.copy(pageIndex = ref.pageIndex - 1)
        if (ref.chapterIndex <= 0) return null
        val previous = ref.chapterIndex - 1
        val previousLayout = layoutForChapter(previous) ?: return null
        return NovelPageRef(previous, (previousLayout.pageCount - 1).coerceAtLeast(0))
    }

    fun refAfter(ref: NovelPageRef): NovelPageRef? {
        val currentLayout = layoutForChapter(ref.chapterIndex)
        if (currentLayout != null && ref.pageIndex + 1 < currentLayout.pageCount) {
            return ref.copy(pageIndex = ref.pageIndex + 1)
        }
        val next = ref.chapterIndex + 1
        if (next >= _state.value.totalChapters) return null
        val nextLayout = layoutForChapter(next) ?: return null
        if (nextLayout.pageCount <= 0) return null
        return NovelPageRef(next, 0)
    }

    /** 某章的排版结果：当前章直接返回，其它章需正文已缓存才能算。 */
    fun layoutForChapter(index: Int): NovelLayout? {
        val state = _state.value
        if (index == state.chapterIndex) return state.layout
        layoutCache[index]?.let { return it }
        val chapterId = state.chapters.getOrNull(index)?.chapterId ?: return null
        val cachedBody = bodyCache[chapterId] ?: return null
        if (state.pageWidthPx <= 0f) return null
        val built = TextPaginator.paginate(
            title = cachedBody.title,
            paragraphs = cachedBody.paragraphs,
            typography = currentTypography(),
            pageWidthPx = state.pageWidthPx,
            pageHeightPx = state.pageHeightPx,
        )
        layoutCache[index] = built
        return built
    }

    /** 仿真翻页翻到别的章时，把「当前章」同步过去（正文已在缓存，不重新请求）。 */
    fun adopt(ref: NovelPageRef) {
        val state = _state.value
        if (ref.chapterIndex == state.chapterIndex) {
            goToPage(ref.pageIndex)
            return
        }
        val chapterId = state.chapters.getOrNull(ref.chapterIndex)?.chapterId ?: return
        val cachedBody = bodyCache[chapterId] ?: return
        _state.value = state.copy(chapterIndex = ref.chapterIndex, body = cachedBody)
        val cachedLayout = layoutCache[ref.chapterIndex]
        if (cachedLayout != null) {
            _state.value = _state.value.copy(
                layout = cachedLayout,
                currentPage = ref.pageIndex.coerceIn(0, (cachedLayout.pageCount - 1).coerceAtLeast(0)),
            )
        } else {
            buildLayout()
            goToPage(ref.pageIndex)
        }
        prefetchNeighbors()
        persistProgress(force = false)
    }

    // MARK: 进度落盘

    private fun persistProgress(force: Boolean) {
        val state = _state.value
        if (!started && !force) return
        val body = state.body ?: return
        if (state.chapters.isEmpty()) return

        val position = state.layout?.progressAtPage(state.currentPage) ?: 0.0
        val signature = "${state.chapterIndex}|${state.currentPage}|${(position * 1000).toInt()}"
        if (!force && signature == lastPersistedSignature) return
        lastPersistedSignature = signature

        BookshelfStore.updateProgress(
            source = state.source,
            bookId = state.bookId,
            title = state.bookTitle,
            author = state.bookAuthor,
            category = state.bookCategory,
            chapterId = body.chapterId,
            chapterTitle = body.title,
            chapterIndex = state.chapterIndex + 1,
            totalChapters = state.totalChapters,
            positionInChapter = position,
        )
    }

    /** 退出 / 切后台时调用，确保进度不丢。 */
    fun flushProgress() = persistProgress(force = true)

    private fun startPeriodicPersist() {
        persistJob?.cancel()
        persistJob = viewModelScope.launch {
            while (true) {
                delay(10_000)
                persistProgress(force = false)
            }
        }
    }

    override fun onCleared() {
        persistJob?.cancel()
        persistProgress(force = true)
        super.onCleared()
    }

    private fun flashHint(text: String) {
        _state.value = _state.value.copy(edgeHint = text)
        viewModelScope.launch {
            delay(1600)
            if (_state.value.edgeHint == text) {
                _state.value = _state.value.copy(edgeHint = null)
            }
        }
    }
}
