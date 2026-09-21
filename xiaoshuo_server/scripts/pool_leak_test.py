#!/usr/bin/env python3
"""连接池滞留回归测试：代理隧道连接变成僵尸后，健康站点是否仍然可用。

httpcore 对代理隧道连接存在滞留缺陷：经代理的请求在「建连成功之后」失败时，
该连接不会被标记为可回收（is_available / is_idle / is_closed / has_expired 全为
False），永久占用一个池名额。默认 max_connections 等于 XS_CONCURRENCY，失败请求
数一旦占满连接池，连健康站点也会一律 PoolTimeout。

修复前：健康站点从第 N 次（N = XS_CONCURRENCY）起永久失败；
修复后：Fetcher 检出 PoolTimeout 即重建 client，健康站点始终可用。

用法：python3 scripts/pool_leak_test.py
      （需 XS_PROXY 指向可用代理，否则本缺陷不触发）
"""
from __future__ import annotations

import asyncio
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from app.client import Fetcher  # noqa: E402

# 代理无法解析的域名：足以在隧道建立之后触发失败
POISON_URL = "https://no-such-host-xyz123abc.com/"
HEALTHY_URL = "https://www.blqvdu.cc/"
ROUNDS = 20


async def main() -> int:
    if not os.getenv("XS_PROXY"):
        print("⚠️  未设置 XS_PROXY，本缺陷不触发，跳过")
        return 0

    f = Fetcher(retries=1, timeout=float(os.getenv("XS_TIMEOUT", "6")))
    await f.start()
    print(f"连接池容量(并发上限)={f.concurrency}  代理={f.proxy_mode()}\n")
    print(f"{'轮次':<5}{'健康站点':<16}{'耗时':<8}{'说明'}")

    failures = 0
    for i in range(1, ROUNDS + 1):
        try:
            await f.get_html(POISON_URL, "utf-8")
        except Exception:  # noqa: BLE001  预期失败，用于占据连接池
            pass

        t0 = time.perf_counter()
        try:
            await f.get_html(HEALTHY_URL, "utf-8")
            state, note = "OK", ""
        except Exception as e:  # noqa: BLE001
            state, note = f"❌{type(e).__name__}", "健康站点失败"
            failures += 1
        print(f"{i:<5}{state:<16}{time.perf_counter() - t0:<8.2f}{note}")

    await f.stop()
    print(f"\n健康站点失败 {failures}/{ROUNDS}")
    if failures:
        print("❌ 连接池滞留缺陷仍存在")
        return 1
    print("✅ 通过：连接池被占满后仍能自愈")
    return 0


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
