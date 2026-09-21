#!/usr/bin/env bash
# Test which configured DSH models actually support image (vision) input.
# Sends the same screenshot to each candidate model and checks the response.

set -u

IMG="/Users/mac/Downloads/ScreenShot_2026-09-05_211714_972.png"
CRED="$HOME/.dsh/.credentials.yaml"

# Load keys from credentials yaml
get_key() { grep -E "^${1}:" "$CRED" | sed -E "s/^${1}:[[:space:]]*//" | tr -d '"' | tr -d "'"; }
NEW_API_KEY=$(get_key NEW_API_API_KEY)
JIUAN_KEY=$(get_key JIUAN_API_KEY)
GLM_KEY=$(get_key GLM_API_KEY)
DD_KEY=$(get_key DD_API_KEY)

# Base64 encode image (single line)
IMG_B64=$(base64 -i "$IMG" | tr -d '\n')
DATA_URL="data:image/png;base64,${IMG_B64}"

PROMPT="这张图里有什么内容?请用一句简短的中文描述你看到的画面。如果你看不到图片或无法识别图像,请直接回复'无法识别图像'这五个字。"

build_payload() {
  jq -n --arg model "$1" --arg prompt "$PROMPT" --arg img "$DATA_URL" '{
    model: $model,
    max_tokens: 150,
    messages: [{ role: "user", content: [
      { type: "text", text: $prompt },
      { type: "image_url", image_url: { url: $img } }
    ]}]
  }'
}

# test_one <label> <baseURL> <key> <model>
test_one() {
  local label="$1" base="$2" key="$3" model="$4"
  local payload out http rc content err finish
  payload=$(build_payload "$model" 2>/dev/null)
  # call API, capture body + http code
  out=$(curl -sS -m 40 -o /tmp/dsh_vision_body.json -w "%{http_code}" \
    -X POST "${base}/chat/completions" \
    -H "Authorization: Bearer ${key}" \
    -H "Content-Type: application/json" \
    -d "$payload" 2>/tmp/dsh_vision_err.txt)
  rc=$?
  http=$(cat /tmp/dsh_vision_body.json >/dev/null 2>&1; echo "$out")
  if [ "$rc" -ne 0 ]; then
    printf "❌ | %-8s | %-30s | %s\n" "$label" "$model" "curl-fail(rc=$rc): $(head -c 80 /tmp/dsh_vision_err.txt)"
    return
  fi
  # parse response
  content=$(jq -r '.choices[0].message.content // empty' /tmp/dsh_vision_body.json 2>/dev/null)
  err=$(jq -r '.error.message // .error // empty' /tmp/dsh_vision_body.json 2>/dev/null)
  if [ -n "$content" ]; then
    # truncate content for display
    local short=$(echo "$content" | tr '\n' ' ' | head -c 70)
    printf "✅ | %-8s | %-30s | %s\n" "$label" "$model" "$short"
  elif [ -n "$err" ]; then
    printf "⚠️ | %-8s | %-30s | ERROR: %s\n" "$label" "$model" "$(echo "$err" | head -c 70)"
  else
    printf "❌ | %-8s | %-30s | %s\n" "$label" "$model" "$(head -c 70 /tmp/dsh_vision_body.json)"
  fi
}

echo "============================================================"
echo " DSH 模型图像识别能力测试  (image: $IMG)"
echo "============================================================"
printf "%-2s | %-8s | %-30s | %s\n" "状" "供应商" "模型" "返回"
echo "------------------------------------------------------------"

# --- new-api (localhost:9000) : declares image support for ALL models ---
NA_BASE="http://localhost:9000/v1"
for m in glm-5.2 glm-5.3 ZHIPU/GLM-5.3 qwen3.8-max qwen3.8-max-0902 deepseek-v4-flash deepseek-v4-pro deepseek-v4-flash-0731 claude-opus-5 claude-opus-4-8 kimi-k3 kimi-k2.7-code qwen3.7-max qwen3.7-plus qwen3.7-flash qwen3.8-27b qwen3.8-2.4t-a95b qwen3.8-flash deepseek-v3.2; do
  test_one "new-api" "$NA_BASE" "$NEW_API_KEY" "$m"
done

# --- jiuan (tokenrhythm.studio) : declares image support for ALL models ---
JU_BASE="https://tokenrhythm.studio/v1"
for m in glm-5.2 glm-5.3 glm-5.3-flash qwen3.8-max deepseek-v4-flash deepseek-v4-pro kimi-k2.5 kimi-k2.6 kimi-k2.7-code minimax-m2.7 mimo-v2.5-pro qwen3.7-max qwen3.7-flash qwen3.8-27b seed-2.1-pro longcat-2.0; do
  test_one "jiuan" "$JU_BASE" "$JIUAN_KEY" "$m"
done

# --- glm (open.bigmodel.cn) : no defaultInput (text only declared) ---
GL_BASE="https://open.bigmodel.cn/api/paas/v4"
for m in glm-5.2 glm-5.3 glm-5.3-flash; do
  test_one "glm" "$GL_BASE" "$GLM_KEY" "$m"
done

# --- dd (aliyun) : no defaultInput, but has explicit VL models ---
DD_BASE="https://ws-swapqm2ruk3axkl4.ap-southeast-1.maas.aliyuncs.com/compatible-mode/v1"
# VL / vision models
for m in qwen-vl-max qwen3-vl-plus qwen3-vl-flash qwen-vl-plus qvq-max qwen3-vl-235b-a22b-instruct qwen3-vl-235b-a22b-thinking qwen3-vl-plus-2025-09-23 qwen3-vl-flash-2026-01-22 qwen-vl-ocr-2025-11-20; do
  test_one "dd" "$DD_BASE" "$DD_KEY" "$m"
done
# a couple of text-only models on dd to confirm they reject images
for m in qwen3.8-max qwen3.7-max deepseek-v4-flash; do
  test_one "dd" "$DD_BASE" "$DD_KEY" "$m"
done

echo "------------------------------------------------------------"
echo "图例: ✅=返回了内容(疑似支持)  ⚠️=API报错  ❌=无内容/请求失败"
