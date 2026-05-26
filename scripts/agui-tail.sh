#!/usr/bin/env bash
# Day 6 §6.3 · AG-UI 事件流可视化
#
# 用法：
#   ./scripts/agui-tail.sh <url> <json-body>
#
# 例：
#   ./scripts/agui-tail.sh http://localhost:8080/agui/run "$(cat fixtures/demo-input.json)"
#
# 作用：POST 一份 RunAgentInput 到 /agui/run，把 SSE 帧的 type +
#       (delta|toolName|toolCallName|content|messageId) 之一打成 TSV，
#       一行一个事件，便于肉眼数 Lifecycle / TextMessage / ToolCall 是否齐全。
#
# 依赖：curl + jq（Windows 学员请用 Git Bash 跑，jq 装一次：scoop install jq）。

set -euo pipefail

if [[ $# -lt 2 ]]; then
  echo "usage: $0 <url> <json-body>" >&2
  echo "example: $0 http://localhost:8080/agui/run \"\$(cat fixtures/demo-input.json)\"" >&2
  exit 2
fi

URL="$1"
BODY="$2"

curl -sN -X POST "$URL" \
  -H "Content-Type: application/json" \
  -H "Accept: text/event-stream" \
  -d "$BODY" \
  | grep '^data:' \
  | sed 's/^data: //' \
  | jq -r '"\(.type)\t\(.delta // .toolCallName // .toolName // .content // .messageId // "")"'
