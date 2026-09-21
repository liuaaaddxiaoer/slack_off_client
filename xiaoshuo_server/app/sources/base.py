"""数据源抽象基类与公共解析工具。"""
from __future__ import annotations

import re
from abc import ABC, abstractmethod
from typing import Optional
from urllib.parse import urljoin

from bs4 import BeautifulSoup, Tag

from app.client import FetchError, fetcher
from app.models import (
    BookBrief,
    BookDetail,
    BookPage,
    ChapterBody,
    ChapterItem,
    ChapterList,
    CategoryInfo,
    HomePage,
    RankBoard,
)

soup_parser = "lxml"


def soup(html: str) -> BeautifulSoup:
    return BeautifulSoup(html, soup_parser)


def clean(text: Optional[str]) -> Optional[str]:
    """压缩空白；空串归一为 None。"""
    if text is None:
        return None
    t = re.sub(r"[\t\r\f\v]+", " ", text)
    t = re.sub(r"[ \u3000]{2,}", " ", t).strip()
    t = t.replace("\u00a0", " ")
    return t or None


def clean_multiline(text: Optional[str]) -> Optional[str]:
    """正文清洗：按 <br> 转换成的换行分段，去掉空行与广告尾巴。"""
    if text is None:
        return None
    lines = [re.sub(r"[ \u3000\t]+", " ", ln).strip() for ln in text.split("\n")]
    return "\n".join(ln for ln in lines if ln) or None


_AD_PATTERNS = [
    r"www\.[\w-]+\.(?:cc|com|net|org|la|so)",
    r"m\.[\w-]+\.(?:cc|com|net|org)",
    r"手机(?:版)?(?:阅读|更新)?(?:网址|地址)?[:：]",
    r"\d*\s*秒记住",
    r"^一秒记住",
    r"^请记住本书首发域名",
    r"^本站内容仅供",
    r"天才一秒记住",
    r"手机版更新最快网址",
    r"^请收藏本站",
    r"^最新章节！",
    r"无弹窗",
    r"笔趣阁.*?更新最快",
    r"^顶点小说网转载",
]
_AD_RE = re.compile("|".join(_AD_PATTERNS))


def strip_ads(content: str) -> str:
    """按行剔除常见源站广告语（保守匹配，只删明显广告行）。"""
    kept = []
    for ln in content.split("\n"):
        if _AD_RE.search(ln) and len(ln) < 120:
            continue
        kept.append(ln)
    return "\n".join(kept)


_INTRO_LABEL = re.compile(r"^\s*(?:内容简介|书籍简介|作品简介|简介|内容介绍)\s*[：:]\s*")


def clean_intro(text: Optional[str]) -> Optional[str]:
    """清洗简介：去掉「简介：」之类的前缀标签。"""
    t = clean(text)
    if not t:
        return None
    return _INTRO_LABEL.sub("", t).strip() or None


def extract_content(box: Optional[Tag]) -> tuple[str, str]:
    """从正文容器提取 (纯文本, 原始HTML)。

    统一处理三类源站脏数据：
      1. 容器内夹带 <script>/<style>（如 app2(); read2(); 广告注入）；
      2. 段落用 <br> 或 <p> 分隔；
      3. 段落标签被 HTML 实体转义（blqvdu 的 &lt;p&gt;…&lt;/p&gt;），
         需 unescape 后二次解析，否则正文里会残留字面 "<p>"。
    """
    if box is None:
        return "", ""
    import copy
    import html as _html

    node = copy.copy(box)  # 避免污染原始 soup（content_html 需保留原样）
    raw_html = str(box)
    for tag in node.find_all(["script", "style"]):
        tag.decompose()
    for br in node.find_all("br"):
        br.replace_with("\n")
    for p in node.find_all("p"):
        p.append("\n")
    text = node.get_text()
    unescaped = _html.unescape(text)
    if "<" in unescaped and re.search(r"</?(?:p|br|div|span)\b", unescaped, re.I):
        # 转义标签还原成真标签，二次解析取纯文本
        unescaped = BeautifulSoup(unescaped, soup_parser).get_text()
    text = clean_multiline(re.sub(r"[ \u3000]*\n[ \u3000]*", "\n", unescaped)) or ""
    return strip_ads(text), raw_html


def abs_url(base: str, href: Optional[str]) -> Optional[str]:
    if not href:
        return None
    href = href.strip()
    if href.startswith(("javascript:", "#", "mailto:")):
        return None
    return urljoin(base, href)


class Source(ABC):
    """一个小说站点的适配器。子类只需实现解析逻辑，抓取与缓存由基类统一负责。"""

    id: str = "base"
    name: str = "未知源"
    base_url: str = ""
    encoding: Optional[str] = "utf-8"
    priority: int = 100
    enabled: bool = True
    notes: Optional[str] = None

    #: 能力矩阵，main.py 会读取用于 /api/sources
    capabilities: dict[str, bool] = {
        "home": True,
        "categories": True,
        "category_books": True,
        "ranks": True,
        "full_books": True,
        "search": True,
        "book_detail": True,
        "chapters": True,
        "chapter_body": True,
    }

    # ---------------- 抓取 ----------------
    async def html(self, kind: str, key: str, path: str) -> str:
        url = urljoin(self.base_url + "/", path.lstrip("/"))
        return await fetcher.get_cached(kind, f"{self.id}:{key}", url, self.encoding)

    async def probe(self) -> dict:
        """连通性探测，供 /api/sources/health 使用。"""
        import time

        t0 = time.perf_counter()
        try:
            html = await fetcher.get_html(self.base_url + "/", self.encoding)
            return {
                "ok": True,
                "status_code": 200,
                "latency_ms": round((time.perf_counter() - t0) * 1000, 1),
                "error": None,
                "_bytes": len(html),
            }
        except Exception as e:  # noqa: BLE001
            status = getattr(e, "status", None)
            return {
                "ok": False,
                "status_code": status,
                "latency_ms": round((time.perf_counter() - t0) * 1000, 1),
                "error": f"{type(e).__name__}: {e}",
            }

    # ---------------- 子类必须实现 ----------------
    @abstractmethod
    async def home(self) -> HomePage: ...

    @abstractmethod
    async def categories(self) -> list[CategoryInfo]: ...

    @abstractmethod
    async def category_books(self, slug: str, page: int = 1) -> BookPage: ...

    @abstractmethod
    async def ranks(self, board: Optional[str] = None) -> list[RankBoard]: ...

    @abstractmethod
    async def full_books(self, page: int = 1) -> BookPage: ...

    @abstractmethod
    async def search(self, keyword: str, page: int = 1) -> BookPage: ...

    @abstractmethod
    async def book_detail(self, book_id: str) -> BookDetail: ...

    @abstractmethod
    async def chapters(self, book_id: str, offset: int = 0, limit: int = 0) -> ChapterList: ...

    @abstractmethod
    async def chapter(self, book_id: str, chapter_id: str) -> ChapterBody: ...

    # ---------------- 公共辅助 ----------------
    def not_supported(self, feature: str) -> FetchError:
        return FetchError(self.base_url, f"源 {self.id} 不支持 {feature}")

    @staticmethod
    def slice_chapters(
        chapters: list[ChapterItem], offset: int, limit: int
    ) -> list[ChapterItem]:
        if limit <= 0:
            return chapters[offset:]
        return chapters[offset : offset + limit]

    @staticmethod
    def og(s: BeautifulSoup, key: str) -> Optional[str]:
        for attr in ("property", "name"):
            tag = s.find("meta", attrs={attr: key})
            if tag and tag.get("content"):
                return clean(tag["content"])
        return None
