#!/usr/bin/env python3
"""端到端冒烟测试：表驱动遍历全部数据源 × 能力矩阵，并验证 auto 故障转移与错误处理。

用法：先启动服务（python3 run.py），再执行 python3 scripts/smoke_test.py
"""
from __future__ import annotations

import asyncio
import sys
import time

import httpx

BASE = "http://127.0.0.1:4321"
TIMEOUT = 180.0

# 每个源的测试样本：(分类slug, 书籍id, 期望书名关键字)
SAMPLES: dict[str, tuple[str, str, str]] = {
    "bqg99": ("xuanhuan", "2639610", "牧神记"),
    "blqvdu": ("1", "4_4903", "百炼飞升录"),
    "biquge365": ("1", "78201", "临渊行"),
}

results: list[tuple[str, bool, str]] = []


def report(name: str, ok: bool, msg: str = "") -> None:
    results.append((name, bool(ok), msg))
    print(f"{'✅' if ok else '❌'} {name:<44} {msg}")


async def get(c: httpx.AsyncClient, path: str, **params):
    """返回 (status_code, json|None, 耗时ms)；trust_env=False 避免系统代理劫持 127.0.0.1。"""
    t0 = time.perf_counter()
    try:
        r = await c.get(BASE + path, params=params or None, timeout=TIMEOUT)
    except Exception as e:  # noqa: BLE001
        return 0, None, (time.perf_counter() - t0) * 1000, f"请求异常 {type(e).__name__}"
    dt = (time.perf_counter() - t0) * 1000
    try:
        data = r.json()
    except Exception:  # noqa: BLE001
        data = None
    return r.status_code, data, dt, r.text[:120]


async def check_service(c: httpx.AsyncClient) -> list[str]:
    code, data, dt, _ = await get(c, "/api/ping")
    report("服务 /api/ping", code == 200 and data.get("ok"), f"{dt:.0f}ms")
    code, data, dt, _ = await get(c, "/")
    report("服务 / 端点索引", code == 200, f"{len(data.get('endpoints', {}))} 端点 {dt:.0f}ms")
    code, srcs, dt, _ = await get(c, "/api/sources")
    ids = [s["id"] for s in srcs] if code == 200 else []
    report("服务 /api/sources", code == 200 and len(ids) >= 3, f"{len(ids)} 源: {','.join(ids)}")
    code, health, dt, _ = await get(c, "/api/sources/health")
    txt = " ".join(f"{h['id']}={'OK' if h['ok'] else 'DOWN'}" for h in (health or []))
    report("服务 /api/sources/health", code == 200, f"{txt} ({dt:.0f}ms)")
    return ids


