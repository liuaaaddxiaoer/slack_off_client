"""小说聚合 API — FastAPI 入口。

统一封装笔趣阁系站点的「首页 / 分类 / 排行榜 / 全本 / 搜索 / 书籍详情 / 目录 / 章节正文」为 JSON 接口。
默认端口 4321，交互式文档 /docs（Swagger UI）与 /redoc（ReDoc）。
"""
from __future__ import annotations

import logging
import time
from contextlib import asynccontextmanager
from typing import Annotated, Any, Optional

from fastapi import Depends, FastAPI, HTTPException, Path, Query
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse

from app import __version__
from app.client import FetchError, cache, fetcher
from app.models import (
    ApiError,
    BookDetail,
    BookPage,
    ChapterBody,
    ChapterList,
    CategoryInfo,
    HomePage,
    RankBoard,
    ServiceInfo,
    SourceHealth,
    SourceInfo,
)
from app.sources import (
    DEFAULT_SOURCE,
    SOURCES,
    first_healthy,
    get_source,
    health_snapshot,
    ordered_sources,
    record_health,
    source_infos,
)
from app.sources.base import Source

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
log = logging.getLogger("xiaoshuo.api")

DESCRIPTION = """
把笔趣阁系小说站点的 HTML 页面实时解析成结构化 JSON 接口。**不落库、不预抓取**，请求到来时才回源，
并用 TTL 内存缓存降低对源站的压力。

### 数据源
| source id | 站点 | 优先级 | 特点 |
|---|---|---|---|
| `bqg99` | 顶点小说网 www.bqg99.cc | 10 | 响应最快（p50 ≈ 0.54s）、目录单页全量、**唯一支持站内搜索** |
| `blqvdu` | 顶点小说网镜像 www.blqvdu.cc | 15 | 与主源同模板、正文服务端渲染、目录单页全量（实测 8684 章）；无搜索 |
| `biquge365` | 新笔趣阁 www.biquge365.net | 20 | 分类页支持深度分页（玄幻魔法 1769 页），藏书量大；无搜索 |

### 通用约定
- 所有接口都接受 `source` 查询参数，**默认 `auto`**：列表类接口按健康度顺序逐个故障转移；
  id 类接口（详情/目录/正文）因 book_id 跨源不通用，`auto` 会取「当前健康且优先级最高」的源。
- 健康度自动跟踪：任一回源成功/失败都会登记（TTL 默认 300s），坏源不会被反复重试拖慢请求，
  过期后自动重新试探——因此主源临时宕机或恢复都无需重启服务。
- `book_id` / `chapter_id` **是源站私有 id，跨源不通用**，可能含前导零，因此一律是字符串。
  请先用搜索/列表接口拿到 `source` + `book_id`，再请求详情、目录、正文。
- 上游偶发 TLS/连接失败已内置指数退避重试（默认 4 次）；重试仍失败返回 `502`。
- 源站返回 `404`（书不存在）时直接透传 `404`。
"""


@asynccontextmanager
async def lifespan(app: FastAPI):
    await fetcher.start()
    log.info("fetcher 已启动 (retries=%d timeout=%.1fs concurrency=%d proxy=%s)",
             fetcher.retries, fetcher.timeout, fetcher.concurrency, fetcher.proxy_mode())
    try:
        yield
    finally:
        await fetcher.stop()
        log.info("fetcher 已关闭")


app = FastAPI(
    title="小说聚合 API · xiaoshuo_server",
    version=__version__,
    summary="笔趣阁系站点实时解析 API：首页/分类/排行榜/全本/搜索/详情/目录/章节正文",
    description=DESCRIPTION,
    lifespan=lifespan,
    docs_url="/docs",
    redoc_url="/redoc",
    openapi_url="/openapi.json",
    contact={"name": "xiaoshuo_server"},
    license_info={"name": "仅用于个人学习与技术研究"},
    openapi_tags=[
        {"name": "服务", "description": "服务信息、数据源清单与健康探测"},
        {"name": "发现", "description": "首页聚合、分类、排行榜、全本小说"},
        {"name": "检索", "description": "站内搜索"},
        {"name": "书籍", "description": "书籍详情与完整目录"},
        {"name": "阅读", "description": "章节正文（含上下章导航）"},
        {"name": "运维", "description": "缓存查看与清理"},
    ],
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=False,
    allow_methods=["*"],
    allow_headers=["*"],
)


