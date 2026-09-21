package com.slackoff.app.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 模型解码测试。样本抓自本地 xiaoshuo_server 的真实响应（bqg99 源，《牧神记》），
 * 只做了条数裁剪，字段与线上一致 —— 用自己编的理想 JSON 测不出 snake_case 与可空字段的坑。
 */
class NovelModelsTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    @Test
    fun `decodes home page`() {
        val home = json.decodeFromString<NovelHomePage>(
            """
            {"source":"bqg99","site_name":"顶点小说网",
             "hot_books":[{"source":"bqg99","book_id":"2639610","title":"牧神记","author":"宅猪",
                           "cover":"https://www.bqg99.cc/bookimages/2640967.jpg",
                           "intro":"大墟的祖训说，天黑，别出门。","url":"https://www.bqg99.cc/book/2639610/"}],
             "recommend_books":[{"source":"bqg99","book_id":"1577189","title":"振南明","author":"一袖乾坤",
                                 "url":"https://www.bqg99.cc/book/1577189/"}],
             "latest_updates":[{"source":"bqg99","book_id":"1036910738","title":"修炼5000年还是练气期方羽",
                                "author":"李道然","category":"女生","latest_chapter":"番外篇5",
                                "latest_chapter_id":"975725998","update_time":"09-08",
                                "url":"https://www.bqg99.cc/book/1036910738/"}],
             "category_blocks":[{"name":"玄幻奇幻","books":[
                {"source":"bqg99","book_id":"1836466917","title":"阴阳石",
                 "cover":"https://www.bqg99.cc/bookimages/1836470988.jpg","url":"https://x/"}]}]}
            """.trimIndent()
        )
        assertEquals("bqg99", home.source)
        assertEquals("顶点小说网", home.siteName)
        assertEquals(1, home.hot.size)
        assertEquals("2639610", home.hot[0].bookId)
        assertEquals("牧神记", home.hot[0].title)
        assertEquals("宅猪", home.hot[0].author)
        // 强力推荐位没有封面，必须解成 null 而不是崩
        assertNull(home.recommend.first().cover)
        assertEquals(1, home.blocks.size)
        assertEquals("玄幻奇幻", home.blocks[0].name)
        assertEquals(1, home.blocks[0].items.size)
        assertEquals("番外篇5", home.latest[0].latestChapter)
        assertEquals("09-08", home.latest[0].updateTime)
    }

    @Test
    fun `decodes book detail and formats word count`() {
        val detail = json.decodeFromString<NovelBookDetail>(
            """
            {"source":"bqg99","book_id":"2639610","title":"牧神记","author":"宅猪","category":"玄幻",
             "cover":"https://www.bqg99.cc/bookimages/2640967.jpg","intro":"简介： 大墟的祖训说…",
             "latest_chapter":"牧神记新番外来啦！","latest_chapter_id":"275761356","update_time":"29:17",
             "url":"https://www.bqg99.cc/book/2639610/","status":"连载","word_count":3360271,
             "chapter_count":1920,"first_chapter":"第一章 天黑别出门","first_chapter_id":"637338569"}
            """.trimIndent()
        )
        assertEquals("牧神记", detail.title)
        assertEquals(1920, detail.chapterCount)
        assertEquals("连载", detail.status)
        assertEquals("637338569", detail.firstChapterId)
        assertEquals("637338569", detail.entryChapterId)
        assertEquals("336.0 万字", detail.wordCountText)
    }

    @Test
    fun `word count formatting handles small and missing`() {
        val small = json.decodeFromString<NovelBookDetail>(
            """{"source":"bqg99","book_id":"1","title":"t","url":"u","chapter_count":1,"word_count":9999}"""
        )
        assertEquals("9999 字", small.wordCountText)

        // 源站没给字数时不该显示 "0 字"
        val missing = json.decodeFromString<NovelBookDetail>(
            """{"source":"bqg99","book_id":"1","title":"t","url":"u","chapter_count":0,"word_count":null}"""
        )
        assertNull(missing.wordCountText)
    }

    @Test
    fun `decodes chapter list and keeps string ids`() {
        val list = json.decodeFromString<NovelChapterList>(
            """
            {"source":"bqg99","book_id":"2639610","title":"牧神记","total":1920,"offset":0,"limit":3,"returned":3,
             "chapters":[{"index":1,"chapter_id":"637338569","title":"第一章 天黑别出门","url":"https://x/1.html"},
                         {"index":2,"chapter_id":"637338511","title":"第二章 四灵血","url":"https://x/2.html"},
                         {"index":3,"chapter_id":"637244286","title":"第三章 神通","url":"https://x/3.html"}]}
            """.trimIndent()
        )
        assertEquals(1920, list.total)
        assertEquals(3, list.items.size)
        assertEquals(1, list.items[0].index)
        assertEquals("637338569", list.items[0].chapterId)
        assertEquals("第三章 神通", list.items[2].title)
    }

    @Test
    fun `decodes chapter body and splits paragraphs`() {
        val body = json.decodeFromString<NovelChapterBody>(
            """
            {"source":"bqg99","book_id":"2639610","chapter_id":"637338569","title":"第一章 天黑别出门",
             "content":"天黑，别出门。\n这句话在残老村流传了很多年。\n残老村的司婆婆看到夕阳一点点藏在山后。",
             "content_html":null,"word_count":3622,"prev_chapter_id":null,"prev_chapter_title":null,
             "next_chapter_id":"637338511","next_chapter_title":"下一章",
             "catalog_url":"https://www.bqg99.cc/book/2639610/"}
            """.trimIndent()
        )
        assertEquals("第一章 天黑别出门", body.title)
        assertNull(body.prevChapterId)
        assertEquals("637338511", body.nextChapterId)
        assertNull(body.contentHtml)
        assertEquals(3, body.paragraphs.size)
        assertEquals("天黑，别出门。", body.paragraphs[0])
    }

    @Test
    fun `decodes rank boards without source field`() {
        val boards = json.decodeFromString<List<NovelRankBoard>>(
            """
            [{"board":"小说总榜","total":15,"items":[
              {"rank":1,"book_id":"1136852999","title":"秦沉陆天雪","category":"女生","author":null,"url":"https://x/"}]}]
            """.trimIndent()
        )
        assertEquals(1, boards.size)
        assertEquals("小说总榜", boards[0].board)
        assertEquals(1, boards[0].entries.first().rank)
        // 榜单条目没有 source，必须由调用方补，这里只确认 book_id 解出来了
        assertTrue(boards[0].entries.first().bookId.isNotEmpty())
        assertNull(boards[0].entries.first().author)
    }

    @Test
    fun `decodes categories and sources with capability matrix`() {
        val categories = json.decodeFromString<List<NovelCategory>>(
            """[{"slug":"xuanhuan","name":"玄幻","url":"https://www.bqg99.cc/xuanhuan/","paginated":false}]"""
        )
        assertEquals("xuanhuan", categories[0].slug)
        assertEquals("玄幻", categories[0].name)
        assertEquals(false, categories[0].paginated)

        val sources = json.decodeFromString<List<NovelSourceInfo>>(
            """
            [{"id":"bqg99","name":"顶点小说网","base_url":"https://www.bqg99.cc","enabled":true,"priority":10,
              "capabilities":{"home":true,"search":true,"ranks":true},"notes":"速度快"},
             {"id":"blqvdu","name":"顶点小说网(镜像)","base_url":"https://x","enabled":true,"priority":15,
              "capabilities":{"home":true,"search":false},"notes":"无站内搜索"}]
            """.trimIndent()
        )
        assertEquals(2, sources.size)
        assertEquals("bqg99", sources[0].id)
        assertTrue(sources[0].supports("search"))
        // blqvdu / biquge365 源站没有搜索页
        assertEquals(false, sources[1].supports("search"))
        // 能力矩阵缺字段时按「支持」处理，服务端只会显式标 false
        assertTrue(sources[1].supports("ranks"))
    }

    @Test
    fun `decodes paged list and exposes has_more`() {
        val page = json.decodeFromString<NovelBookPage>(
            """
            {"source":"bqg99","kind":"category","name":"玄幻","page":1,"page_size":30,
             "total_pages":1,"total":30,"has_more":false,
             "items":[{"source":"bqg99","book_id":"1733848427","title":"寰宇之证","author":"变了",
                       "category":"玄幻","latest_chapter":"第五十一章 怪事","latest_chapter_id":"971395229",
                       "update_time":"09-08","url":"https://x/"}]}
            """.trimIndent()
        )
        assertEquals("category", page.kind)
        assertEquals(1, page.page)
        assertEquals(false, page.more)
        assertEquals(1, page.books.size)
        assertEquals("寰宇之证", page.books[0].title)
    }

    @Test
    fun `book id keeps leading zeros`() {
        // book_id 用整型解析会静默丢掉前导零 → 请求详情直接 404
        val book = json.decodeFromString<NovelBook>(
            """{"source":"bqg99","book_id":"0012345","title":"前导零","url":"https://www.bqg99.cc/book/0012345/"}"""
        )
        assertEquals("0012345", book.bookId)
        assertEquals("bqg99:0012345", book.key)
    }

    @Test
    fun `tolerates missing optional fields`() {
        val book = json.decodeFromString<NovelBook>(
            """{"source":"blqvdu","book_id":"77","title":"最小字段","url":"https://x/77/"}"""
        )
        assertNull(book.author)
        assertNull(book.cover)
        assertNull(book.latestChapterId)
        assertEquals("blqvdu", book.source)
    }

    @Test
    fun `decodes ping and cache stats`() {
        val ping = json.decodeFromString<NovelPing>(
            """{"ok":true,"service":"xiaoshuo_server","version":"1.0.0","ts":1788850280}"""
        )
        assertEquals(true, ping.ok)

        val stats = json.decodeFromString<NovelCacheStats>(
            """
            {"cache":{"entries":0,"hits":0,"misses":4},"hit_rate":0.0,
             "fetcher":{"retries":4,"timeout_s":15.0,"concurrency":8,
                        "proxy_mode":"trust_env(系统/环境代理自动)"}}
            """.trimIndent()
        )
        assertEquals(0, stats.cache?.entries)
        assertEquals(4, stats.cache?.misses)
        assertEquals(15.0, stats.fetcher?.timeoutS ?: 0.0, 0.001)
        assertTrue(stats.fetcher?.proxyMode?.contains("trust_env") == true)
    }
}
