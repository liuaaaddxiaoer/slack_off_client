package com.slackoff.app.novel

import com.slackoff.app.network.NovelService
import com.slackoff.app.support.NovelSettingsData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** URL 构造与服务地址解析（纯函数，不触网、不碰 SharedPreferences）。 */
class NovelServiceUrlTest {

    private val base = "http://127.0.0.1:4321"

    @Test
    fun `builds path and query`() {
        val url = NovelService.makeUrl(
            path = "api/book/2639610/chapters",
            query = listOf("offset" to "0", "limit" to "0", "source" to "bqg99"),
            base = base,
        )
        assertEquals("http://127.0.0.1:4321/api/book/2639610/chapters?offset=0&limit=0&source=bqg99", url)
    }

    @Test
    fun `preserves leading zero book id`() {
        // 前导零是源站真实存在的形态，被吞掉就直接 404
        val url = NovelService.makeUrl("api/book/0012345", listOf("source" to "bqg99"), base)
        assertTrue(url.contains("/api/book/0012345?"))
    }

    @Test
    fun `encodes chinese keyword`() {
        val url = NovelService.makeUrl("api/search", listOf("kw" to "牧神记"), base)
        // 中文必须百分号编码，否则 OkHttp 会直接抛 IllegalArgumentException
        assertTrue(url.contains("kw=%E7%89%A7%E7%A5%9E%E8%AE%B0"))
    }

    @Test
    fun `omits null query values`() {
        val url = NovelService.makeUrl(
            "api/ranks",
            listOf("board" to null, "source" to "bqg99"),
            base,
        )
        assertEquals("http://127.0.0.1:4321/api/ranks?source=bqg99", url)

        val allNull = NovelService.makeUrl("api/home", listOf("board" to null), base)
        assertEquals("http://127.0.0.1:4321/api/home", allNull)
    }

    @Test
    fun `encodes chapter path with two ids`() {
        val url = NovelService.makeUrl(
            "api/chapter/2639610/637338569",
            listOf("source" to "bqg99", "clean_ads" to "true"),
            base,
        )
        assertTrue(url.startsWith("http://127.0.0.1:4321/api/chapter/2639610/637338569?"))
        assertTrue(url.contains("clean_ads=true"))
    }

    @Test
    fun `base url accepts bare host and port`() {
        assertEquals("http://192.168.1.20:4321", NovelSettingsData.makeBaseUrl("192.168.1.20", 4321))
    }

    @Test
    fun `base url accepts pasted url with scheme`() {
        // 用户从终端直接复制 http://192.168.1.20:4321 粘进来也要能用
        assertEquals("http://192.168.1.20:4321", NovelSettingsData.makeBaseUrl("http://192.168.1.20:4321", 4321))
        assertEquals("http://192.168.1.20:8080", NovelSettingsData.makeBaseUrl("http://192.168.1.20", 8080))
    }

    @Test
    fun `base url falls back to localhost when blank`() {
        assertEquals("http://127.0.0.1:4321", NovelSettingsData.makeBaseUrl("   ", 4321))
        assertEquals("http://127.0.0.1:5000", NovelSettingsData.makeBaseUrl("", 5000))
    }

    @Test
    fun `base url strips surrounding whitespace`() {
        assertEquals("http://10.0.0.5:4321", NovelSettingsData.makeBaseUrl(" 10.0.0.5 ", 4321))
    }

    @Test
    fun `night mode switches paper`() {
        val settings = NovelSettingsData()
        assertEquals(false, settings.isNightMode)
        assertEquals(true, settings.copy(paper = com.slackoff.app.support.NovelPaper.NIGHT).isNightMode)
    }

    @Test
    fun `font size range matches ios`() {
        // 两端滑杆范围必须一致，否则同一份设置在不同平台表现不同
        assertEquals(14f, NovelSettingsData.FONT_SIZE_RANGE.start, 0.001f)
        assertEquals(30f, NovelSettingsData.FONT_SIZE_RANGE.endInclusive, 0.001f)
    }
}
