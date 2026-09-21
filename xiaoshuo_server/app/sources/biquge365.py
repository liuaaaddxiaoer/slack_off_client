"""备用数据源：新笔趣阁 www.biquge365.net

价值：分类页有真实深度分页（玄幻魔法 1769 页），藏书量远大于主源的固定 30 条列表。
实测结构（2026-09 抓取确认）：
  首页   /                              多个 h2 区块；ul.wanben(封面推荐) / span.name+jie+zuo+time(更新列表)
  分类   /sort/{1..8}_{page}/           div.right_border(h2 以「全部」开头) → li(span.name/jie/zuo/time)；div.page 给出总页数
  全本   /full/                         同分类页 li 结构
  排行   无独立排行页                    用各分类页侧栏 ul.bangdan（收藏排行）聚合而成
  搜索   源站未提供                      capabilities.search = False
  详情   /book/{bid}/                   h1 + div.xiangqing(div.zhutu img / div.xinxi span.x*)
  目录   /newbook/{bid}/                div.border(h2「《X》全部章节」) → ul.info li a
  正文   /chapter/{bid}/{cid}.html      h1 + div#txt + 上一章/目录/下一章 导航
"""
from __future__ import annotations

import asyncio
import re
from typing import Optional

from bs4 import BeautifulSoup, Tag

from app.models import (
    BookBrief,
    BookDetail,
    BookPage,
    ChapterBody,
    ChapterItem,
    ChapterList,
    CategoryInfo,
    HomePage,
    HomeCategoryBlock,
    RankBoard,
    RankEntry,
)

from .base import Source, abs_url, clean, clean_intro, extract_content, soup

CATEGORIES: dict[str, str] = {
    "1": "玄幻魔法",
    "2": "仙侠修真",
    "3": "都市言情",
    "4": "网游动漫",
    "5": "科幻小说",
    "6": "恐怖灵异",
    "7": "历史军事",
    "8": "其他小说",
}
NAME_TO_SLUG = {v: k for k, v in CATEGORIES.items()}

_BOOK_HREF = re.compile(r"/book/(\d+)/$")
# 首页「本站强推」用 /newbook/{id}/ 作为书籍链接，故解析时放宽匹配
_ANY_BOOK_HREF = re.compile(r"/(?:new)?book/(\d+)/$")
_CHAP_HREF = re.compile(r"/chapter/(\d+)/(\w+)\.html$")
_PAGE_RE = re.compile(r"第\s*(\d+)\s*/\s*(\d+)\s*页")


