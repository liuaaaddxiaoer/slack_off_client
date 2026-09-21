"""主数据源：顶点小说网 www.bqg99.cc

实测结构（2026-09 抓取确认）：
  首页   /                              div.hot(热门) / div.r.bd(强推) / div.up(最近更新) / div.type.bd div.block(分类推荐)
  分类   /xuanhuan/ 等 7 个 slug        div.l.bd ul li  → span.s1 分类 / s2 a 书名 / s3 a 最新章节 / s4 作者 / s5 日期
  排行   /rank.html                     div.wrap.rank → 8 × div.block.bd（h2 榜名 + 15 条）
  全本   /quanben/                      同分类页结构
  搜索   /s.php?ie=utf-8&q={kw}         div.so_list.bookcase div.bookbox
  详情   /book/{bid}/                   div.info(h1/cover/small/intro) + meta[property^=og:novel:*]
  目录   /book/{bid}/                   div.listmain dl（dd a，单页返回全部章节，实测 1920+ 章无需翻页）
  正文   /book/{bid}/{cid}.html         div#content + div.page_chapter(上一章/返回目录/下一章)
"""
from __future__ import annotations

import re
from typing import Optional
from urllib.parse import quote

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
    "xuanhuan": "玄幻",
    "wuxia": "武侠",
    "dushi": "都市",
    "lishi": "历史",
    "wangyou": "网游",
    "kehuan": "科幻",
    "mm": "女生",
}
CATEGORY_BY_NAME = {v: k for k, v in CATEGORIES.items()}

_BOOK_HREF = re.compile(r"/book/(\d+)/$")
_CHAP_HREF = re.compile(r"/book/(\d+)/(\w+)\.html$")


