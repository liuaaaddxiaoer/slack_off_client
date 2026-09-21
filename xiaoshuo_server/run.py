#!/usr/bin/env python3
"""启动入口：python3 run.py  →  http://127.0.0.1:4321/docs"""
import os

import uvicorn

if __name__ == "__main__":
    uvicorn.run(
        "app.main:app",
        host=os.getenv("XS_HOST", "0.0.0.0"),
        port=int(os.getenv("XS_PORT", "4321")),
        reload=os.getenv("XS_RELOAD", "0") == "1",
        log_level=os.getenv("XS_LOG_LEVEL", "info"),
    )