# ---------------- 异常映射 ----------------
@app.exception_handler(FetchError)
async def _fetch_error(_, exc: FetchError) -> JSONResponse:
    status = 404 if exc.status == 404 else 502
    return JSONResponse(
        status_code=status,
        content=ApiError(source="-", error=str(exc.reason), detail=exc.url).model_dump(),
    )


def _translate(exc: Exception) -> HTTPException:
    """把源适配器抛出的领域异常翻译成 HTTP 状态码。"""
    if isinstance(exc, FetchError):
        return HTTPException(404 if exc.status == 404 else 502, detail=f"{exc.reason} | {exc.url}")
    if isinstance(exc, KeyError):
        return HTTPException(404, detail=str(exc.args[0] if exc.args else exc))
    if isinstance(exc, ValueError):
        return HTTPException(422, detail=str(exc))
    return HTTPException(502, detail=f"{type(exc).__name__}: {exc}")


SourceParam = Annotated[
    str,
    Query(
        description=(
            "数据源 id：`bqg99`（顶点小说网）/ `blqvdu`（顶点小说网镜像）/ `biquge365`（新笔趣阁）/ "
            "`auto`（默认，健康度感知：列表类逐个故障转移，id 类取当前健康且优先级最高的源）"
        ),
        examples=["auto", "bqg99", "blqvdu", "biquge365"],
    ),
]
PageParam = Annotated[int, Query(ge=1, le=5000, description="页码，从 1 开始")]
BookIdParam = Annotated[str, Path(pattern=r"^\w{1,32}$", description="源站书籍 id（字符串，可能含前导零）", examples=["2639610"])]
ChapterIdParam = Annotated[str, Path(pattern=r"^\w{1,32}$", description="源站章节 id（字符串，可能含前导零）", examples=["275761356"])]


def _safe_get_source(source: str) -> Source:
    """按 id 取源；未知源统一转 404（get_source 抛的是 KeyError）。"""
    try:
        return get_source(source)
    except KeyError as e:
        raise HTTPException(404, detail=str(e.args[0] if e.args else e)) from e


async def run_with_fallback(feature: str, source: str, fn) -> Any:
    """列表类接口的自动故障转移：按优先级依次尝试，全部失败才抛错。"""
    wanted = None if source.lower() in ("auto", "default", "") else source.lower()
    if wanted:
        src = _safe_get_source(wanted)
        if not src.capabilities.get(feature, False):
            raise HTTPException(400, detail=f"源 {src.id} 不支持 {feature}")
        try:
            out = await fn(src)
            record_health(src.id, True)
            return out
        except HTTPException as e:
            if e.status_code != 502:
                record_health(src.id, True)  # 400/404 说明源是通的
            raise
        except Exception as e:  # noqa: BLE001
            record_health(src.id, False)
            raise _translate(e) from e

    errors: list[str] = []
    for src in ordered_sources():
        if not src.capabilities.get(feature, False):
            continue
        try:
            out = await fn(src)
            record_health(src.id, True)
            return out
        except Exception as e:  # noqa: BLE001
            record_health(src.id, False)
            errors.append(f"{src.id}: {type(e).__name__}: {e}")
            log.warning("auto 回源失败 %s/%s: %s", src.id, feature, e)
    raise HTTPException(502, detail="所有数据源均失败 → " + " | ".join(errors))


