"""异步 HTTP 抓取层：连接复用、UA 伪装、指数退避重试、TTL 内存缓存。

站点实测存在间歇性 TLS/连接失败，因此重试是必需能力，而非可选项。
"""
from __future__ import annotations

import asyncio
import logging
import os
import random
import time
from typing import Any, Optional

import httpx

log = logging.getLogger("xiaoshuo.client")

_UA_POOL = [
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36",
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 "
    "(KHTML, like Gecko) Version/17.4 Safari/605.1.15",
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:127.0) Gecko/20100101 Firefox/127.0",
]

_BASE_HEADERS = {
    "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,*/*;q=0.8",
    "Accept-Language": "zh-CN,zh;q=0.9,en;q=0.8",
    "Connection": "keep-alive",
    "Upgrade-Insecure-Requests": "1",
}

# 各资源的缓存时长（秒），可用 XS_TTL_* 环境变量覆盖
def _ttl(name: str, default: int) -> int:
    try:
        return int(os.getenv(f"XS_TTL_{name}", default))
    except (TypeError, ValueError):
        return default


TTL = {
    "home": _ttl("HOME", 600),
    "rank": _ttl("RANK", 600),
    "category": _ttl("CATEGORY", 600),
    "full": _ttl("FULL", 600),
    "search": _ttl("SEARCH", 300),
    "book": _ttl("BOOK", 21600),
    "chapters": _ttl("CHAPTERS", 21600),
    "chapter": _ttl("CHAPTER", 86400),
}


class FetchError(RuntimeError):
    """抓取失败（重试后仍不可用）。"""

    def __init__(self, url: str, reason: str, status: Optional[int] = None):
        self.url = url
        self.reason = reason
        self.status = status
        super().__init__(f"{reason} ({status}) {url}" if status else f"{reason} {url}")


class TtlCache:
    """极简 TTL 内存缓存（单进程足够，无需引入 Redis）。"""

    def __init__(self) -> None:
        self._data: dict[str, tuple[float, Any]] = {}
        self._locks: dict[str, asyncio.Lock] = {}
        self.hits = 0
        self.misses = 0

    def get(self, key: str) -> Optional[Any]:
        item = self._data.get(key)
        if not item:
            self.misses += 1
            return None
        expires_at, value = item
        if expires_at < time.time():
            self._data.pop(key, None)
            self.misses += 1
            return None
        self.hits += 1
        return value

    def set(self, key: str, value: Any, ttl: int) -> None:
        if ttl > 0:
            self._data[key] = (time.time() + ttl, value)

    def lock(self, key: str) -> asyncio.Lock:
        lk = self._locks.get(key)
        if lk is None:
            lk = self._locks.setdefault(key, asyncio.Lock())
        return lk

    def clear(self) -> None:
        self._data.clear()
        self._locks.clear()
        self.hits = self.misses = 0

    def stats(self) -> dict[str, int]:
        return {"entries": len(self._data), "hits": self.hits, "misses": self.misses}


cache = TtlCache()


def _env_flag(name: str) -> bool:
    return os.getenv(name, "").strip().lower() in ("1", "true", "yes", "on")