class Bqg99Source(Source):
    id = "bqg99"
    name = "顶点小说网"
    base_url = "https://www.bqg99.cc"
    encoding = "utf-8"
    priority = 10
    notes = "速度快、目录单页全量返回、支持站内搜索；分类/全本页源站不提供翻页"

    # ---------------- 内部解析工具 ----------------
    def _brief_from_li(self, li: Tag) -> Optional[BookBrief]:
        """解析 s1~s5 结构的列表项（分类页/全本页/最近更新共用）。"""
        s = {c: li.select_one(f"span.{c}") for c in ("s1", "s2", "s3", "s4", "s5")}
        a = s["s2"].select_one("a") if s["s2"] else li.select_one("a")
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
            title=clean(a.get_text()) or "",
            author=clean(s["s4"].get_text()) if s["s4"] else None,
            category=(clean(s["s1"].get_text()) or "").strip("[]") or None if s["s1"] else None,
            cover=None,
            intro=None,
            latest_chapter=clean(latest_a.get_text()) if latest_a else None,
            latest_chapter_id=cid,
            update_time=clean(s["s5"].get_text()) if s["s5"] else None,
            url=abs_url(self.base_url, a["href"]) or "",
        )

    def _brief_from_card(self, p10: Tag) -> Optional[BookBrief]:
        """解析带封面的卡片：div.p10 > div.image a img + dl(dt span 作者, dt a 书名, dd 简介)。"""
        img_a = p10.select_one("div.image a") or p10.select_one("a")
        if img_a is None or not img_a.get("href"):
            return None
        m = _BOOK_HREF.search(img_a["href"])
        if not m:
            return None
        dt = p10.select_one("dl dt")
        title_a = dt.select_one("a") if dt else img_a
        author_span = dt.select_one("span") if dt else None
        img = p10.select_one("img")
        dd = p10.select_one("dl dd")
        return BookBrief(
            source=self.id,
            book_id=m.group(1),
            title=clean(title_a.get_text()) if title_a else (clean(img.get("alt")) if img else "") or "",
            author=clean(author_span.get_text()) if author_span else None,
            category=None,
            cover=abs_url(self.base_url, img.get("src")) if img and img.get("src") else None,
            intro=clean(dd.get_text()) if dd else None,
            url=abs_url(self.base_url, img_a["href"]) or "",
        )

    def _lis_books(self, root: Tag) -> list[BookBrief]:
        out: list[BookBrief] = []
        seen: set[str] = set()
        for li in root.select("ul li"):
            b = self._brief_from_li(li)
            if b and b.book_id not in seen:
                seen.add(b.book_id)
                out.append(b)
        return out

    def _meta_from_small(self, info: Optional[Tag]) -> dict[str, Optional[str]]:
        """div.info div.small span → {作者, 分类, 状态, 字数, 更新时间}"""
        out: dict[str, Optional[str]] = {}
        if info is None:
            return out
        small = info.select_one("div.small") or info
        for sp in small.select("span"):
            txt = clean(sp.get_text()) or ""
            for label in ("作者", "分类", "状态", "字数", "更新时间"):
                if txt.startswith(label):
                    out[label] = txt.split("：", 1)[-1].split(":", 1)[-1].strip()
        return out

    # ---------------- 首页 ----------------
    async def home(self) -> HomePage:
        html = await self.html("home", "home", "/")
        s = soup(html)

        hot: list[BookBrief] = []
        hot_root = s.select_one("div.hot")
        if hot_root:
            for p10 in hot_root.select("div.p10"):
                b = self._brief_from_card(p10)
                if b:
                    hot.append(b)

        recommend: list[BookBrief] = []
        for r in s.select("div.r.bd"):
            h2 = r.select_one("h2")
            if not (h2 and "推荐" in h2.get_text()):
                continue
            for li in r.select("ul li"):
                a = li.select_one("span.s2 a") or li.select_one("a")
                if a is None or not a.get("href"):
                    continue
                m = _BOOK_HREF.search(a["href"])
                if not m:
                    continue
                cat_sp = li.select_one("span.s1")
                au_sp = li.select_one("span.s5")
                recommend.append(
                    BookBrief(
                        source=self.id,
                        book_id=m.group(1),
                        title=clean(a.get_text()) or "",
                        category=(clean(cat_sp.get_text()) or "").strip("[]") or None if cat_sp else None,
                        author=clean(au_sp.get_text()) if au_sp else None,
                        url=abs_url(self.base_url, a["href"]) or "",
                    )
                )
            break

        latest: list[BookBrief] = []
        up = s.select_one("div.up")
        if up:
            latest = self._lis_books(up)

        blocks: list[HomeCategoryBlock] = []
        for blk in s.select("div.type.bd div.block"):
            h2 = blk.select_one("h2")
            name = clean(h2.get_text()) if h2 else "推荐"
            books: list[BookBrief] = []
            seen: set[str] = set()
            top = blk.select_one("div.top")
            if top:
                b = self._brief_from_card(top)
                if b:
                    books.append(b)
                    seen.add(b.book_id)
            for li in blk.select("ul li"):
                a = li.select_one("a")
                if not a or not a.get("href"):
                    continue
                m = _BOOK_HREF.search(a["href"])
                if not m or m.group(1) in seen:
                    continue
                seen.add(m.group(1))
                raw = clean(li.get_text()) or ""
                author = raw.split("/")[-1].strip() if "/" in raw else None
                books.append(
                    BookBrief(
                        source=self.id,
                        book_id=m.group(1),
                        title=clean(a.get_text()) or "",
                        author=author,
                        url=abs_url(self.base_url, a["href"]) or "",
                    )
                )
            if books:
                blocks.append(HomeCategoryBlock(name=name or "推荐", books=books))

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
            CategoryInfo(
                slug=slug,
                name=name,
                url=f"{self.base_url}/{slug}/",
                paginated=False,
            )
            for slug, name in CATEGORIES.items()
        ]

    async def category_books(self, slug: str, page: int = 1) -> BookPage:
        slug = slug.strip().lower()
        if slug in CATEGORY_BY_NAME:  # 允许传中文分类名
            slug = CATEGORY_BY_NAME[slug]
        if slug not in CATEGORIES:
            raise KeyError(f"未知分类 {slug}，可选：{', '.join(CATEGORIES)}")
        html = await self.html("category", f"cat:{slug}:{page}", f"/{slug}/")
        s = soup(html)
        items: list[BookBrief] = []
        left = s.select_one("div.l.bd")
        if left:
            items = self._lis_books(left)
        if not items:  # 兜底：全页扫
            items = self._lis_books(s)
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
        html = await self.html("rank", f"rank:{board or 'all'}", "/rank.html")
        s = soup(html)
        wrap = s.select_one("div.wrap.rank") or s
        out: list[RankBoard] = []
        for blk in wrap.select("div.block.bd"):
            h2 = blk.select_one("h2")
            name = clean(h2.get_text()) if h2 else "榜单"
            if board and board not in (name or ""):
                continue
            entries: list[RankEntry] = []
            for i, li in enumerate(blk.select("ul li"), start=1):
                a = li.select_one("a")
                if not a or not a.get("href"):
                    continue
                m = _BOOK_HREF.search(a["href"])
                if not m:
                    continue
                spans = [clean(sp.get_text()) or "" for sp in li.select("span")]
                rank_no = i
                if spans and spans[0].isdigit():
                    rank_no = int(spans[0])
                cat = next((x.strip("[]") for x in spans if x.strip("[]") in CATEGORIES.values()), None)
                entries.append(
                    RankEntry(
                        rank=rank_no,
                        book_id=m.group(1),
                        title=clean(a.get_text()) or "",
                        category=cat,
                        author=None,
                        url=abs_url(self.base_url, a["href"]) or "",
                    )
                )
            if entries:
                out.append(RankBoard(board=name or "榜单", total=len(entries), items=entries))
        if board and not out:
            raise KeyError(f"未找到榜单 {board}")
        return out

    # ---------------- 全本 ----------------
    async def full_books(self, page: int = 1) -> BookPage:
        html = await self.html("full", f"full:{page}", "/quanben/")
        s = soup(html)
        items = self._lis_books(s.select_one("div.l.bd") or s)
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

    # ---------------- 搜索 ----------------
    async def search(self, keyword: str, page: int = 1) -> BookPage:
        kw = keyword.strip()
        if not kw:
            raise ValueError("搜索词不能为空")
        path = f"/s.php?ie=utf-8&q={quote(kw)}"
        html = await self.html("search", f"search:{kw}:{page}", path)
        s = soup(html)
        items: list[BookBrief] = []
        boxes = s.select("div.bookbox") or s.select("div.so_list div.type_show > div")
        for box in boxes:
            a = box.select_one("h4.bookname a") or box.select_one("div.bookimg a") or box.select_one("a")
            if not a or not a.get("href"):
                continue
            m = _BOOK_HREF.search(a["href"])
            if not m:
                continue
            img = box.select_one("img")
            cat = box.select_one("div.cat")
            author = box.select_one("div.author")
            upd = box.select_one("div.update a")
            intro = box.select_one("div.bookinfo p") or box.select_one("p")
            items.append(
                BookBrief(
                    source=self.id,
                    book_id=m.group(1),
                    title=clean(a.get_text()) or (clean(img.get("alt")) if img else "") or "",
                    author=(clean(author.get_text()) or "").split("：", 1)[-1] if author else None,
                    category=(clean(cat.get_text()) or "").split("：", 1)[-1] if cat else None,
                    cover=abs_url(self.base_url, img.get("src")) if img and img.get("src") else None,
                    intro=clean(intro.get_text()) if intro else None,
                    latest_chapter=clean(upd.get_text()) if upd else None,
                    latest_chapter_id=(_CHAP_HREF.search(upd["href"]).group(2)
                                       if upd and upd.get("href") and _CHAP_HREF.search(upd["href"]) else None),
                    url=abs_url(self.base_url, a["href"]) or "",
                )
            )
        # 去重（源站会返回同一本书的多个镜像条目）
        uniq: list[BookBrief] = []
        seen: set[tuple[str, str]] = set()
        for b in items:
            k = (b.title, b.author or "")
            if k in seen:
                continue
            seen.add(k)
            uniq.append(b)
        return BookPage(
            source=self.id,
            kind="search",
            name=kw,
            page=1,
            page_size=len(uniq),
            total_pages=1,
            total=len(uniq),
            has_more=False,
            items=uniq,
        )

    # ---------------- 详情 + 目录 ----------------
    async def _book_soup(self, book_id: str) -> BeautifulSoup:
        html = await self.html("book", f"book:{book_id}", f"/book/{book_id}/")
        return soup(html)

    async def book_detail(self, book_id: str) -> BookDetail:
        s = await self._book_soup(book_id)
        info = s.select_one("div.info")
        h1 = (info.select_one("h1") if info else None) or s.select_one("h1")
        small = self._meta_from_small(info)
        intro_el = s.select_one("div.intro")
        cover = (info.select_one("div.cover img") if info else None) or s.select_one("img")
        chapters = self._extract_chapters(s, book_id)
        wc = small.get("字数")
        latest_url = self.og(s, "og:novel:latest_chapter_url")
        latest_id = None
        if latest_url:
            m = _CHAP_HREF.search(latest_url)
            latest_id = m.group(2) if m else None
        return BookDetail(
            source=self.id,
            book_id=book_id,
            title=clean(h1.get_text()) if h1 else (self.og(s, "og:title") or ""),
            author=small.get("作者") or self.og(s, "og:novel:author"),
            category=small.get("分类") or self.og(s, "og:novel:category"),
            cover=abs_url(self.base_url, cover.get("src")) if cover and cover.get("src") else self.og(s, "og:image"),
            intro=(clean_intro(intro_el.get_text(" ")) if intro_el else None)
            or clean(self.og(s, "og:description")),
            latest_chapter=small.get("最新章节") or self.og(s, "og:novel:latest_chapter_name"),
            latest_chapter_id=latest_id,
            update_time=small.get("更新时间") or self.og(s, "og:novel:update_time"),
            status=small.get("状态") or self.og(s, "og:novel:status"),
            word_count=int(wc) if wc and wc.isdigit() else None,
            chapter_count=len(chapters),
            first_chapter=chapters[0].title if chapters else None,
            first_chapter_id=chapters[0].chapter_id if chapters else None,
            url=f"{self.base_url}/book/{book_id}/",
        )

    def _extract_chapters(self, s: BeautifulSoup, book_id: str) -> list[ChapterItem]:
        """从 div.listmain dl 提取全部章节（源站单页返回，无需翻页）。"""
        pat = re.compile(rf"/book/{re.escape(book_id)}/(\w+)\.html$")
        candidates: list[Tag] = []
        lm = s.select_one("div.listmain")
        roots = [lm] if lm else []
        roots += [d for d in s.select("div#list, div.book, div.box_con") if d not in roots]
        if not roots:
            roots = [s]
        best: list[ChapterItem] = []
        for root in roots:
            for dl in root.select("dl") or [root]:
                items: list[ChapterItem] = []
                seen: set[str] = set()
                idx = 0
                for a in dl.select("a[href]"):
                    m = pat.search(a["href"])
                    if not m or m.group(1) in seen:
                        continue
                    title = clean(a.get_text()) or clean(a.get("title"))
                    if not title:
                        continue
                    seen.add(m.group(1))
                    idx += 1
                    items.append(
                        ChapterItem(
                            index=idx,
                            chapter_id=m.group(1),
                            title=title,
                            url=abs_url(self.base_url, a["href"]) or "",
                        )
                    )
                if len(items) > len(best):
                    best = items
        return best

    async def chapters(self, book_id: str, offset: int = 0, limit: int = 0) -> ChapterList:
        s = await self._book_soup(book_id)
        all_ch = self._extract_chapters(s, book_id)
        h1 = s.select_one("div.info h1") or s.select_one("h1")
        picked = self.slice_chapters(all_ch, offset, limit)
        return ChapterList(
            source=self.id,
            book_id=book_id,
            title=clean(h1.get_text()) if h1 else (self.og(s, "og:title") or ""),
            total=len(all_ch),
            offset=offset,
            limit=limit or len(all_ch),
            returned=len(picked),
            chapters=picked,
        )

    # ---------------- 正文 ----------------
    async def chapter(self, book_id: str, chapter_id: str) -> ChapterBody:
        path = f"/book/{book_id}/{chapter_id}.html"
        html = await self.html("chapter", f"chap:{book_id}:{chapter_id}", path)
        s = soup(html)
        box = s.select_one("div#content") or s.select_one("div.content") or s.select_one("div.bookcontent")
        h1 = s.select_one("h1")
        prev_id = next_id = prev_t = next_t = None
        catalog_url = f"{self.base_url}/book/{book_id}/"
        for a in s.select("div.page_chapter a, div.page a, div.bottom a"):
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
        title = clean(h1.get_text()) if h1 else None
        if not title:
            t = s.select_one("title")
            title = clean(t.get_text()).split("_")[1] if t and "_" in t.get_text() else ""
        return ChapterBody(
            source=self.id,
            book_id=book_id,
            chapter_id=chapter_id,
            title=title or chapter_id,
            content=text,
            content_html=raw_html or None,
            word_count=len(re.sub(r"\s", "", text)),
            prev_chapter_id=prev_id,
            prev_chapter_title=prev_t,
            next_chapter_id=next_id,
            next_chapter_title=next_t,
            catalog_url=catalog_url,
        )
