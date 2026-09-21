"""第三数据源：顶点小说网镜像 www.blqvdu.cc

与主源 bqg99.cc 是**同一套模板**（div.up 的 s1~s5 列表、div.wrap.rank 榜单、
div.listmain dl 目录、div#content 正文、og:novel:* 元数据全部一致），
差异只在 URL 形态与能力：

  首页   /                          div.hot(封面推荐) / div.r.bd(强力推荐) / div.up(最近更新·最新入库) / div.type.bd div.block
  分类   /class/{1..10}_1.html      div.up|div.l.bd ul li（span.s1 分类 / s2 书名 / s3 最新章节 / s4 作者 / s5 日期）；源站无翻页
  排行   /top/                      div.wrap.rank → 10 × div.block.bd（h2 榜名 + 15 条）
  全本   /full.html                 同分类页结构
  搜索   ❌ 源站未提供（404）
  详情   /{catId}_{bookId}/         h1 + div.intro + img 封面 + og:novel:*
  目录   /{catId}_{bookId}/         div.listmain dl，按 dt 分段（「最新章节」12 条 + 「正文卷」全量），取最大段
  正文   /{catId}_{bookId}/{cid}.html  div#content（服务端渲染，<p> 分段）+ div.page_chapter 导航

注意：首页含 display:none 的隐藏块，内有 /73560_73560376/《雪中悍刀行》电视剧 之类伪书籍链接，必须排除。
"""
from __future__ import annotations

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
    "2": "武侠修真",
    "3": "都市言情",
    "4": "历史军事",
    "5": "侦探推理",
    "6": "网游动漫",
    "7": "科幻小说",
    "8": "恐怖灵异",
    "10": "其他类型",
}
NAME_TO_SLUG = {v: k for k, v in CATEGORIES.items()}

# 书籍 id 形如 4_4903（分类id_书籍id），章节 id 形如 82939022
_BOOK_HREF = re.compile(r"/(\d+_\d+)/$")
_CHAP_HREF = re.compile(r"/(\d+_\d+)/(\w+)\.html$")


def _hidden(el: Tag) -> bool:
    """判断元素是否位于 display:none 容器内（源站首页有伪书籍链接藏在其中）。"""
    node: Optional[Tag] = el
    while node is not None and node.name != "[document]":
        style = (node.get("style") or "") if isinstance(node, Tag) else ""
        if "display:none" in style.replace(" ", "").lower():
            return True
        node = node.parent
    return False