async def check_source(c: httpx.AsyncClient, sid: str, caps: dict[str, bool]) -> None:
    slug, bid, title_kw = SAMPLES.get(sid, ("1", "", ""))
    P = f"[{sid}]"

    # 首页
    code, d, dt, raw = await get(c, "/api/home", source=sid)
    ok = code == 200 and d and d["source"] == sid and (
        len(d["hot_books"]) + len(d["latest_updates"]) + len(d["category_blocks"]) > 0
    )
    report(f"首页 /api/home {P}", ok,
           f"热门{len(d['hot_books'])} 推荐{len(d['recommend_books'])} 更新{len(d['latest_updates'])} 块{len(d['category_blocks'])} {dt:.0f}ms" if d else raw)

    # 分类字典
    code, d, dt, raw = await get(c, "/api/categories", source=sid)
    slugs = [x["slug"] for x in d] if code == 200 and d else []
    report(f"分类字典 /api/categories {P}", code == 200 and len(slugs) >= 7,
           f"{len(slugs)}类 {','.join(slugs[:4])}… paginated={d[0]['paginated'] if d else '-'}")

    # 分类列表
    code, d, dt, raw = await get(c, f"/api/categories/{slug}", source=sid)
    ok = code == 200 and d and len(d["items"]) > 0 and all(i["source"] == sid for i in d["items"])
    report(f"分类列表 /api/categories/{slug} {P}", ok,
           f"{d['name']} {len(d['items'])}本 第{d['page']}/{d['total_pages']}页 has_more={d['has_more']} 首《{d['items'][0]['title']}》 {dt:.0f}ms" if ok else raw)

    # 分类翻页（仅支持分页的源）
    if d and d.get("has_more"):
        code, d2, dt, raw = await get(c, f"/api/categories/{slug}", page=2, source=sid)
        first1 = d["items"][0]["book_id"]
        ok = code == 200 and d2 and len(d2["items"]) > 0 and d2["items"][0]["book_id"] != first1
        report(f"分类翻页 page=2 {P}", ok,
               f"第{d2['page']}页 {len(d2['items'])}本 首《{d2['items'][0]['title']}》 与首页不同={ok}" if d2 else raw)

    # 排行榜
    code, d, dt, raw = await get(c, "/api/ranks", source=sid)
    ok = code == 200 and d and len(d) >= 5 and all(b["items"] for b in d)
    report(f"排行榜 /api/ranks {P}", ok,
           f"{len(d)}榜: {','.join(b['board'] for b in d[:3])}… 首榜{d[0]['total']}条 {dt:.0f}ms" if ok else raw)

    # 全本
    code, d, dt, raw = await get(c, "/api/full", source=sid)
    ok = code == 200 and d and len(d["items"]) > 0
    report(f"全本 /api/full {P}", ok, f"{len(d['items'])}本 首《{d['items'][0]['title']}》 {dt:.0f}ms" if ok else raw)

    # 搜索（按能力矩阵断言）
    if caps.get("search"):
        code, d, dt, raw = await get(c, "/api/search", kw="牧神记", source=sid)
        ok = code == 200 and d and len(d["items"]) > 0
        report(f"搜索 /api/search {P}", ok,
               f"{d['total']}条 首《{d['items'][0]['title']}》/{d['items'][0]['author']} {dt:.0f}ms" if ok else raw)
    else:
        code, d, dt, raw = await get(c, "/api/search", kw="牧神记", source=sid)
        report(f"搜索应拒绝 {P}", code == 400, f"HTTP {code} {str((d or {}).get('detail', ''))[:50]}")

    if not bid:
        return

    # 详情
    code, d, dt, raw = await get(c, f"/api/book/{bid}", source=sid)
    ok = code == 200 and d and title_kw in d["title"] and d["chapter_count"] > 0
    report(f"详情 /api/book/{bid} {P}", ok,
           f"《{d['title']}》{d['author']} {d['status']} {d['word_count'] or '?'}字 {d['chapter_count']}章 {dt:.0f}ms" if d else raw)

    # 目录全量
    code, d, dt, raw = await get(c, f"/api/book/{bid}/chapters", source=sid)
    ok = code == 200 and d and d["total"] > 0 and d["returned"] == d["total"] and len(d["chapters"]) == d["total"]
    report(f"目录全量 /chapters {P}", ok,
           f"total={d['total']} returned={d['returned']} 首《{d['chapters'][0]['title']}》 末《{d['chapters'][-1]['title']}》 {dt:.0f}ms" if ok else raw)

    # 目录分页
    code, d2, dt, raw = await get(c, f"/api/book/{bid}/chapters", offset=500, limit=2, source=sid)
    ok = code == 200 and d2 and d2["returned"] == 2 and d2["offset"] == 500 and d2["chapters"][0]["index"] == 501
    report(f"目录分页 offset=500&limit=2 {P}", ok,
           f"returned={d2['returned']} index={[x['index'] for x in d2['chapters']]}" if d2 else raw)

    # 正文 + 导航
    cid = d["chapters"][0]["chapter_id"] if ok or d else None
    if cid:
        code, b, dt, raw = await get(c, f"/api/chapter/{bid}/{cid}", source=sid)
        dirty = b and (("<p" in b["content"]) or ("script" in b["content"].lower()))
        ok = code == 200 and b and b["word_count"] > 200 and not dirty
        report(f"正文 /api/chapter {P}", ok,
               f"《{b['title']}》{b['word_count']}字 prev={b['prev_chapter_id']} next={b['next_chapter_id']} 脏标记={bool(dirty)} {dt:.0f}ms" if b else raw)
        # 连读：用 next 再取一章
        if b and b["next_chapter_id"]:
            code, b2, dt, raw = await get(c, f"/api/chapter/{bid}/{b['next_chapter_id']}", source=sid)
            ok2 = code == 200 and b2 and b2["word_count"] > 200 and b2["prev_chapter_id"] == cid
            report(f"连读 next→prev 回环 {P}", ok2,
                   f"《{b2['title']}》{b2['word_count']}字 prev={b2['prev_chapter_id']}=={cid}" if b2 else raw)
        # 广告清洗开关
        code, b3, dt, raw = await get(c, f"/api/chapter/{bid}/{cid}", source=sid, clean_ads="false")
        report(f"clean_ads=false {P}", code == 200 and b3 and b3["word_count"] >= b["word_count"],
               f"{b3['word_count']}字 ≥ 清洗后 {b['word_count']}字" if b3 else raw)