async def resolve_source(feature: str, source: str) -> Source:
    """id 类接口（详情/目录/正文）：book_id 与源绑定、跨源不通用，故不做逐个回退。

    source=auto 时取「当前健康且优先级最高」的源；显式指定时以指定值为准。
    """
    if source.lower() in ("auto", "default", ""):
        src = await first_healthy()
    else:
        src = _safe_get_source(source)
    if not src.capabilities.get(feature, False):
        raise HTTPException(400, detail=f"源 {src.id} 不支持 {feature}，可用源见 /api/sources")
    return src


async def run_id_call(src: Source, coro):
    """执行 id 类接口调用并登记健康度。"""
    try:
        out = await coro
        record_health(src.id, True)
        return out
    except HTTPException as e:
        if e.status_code != 502:
            record_health(src.id, True)
        raise
    except FetchError as e:
        record_health(src.id, e.status != 404)
        raise _translate(e) from e
    except Exception as e:  # noqa: BLE001
        record_health(src.id, not isinstance(e, KeyError))
        raise _translate(e) from e


# ==================== 服务 ====================
@app.get("/", tags=["服务"], response_model=ServiceInfo, summary="服务信息与端点索引")
async def root() -> ServiceInfo:
    """返回服务元信息与全部端点清单，方便快速上手（等同于本页文字版）。"""
    routes = {}
    for r in app.routes:
        methods = getattr(r, "methods", None)
        path = getattr(r, "path", None)
        if methods and path and "GET" in methods and path not in ("/openapi.json", "/docs", "/docs/oauth2-redirect", "/redoc"):
            routes[path] = (getattr(r, "summary", None) or getattr(r, "name", ""))
    return ServiceInfo(
        service="小说聚合 API",
        version=__version__,
        docs="/docs",
        openapi="/openapi.json",
        default_source=DEFAULT_SOURCE,
        sources=list(SOURCES),
        endpoints=dict(sorted(routes.items())),
    )


@app.get("/api/sources", tags=["服务"], response_model=list[SourceInfo], summary="数据源清单与能力矩阵")
async def list_sources() -> list[SourceInfo]:
    """列出所有已接入数据源，及其支持的能力（home/分类/排行/全本/搜索/详情/目录/正文）。"""
    return source_infos()


@app.get("/api/sources/health", tags=["服务"], response_model=list[SourceHealth], summary="数据源连通性探测")
async def sources_health() -> list[SourceHealth]:
    """实时请求各源首页，返回可用性、HTTP 状态与延迟（毫秒）。上游偶发抖动时可用来判断该用哪个源。"""
    results = []
    for src in ordered_sources():
        info = await src.probe()
        record_health(src.id, bool(info.get("ok")))
        results.append(SourceHealth(id=src.id, name=src.name, **{k: v for k, v in info.items() if k != "_bytes"}))
    return results


# ==================== 发现 ====================
@app.get("/api/home", tags=["发现"], response_model=HomePage, summary="首页聚合")
async def home(source: SourceParam = "auto") -> HomePage:
    """
    聚合源站首页的四类内容：
    - `hot_books`：带封面的热门推荐
    - `recommend_books`：强力推荐（仅 bqg99 有）
    - `latest_updates`：最近更新列表（含最新章节与更新时间）
    - `category_blocks`：各分类推荐块
    """
    return await run_with_fallback("home", source, lambda s: s.home())


@app.get("/api/categories", tags=["发现"], response_model=list[CategoryInfo], summary="分类字典")
async def categories(source: SourceParam = "auto") -> list[CategoryInfo]:
    """返回该源的分类 slug ↔ 中文名映射，`slug` 用于 `/api/categories/{slug}`，`paginated` 表示是否支持翻页。"""
    src = await resolve_source("categories", source)
    return await run_id_call(src, src.categories())