class Fetcher:
    """带重试与缓存的异步抓取器，进程内共享一个 httpx.AsyncClient。

    代理策略（实测本机 biquge365 直连必失败、走系统代理才通）：
      1. XS_PROXY=http://host:port  → 显式指定代理；
      2. XS_NO_PROXY=1              → 强制直连，忽略一切代理；
      3. 默认                       → trust_env=True，自动沿用环境变量与 macOS 系统代理。
    """

    def __init__(
        self,
        retries: int = int(os.getenv("XS_RETRIES", "4")),
        timeout: float = float(os.getenv("XS_TIMEOUT", "15")),
        concurrency: int = int(os.getenv("XS_CONCURRENCY", "8")),
        proxy: Optional[str] = os.getenv("XS_PROXY") or None,
        no_proxy: bool = _env_flag("XS_NO_PROXY"),
    ) -> None:
        self.retries = retries
        self.timeout = timeout
        self.concurrency = concurrency
        self.proxy = proxy
        self.no_proxy = no_proxy
        self._client: Optional[httpx.AsyncClient] = None
        self._sem = asyncio.Semaphore(concurrency)
        self._recycle_lock = asyncio.Lock()

    def proxy_mode(self) -> str:
        if self.no_proxy:
            return "direct(强制直连)"
        return f"explicit({self.proxy})" if self.proxy else "trust_env(系统/环境代理自动)"

    async def start(self) -> None:
        if self._client is None:
            self._client = httpx.AsyncClient(
                headers=dict(_BASE_HEADERS),
                follow_redirects=True,
                timeout=httpx.Timeout(self.timeout, connect=8.0),
                limits=httpx.Limits(
                    max_connections=self.concurrency,
                    max_keepalive_connections=self.concurrency,
                ),
                proxy=self.proxy,
                trust_env=not self.no_proxy,
                http2=False,
            )

    async def stop(self) -> None:
        # 先摘除引用：即便 aclose 抛异常，后续 start() 也能重建出干净的 client
        client, self._client = self._client, None
        if client is not None:
            await client.aclose()

    async def _recycle(self, poisoned: httpx.AsyncClient) -> None:
        """重建 client 以清空连接池。

        httpcore 对代理隧道连接存在滞留缺陷：经代理的请求在建连成功之后失败时，
        该连接不会被标记为可回收（is_available / is_idle / is_closed / has_expired
        全为 False），成为永久占用池名额的僵尸。默认 max_connections 等于并发数，
        失败请求数一旦占满连接池，连健康站点也会一律 PoolTimeout，只能重建恢复。
        """
        async with self._recycle_lock:
            if self._client is not poisoned:  # 已被其它并发请求重建
                return
            await self.stop()
            await self.start()

    @property
    def client(self) -> httpx.AsyncClient:
        if self._client is None:
            raise RuntimeError("Fetcher 未启动，请先 await start()")
        return self._client

    async def _once(self, url: str, encoding: Optional[str]) -> str:
        headers = {"User-Agent": random.choice(_UA_POOL)}
        async with self._sem:
            client = self.client
            try:
                r = await client.get(url, headers=headers)
            except httpx.PoolTimeout:
                # 连接池可能已被僵尸连接占满，重建后立即重试一次
                log.warning("PoolTimeout，疑似连接池被僵尸连接占满，重建 client: %s", url)
                await self._recycle(client)
                r = await self.client.get(url, headers=headers)
        if r.status_code in (403, 404, 405, 500, 502, 503):
            raise FetchError(url, f"HTTP {r.status_code}", r.status_code)
        r.raise_for_status()
        if encoding:
            r.encoding = encoding
        elif not r.encoding or r.encoding.lower() in ("ascii", "iso-8859-1"):
            r.encoding = r.charset_encoding or "utf-8"
        return r.text

    async def get_html(self, url: str, encoding: Optional[str] = "utf-8") -> str:
        """抓取 HTML 文本，失败时按指数退避重试。"""
        last: Optional[Exception] = None
        for attempt in range(1, self.retries + 1):
            try:
                return await self._once(url, encoding)
            except FetchError as e:
                if e.status == 404:  # 404 不重试，直接抛出
                    raise
                last = e
            except (httpx.TransportError, httpx.HTTPStatusError) as e:
                last = e
            delay = min(2.0, 0.3 * (2 ** (attempt - 1))) + random.uniform(0, 0.25)
            log.warning("fetch retry %d/%d %s (%s) sleep %.2fs",
                        attempt, self.retries, url, type(last).__name__, delay)
            await asyncio.sleep(delay)
        reason = type(last).__name__ if last else "Unknown"
        raise FetchError(url, f"重试 {self.retries} 次后仍失败: {reason}")

    async def get_cached(
        self,
        kind: str,
        key: str,
        url: str,
        encoding: Optional[str] = "utf-8",
        ttl: Optional[int] = None,
    ) -> str:
        """带 TTL 缓存的抓取；同一 key 并发时只发一次真实请求。"""
        ck = f"{kind}:{key}"
        hit = cache.get(ck)
        if hit is not None:
            return hit
        async with cache.lock(ck):
            hit = cache.get(ck)
            if hit is not None:
                return hit
            html = await self.get_html(url, encoding)
            cache.set(ck, html, TTL.get(kind, 300) if ttl is None else ttl)
            return html


fetcher = Fetcher()
