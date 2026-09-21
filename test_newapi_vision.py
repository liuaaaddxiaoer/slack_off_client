#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Test ALL new-api models for image (vision) support.

Sends the same screenshot to each model, then classifies:
  YES  - model actually described the image (content or reasoning)
  NO   - model explicitly says it cannot see the image
  ERR  - API error / no channel / quota
"""
import base64, json, re, sys, urllib.request, urllib.error
from concurrent.futures import ThreadPoolExecutor, as_completed

IMG = "/Users/mac/Downloads/ScreenShot_2026-09-05_211714_972.png"
CRED = "/Users/mac/.dsh/.credentials.yaml"
BASE = "http://localhost:9000/v1"

def get_key(name):
    for line in open(CRED):
        if line.startswith(name + ":"):
            return line.split(":", 1)[1].strip().strip("'\"")
    return ""

KEY = get_key("NEW_API_API_KEY")
with open(IMG, "rb") as f:
    DATA_URL = "data:image/png;base64," + base64.b64encode(f.read()).decode()

MODELS = [
    "glm-5.2", "deepseek-v4-flash-0731", "glm-5.1", "kimi-k2.7-code", "kimi-k3",
    "deepseek-v4-pro", "deepseek-v4-pro-0813", "qwen3.6-flash", "deepseek-v4-flash",
    "claude-opus-5", "qwen3.7-plus-2026-05-26", "claude-opus-4-8", "qwen3.7-flash",
    "qwen3.8-max", "claude-opus-4-8-thinking", "claude-opus-5-thinking",
    "qwen3.8-flash", "qwen3.8-2.4t-a95b", "qwen3.7-max-2026-05-17",
    "qwen3.7-max-2026-05-20", "qwen3.7-max-2026-06-08", "deepseek-v3.2",
    "qwen3.7-plus", "qwen3.7-max", "qwen3.8-27b", "qwen3.7-flash-2026-07-15",
    "ZHIPU/GLM-5.3", "qwen3.8-max-0902",
]

PROMPT = ("这张图里有什么?用一句简短中文描述画面。"
          "如果你看不到图片或无法识别图像,请只回复'无法识别图像'这六个字。")

# keywords indicating the model actually SAW the image (black bg, white text, list)
SEE_KW = ["黑", "白", "文字", "列表", "说明", "背景", "截图", "要点", "项目符号", "上传", "路径", "OCR"]

def classify(content, reason):
    c = (content or "").strip()
    r = (reason or "").strip()
    # explicit refusal in content
    if "无法识别" in c or "看不到" in c or "不能识别" in c:
        return "NO", c[:60]
    # content describes image
    if c and any(k in c for k in SEE_KW):
        return "YES", c[:60]
    # content empty -> look at reasoning
    if not c and r:
        if "无法识别" in r or "文本模型" in r or "没有收到" in r or "看不到" in r or "不是图" in r:
            return "NO", ("[reason] " + r[:50])
        if any(k in r for k in SEE_KW):
            return "YES", ("[reason] " + r[:50])
    if c:  # content present but no keyword -> likely generic, treat as YES-ish? be safe
        return "YES?", c[:60]
    return "NO", ("[empty] " + (r[:50] if r else ""))

def test(model):
    payload = {
        "model": model,
        "max_tokens": 800,
        "messages": [{"role": "user", "content": [
            {"type": "text", "text": PROMPT},
            {"type": "image_url", "image_url": {"url": DATA_URL}},
        ]}],
    }
    req = urllib.request.Request(
        BASE + "/chat/completions",
        data=json.dumps(payload).encode(),
        headers={"Authorization": "Bearer " + KEY, "Content-Type": "application/json"},
        method="POST")
    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
            body = json.load(resp)
    except urllib.error.HTTPError as e:
        try:
            err = json.load(e).get("error", {}).get("message", str(e))
        except Exception:
            err = str(e)
        return model, "ERR", err[:70]
    except Exception as e:
        return model, "ERR", str(e)[:70]

    if "error" in body and body["error"]:
        return model, "ERR", str(body["error"].get("message", body["error"]))[:70]
    try:
        msg = body["choices"][0]["message"]
        content = msg.get("content") or ""
        reason = msg.get("reasoning_content") or ""
    except Exception:
        return model, "ERR", "bad response shape"
    return model, *classify(content, reason)

def main():
    results = {}
    with ThreadPoolExecutor(max_workers=6) as ex:
        futs = {ex.submit(test, m): m for m in MODELS}
        for fut in as_completed(futs):
            m, verdict, detail = fut.result()
            results[m] = (verdict, detail)
            print(f"[done] {m} -> {verdict}", file=sys.stderr)

    order = {"YES": [], "YES?": [], "NO": [], "ERR": []}
    for m in MODELS:
        v, d = results[m]
        order.setdefault(v, []).append((m, d))

    print("\n================ new-api 视觉能力测试 ================")
    print("\n✅ 支持图像识别 (YES):")
    for m, d in order["YES"]:
        print(f"   {m:32s} {d}")
    if order.get("YES?"):
        print("\n❓ 返回了内容但未命中关键词 (YES?, 人工确认):")
        for m, d in order["YES?"]:
            print(f"   {m:32s} {d}")
    print("\n❌ 不支持图像 (NO):")
    for m, d in order["NO"]:
        print(f"   {m:32s} {d}")
    print("\n⚠️  不可用/报错 (ERR):")
    for m, d in order["ERR"]:
        print(f"   {m:32s} {d}")

if __name__ == "__main__":
    main()
