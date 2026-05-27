#!/usr/bin/env bash
# Day 7 §6.5 验收脚本：按 traceId 过滤 logs/scope.json.log，把 7 个 stage 各点几次列出来。
#
# 用法：bash scripts/stage-check.sh [TRACE_ID]
#
# 不传 TRACE_ID 就用日志里最近出现的那一个；传了就按指定的过。

set -euo pipefail

LOG="logs/scope.json.log"
if [[ ! -f "$LOG" ]]; then
    echo "找不到 $LOG —— 请先 mvn spring-boot:run 起后端并跑一次完整剧本"
    exit 1
fi

TID="${1:-}"
if [[ -z "$TID" ]]; then
    TID=$(jq -rs '[.[] | select(.traceId != null)] | last | .traceId' "$LOG" 2>/dev/null || true)
    if [[ -z "$TID" || "$TID" == "null" ]]; then
        echo "日志里没找到任何 traceId —— TraceIdFilter 没装好？"
        exit 1
    fi
    echo "未指定 traceId，使用最近一条：$TID"
fi

echo "════════════════════════════════════════════════════════════"
echo "traceId = $TID"
echo "════════════════════════════════════════════════════════════"

# 按 stage 计数
jq -r --arg tid "$TID" '
    select(.traceId == $tid) | .stage // "_"
' "$LOG" | sort | uniq -c | awk '{printf "  %-22s %s\n", $2, $1}'

echo ""
echo "────────────────────────────────────────────────────────────"
echo "需求 #10 兑现表（7 个 stage 一个不少才算过）："
echo "  INPUT / LLM_CALL / TOOL_CALL / SCHEMA_VALIDATE /"
echo "  TODO_UPDATE / FRONTEND_DISPATCH / FRONTEND_CALLBACK"
echo "────────────────────────────────────────────────────────────"

MISSING=()
for STAGE in INPUT LLM_CALL TOOL_CALL SCHEMA_VALIDATE TODO_UPDATE FRONTEND_DISPATCH FRONTEND_CALLBACK; do
    COUNT=$(jq -r --arg tid "$TID" --arg s "$STAGE" '
        select(.traceId == $tid and .stage == $s)
    ' "$LOG" | wc -l)
    if [[ "$COUNT" -eq 0 ]]; then
        MISSING+=("$STAGE")
    fi
done

if [[ ${#MISSING[@]} -eq 0 ]]; then
    echo "✅ 7 个 stage 全部出现"
else
    echo "❌ 缺失 stage："
    for s in "${MISSING[@]}"; do echo "   - $s"; done
    exit 2
fi