@app.get("/api/categories/{slug}", tags=["发现"], response_model=BookPage, summary="分类下的书籍列表")
async def category_books(
    slug: Annotated[str, Path(description="分类 slug（见 /api/categories），也接受中文分类名", examples=["xuanhuan", "1"])],
    page: PageParam = 1,
    source: SourceParam = "auto",
) -> BookPage:
    """
    按分类浏览书籍。
    - `bqg99`：7 个 slug（xuanhuan/wuxia/dushi/lishi/wangyou/kehuan/mm），源站每类固定返回 30 条、**不支持翻页**。
    - `biquge365`：8 个 slug（1~8），**支持深度翻页**，`total_pages` 来自源站分页条（玄幻魔法 1769 页）。
    """
    async def call(s: Source) -> BookPage:
        try:
            return await s.category_books(slug, page)
        except Exception as e:  # noqa: BLE001
            raise _translate(e) from e

    return await run_with_fallback("category_books", source, call)


@app.get("/api/ranks", tags=["发现"], response_model=list[RankBoard], summary="排行榜")
async def ranks(
    board: Annotated[Optional[str], Query(description="榜单名过滤（子串匹配），不传返回全部榜单", examples=["玄幻"])] = None,
    source: SourceParam = "auto",
) -> list[RankBoard]:
    """
    排行榜。
    - `bqg99`：解析 `/rank.html`，返回 8 个榜单（小说总榜 + 7 个分类榜），每榜 15 条。
    - `biquge365`：源站无独立排行页，由 8 个分类页侧栏的「收藏排行」聚合而成。
    """
    async def call(s: Source) -> list[RankBoard]:
        try:
            return await s.ranks(board)
        except Exception as e:  # noqa: BLE001
            raise _translate(e) from e

    return await run_with_fallback("ranks", source, call)


@app.get("/api/full", tags=["发现"], response_model=BookPage, summary="全本小说列表")
async def full_books(page: PageParam = 1, source: SourceParam = "auto") -> BookPage:
    """已完结（全本）书籍列表。`bqg99` 取 `/quanben/`（固定 30 条），`biquge365` 取 `/full/`（支持翻页）。"""
    async def call(s: Source) -> BookPage:
        try:
            return await s.full_books(page)
        except Exception as e:  # noqa: BLE001
            raise _translate(e) from e

    return await run_with_fallback("full_books", source, call)


# ==================== 检索 ====================
@app.get("/api/search", tags=["检索"], response_model=BookPage, summary="站内搜索")
async def search(
    kw: Annotated[str, Query(min_length=1, max_length=64, description="书名/作者关键词", examples=["万相之王"])],
    page: PageParam = 1,
    source: SourceParam = "auto",
) -> BookPage:
    """
    按关键词搜索书籍（书名或作者）。
    - `bqg99`：走源站 `/s.php`，最多返回 100 条，含分类、作者、封面、最新章节、简介；已对重复镜像条目去重。
    - `biquge365`：**源站未提供搜索**，指定该源会返回 400；`source=auto`（默认）会自动使用 bqg99。
    """
    async def call(s: Source) -> BookPage:
        try:
            return await s.search(kw, page)
        except FetchError as e:
            raise _translate(e) from e
        except (KeyError, ValueError) as e:
            raise _translate(e) from e

    return await run_with_fallback("search", source, call)


# ==================== 书籍 ====================
@app.get("/api/book/{book_id}", tags=["书籍"], response_model=BookDetail, summary="书籍详情")
async def book_detail(book_id: BookIdParam, source: SourceParam = "auto") -> BookDetail:
    """
    书籍详情：书名、作者、分类、封面、简介、状态、字数、更新时间、最新章节、**章节总数**与首章信息。
    `book_id` 与源绑定，跨源不通用；`biquge365` 会额外请求一次全目录页以给出准确的 `chapter_count`。
    """
    src = await resolve_source("book_detail", source)
    return await run_id_call(src, src.book_detail(book_id))


