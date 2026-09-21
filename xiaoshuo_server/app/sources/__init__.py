"""数据源注册表 + 健康度感知路由。

新增站点：继承 app.sources.base.Source 实现 9 个解析方法，然后加入 _ALL 即可，
抓取/重试/缓存/路由/文档全部自动复用。

健康度：每次回源成功或失败都会写入 _health（TTL 内有效），
ordered_sources() 据此把健康源排在前面，first_healthy() 据此决定 id 类接口的默认源。
好处是坏源（如上游临时宕机）只会在 TTL 过期后被重试一次，不会拖慢每个请求。
"""
from __future__ import annotations

import asyncio
import os
import time
from typing import Optional

from app.models import SourceInfo

from .base import Source
from .biquge365 import Biquge365Source
from .blqvdu import BlqvduSource
from .bqg99 import Bqg99Source

_ALL: list[Source] = sorted(
    [Bqg99Source(), BlqvduSource(), Biquge365Source()], key=lambda s: s.priority
)

SOURCES: dict[str, Source] = {s.id: s for s in _ALL}
DEFAULT_SOURCE: str = _ALL[0].id

HEALTH_TTL: float = float(os.getenv("XS_HEALTH_TTL", "300"))
_health: dict[str, tuple[float, bool]] = {}


def record_health(source_id: str, ok: bool) -> None:
    """记录一次回源结果，供健康度感知路由使用。"""
    _health[source_id] = (time.time(), ok)


def health_snapshot() -> dict[str, dict]:
    now = time.time()
    out: dict[str, dict] = {}
    for sid, (ts, ok) in _health.items():
        out[sid] = {"ok": ok, "age_s": round(now - ts, 1), "fresh": (now - ts) < HEALTH_TTL}
    return out


def _is_fresh_ok(sid: str) -> Optional[bool]:
    """返回 TTL 内的健康状态；无记录返回 None。"""
    rec = _health.get(sid)
    if not rec:
        return None
    ts, ok = rec
    return ok if (time.time() - ts) < HEALTH_TTL else None


def get_source(source: Optional[str] = None) -> Source:
    """按 id 取源；None / auto 返回优先级最高的源。"""
    if not source or source.lower() in ("auto", "default"):
        return _ALL[0]
    src = SOURCES.get(source.lower())
    if src is None:
        raise KeyError(f"未知数据源 {source}，可选：{', '.join(SOURCES)}")
    return src


def ordered_sources(preferred: Optional[str] = None) -> list[Source]:
    """排序：preferred 置顶 → TTL 内已知健康的源 → 状态未知的源 → 已知故障的源，同组内按 priority。"""
    pref = SOURCES.get(preferred.lower()) if preferred and preferred.lower() in SOURCES else None
    healthy, unknown, down = [], [], []
    for s in _ALL:
        if pref and s.id == pref.id:
            continue
        state = _is_fresh_ok(s.id)
        (healthy if state else down if state is False else unknown).append(s)
    ordered = healthy + unknown + down
    return ([pref] + ordered) if pref else ordered


async def first_healthy() -> Source:
    """id 类接口（详情/目录/正文）的默认源：取第一个健康的源。

    book_id 是源站私有 id、跨源不通用，所以不能像列表接口那样逐个回退，
    只能挑一个当前可用的源作为默认；用户显式传 source 时以显式值为准。
    """
    for s in _ALL:
        if _is_fresh_ok(s.id):
            return s
    # 健康缓存为空/全过期：并发探测一次（每个 TTL 周期最多发生一次）
    results = await asyncio.gather(*[s.probe() for s in _ALL], return_exceptions=True)
    for s, r in zip(_ALL, results):
        record_health(s.id, bool(isinstance(r, dict) and r.get("ok")))
    for s, r in zip(_ALL, results):
        if isinstance(r, dict) and r.get("ok"):
            return s
    return _ALL[0]


def source_infos() -> list[SourceInfo]:
    return [
        SourceInfo(
            id=s.id,
            name=s.name,
            base_url=s.base_url,
            enabled=s.enabled,
            priority=s.priority,
            capabilities=dict(s.capabilities),
            notes=s.notes,
        )
        for s in _ALL
    ]


__all__ = [
    "Source",
    "SOURCES",
    "DEFAULT_SOURCE",
    "HEALTH_TTL",
    "get_source",
    "ordered_sources",
    "first_healthy",
    "record_health",
    "health_snapshot",
    "source_infos",
]
