"""统一响应模型（所有数据源共用同一套 schema）。"""
from __future__ import annotations

from typing import Optional

from pydantic import BaseModel, Field


class BookBrief(BaseModel):
    """书籍摘要（列表页用）。"""

    source: str = Field(..., description="数据源 id，如 bqg99")
    book_id: str = Field(..., description="书籍在源站的唯一 id（可能含前导零，故为字符串）")
    title: str = Field(..., description="书名")
    author: Optional[str] = Field(None, description="作者")
    category: Optional[str] = Field(None, description="分类名")
    cover: Optional[str] = Field(None, description="封面图 URL")
    intro: Optional[str] = Field(None, description="简介（列表页可能为空）")
    latest_chapter: Optional[str] = Field(None, description="最新章节标题")
    latest_chapter_id: Optional[str] = Field(None, description="最新章节 id")
    update_time: Optional[str] = Field(None, description="最后更新时间（源站原始格式）")
    url: str = Field(..., description="源站书籍详情页原始 URL")


class BookDetail(BookBrief):
    """书籍详情（含完整统计信息）。"""

    status: Optional[str] = Field(None, description="连载 / 完结")
    word_count: Optional[int] = Field(None, description="字数")
    chapter_count: int = Field(0, description="目录章节总数")
    first_chapter: Optional[str] = Field(None, description="首章标题")
    first_chapter_id: Optional[str] = Field(None, description="首章 id")


class ChapterItem(BaseModel):
    """目录中的一章。"""

    index: int = Field(..., description="序号，从 1 开始")
    chapter_id: str = Field(..., description="章节 id（可能含前导零）")
    title: str = Field(..., description="章节标题")
    url: str = Field(..., description="源站章节页原始 URL")


class ChapterList(BaseModel):
    """目录分页结果。"""

    source: str
    book_id: str
    title: str
    total: int = Field(..., description="章节总数")
    offset: int
    limit: int
    returned: int
    chapters: list[ChapterItem]


class ChapterBody(BaseModel):
    """章节正文。"""

    source: str
    book_id: str
    chapter_id: str
    title: str
    content: str = Field(..., description="纯文本正文，段落以 \\n 分隔")
    content_html: Optional[str] = Field(None, description="原始 HTML 正文（保留 <br>）")
    word_count: int = Field(0, description="正文字数")
    prev_chapter_id: Optional[str] = Field(None, description="上一章 id")
    prev_chapter_title: Optional[str] = None
    next_chapter_id: Optional[str] = Field(None, description="下一章 id")
    next_chapter_title: Optional[str] = None
    catalog_url: Optional[str] = Field(None, description="返回目录的源站 URL")


class RankEntry(BaseModel):
    """排行榜单条。"""

    rank: int
    book_id: str
    title: str
    category: Optional[str] = None
    author: Optional[str] = None
    url: str


class RankBoard(BaseModel):
    """一个排行榜。"""

    board: str = Field(..., description="榜单名，如 小说总榜 / 玄幻小说")
    total: int
    items: list[RankEntry]


class CategoryInfo(BaseModel):
    """分类元信息。"""

    slug: str = Field(..., description="分类标识，用于 /api/categories/{slug}")
    name: str = Field(..., description="分类中文名")
    url: str = Field(..., description="源站分类页 URL")
    paginated: bool = Field(False, description="是否支持翻页")


class HomeCategoryBlock(BaseModel):
    """首页分类推荐块。"""

    name: str
    books: list[BookBrief]


class HomePage(BaseModel):
    """首页聚合数据。"""

    source: str
    site_name: str
    hot_books: list[BookBrief] = Field(default_factory=list, description="热门/封面推荐")
    recommend_books: list[BookBrief] = Field(default_factory=list, description="强力推荐")
    latest_updates: list[BookBrief] = Field(default_factory=list, description="最近更新")
    category_blocks: list[HomeCategoryBlock] = Field(default_factory=list, description="各分类推荐")


class BookPage(BaseModel):
    """分页书籍列表。"""

    source: str
    kind: str = Field(..., description="列表类型：category / full / search")
    name: Optional[str] = Field(None, description="分类名或搜索词")
    page: int
    page_size: int
    total_pages: Optional[int] = Field(None, description="总页数，源站未提供时为 null")
    total: Optional[int] = Field(None, description="总条数，源站未提供时为 null")
    has_more: bool
    items: list[BookBrief]


class SourceInfo(BaseModel):
    """数据源信息与能力矩阵。"""

    id: str
    name: str
    base_url: str
    enabled: bool
    priority: int = Field(..., description="优先级，数字越小越优先")
    capabilities: dict[str, bool] = Field(..., description="各能力是否支持")
    notes: Optional[str] = None


class SourceHealth(BaseModel):
    """数据源连通性探测结果。"""

    id: str
    name: str
    ok: bool
    status_code: Optional[int] = None
    latency_ms: Optional[float] = None
    error: Optional[str] = None


class ApiError(BaseModel):
    """统一错误响应。"""

    ok: bool = False
    source: str
    error: str
    detail: Optional[str] = None


class ServiceInfo(BaseModel):
    """根路径服务信息。"""

    service: str
    version: str
    docs: str
    openapi: str
    default_source: str
    sources: list[str]
    endpoints: dict[str, str]