@app.get("/api/book/{book_id}/chapters", tags=["书籍"], response_model=ChapterList, summary="书籍目录（完整章节列表）")
async def chapters(
    book_id: BookIdParam,
    offset: Annotated[int, Query(ge=0, description="起始下标，用于分页；0 表示从头开始")] = 0,
    limit: Annotated[int, Query(ge=0, le=5000, description="返回条数上限；0 表示返回全部章节")] = 0,
    source: SourceParam = "auto",
) -> ChapterList:
    """
    **最重要的接口之一**：返回完整章节目录。
    - `bqg99`：源站在详情页单页输出全部章节（实测《牧神记》1920 章、其他书可达 4800+ 章），一次请求即可拿全，无需翻页。
    - `biquge365`：目录在独立的 `/newbook/{id}/` 页，同样单页全量返回（实测《临渊行》986 章）。
    - 章节多时可用 `offset` / `limit` 在本地分页，避免一次传输上万条。
    """
    src = await resolve_source("chapters", source)
    return await run_id_call(src, src.chapters(book_id, offset, limit))


# ==================== 阅读 ====================
@app.get("/api/chapter/{book_id}/{chapter_id}", tags=["阅读"], response_model=ChapterBody, summary="章节正文")
async def chapter(
    book_id: BookIdParam,
    chapter_id: ChapterIdParam,
    source: SourceParam = "auto",
    clean_ads: Annotated[bool, Query(description="是否剔除源站广告行（一秒记住/无弹窗等），默认开启")] = True,
) -> ChapterBody:
    """
    **最重要的接口之一**：返回章节正文纯文本（段落以 `\\n` 分隔），并附带上一章/下一章 id 与返回目录链接，可直接驱动阅读器连续翻页。
    `content_html` 保留源站原始 HTML 片段；`clean_ads=false` 时不做广告行过滤。
    """
    src = await resolve_source("chapter_body", source)
    body = await run_id_call(src, src.chapter(book_id, chapter_id))
    if not clean_ads:
        # 重新取未过滤版本：strip_ads 已在适配器内执行，这里按行还原代价高，
        # 因此 close_ads=False 时直接返回 content_html 提取的原文
        from bs4 import BeautifulSoup as _BS
        if body.content_html:
            txt = _BS(body.content_html, "lxml").get_text()
            body.content = "\n".join(ln.strip() for ln in txt.split("\n") if ln.strip())
            body.word_count = len(body.content.replace("\n", "").replace(" ", ""))
    return body


# ==================== 运维 ====================
@app.get("/api/cache/stats", tags=["运维"], response_model=dict[str, Any], summary="缓存统计")
async def cache_stats() -> dict[str, Any]:
    """返回内存缓存的条目数、命中/未命中次数与命中率，以及抓取器的运行参数。"""
    st = cache.stats()
    total = st["hits"] + st["misses"]
    return {
        "cache": st,
        "hit_rate": round(st["hits"] / total, 4) if total else 0.0,
        "fetcher": {
            "retries": fetcher.retries,
            "timeout_s": fetcher.timeout,
            "concurrency": fetcher.concurrency,
            "proxy_mode": fetcher.proxy_mode(),
        },
        "source_health": health_snapshot(),
        "uptime_note": "缓存为进程内内存缓存，重启即清空；健康度 TTL 见 XS_HEALTH_TTL",
    }


@app.delete("/api/cache", tags=["运维"], response_model=dict[str, Any], summary="清空缓存")
async def cache_clear() -> dict[str, Any]:
    """清空全部内存缓存（源站更新后想立刻看到新数据时用）。"""
    before = cache.stats()["entries"]
    cache.clear()
    return {"ok": True, "cleared_entries": before}


@app.get("/api/ping", tags=["运维"], response_model=dict[str, Any], summary="存活探测")
async def ping() -> dict[str, Any]:
    """轻量存活探测，不发起任何上游请求。"""
    return {"ok": True, "service": "xiaoshuo_server", "version": __version__, "ts": int(time.time())}
