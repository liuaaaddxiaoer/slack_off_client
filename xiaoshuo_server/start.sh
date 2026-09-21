#!/usr/bin/env bash
# 启动小说聚合 API。
#
# 抓取层默认 trust_env（只读环境变量代理，不读 macOS 系统代理），
# 而两个源站直连经常不通，所以这里默认带上本机代理；
# 外部环境变量可覆盖（XS_PROXY="" 或 XS_NO_PROXY=1 强制直连）。
set -euo pipefail
cd "$(dirname "$0")"

export XS_PROXY="${XS_PROXY:-http://127.0.0.1:10808}"
export XS_PORT="${XS_PORT:-4321}"
export XS_HOST="${XS_HOST:-0.0.0.0}"

exec python3 run.py
