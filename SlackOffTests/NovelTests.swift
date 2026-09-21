import CoreGraphics
import Foundation
import Testing
import UIKit
@testable import SlackOff

// MARK: - 真实响应样本
//
// 全部抓自本地 xiaoshuo_server 的真实响应（bqg99 源，《牧神记》），只做了条数裁剪，
// 字段与线上一致。用它当 fixture 是为了让「snake_case 转 camelCase + 可空字段」这条
// 链路被真实数据覆盖，而不是自己编一份理想 JSON 自欺欺人。
//
// 注意：这些样本放在 Swift **raw string**（#""" ... """#）里，raw string 不做转义处理，
// 所以 JSON 原文直接贴进来即可；一旦手动把 \ 加倍，"\n" 就会变成字面的反斜杠 + n。
private enum NovelFixtures {

    static let homeJSON = #"""
{
 "source": "bqg99",
 "site_name": "顶点小说网",
 "hot_books": [
  {
   "source": "bqg99",
   "book_id": "2639610",
   "title": "牧神记",
   "author": "宅猪",
   "category": null,
   "cover": "https://www.bgq99.cc/bookimages/2640967.jpg",
   "intro": "大墟的祖训说，天黑，别出门。大墟残老村的老弱病残们从江边捡到了一个婴儿，取名秦牧，含辛茹苦将他养大。这一天夜幕降临，黑暗笼罩大墟，秦牧走出了家门……做个......",
   "latest_chapter": null,
   "latest_chapter_id": null,
   "update_time": null,
   "url": "https://www.bqg99.cc/book/2639610/"
  },
  {
   "source": "bqg99",
   "book_id": "6357328",
   "title": "我是至尊",
   "author": "风凌天下",
   "category": null,
   "cover": "https://www.bgq99.cc/bookimages/6358685.jpg",
   "intro": "药不成丹只是毒，人不成神终成灰。 ………… 天道有缺，人间不平，红尘世外，魍魉横行；哀尔良善，怒尔不争；规则之外，吾来执行。 布武天下，屠尽不平；......",
   "latest_chapter": null,
   "latest_chapter_id": null,
   "update_time": null,
   "url": "https://www.bqg99.cc/book/6357328/"
  }
 ],
 "recommend_books": [
  {
   "source": "bqg99",
   "book_id": "1577189",
   "title": "振南明",
   "author": "一袖乾坤",
   "category": "历史",
   "cover": null,
   "intro": null,
   "latest_chapter": null,
   "latest_chapter_id": null,
   "update_time": null,
   "url": "https://www.bqg99.cc/book/1577189/"
  }
 ],
 "latest_updates": [
  {
   "source": "bqg99",
   "book_id": "1036885610",
   "title": "异域山海志",
   "author": "花沐雪111",
   "category": "女生",
   "cover": null,
   "intro": null,
   "latest_chapter": "一百零四、代价",
   "latest_chapter_id": "968167302",
   "update_time": "09-08",
   "url": "https://www.bqg99.cc/book/1036885610/"
  },
  {
   "source": "bqg99",
   "book_id": "1036886519",
   "title": "豢龙氏传人",
   "author": "惟精锅",
   "category": "女生",
   "cover": null,
   "intro": null,
   "latest_chapter": "第一百五十五章 老顾的选择",
   "latest_chapter_id": "967299747",
   "update_time": "09-08",
   "url": "https://www.bqg99.cc/book/1036886519/"
  }
 ],
 "category_blocks": [
  {
   "name": "玄幻奇幻",
   "books": [
    {
     "source": "bqg99",
     "book_id": "2015654451",
     "title": "开局签到荒古圣体",
     "author": null,
     "category": null,
     "cover": "https://www.bgq99.cc/bookimages/2015658522.jpg",
     "intro": "【不废柴，不舔狗，天骄争霸暴爽无敌流】君逍遥穿越玄幻世界，成为荒古世家神......",
     "latest_chapter": null,
     "latest_chapter_id": null,
     "update_time": null,
     "url": "https://www.bqg99.cc/book/2015654451/"
    },
    {
     "source": "bqg99",
     "book_id": "1835990327",
     "title": "魔法原典",
     "author": "魔王十三町",
     "category": null,
     "cover": null,
     "intro": null,
     "latest_chapter": null,
     "latest_chapter_id": null,
     "update_time": null,
     "url": "https://www.bqg99.cc/book/1835990327/"
    }
   ]
  }
 ]
}
"""#

    static let searchJSON = #"""
{
 "source": "bqg99",
 "kind": "search",
 "name": "牧神记",
 "page": 1,
 "page_size": 1,
 "total_pages": 1,
 "total": 1,
 "has_more": false,
 "items": [
  {
   "source": "bqg99",
   "book_id": "2639610",
   "title": "牧神记",
   "author": "宅猪",
   "category": "玄幻",
   "cover": "https://www.bgq99.cc/bookimages/2640967.jpg",
   "intro": "大墟的祖训说，天黑，别出门。大墟残老村的老弱病残们从江边捡到了一个婴儿，取名秦牧，含辛茹苦将他养大。这一天夜幕降临，……",
   "latest_chapter": "牧神记新番外来啦！",
   "latest_chapter_id": "275761356",
   "update_time": null,
   "url": "https://www.bqg99.cc/book/2639610/"
  }
 ]
}
"""#

    static let detailJSON = #"""
{
 "source": "bqg99",
 "book_id": "2639610",
 "title": "牧神记",
 "author": "宅猪",
 "category": "玄幻",
 "cover": "https://www.bqg99.cc/bookimages/2640967.jpg",
 "intro": "大墟的祖训说，天黑，别出门。大墟残老村的老弱病残们从江边捡到了一个婴儿，取名秦牧，含辛茹苦将他养大。这一天夜幕降临，黑暗笼罩大墟，秦牧走出了家门……做个春风中荡漾的反派吧！瞎子对他说。秦牧的反派之路，正在崛起！书友群：600290060，624672265，VIP群：663057414（有验证）普通群：424940671",
 "latest_chapter": "牧神记新番外来啦！",
 "latest_chapter_id": "275761356",
 "update_time": "29:17",
 "url": "https://www.bqg99.cc/book/2639610/",
 "status": "连载",
 "word_count": 3360271,
 "chapter_count": 1920,
 "first_chapter": "第一章 天黑别出门",
 "first_chapter_id": "637338569"
}
"""#

    static let chaptersJSON = #"""
{
 "source": "bqg99",
 "book_id": "2639610",
 "title": "牧神记",
 "total": 1920,
 "offset": 0,
 "limit": 3,
 "returned": 3,
 "chapters": [
  {
   "index": 1,
   "chapter_id": "637338569",
   "title": "第一章 天黑别出门",
   "url": "https://www.bqg99.cc/book/2639610/637338569.html"
  },
  {
   "index": 2,
   "chapter_id": "637338511",
   "title": "第二章 四灵血",
   "url": "https://www.bqg99.cc/book/2639610/637338511.html"
  },
  {
   "index": 3,
   "chapter_id": "637244286",
   "title": "第三章 神通",
   "url": "https://www.bqg99.cc/book/2639610/637244286.html"
  }
 ]
}
"""#

    static let bodyJSON = #"""
{
 "source": "bqg99",
 "book_id": "2639610",
 "chapter_id": "637338569",
 "title": "第一章 天黑别出门",
 "content": "天黑，别出门。\n这句话在残老村流传了很多年，具体是从什么时候传下来的，已经无从考证。不过这句话却是真理，无需怀疑。\n残老村的司婆婆看到夕阳一点点藏在山后，心里又紧张起来。随着夕阳落下，最后一缕阳光消失，天地间突然一下子寂静无比，没有任何声音。只见黑暗从西方缓缓的淹没过来，沿途吞噬山川河流道路树木，然后来到残老村，将残老村淹没。",
 "content_html": null,
 "word_count": 3622,
 "prev_chapter_id": null,
 "prev_chapter_title": null,
 "next_chapter_id": "637338511",
 "next_chapter_title": "下一章",
 "catalog_url": "https://www.bqg99.cc/book/2639610/"
}
"""#

    static let ranksJSON = #"""
[
 {
  "board": "小说总榜",
  "total": 15,
  "items": [
   {
    "rank": 1,
    "book_id": "1835902739",
    "title": "战魂赵子龙",
    "category": "玄幻",
    "author": null,
    "url": "https://www.bqg99.cc/book/1835902739/"
   },
   {
    "rank": 2,
    "book_id": "1036905891",
    "title": "西游之一拳圣人",
    "category": "女生",
    "author": null,
    "url": "https://www.bqg99.cc/book/1036905891/"
   }
  ]
 }
]
"""#

    static let categoriesJSON = #"""
[
 {
  "slug": "xuanhuan",
  "name": "玄幻",
  "url": "https://www.bqg99.cc/xuanhuan/",
  "paginated": false
 },
 {
  "slug": "wuxia",
  "name": "武侠",
  "url": "https://www.bqg99.cc/wuxia/",
  "paginated": false
 },
 {
  "slug": "dushi",
  "name": "都市",
  "url": "https://www.bqg99.cc/dushi/",
  "paginated": false
 },
 {
  "slug": "lishi",
  "name": "历史",
  "url": "https://www.bqg99.cc/lishi/",
  "paginated": false
 },
 {
  "slug": "wangyou",
  "name": "网游",
  "url": "https://www.bqg99.cc/wangyou/",
  "paginated": false
 },
 {
  "slug": "kehuan",
  "name": "科幻",
  "url": "https://www.bqg99.cc/kehuan/",
  "paginated": false
 },
 {
  "slug": "mm",
  "name": "女生",
  "url": "https://www.bqg99.cc/mm/",
  "paginated": false
 }
]
"""#

    static let sourcesJSON = #"""
[
 {
  "id": "bqg99",
  "name": "顶点小说网",
  "base_url": "https://www.bqg99.cc",
  "enabled": true,
  "priority": 10,
  "capabilities": {
   "home": true,
   "categories": true,
   "category_books": true,
   "ranks": true,
   "full_books": true,
   "search": true,
   "book_detail": true,
   "chapters": true,
   "chapter_body": true
  },
  "notes": "速度快、目录单页全量返回、支持站内搜索；分类/全本页源站不提供翻页"
 },
 {
  "id": "blqvdu",
  "name": "顶点小说网(镜像)",
  "base_url": "https://www.blqvdu.cc",
  "enabled": true,
  "priority": 15,
  "capabilities": {
   "home": true,
   "categories": true,
   "category_books": true,
   "ranks": true,
   "full_books": true,
   "search": false,
   "book_detail": true,
   "chapters": true,
   "chapter_body": true
  },
  "notes": "与主源同模板；目录单页全量（实测《百炼飞升录》8684 章）、正文服务端渲染；无站内搜索、分类不翻页"
 },
 {
  "id": "biquge365",
  "name": "新笔趣阁",
  "base_url": "https://www.biquge365.net",
  "enabled": true,
  "priority": 20,
  "capabilities": {
   "home": true,
   "categories": true,
   "category_books": true,
   "ranks": true,
   "full_books": true,
   "search": false,
   "book_detail": true,
   "chapters": true,
   "chapter_body": true
  },
  "notes": "分类页支持深度分页（藏书量大）；无站内搜索，排行榜由各分类收藏榜聚合"
 }
]
"""#

    static let catbooksJSON = #"""
{
 "source": "bqg99",
 "kind": "category",
 "name": "玄幻",
 "page": 1,
 "page_size": 30,
 "total_pages": 1,
 "total": 30,
 "has_more": false,
 "items": [
  {
   "source": "bqg99",
   "book_id": "1733848427",
   "title": "寰宇之证",
   "author": "变了",
   "category": "玄幻",
   "cover": null,
   "intro": null,
   "latest_chapter": "第五十一章 怪事",
   "latest_chapter_id": "971395229",
   "update_time": "09-08",
   "url": "https://www.bqg99.cc/book/1733848427/"
  },
  {
   "source": "bqg99",
   "book_id": "2010807732",
   "title": "人族镇守使",
   "author": "白驹易逝",
   "category": "玄幻",
   "cover": null,
   "intro": null,
   "latest_chapter": "第三千六百六十六章 灵尘剑尊",
   "latest_chapter_id": "85414809",
   "update_time": "09-08",
   "url": "https://www.bqg99.cc/book/2010807732/"
  }
 ]
}
"""#

    static let fullJSON = #"""
{
 "source": "bqg99",
 "kind": "full",
 "name": "全本小说",
 "page": 1,
 "page_size": 30,
 "total_pages": 1,
 "total": 30,
 "has_more": false,
 "items": [
  {
   "source": "bqg99",
   "book_id": "1036888110",
   "title": "藏王",
   "author": "勇之心",
   "category": "女生",
   "cover": null,
   "intro": null,
   "latest_chapter": "三〇二 世纪大战 （大结局）",
   "latest_chapter_id": "977990059",
   "update_time": "09-08",
   "url": "https://www.bqg99.cc/book/1036888110/"
  },
  {
   "source": "bqg99",
   "book_id": "1036891236",
   "title": "人间罪恶",
   "author": "好梦连连",
   "category": "女生",
   "cover": null,
   "intro": null,
   "latest_chapter": "第11章 执子之手",
   "latest_chapter_id": "982514582",
   "update_time": "09-08",
   "url": "https://www.bqg99.cc/book/1036891236/"
  }
 ]
}
"""#


    /// 服务不可用时的真实错误体（代理没开 / 源站宕机就是这个）。
    static let errorTextJSON = #"""
{"detail":"重试 4 次后仍失败: PoolTimeout | https://www.bqg99.cc/"}
"""#

    /// FastAPI 参数校验错误体，detail 是数组而不是字符串。
    static let errorValidationJSON = #"""
{"detail":[{"loc":["query","kw"],"msg":"Field required","type":"missing"}]}
"""#

    /// book_id 带前导零：源站确实存在这种 id，用整型解析会静默丢零。
    static let leadingZeroJSON = #"""
{"source":"bqg99","book_id":"0012345","title":"前导零","url":"https://www.bqg99.cc/book/0012345/"}
"""#

    /// 缺一堆可选字段：列表页经常只有书名和 id。
    static let minimalJSON = #"""
{"source":"blqvdu","book_id":"77","title":"最小字段","url":"https://x/77/"}
"""#

    static func data(_ text: String) throws -> Data {
        try #require(Data(text.utf8))
    }

    static var decoder: JSONDecoder { APIConfig.novelDecoder }
}

// MARK: - 模型解码

struct NovelModelsTests {
    @Test func decodesHomePage() throws {
        let home = try NovelFixtures.decoder.decode(
            NovelHomePage.self, from: NovelFixtures.data(NovelFixtures.homeJSON))
        #expect(home.source == "bqg99")
        #expect(home.siteName == "顶点小说网")
        #expect(home.hot.count == 2)
        #expect(home.hot[0].bookId == "2639610")
        #expect(home.hot[0].title == "牧神记")
        #expect(home.hot[0].author == "宅猪")
        // 强力推荐位没有封面，必须解成 nil 而不是崩
        #expect(home.recommend.first?.cover == nil)
        #expect(home.blocks.count == 1)
        #expect(home.blocks[0].items.count == 2)
        #expect(home.latest.count == 2)
        #expect(home.latest[0].updateTime != nil)
    }

    @Test func decodesSearchPage() throws {
        let page = try NovelFixtures.decoder.decode(
            NovelBookPage.self, from: NovelFixtures.data(NovelFixtures.searchJSON))
        #expect(page.kind == "search")
        #expect(page.name == "牧神记")
        #expect(page.hasMore == false)
        #expect(page.books.first?.intro != nil)
        #expect(page.books.first?.cover != nil)
    }

    @Test func decodesBookDetailAndFormatsWordCount() throws {
        let detail = try NovelFixtures.decoder.decode(
            NovelBookDetail.self, from: NovelFixtures.data(NovelFixtures.detailJSON))
        #expect(detail.title == "牧神记")
        #expect(detail.chapterCount == 1920)
        #expect(detail.status == "连载")
        #expect(detail.firstChapterId == "637338569")
        #expect(detail.wordCountText?.hasSuffix("万字") == true)
        // 336 万字 → "336.0 万字"
        #expect(detail.wordCountText == "336.0 万字")
    }

    @Test func decodesChapterList() throws {
        let list = try NovelFixtures.decoder.decode(
            NovelChapterList.self, from: NovelFixtures.data(NovelFixtures.chaptersJSON))
        #expect(list.total == 1920)
        #expect(list.items.count == 3)
        #expect(list.items[0].index == 1)
        #expect(list.items[0].chapterId == "637338569")
        #expect(list.items[2].title == "第三章 神通")
    }

    @Test func decodesChapterBodyAndSplitsParagraphs() throws {
        let body = try NovelFixtures.decoder.decode(
            NovelChapterBody.self, from: NovelFixtures.data(NovelFixtures.bodyJSON))
        #expect(body.title == "第一章 天黑别出门")
        #expect(body.prevChapterId == nil)
        #expect(body.nextChapterId == "637338511")
        #expect(body.paragraphs.count == 3)
        #expect(body.paragraphs[0] == "天黑，别出门。")
        #expect(body.contentHtml == nil)
    }

    @Test func decodesRankBoards() throws {
        let boards = try NovelFixtures.decoder.decode(
            [NovelRankBoard].self, from: NovelFixtures.data(NovelFixtures.ranksJSON))
        #expect(boards.count == 1)
        #expect(boards[0].board == "小说总榜")
        #expect(boards[0].entries.first?.rank == 1)
        // 榜单条目没有 source，必须由调用方补，这里只确认 book_id 解出来了
        #expect(boards[0].entries.first?.bookId.isEmpty == false)
    }

    @Test func decodesCategoriesAndSources() throws {
        let categories = try NovelFixtures.decoder.decode(
            [NovelCategory].self, from: NovelFixtures.data(NovelFixtures.categoriesJSON))
        #expect(categories.count == 7)
        #expect(categories[0].slug == "xuanhuan")
        #expect(categories[0].name == "玄幻")
        #expect(categories[0].paginated == false)

        let sources = try NovelFixtures.decoder.decode(
            [NovelSourceInfo].self, from: NovelFixtures.data(NovelFixtures.sourcesJSON))
        #expect(sources.count == 3)
        #expect(sources.map(\.id) == ["bqg99", "blqvdu", "biquge365"])
        #expect(sources[0].supports("search"))
        // blqvdu / biquge365 源站没有搜索页
        #expect(sources[1].supports("search") == false)
        #expect(sources[2].supports("search") == false)
    }

    @Test func decodesPagedLists() throws {
        let categoryPage = try NovelFixtures.decoder.decode(
            NovelBookPage.self, from: NovelFixtures.data(NovelFixtures.catbooksJSON))
        #expect(categoryPage.kind == "category")
        #expect(categoryPage.page == 1)
        #expect(categoryPage.items?.count == 2)

        let fullPage = try NovelFixtures.decoder.decode(
            NovelBookPage.self, from: NovelFixtures.data(NovelFixtures.fullJSON))
        #expect(fullPage.kind == "full")
        #expect(fullPage.books.count == 2)
    }

    @Test func bookIdKeepsLeadingZeros() throws {
        let book = try NovelFixtures.decoder.decode(
            NovelBook.self, from: NovelFixtures.data(NovelFixtures.leadingZeroJSON))
        #expect(book.bookId == "0012345")
        #expect(book.id == "bqg99:0012345")
    }

    @Test func toleratesMissingOptionalFields() throws {
        let book = try NovelFixtures.decoder.decode(
            NovelBook.self, from: NovelFixtures.data(NovelFixtures.minimalJSON))
        #expect(book.author == nil)
        #expect(book.cover == nil)
        #expect(book.latestChapterId == nil)
        #expect(book.source == "blqvdu")
    }

    @Test func decodesBothErrorShapes() throws {
        let textError = try NovelFixtures.decoder.decode(
            NovelErrorPayload.self, from: NovelFixtures.data(NovelFixtures.errorTextJSON))
        #expect(textError.detail?.message.contains("PoolTimeout") == true)

        let validationError = try NovelFixtures.decoder.decode(
            NovelErrorPayload.self, from: NovelFixtures.data(NovelFixtures.errorValidationJSON))
        #expect(validationError.detail?.message == "Field required")
    }

    @Test func wordCountFormattingHandlesSmallAndMissing() throws {
        // 用 JSON 而不是 memberwise init 构造：顺带验证「源站给了 word_count 就格式化，
        // 给了 null 就别显示 0 字」这条真实分支。
        let small = #"""
        {"source":"bqg99","book_id":"1","title":"t","url":"u","chapter_count":1,"word_count":9999}
        """#
        let smallDetail = try NovelFixtures.decoder.decode(
            NovelBookDetail.self, from: NovelFixtures.data(small))
        #expect(smallDetail.wordCountText == "9999 字")

        let missing = #"""
        {"source":"bqg99","book_id":"1","title":"t","url":"u","chapter_count":0,"word_count":null}
        """#
        let missingDetail = try NovelFixtures.decoder.decode(
            NovelBookDetail.self, from: NovelFixtures.data(missing))
        #expect(missingDetail.wordCountText == nil)
    }

}

// MARK: - 服务 URL 构造

struct NovelServiceURLTests {
    private let base = URL(string: "http://127.0.0.1:4321")!

    @Test func buildsPathAndQuery() {
        let url = NovelService.makeURL(
            base: base,
            path: "api/book/2639610/chapters",
            query: [(name: "offset", value: "0"), (name: "limit", value: "0"), (name: "source", value: "bqg99")])
        #expect(url.absoluteString == "http://127.0.0.1:4321/api/book/2639610/chapters?offset=0&limit=0&source=bqg99")
    }

    @Test func preservesLeadingZeroBookId() {
        // 前导零是源站真实存在的形态，被 URL 编码吞掉就直接 404
        let url = NovelService.makeURL(base: base, path: "api/book/0012345", query: [(name: "source", value: "bqg99")])
        #expect(url.path == "/api/book/0012345")
    }

    @Test func encodesChineseKeyword() {
        let url = NovelService.makeURL(base: base, path: "api/search", query: [(name: "kw", value: "牧神记")])
        let query = url.query ?? ""
        #expect(query.contains("%E7%89%A7") || query.contains("%e7%89%a7"))
        // 解码回来必须还原
        #expect(url.query(percentEncoded: false)?.contains("kw=牧神记") == true)
    }

    @Test func omitsNilQueryValues() {
        let url = NovelService.makeURL(
            base: base,
            path: "api/ranks",
            query: [(name: "board", value: nil), (name: "source", value: "bqg99")])
        #expect(url.absoluteString == "http://127.0.0.1:4321/api/ranks?source=bqg99")

        let allNil = NovelService.makeURL(base: base, path: "api/home", query: [(name: "board", value: nil)])
        #expect(allNil.query == nil)
    }

    @Test func encodesChapterPathWithTwoIds() {
        let url = NovelService.makeURL(
            base: base,
            path: "api/chapter/2639610/637338569",
            query: [(name: "source", value: "bqg99"), (name: "clean_ads", value: "true")])
        #expect(url.path == "/api/chapter/2639610/637338569")
        #expect(url.query?.contains("clean_ads=true") == true)
    }

    @Test func baseURLAcceptsBareHostAndPort() {
        let url = NovelSettings.makeBaseURL(host: "192.168.1.20", port: 4321)
        #expect(url.absoluteString == "http://192.168.1.20:4321")
    }

    @Test func baseURLAcceptsPastedURLWithScheme() {
        // 用户从终端直接复制 http://192.168.1.20:4321 粘进来也要能用
        let url = NovelSettings.makeBaseURL(host: "http://192.168.1.20:4321", port: 4321)
        #expect(url.host == "192.168.1.20")
        #expect(url.port == 4321)

        let withoutPort = NovelSettings.makeBaseURL(host: "http://192.168.1.20", port: 8080)
        #expect(withoutPort.port == 8080)
    }

    @Test func baseURLFallsBackToLocalhostWhenBlank() {
        let url = NovelSettings.makeBaseURL(host: "   ", port: 4321)
        #expect(url.absoluteString == "http://127.0.0.1:4321")
    }

    @Test func baseURLStripsTrailingWhitespace() {
        let url = NovelSettings.makeBaseURL(host: " 10.0.0.5 ", port: 4321)
        #expect(url.host == "10.0.0.5")
    }
}

// MARK: - 切页纯函数

struct PageSplitterTests {
    private func lines(_ heights: [CGFloat], stride: Int = 10) -> [NovelLineMetric] {
        heights.enumerated().map { index, height in
            NovelLineMetric(start: index * stride, end: (index + 1) * stride, height: height)
        }
    }

    @Test func splitsIntoMultiplePages() {
        let metrics = lines(Array(repeating: CGFloat(24), count: 10))
        // 页高 100、无页眉页脚 → 每页 4 行（4×24=96 ≤ 100，第 5 行会到 120）
        let pages = PageSplitter.split(lines: metrics, pageHeight: 100)
        #expect(pages.count == 3)
        #expect(pages[0].lineRange == 0..<4)
        #expect(pages[1].lineRange == 4..<8)
        #expect(pages[2].lineRange == 8..<10)
    }

    @Test func pageRangesAreContiguousAndCoverEverything() {
        let metrics = lines([20, 30, 25, 40, 15, 35, 22, 18, 27, 33, 19, 24])
        let pages = PageSplitter.split(lines: metrics, pageHeight: 70)

        // 行区间首尾相接
        for index in 1..<pages.count {
            #expect(pages[index].lineRange.lowerBound == pages[index - 1].lineRange.upperBound)
        }
        // 字符区间首尾相接，且覆盖 [0, 最后一行末尾)
        for index in 1..<pages.count {
            #expect(pages[index].charRange.lowerBound == pages[index - 1].charRange.upperBound)
        }
        #expect(pages.first?.charRange.lowerBound == 0)
        #expect(pages.last?.charRange.upperBound == metrics.last?.end)
        // 页序号连续
        #expect(pages.map(\.index) == Array(0..<pages.count))
    }

    @Test func oversizedSingleLineStillAdvances() {
        // 一行比整页还高（超大字号 + 超小窗口）：必须每页塞一行，绝不能原地打转
        let metrics = lines([CGFloat(500), CGFloat(500), CGFloat(500)])
        let pages = PageSplitter.split(lines: metrics, pageHeight: 100)
        #expect(pages.count == 3)
        #expect(pages.map(\.lineRange) == [0..<1, 1..<2, 2..<3])
    }

    @Test func emptyLinesYieldSingleEmptyPage() {
        let pages = PageSplitter.split(lines: [], pageHeight: 600)
        #expect(pages.count == 1)
        #expect(pages[0].isEmpty)
        #expect(pages[0].charRange == 0..<0)
    }

    @Test func headerAndFooterReduceCapacity() {
        let metrics = lines(Array(repeating: CGFloat(20), count: 10))
        // 净高 100 → 每页 5 行
        let withoutInsets = PageSplitter.split(lines: metrics, pageHeight: 100)
        // 页眉 20 + 页脚 30 → 净高 50 → 每页 2 行
        let withInsets = PageSplitter.split(
            lines: metrics, pageHeight: 100, headerHeight: 20, footerHeight: 30)
        #expect(withoutInsets.count == 2)
        #expect(withInsets.count == 5)
        #expect(withInsets[0].lineRange == 0..<2)
    }

    @Test func insetsEatingWholePageDegradeToLinePerPage() {
        // 边距比页高还大：净高为负，退化成每页一行而不是死循环
        let metrics = lines([CGFloat(20), CGFloat(20), CGFloat(20)])
        let pages = PageSplitter.split(
            lines: metrics, pageHeight: 100, headerHeight: 80, footerHeight: 80)
        #expect(pages.count == 3)
    }

    @Test func zeroHeightLinesAreTolerated() {
        let metrics = lines([0, 0, 25, 0, 25])
        let pages = PageSplitter.split(lines: metrics, pageHeight: 30)
        // 0 高度的行不占地方，25 的两行各占一页
        #expect(pages.count >= 2)
        #expect(pages.first?.charRange.lowerBound == 0)
        #expect(pages.last?.charRange.upperBound == metrics.last?.end)
    }

    @Test func pageIndexLookupFindsOwningPage() {
        let metrics = lines(Array(repeating: CGFloat(24), count: 10))
        let pages = PageSplitter.split(lines: metrics, pageHeight: 100)
        #expect(pages.count == 3)

        #expect(PageSplitter.pageIndex(forCharOffset: 0, in: pages) == 0)
        #expect(PageSplitter.pageIndex(forCharOffset: 39, in: pages) == 0)
        #expect(PageSplitter.pageIndex(forCharOffset: 40, in: pages) == 1)
        #expect(PageSplitter.pageIndex(forCharOffset: 85, in: pages) == 2)
        // 超出末尾 → 落在最后一页
        #expect(PageSplitter.pageIndex(forCharOffset: 9999, in: pages) == 2)
        // 负数 → 第一页
        #expect(PageSplitter.pageIndex(forCharOffset: -5, in: pages) == 0)
        #expect(PageSplitter.pageIndex(forCharOffset: 0, in: []) == 0)
    }
}

// MARK: - CoreText 分页

@MainActor
struct TextPaginatorTests {
    private func paragraphs(count: Int) -> [String] {
        (0..<count).map { index in
            "这是第\(index + 1)段测试正文，用来验证 CoreText 逐行测量与切页是否一致，内容需要足够长才能在窄屏上折成多行。"
        }
    }

    private func typography(fontSize: Double = 18, lineSpacing: NovelLineSpacing = .standard) -> NovelTypography {
        var value = NovelTypography()
        value.fontSize = fontSize
        value.lineSpacing = lineSpacing
        return value
    }

    private let phone = CGSize(width: 390, height: 844)

    @Test func paginatesRealChapterContent() throws {
        let body = try NovelFixtures.decoder.decode(
            NovelChapterBody.self, from: NovelFixtures.data(NovelFixtures.bodyJSON))
        let layout = NovelTextPaginator.paginate(
            title: body.title,
            paragraphs: body.paragraphs,
            typography: typography(),
            pageSize: phone)

        #expect(layout.lines.isEmpty == false)
        #expect(layout.pages.count >= 1)
        #expect(layout.chapterTitle == "第一章 天黑别出门")
        // 首页从 0 开始，末页覆盖到全文末尾
        #expect(layout.pages.first?.charRange.lowerBound == 0)
        #expect(layout.pages.last?.charRange.upperBound == (layout.fullText as NSString).length)
        // 每页都不能超出正文净高
        let available = layout.availableHeight
        for page in layout.pages {
            let height = page.lineRange.reduce(CGFloat(0)) { $0 + layout.lines[$1].height }
            #expect(height <= available + 0.5)
        }
    }

    @Test func reassembledTextMatchesOriginal() {
        let text = paragraphs(count: 40)
        let layout = NovelTextPaginator.paginate(
            title: "第一章 测试",
            paragraphs: text,
            typography: typography(),
            pageSize: phone)
        #expect(layout.pages.count > 1)

        let full = layout.fullText as NSString
        var rebuilt = ""
        for page in layout.pages {
            let range = CFRange(location: page.charRange.lowerBound, length: page.charRange.upperBound - page.charRange.lowerBound)
            let nsRange = NSRange(location: range.location, length: range.length)
            #expect(nsRange.location + nsRange.length <= full.length)
            rebuilt += full.substring(with: nsRange)
        }
        // 逐字符相等：分页不许吞字、不许重复字
        #expect(rebuilt == layout.fullText)
        #expect(rebuilt == (["第一章 测试"] + text).joined(separator: "\n"))
    }

    @Test func lineRangesAreContiguous() {
        let layout = NovelTextPaginator.paginate(
            title: "标题",
            paragraphs: paragraphs(count: 30),
            typography: typography(),
            pageSize: phone)

        for index in 1..<layout.lines.count {
            #expect(layout.lines[index].metric.start == layout.lines[index - 1].metric.end)
        }
        for index in 1..<layout.pages.count {
            #expect(layout.pages[index].lineRange.lowerBound == layout.pages[index - 1].lineRange.upperBound)
        }
    }

    @Test func largerFontProducesMorePages() {
        let text = paragraphs(count: 40)
        let small = NovelTextPaginator.paginate(
            title: "第一章", paragraphs: text, typography: typography(fontSize: 14), pageSize: phone)
        let large = NovelTextPaginator.paginate(
            title: "第一章", paragraphs: text, typography: typography(fontSize: 30), pageSize: phone)
        #expect(large.pages.count > small.pages.count)
    }

    @Test func looserLineSpacingProducesMorePages() {
        let text = paragraphs(count: 40)
        let compact = NovelTextPaginator.paginate(
            title: "第一章", paragraphs: text, typography: typography(lineSpacing: .compact), pageSize: phone)
        let loose = NovelTextPaginator.paginate(
            title: "第一章", paragraphs: text, typography: typography(lineSpacing: .loose), pageSize: phone)
        #expect(loose.pages.count > compact.pages.count)
    }

    @Test func widerPageProducesFewerPages() {
        let text = paragraphs(count: 40)
        let portrait = NovelTextPaginator.paginate(
            title: "第一章", paragraphs: text, typography: typography(), pageSize: phone)
        let landscape = NovelTextPaginator.paginate(
            title: "第一章", paragraphs: text, typography: typography(),
            pageSize: CGSize(width: 844, height: 390))
        // 横屏更宽但更矮：行数变少、每页行数也变少，这里只要求确实重排了
        #expect(landscape.lines.count != portrait.lines.count)
    }

    @Test func emptyChapterStillHasOnePage() {
        let layout = NovelTextPaginator.paginate(
            title: "", paragraphs: [], typography: typography(), pageSize: phone)
        #expect(layout.pages.count == 1)
        #expect(layout.pageCount == 1)
        #expect(layout.progress(atPage: 0) == 1)
    }

    @Test func unbreakableLongTokenDoesNotHang() {
        // 源站偶尔会混进一长串不可断的英文/数字，SuggestLineBreak 可能原地不动
        let longToken = String(repeating: "abcdefghij", count: 60)
        let layout = NovelTextPaginator.paginate(
            title: "T", paragraphs: [longToken], typography: typography(), pageSize: phone)
        #expect(layout.pages.count >= 1)
        #expect(layout.lines.count >= 1)
    }

    @Test func progressIsMonotonicAcrossPages() {
        let layout = NovelTextPaginator.paginate(
            title: "第一章", paragraphs: paragraphs(count: 60), typography: typography(), pageSize: phone)
        guard layout.pageCount > 2 else { return }
        var previous = -1.0
        for index in 0..<layout.pageCount {
            let value = layout.progress(atPage: index)
            #expect(value >= previous)
            #expect(value >= 0 && value <= 1)
            previous = value
        }
        #expect(layout.progress(atPage: layout.pageCount - 1) == 1)
    }
}

// MARK: - 书架与进度

@MainActor
struct BookshelfStoreTests {
    private func makeStore() -> (BookshelfStore, UserDefaults, String) {
        let defaults = UserDefaults.standard
        let key = "test.shelf.\(UUID().uuidString)"
        defaults.removeObject(forKey: key)
        return (BookshelfStore(defaults: defaults, storageKey: key), defaults, key)
    }

    private func makeBook(id: String = "2639610", title: String = "牧神记") -> NovelBook {
        NovelBook(
            source: "bqg99", bookId: id, title: title, author: "宅猪", category: "玄幻",
            cover: nil, intro: nil, latestChapter: "第1920章", latestChapterId: "999",
            updateTime: "09-08", url: nil)
    }

    @Test func addThenQuery() {
        let (store, defaults, suite) = makeStore()
        defer { defaults.removeObject(forKey: suite) }

        #expect(store.books.isEmpty)
        store.add(makeBook())
        #expect(store.books.count == 1)
        #expect(store.contains(source: "bqg99", bookId: "2639610"))
        #expect(store.book(source: "bqg99", bookId: "2639610")?.title == "牧神记")
        #expect(store.mostRecent?.title == "牧神记")
    }

    @Test func duplicateAddDoesNotCreateSecondEntry() {
        let (store, defaults, suite) = makeStore()
        defer { defaults.removeObject(forKey: suite) }

        store.add(makeBook())
        store.add(makeBook(title: "牧神记（源站改名）"))
        #expect(store.books.count == 1)
        // 元信息按最新一次为准
        #expect(store.books[0].title == "牧神记（源站改名）")
    }

    @Test func progressUpdateComputesPercent() throws {
        let (store, defaults, suite) = makeStore()
        defer { defaults.removeObject(forKey: suite) }

        store.updateProgress(
            source: "bqg99", bookId: "1", title: "书", chapterId: "c100",
            chapterTitle: "第100章", chapterIndex: 100, totalChapters: 1000,
            positionInChapter: 0.5)

        let entry = try #require(store.book(source: "bqg99", bookId: "1"))
        // (100 - 1 + 0.5) / 1000 = 0.0995
        #expect(abs(entry.percent - 0.0995) < 0.0001)
        #expect(entry.percentText == "10%")
        #expect(entry.progressText == "读至 第100章")
    }

    @Test func progressClampsOutOfRangeValues() {
        let (store, defaults, suite) = makeStore()
        defer { defaults.removeObject(forKey: suite) }

        store.updateProgress(
            source: "s", bookId: "b", title: "t", chapterId: "c", chapterTitle: "c",
            chapterIndex: 1, totalChapters: 10, positionInChapter: 5.0)
        #expect(store.book(source: "s", bookId: "b")?.positionInChapter == 1.0)

        store.updateProgress(
            source: "s", bookId: "b", title: "t", chapterId: "c", chapterTitle: "c",
            chapterIndex: 1, totalChapters: 10, positionInChapter: -3.0)
        #expect(store.book(source: "s", bookId: "b")?.positionInChapter == 0.0)
    }

    @Test func percentFallsBackWhenTotalUnknown() {
        let (store, defaults, suite) = makeStore()
        defer { defaults.removeObject(forKey: suite) }

        store.updateProgress(
            source: "s", bookId: "b", title: "t", chapterId: "c", chapterTitle: "c",
            chapterIndex: 1, totalChapters: 0, positionInChapter: 0.4)
        #expect(store.book(source: "s", bookId: "b")?.percent == 0.4)
    }

    @Test func mostRecentFollowsLastRead() throws {
        let (store, defaults, suite) = makeStore()
        defer { defaults.removeObject(forKey: suite) }

        store.add(makeBook(id: "1", title: "第一本"))
        store.add(makeBook(id: "2", title: "第二本"))
        // 再读第一本 → 它应该回到最前
        store.updateProgress(
            source: "bqg99", bookId: "1", title: "第一本", chapterId: "c",
            chapterTitle: "第5章", chapterIndex: 5, totalChapters: 100, positionInChapter: 0)
        #expect(store.mostRecent?.bookId == "1")
        #expect(store.sorted.map(\.bookId) == ["1", "2"])
    }

    @Test func removeAndRemoveAll() {
        let (store, defaults, suite) = makeStore()
        defer { defaults.removeObject(forKey: suite) }

        store.add(makeBook(id: "1", title: "A"))
        store.add(makeBook(id: "2", title: "B"))
        store.remove("bqg99:1")
        #expect(store.books.count == 1)
        #expect(store.contains("bqg99:1") == false)
        store.removeAll()
        #expect(store.books.isEmpty)
    }

    @Test func survivesUserDefaultsRoundTrip() throws {
        let defaults = UserDefaults.standard
        let key = "test.shelf.roundtrip.\(UUID().uuidString)"
        defer { defaults.removeObject(forKey: key) }

        let first = BookshelfStore(defaults: defaults, storageKey: key)
        first.updateProgress(
            source: "blqvdu", bookId: "0012345", title: "前导零书", author: "某人",
            category: "玄幻", chapterId: "c9", chapterTitle: "第九章",
            chapterIndex: 9, totalChapters: 200, positionInChapter: 0.25)

        // 换一个实例读同一份磁盘数据（模拟杀进程重进）
        let second = BookshelfStore(defaults: defaults, storageKey: key)
        #expect(second.books.count == 1)
        let entry = try #require(second.book(source: "blqvdu", bookId: "0012345"))
        #expect(entry.title == "前导零书")
        #expect(entry.lastChapterIndex == 9)
        #expect(entry.positionInChapter == 0.25)
        #expect(entry.totalChapters == 200)
        #expect(entry.author == "某人")
    }
}

// MARK: - 设置持久化

@MainActor
struct NovelSettingsTests {
    @Test func persistsReaderSettingsAcrossInstances() throws {
        let defaults = UserDefaults.standard
        let key = "test.settings.roundtrip.\(UUID().uuidString)"
        defer { defaults.removeObject(forKey: key) }

        let first = NovelSettings(defaults: defaults, storageKey: key)
        #expect(first.fontSize == 18)
        first.fontSize = 24
        first.lineSpacing = .loose
        first.paper = .night
        first.pagingMode = .curl
        first.apiHost = "192.168.1.33"
        first.apiPort = 5000
        first.sourceChoice = "blqvdu"

        let second = NovelSettings(defaults: defaults, storageKey: key)
        #expect(second.fontSize == 24)
        #expect(second.lineSpacing == .loose)
        #expect(second.paper == .night)
        #expect(second.pagingMode == .curl)
        #expect(second.apiHost == "192.168.1.33")
        #expect(second.apiPort == 5000)
        #expect(second.sourceChoice == "blqvdu")
        #expect(second.baseURL.absoluteString == "http://192.168.1.33:5000")
    }

    /// 回归：恢复快照期间不能写盘。
    ///
    /// `apply()` 逐字段赋值会触发 didSet → persist()，若不加抑制，写回磁盘的就是
    /// 「前几个字段已恢复、后面还是默认值」的半成品快照，把正在读取的正确数据覆盖掉。
    @Test func restoringSnapshotDoesNotOverwriteDisk() throws {
        let defaults = UserDefaults.standard
        let key = "test.settings.noOverwrite.\(UUID().uuidString)"
        defer { defaults.removeObject(forKey: key) }

        let preset = #"{"apiHost":"10.1.2.3","apiPort":9000,"sourceChoice":"blqvdu","fontSize":26,"lineSpacing":"loose","paper":"green","pagingMode":"curl","brightness":0.4}"#
        defaults.set(Data(preset.utf8), forKey: key)

        let restored = NovelSettings(defaults: defaults, storageKey: key)
        #expect(restored.apiHost == "10.1.2.3")
        #expect(restored.apiPort == 9000)
        #expect(restored.fontSize == 26)
        #expect(restored.paper == .green)
        #expect(restored.pagingMode == .curl)
        #expect(restored.brightness == 0.4)

        // 恢复动作本身不许改动磁盘上的原始字节
        let after = try #require(defaults.data(forKey: key))
        #expect(after == Data(preset.utf8))
    }

    @Test func nightModeToggleSwitchesPaper() throws {
        let defaults = UserDefaults.standard
        let key = "test.settings.night.\(UUID().uuidString)"
        defer { defaults.removeObject(forKey: key) }
        let settings = NovelSettings(defaults: defaults, storageKey: key)
        #expect(settings.isNightMode == false)
        settings.isNightMode = true
        #expect(settings.paper == .night)
        settings.isNightMode = false
        #expect(settings.paper == .cream)
    }

    @Test func oldSnapshotWithoutNewFieldStillDecodes() throws {
        // 模拟升级前存的旧配置（没有 lastActiveSource / brightness 字段）
        let defaults = UserDefaults.standard
        let key = "test.settings.legacy.\(UUID().uuidString)"
        defer { defaults.removeObject(forKey: key) }
        defaults.set(
            Data(#"{"apiHost":"127.0.0.1","apiPort":4321,"sourceChoice":"auto","fontSize":20,"lineSpacing":"compact","paper":"green","pagingMode":"cover"}"#.utf8),
            forKey: key)

        let settings = NovelSettings(defaults: defaults, storageKey: key)
        // 旧字段读出来了，新字段回落默认值而不是整体解码失败
        #expect(settings.fontSize == 20)
        #expect(settings.paper == .green)
        #expect(settings.pagingMode == .cover)
        #expect(settings.lastActiveSource == "bqg99")
    }
}

// MARK: - 文字封面取色

struct NovelTextCoverTests {
    @Test func stableHashIsDeterministic() {
        // 同一 seed 反复算必须一致（Swift 的 hashValue 每次启动都换种子，不能用）
        let seed = "bqg99:2639610"
        let first = NovelTextCover.stableHash(seed)
        for _ in 0..<100 {
            #expect(NovelTextCover.stableHash(seed) == first)
        }
        #expect(first >= 0)
    }

    @Test func knownHashValueIsFNV1a() {
        // FNV-1a("") = 2166136261，固定住算法本身，防止哪天被换实现
        #expect(NovelTextCover.stableHash("") == 2_166_136_261 & 0x7FFF_FFFF)
    }

    @Test func differentBooksSpreadAcrossPalette() {
        var buckets = Set<Int>()
        for index in 0..<200 {
            let palette = NovelTextCover.palette(for: "bqg99:\(index)")
            #expect(palette.count == 2)
            buckets.insert(NovelTextCover.stableHash("bqg99:\(index)") % 8)
        }
        // 200 本书只落到一两种颜色就说明哈希分布有问题
        #expect(buckets.count >= 6)
    }
}