class BlqvduSource(Source):
    id = "blqvdu"
    name = "顶点小说网(镜像)"
    base_url = "https://www.blqvdu.cc"
    encoding = "gb18030"   # 源站 header=gb2312 / meta=gbk，统一用超集 gb18030
    priority = 15
    notes = "与主源同模板；目录单页全量（实测《百炼飞升录》8684 章）、正文服务端渲染；无站内搜索、分类不翻页"

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
    def _brief_from_li(self, li: Tag) -> Optional[BookBrief]:
        """s1~s5 列表项（与主源同构）。"""
        if _hidden(li):
            return None
        s = {c: li.select_one(f"span.{c}") for c in ("s1", "s2", "s3", "s4", "s5")}
        a = (s["s2"].select_one("a") if s["s2"] else None) or li.select_one("a[href]")
        if a is None or not a.get("href"):
            return None
        m = _BOOK_HREF.search(a["href"])
        if not m:
            return None
        latest_a = s["s3"].select_one("a") if s["s3"] else None
        cid = None
        if latest_a and latest_a.get("href"):
            cm = _CHAP_HREF.search(latest_a["href"])
            cid = cm.group(2) if cm else None
        return BookBrief(
            source=self.id,
            book_id=m.group(1),
            title=(clean(a.get_text()) or "").strip("《》"),
            author=clean(s["s4"].get_text()) if s["s4"] else None,
            category=(clean(s["s1"].get_text()) or "").strip("[]") or None if s["s1"] else None,
            latest_chapter=clean(latest_a.get_text()) if latest_a else None,
            latest_chapter_id=cid,
            update_time=clean(s["s5"].get_text()) if s["s5"] else None,
            url=abs_url(self.base_url, a["href"]) or "",
        )

    def _brief_from_card(self, box: Tag) -> Optional[BookBrief]:
        """封面卡片：div.p10/div.item > div.image a img + dl(dt a 书名, dd 简介)。"""
        if _hidden(box):
            return None
        a = box.select_one("div.image a") or box.select_one("a[href]")
        if a is None or not a.get("href"):
            return None
        m = _BOOK_HREF.search(a["href"])
        if not m:
            return None
        img = box.select_one("img")
        dt = box.select_one("dl dt")
        title_a = (dt.select_one("a") if dt else None) or a
        author_span = dt.select_one("span") if dt else None
        dd = box.select_one("dl dd")
        title = (clean(title_a.get_text()) if title_a else None) or (clean(img.get("alt")) if img else None) or ""
        return BookBrief(
            source=self.id,
            book_id=m.group(1),
            title=title.strip("《》"),
            author=clean(author_span.get_text()) if author_span else None,
            cover=abs_url(self.base_url, img.get("src")) if img and img.get("src") else None,
            intro=clean(dd.get_text(" ")) if dd else None,
            url=abs_url(self.base_url, a["href"]) or "",
        )

    def _rows(self, root: Tag) -> list[BookBrief]:
        """优先取 s1~s5 行式列表，其次取封面卡片；按 book_id 去重保序。"""
        out: list[BookBrief] = []
        seen: set[str] = set()
        for li in root.select("ul li"):
            b = self._brief_from_li(li)
            if b and b.book_id not in seen:
                seen.add(b.book_id)
                out.append(b)
        for sel in ("div.p10", "div.item"):
            for card in root.select(sel):
                b = self._brief_from_card(card)
                if b and b.book_id not in seen:
                    seen.add(b.book_id)
                    out.append(b)
        return out

    def _list_root(self, s: BeautifulSoup) -> Tag:
        """分类/全本页的主列表容器。"""
        for sel in ("div.up", "div.l.bd", "div.hot.bd"):
            el = s.select_one(sel)
            if el is not None and el.select("a[href]"):
                return el
        return s

    # ---------------- 首页 ----------------
    async def home(self) -> HomePage:
        html = await self.html("home", "home", "/")
        s = soup(html)

        hot: list[BookBrief] = []
        hot_root = s.select_one("div.hot")
        if hot_root:
            seen: set[str] = set()
            for sel in ("div.p10", "div.item"):
                for card in hot_root.select(sel):
                    b = self._brief_from_card(card)
                    if b and b.book_id not in seen:
                        seen.add(b.book_id)
                        hot.append(b)

        recommend: list[BookBrief] = []
        for r in s.select("div.r.bd"):
            h2 = r.select_one("h2")
            if not (h2 and "推荐" in h2.get_text()):
                continue
            recommend = self._rows(r)
            break

        latest: list[BookBrief] = []
        for u in s.select("div.up"):
            h2 = u.select_one("h2")
            if h2 and "更新" in h2.get_text():
                latest = self._rows(u)
                break
        if not latest:
            up = s.select_one("div.up")
            latest = self._rows(up) if up else []

        blocks: list[HomeCategoryBlock] = []
        seen_b: set[str] = set()
        for blk in s.select("div.type.bd div.block, div.block"):
            h2 = blk.select_one("h2")
            name = (clean(h2.get_text()) if h2 else None) or ""
            if not name or name in seen_b:
                continue
            books = self._rows(blk)[:30]
            if books:
                seen_b.add(name)
                blocks.append(HomeCategoryBlock(name=name, books=books))

        return HomePage(
            source=self.id,
            site_name=self.name,
            hot_books=hot,
            recommend_books=recommend,
            latest_updates=latest,
            category_blocks=blocks,
        )

    # ---------------- 分类 ----------------
    async def categories(self) -> list[CategoryInfo]:
        return [
            CategoryInfo(slug=slug, name=name, url=f"{self.base_url}/class/{slug}_1.html", paginated=False)
            for slug, name in CATEGORIES.items()
        ]

    async def category_books(self, slug: str, page: int = 1) -> BookPage:
        slug = slug.strip()
        if slug in NAME_TO_SLUG:
            slug = NAME_TO_SLUG[slug]
        if slug not in CATEGORIES:
            raise KeyError(f"未知分类 {slug}，可选：{', '.join(f'{k}={v}' for k, v in CATEGORIES.items())}")
        html = await self.html("category", f"class:{slug}:{page}", f"/class/{slug}_1.html")
        s = soup(html)
        items = self._rows(self._list_root(s))
        return BookPage(
            source=self.id,
            kind="category",
            name=CATEGORIES[slug],
            page=1,
            page_size=len(items),
            total_pages=1,
            total=len(items),
            has_more=False,
            items=items,
        )

    # ---------------- 排行 ----------------
    async def ranks(self, board: Optional[str] = None) -> list[RankBoard]:
        html = await self.html("rank", f"top:{board or 'all'}", "/top/")
        s = soup(html)
        wrap = s.select_one("div.wrap.rank") or s
        out: list[RankBoard] = []
        for blk in wrap.select("div.block.bd"):
            h2 = blk.select_one("h2")
            name = (clean(h2.get_text()) if h2 else None) or "榜单"
            if board and board not in name:
                continue
            entries: list[RankEntry] = []
            for i, li in enumerate(blk.select("ul li"), start=1):
                if _hidden(li):
                    continue
                a = li.select_one("a[href]")
                if a is None or not a.get("href"):
                    continue
                m = _BOOK_HREF.search(a["href"])
                if not m:
                    continue
                spans = [clean(sp.get_text()) or "" for sp in li.select("span")]
                rank_no = int(spans[0]) if spans and spans[0].isdigit() else i
                raw = clean(li.get_text()) or ""
                author = raw.split("/")[-1].strip() if "/" in raw else None
                entries.append(
                    RankEntry(
                        rank=rank_no,
                        book_id=m.group(1),
                        title=(clean(a.get_text()) or "").strip("《》"),
                        category=name if name != "小说总榜" else None,
                        author=author or None,
                        url=abs_url(self.base_url, a["href"]) or "",
                    )
                )
            if entries:
                out.append(RankBoard(board=name, total=len(entries), items=entries))
        if board and not out:
            raise KeyError(f"未找到榜单 {board}")
        return out

    # ---------------- 全本 ----------------
    async def full_books(self, page: int = 1) -> BookPage:
        html = await self.html("full", f"full:{page}", "/full.html")
        s = soup(html)
        items = self._rows(self._list_root(s))
        return BookPage(
            source=self.id,
            kind="full",
            name="全本小说",
            page=1,
            page_size=len(items),
            total_pages=1,
            total=len(items),
            has_more=False,
            items=items,
        )

    # ---------------- 搜索：源站未提供 ----------------
    async def search(self, keyword: str, page: int = 1) -> BookPage:
        raise self.not_supported("站内搜索（请用 source=bqg99）")

    # ---------------- 详情 + 目录 ----------------
    async def _book_soup(self, book_id: str) -> BeautifulSoup:
        return soup(await self.html("book", f"book:{book_id}", f"/{book_id}/"))

    def _extract_chapters(self, s: BeautifulSoup, book_id: str) -> list[ChapterItem]:
        """div.listmain dl 按 dt 分段：「最新章节」是重复块，「正文卷」才是全量目录 → 取最大段。"""
        pat = re.compile(rf"/{re.escape(book_id)}/(\w+)\.html$")
        lm = s.select_one("div.listmain") or s.select_one("div#list") or s
        best: list[ChapterItem] = []
        for dl in lm.select("dl") or [lm]:
            segments: list[list[tuple[str, str]]] = []
            cur: list[tuple[str, str]] = []
            for el in dl.children:
                name = getattr(el, "name", None)
                if name == "dt":
                    if cur:
                        segments.append(cur)
                    cur = []
                elif name == "dd":
                    a = el.select_one("a[href]")
                    if a is None:
                        continue
                    m = pat.search(a["href"])
                    if not m:
                        continue
                    title = clean(a.get_text()) or clean(a.get("title"))
                    if title:
                        cur.append((m.group(1), title))
            if cur:
                segments.append(cur)
            for seg in segments:
                if len(seg) > len(best):
                    best = seg
        items: list[ChapterItem] = []
        seen: set[str] = set()
        for cid, title in best:
            if cid in seen:
                continue
            seen.add(cid)
            items.append(
                ChapterItem(
                    index=len(items) + 1,
                    chapter_id=cid,
                    title=title,
                    url=f"{self.base_url}/{book_id}/{cid}.html",
                )
            )
        return items

    async def book_detail(self, book_id: str) -> BookDetail:
        s = await self._book_soup(book_id)
        h1 = s.select_one("h1")
        intro_el = s.select_one("div.intro") or s.select_one("div#intro") or s.select_one("div.desc")
        cover = s.select_one("div.cover img") or s.select_one("img[src*='article/image']") or s.select_one("img")
        chapters = self._extract_chapters(s, book_id)

        # div.small / div.xinxi 里的「作者：X 分类：Y 状态：Z 字数：N 更新时间：T」
        meta: dict[str, Optional[str]] = {}
        scope = s.select_one("div.info") or s.select_one("div.small") or s
        for sp in scope.select("span"):
            txt = clean(sp.get_text(" ")) or ""
            for label in ("作者", "分类", "状态", "字数", "更新时间"):
                if txt.startswith(label):
                    meta[label] = txt.split("：", 1)[-1].split(":", 1)[-1].strip()
        latest_url = self.og(s, "og:novel:latest_chapter_url")
        latest_id = None
        if latest_url:
            m = _CHAP_HREF.search(latest_url)
            latest_id = m.group(2) if m else None
        wc = meta.get("字数")
        desc = self.og(s, "og:description") or (s.select_one('meta[name="description"]') or {}).get("content")
        return BookDetail(
            source=self.id,
            book_id=book_id,
            title=(clean(h1.get_text()) if h1 else None) or self.og(s, "og:novel:book_name") or "",
            author=meta.get("作者") or self.og(s, "og:novel:author"),
            category=meta.get("分类") or self.og(s, "og:novel:category"),
            cover=abs_url(self.base_url, cover.get("src")) if cover and cover.get("src") else self.og(s, "og:image"),
            intro=(clean_intro(intro_el.get_text(" ")) if intro_el else None) or clean(desc),
            latest_chapter=meta.get("最新章节") or self.og(s, "og:novel:latest_chapter_name"),
            latest_chapter_id=latest_id,
            update_time=meta.get("更新时间") or self.og(s, "og:novel:update_time"),
            status=meta.get("状态") or self.og(s, "og:novel:status"),
            word_count=int(re.sub(r"\D", "", wc)) if wc and re.search(r"\d", wc) else None,
            chapter_count=len(chapters),
            first_chapter=chapters[0].title if chapters else None,
            first_chapter_id=chapters[0].chapter_id if chapters else None,
            url=f"{self.base_url}/{book_id}/",
        )

    async def chapters(self, book_id: str, offset: int = 0, limit: int = 0) -> ChapterList:
        s = await self._book_soup(book_id)
        all_ch = self._extract_chapters(s, book_id)
        h1 = s.select_one("h1")
        picked = self.slice_chapters(all_ch, offset, limit)
        return ChapterList(
            source=self.id,
            book_id=book_id,
            title=(clean(h1.get_text()) if h1 else None) or self.og(s, "og:novel:book_name") or book_id,
            total=len(all_ch),
            offset=offset,
            limit=limit or len(all_ch),
            returned=len(picked),
            chapters=picked,
        )

    # ---------------- 正文 ----------------
    async def chapter(self, book_id: str, chapter_id: str) -> ChapterBody:
        path = f"/{book_id}/{chapter_id}.html"
        html = await self.html("chapter", f"chap:{book_id}:{chapter_id}", path)
        s = soup(html)
        box = s.select_one("div#content") or s.select_one("div.showtxt") or s.select_one("div.content")
        h1 = s.select_one("h1")
        prev_id = next_id = prev_t = next_t = None
        catalog_url = f"{self.base_url}/{book_id}/"
        for a in s.select("div.page_chapter a, div.page a, div.link a"):
            txt = clean(a.get_text()) or ""
            href = a.get("href") or ""
            m = _CHAP_HREF.search(href)
            if "上一章" in txt and m:
                prev_id, prev_t = m.group(2), txt
            elif "下一章" in txt and m:
                next_id, next_t = m.group(2), txt
            elif ("目录" in txt or "返回" in txt) and _BOOK_HREF.search(href):
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