async def check_routing(c: httpx.AsyncClient) -> None:
    # auto：搜索应落到唯一支持搜索的源
    code, d, dt, raw = await get(c, "/api/search", kw="牧神记", source="auto")
    ok = code == 200 and d and d["items"] and d["items"][0]["source"] == "bqg99"
    report("auto 故障转移 /api/search", ok,
           f"auto→{d['items'][0]['source']} {d['total']}条 {dt:.0f}ms" if d else raw)

    # auto：首页应落到健康且优先级最高的源
    code, d, dt, raw = await get(c, "/api/home", source="auto")
    report("auto 优先级 /api/home", code == 200 and d, f"落到 {d['source']} {dt:.0f}ms" if d else raw)

    # auto：id 类接口应落到健康源
    code, d, dt, raw = await get(c, "/api/book/2639610")
    report("auto 默认源 /api/book/2639610", code == 200 and d and d["title"],
           f"源={d.get('source')} 《{d.get('title')}》 {dt:.0f}ms" if d else raw)

    # 错误处理
    code, d, dt, raw = await get(c, "/api/categories/不存在的分类", source="bqg99")
    report("错误 未知分类 → 404", code == 404, f"HTTP {code}")
    code, d, dt, raw = await get(c, "/api/book/999999999999", source="bqg99")
    report("错误 不存在书籍 → 404", code in (404, 502), f"HTTP {code}")
    code, d, dt, raw = await get(c, "/api/book/1", source="nope")
    report("错误 未知源 → 404", code == 404, f"HTTP {code}")
    code, d, dt, raw = await get(c, "/api/search", kw="", source="auto")
    report("错误 空搜索词 → 422", code == 422, f"HTTP {code}")

    # 缓存
    code, d, dt, raw = await get(c, "/api/cache/stats")
    report("缓存 /api/cache/stats", code == 200 and d,
           f"条目{d['cache']['entries']} 命中率{d['hit_rate']:.0%} 健康度{len(d.get('source_health', {}))}源" if d else raw)
    t0 = time.perf_counter()
    await get(c, "/api/home", source="bqg99")
    dt = (time.perf_counter() - t0) * 1000
    report("缓存命中 二次 /api/home", dt < 300, f"{dt:.0f}ms（应 <300ms）")


async def main() -> int:
    async with httpx.AsyncClient(trust_env=False) as c:
        try:
            code, _, _, _ = await get(c, "/api/ping")
        except Exception:  # noqa: BLE001
            code = 0
        if code != 200:
            print("❌ 服务未启动，请先执行：python3 run.py")
            return 2
        ids = await check_service(c)
        code, srcs, _, _ = await get(c, "/api/sources")
        caps = {s["id"]: s["capabilities"] for s in srcs}
        print()
        for sid in ids:
            print(f"———— 数据源 {sid} ————")
            await check_source(c, sid, caps.get(sid, {}))
            print()
        print("———— 路由/错误/缓存 ————")
        await check_routing(c)

    ok = sum(1 for _, s, _ in results if s)
    print(f"\n{'=' * 66}\n通过 {ok}/{len(results)}")
    for n, s, m in results:
        if not s:
            print(f"  ❌ {n} → {m}")
    return 0 if ok == len(results) else 1


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