class Biquge365Source(Source):
    id = "biquge365"
    name = "新笔趣阁"
    base_url = "https://www.biquge365.net"
    encoding = "utf-8"
    priority = 20
    notes = "分类页支持深度分页（藏书量大）；无站内搜索，排行榜由各分类收藏榜聚合"

    capabilities = {
        "home": True,
        "categories": True,
        "category_books": True,
        "ranks": True,
        "full_books": True,
        "search": False,
        "book_detail": True,
        "chapters": True,
        "chapter_body": True,
    }

    # ---------------- 解析工具 ----------------
    def _book_from_li(self, li: Tag) -> Optional[BookBrief]:
        """li 结构：<span.name>《<a href=/book/id/>书名</a>》 <span.jie><a>最新章节</a> <span.zuo>作者 <span.time>时间"""
        name_a = li.select_one("span.name a") or li.select_one("h3.p2 a") or li.select_one("p.p1 a")
        if name_a is None:
            a = li.select_one("a[href*='/book/']")
            name_a = a
        if name_a is None or not name_a.get("href"):
            return None
        m = _ANY_BOOK_HREF.search(name_a["href"])
        if not m:
            return None
        jie = li.select_one("span.jie a")
        cid = None
        if jie and jie.get("href"):
            cm = _CHAP_HREF.search(jie["href"])
            cid = cm.group(2) if cm else None
        img = li.select_one("img")
        h3 = li.select_one("h3.p2 a")
        title = (clean(h3.get_text()) if h3 else None) or clean(name_a.get_text()) or \
            ((clean(img.get("alt")) or "").replace("文章列表", "") if img else "") or ""
        p3 = li.select_one("p.p3")
        return BookBrief(
            source=self.id,
            book_id=m.group(1),
            title=title.strip("《》"),
            author=clean(li.select_one("span.zuo").get_text()) if li.select_one("span.zuo") else None,
            category=None,
            cover=abs_url(self.base_url, img.get("src")) if img and img.get("src") else None,
            intro=clean(p3.get_text(" ")) if p3 else None,
            latest_chapter=clean(jie.get_text()) if jie else None,
            latest_chapter_id=cid,
            update_time=clean(li.select_one("span.time").get_text()) if li.select_one("span.time") else None,
            url=abs_url(self.base_url, name_a["href"]) or "",
        )

    def _books_in(self, root: Tag, cap: int = 10000) -> list[BookBrief]:
        out: list[BookBrief] = []
        seen: set[str] = set()
        for li in root.select("li"):
            b = self._book_from_li(li)
            if b and b.title and b.book_id not in seen:
                seen.add(b.book_id)
                out.append(b)
                if len(out) >= cap:
                    break
        return out

    @staticmethod
    def _page_info(s: BeautifulSoup) -> tuple[Optional[int], Optional[int]]:
        """从 div.page 的「(第1/1769页)」解析当前页与总页数。"""
        pg = s.select_one("div.page") or s.find(string=_PAGE_RE)
        if pg is None:
            return None, None
        text = pg.get_text() if hasattr(pg, "get_text") else str(pg)
        m = _PAGE_RE.search(text or "")
        if not m:
            return None, None
        return int(m.group(1)), int(m.group(2))

    def _main_list_root(self, s: BeautifulSoup) -> Optional[Tag]:
        """定位「全部XX小说」主列表容器；找不到就退回 div.menu。"""
        for h2 in s.select("h2"):
            if h2.get_text(strip=True).startswith("全部"):
                return h2.parent
        return s.select_one("div.menu") or s

    # ---------------- 首页 ----------------
    async def home(self) -> HomePage:
        html = await self.html("home", "home", "/")
        s = soup(html)
        hot: list[BookBrief] = []
        seen_hot: set[str] = set()
        for ul in s.select("ul.qiangtui, ul.wanben"):
            for li in ul.select("li"):
                b = self._book_from_li(li)
                if b and b.book_id not in seen_hot:
                    seen_hot.add(b.book_id)
                    hot.append(b)

        latest: list[BookBrief] = []
        seen_l: set[str] = set()
        for li in s.select("li"):
            if li.select_one("span.jie") and li.select_one("span.time"):
                b = self._book_from_li(li)
                if b and b.book_id not in seen_l and b.book_id not in seen_hot:
                    seen_l.add(b.book_id)
                    latest.append(b)

        blocks: list[HomeCategoryBlock] = []
        seen_blocks: set[str] = set()
        for h2 in s.select("h2"):
            name = clean(h2.get_text()) or ""
            if not name or name in seen_blocks or name == "阅读记录":
                continue
            parent = h2.parent
            books = self._books_in(parent, cap=30)
            if books:
                seen_blocks.add(name)
                blocks.append(HomeCategoryBlock(name=name, books=books))

        return HomePage(
            source=self.id,
            site_name=self.name,
            hot_books=hot,
            recommend_books=[],
            latest_updates=latest,
            category_blocks=blocks,
        )

    # ---------------- 分类 ----------------
    async def categories(self) -> list[CategoryInfo]:
        return [
            CategoryInfo(slug=slug, name=name, url=f"{self.base_url}/sort/{slug}_1/", paginated=True)
            for slug, name in CATEGORIES.items()
        ]

    async def category_books(self, slug: str, page: int = 1) -> BookPage:
        slug = slug.strip()
        if slug in NAME_TO_SLUG:
            slug = NAME_TO_SLUG[slug]
        if slug not in CATEGORIES:
            raise KeyError(f"未知分类 {slug}，可选：{', '.join(f'{k}={v}' for k, v in CATEGORIES.items())}")
        page = max(1, page)
        html = await self.html("category", f"sort:{slug}:{page}", f"/sort/{slug}_{page}/")
        s = soup(html)
        root = self._main_list_root(s)
        items = self._books_in(root)
        cur, total_pages = self._page_info(s)
        return BookPage(
            source=self.id,
            kind="category",
            name=CATEGORIES[slug],
            page=cur or page,
            page_size=len(items),
            total_pages=total_pages,
            total=(total_pages * len(items)) if total_pages and items else None,
            has_more=bool(total_pages and (cur or page) < total_pages),
            items=items,
        )

    # ---------------- 排行（各分类收藏榜聚合） ----------------
    async def ranks(self, board: Optional[str] = None) -> list[RankBoard]:
        slugs = [
            NAME_TO_SLUG.get(board.strip(), board.strip()) if board and not board.isdigit() else board
        ] if board else list(CATEGORIES)
        slugs = [s for s in slugs if s in CATEGORIES] or list(CATEGORIES)

        async def one(slug: str) -> Optional[RankBoard]:
            try:
                html = await self.html("rank", f"bangdan:{slug}", f"/sort/{slug}_1/")
            except Exception:  # noqa: BLE001
                return None
            s = soup(html)
            for h2 in s.select("h2"):
                if "排行" not in h2.get_text():
                    continue
                entries: list[RankEntry] = []
                for i, li in enumerate(h2.parent.select("ul li"), start=1):
                    a = li.select_one("a[href*='/book/']")
                    if not a or not a.get("href"):
                        continue
                    m = _BOOK_HREF.search(a["href"])
                    if not m:
                        continue
                    xu = li.select_one("span.xuhao, span.xuhao1")
                    entries.append(
                        RankEntry(
                            rank=int(xu.get_text(strip=True)) if xu and xu.get_text(strip=True).isdigit() else i,
                            book_id=m.group(1),
                            title=(clean(a.get_text()) or "").strip("《》"),
                            category=CATEGORIES[slug],
                            url=abs_url(self.base_url, a["href"]) or "",
                        )
                    )
                if entries:
                    return RankBoard(board=clean(h2.get_text()) or f"{CATEGORIES[slug]}排行",
                                     total=len(entries), items=entries)
            return None

        boards = [b for b in await asyncio.gather(*(one(s) for s in slugs)) if b]
        if board and not boards:
            raise KeyError(f"未找到榜单 {board}")
        return boards

    # ---------------- 全本 ----------------
    async def full_books(self, page: int = 1) -> BookPage:
        page = max(1, page)
        path = "/full/" if page == 1 else f"/full/{page}/"
        html = await self.html("full", f"full:{page}", path)
        s = soup(html)
        root = self._main_list_root(s)
        items = self._books_in(root)
        cur, total_pages = self._page_info(s)
        return BookPage(
            source=self.id,
            kind="full",
            name="全本小说",
            page=cur or page,
            page_size=len(items),
            total_pages=total_pages,
            total=(total_pages * len(items)) if total_pages and items else None,
            has_more=bool(total_pages and (cur or page) < total_pages),
            items=items,
        )

    # ---------------- 搜索：源站未提供 ----------------
    async def search(self, keyword: str, page: int = 1) -> BookPage:
        raise self.not_supported("站内搜索（请改用 source=bqg99）")

    # ---------------- 详情 + 目录 ----------------
    async def book_detail(self, book_id: str) -> BookDetail:
        html = await self.html("book", f"book:{book_id}", f"/book/{book_id}/")
        s = soup(html)
        h1 = s.select_one("h1")
        info = s.select_one("div.xiangqing") or s
        cover = info.select_one("div.zhutu img") or s.select_one("img")
        meta: dict[str, Optional[str]] = {}
        for sp in info.select("div.xinxi span"):
            txt = clean(sp.get_text(" ")) or ""
            for label in ("作者", "分类", "状态", "字数", "更新时间", "最新章节"):
                if txt.startswith(label):
                    meta[label] = txt.split("：", 1)[-1].split(":", 1)[-1].strip()
        intro_el = s.select_one("div.intro") or s.select_one("div.neirong") or s.select_one("p.intro")
        chapters = await self._all_chapters(book_id)
        latest_a = info.select_one("span.x2 a[href*='/chapter/']")
        latest_id = None
        if latest_a and latest_a.get("href"):
            m = _CHAP_HREF.search(latest_a["href"])
            latest_id = m.group(2) if m else None
        wc = meta.get("字数")
        return BookDetail(
            source=self.id,
            book_id=book_id,
            title=clean(h1.get_text()) if h1 else (self.og(s, "og:title") or ""),
            author=meta.get("作者") or self.og(s, "og:novel:author"),
            category=meta.get("分类") or self.og(s, "og:novel:category"),
            cover=abs_url(self.base_url, cover.get("src")) if cover and cover.get("src") else None,
            intro=(clean_intro(intro_el.get_text(" ")) if intro_el else None)
            or clean(self.og(s, "og:description")),
            latest_chapter=(meta.get("最新章节") or (clean(latest_a.get_text()) if latest_a else None)
                            or self.og(s, "og:novel:latest_chapter_name")),
            latest_chapter_id=latest_id,
            update_time=meta.get("更新时间") or self.og(s, "og:novel:update_time"),
            status=meta.get("状态") or self.og(s, "og:novel:status"),
            word_count=int(re.sub(r"\D", "", wc)) if wc and re.search(r"\d", wc) else None,
            chapter_count=len(chapters),
            first_chapter=chapters[0].title if chapters else None,
            first_chapter_id=chapters[0].chapter_id if chapters else None,
            url=f"{self.base_url}/book/{book_id}/",
        )

    async def _all_chapters(self, book_id: str) -> list[ChapterItem]:
        """全目录在独立页 /newbook/{id}/ 上。"""
        html = await self.html("chapters", f"chaps:{book_id}", f"/newbook/{book_id}/")
        s = soup(html)
        pat = re.compile(rf"/chapter/{re.escape(book_id)}/(\w+)\.html$")
        # 优先取「全部章节」区块，避免混入侧栏「猜你喜欢」
        root: Tag = s
        for h2 in s.select("h2"):
            if "全部章节" in h2.get_text():
                root = h2.parent
                break
        items: list[ChapterItem] = []
        seen: set[str] = set()
        for a in root.select("a[href]"):
            m = pat.search(a["href"])
            if not m or m.group(1) in seen:
                continue
            title = clean(a.get_text()) or clean(a.get("title"))
            if not title:
                continue
            seen.add(m.group(1))
            items.append(
                ChapterItem(
                    index=len(items) + 1,
                    chapter_id=m.group(1),
                    title=title,
                    url=abs_url(self.base_url, a["href"]) or "",
                )
            )
        return items

    async def chapters(self, book_id: str, offset: int = 0, limit: int = 0) -> ChapterList:
        all_ch = await self._all_chapters(book_id)
        # 书名从目录页 h1 拿不到，用详情页标题兜底
        title = ""
        try:
            html = await self.html("book", f"book:{book_id}", f"/book/{book_id}/")
            h1 = soup(html).select_one("h1")
            title = clean(h1.get_text()) if h1 else ""
        except Exception:  # noqa: BLE001
            title = book_id
        picked = self.slice_chapters(all_ch, offset, limit)
        return ChapterList(
            source=self.id,
            book_id=book_id,
            title=title or book_id,
            total=len(all_ch),
            offset=offset,
            limit=limit or len(all_ch),
            returned=len(picked),
            chapters=picked,
        )

    # ---------------- 正文 ----------------
    async def chapter(self, book_id: str, chapter_id: str) -> ChapterBody:
        path = f"/chapter/{book_id}/{chapter_id}.html"
        html = await self.html("chapter", f"chap:{book_id}:{chapter_id}", path)
        s = soup(html)
        box = s.select_one("div#txt") or s.select_one("div.txt") or s.select_one("div.content")
        h1 = s.select_one("h1")
        prev_id = next_id = prev_t = next_t = None
        catalog_url = f"{self.base_url}/book/{book_id}/"
        for a in s.select("a[href]"):
            txt = clean(a.get_text()) or ""
            href = a["href"]
            m = _CHAP_HREF.search(href)
            if "上一章" in txt and m:
                prev_id, prev_t = m.group(2), txt
            elif "下一章" in txt and m:
                next_id, next_t = m.group(2), txt
            elif txt in ("目录", "章节目录", "返回目录") and _BOOK_HREF.search(href):
                catalog_url = abs_url(self.base_url, href) or catalog_url

        text, raw_html = extract_content(box)
        return ChapterBody(
            source=self.id,
            book_id=book_id,
            chapter_id=chapter_id,
            title=(clean(h1.get_text()) if h1 else None) or chapter_id,
            content=text,
            content_html=raw_html or None,
            word_count=len(re.sub(r"\s", "", text)),
            prev_chapter_id=prev_id,
            prev_chapter_title=prev_t,
            next_chapter_id=next_id,
            next_chapter_title=next_t,
            catalog_url=catalog_url,
        )
