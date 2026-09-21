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

# 选一个装了 uvicorn 的解释器：PATH 里的 python3 可能来自 workbuddy 等
# 未装依赖的运行时，直接用会 ModuleNotFoundError: No module named 'uvicorn'。
# 可用 XS_PYTHON 显式覆盖。
pick_python() {
  for candidate in \
    "${XS_PYTHON:-}" \
    python3 \
    /Library/Frameworks/Python.framework/Versions/3.13/bin/python3 \
    /usr/local/bin/python3 \
    /opt/homebrew/bin/python3
  do
    [ -n "$candidate" ] || continue
    if command -v "$candidate" >/dev/null 2>&1 &&
       "$candidate" -c "import uvicorn" >/dev/null 2>&1; then
      echo "$candidate"
      return 0
    fi
  done
  return 1
}

PYTHON="$(pick_python)" || {
  echo "找不到安装了 uvicorn 的 Python。请先 pip install -r requirements.txt，或用 XS_PYTHON 指定解释器。" >&2
  exit 1
}

exec "$PYTHON" run.py
