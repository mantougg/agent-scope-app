<script setup lang="ts">
import { computed, ref } from 'vue'
import type { Todo } from '../types'
import JsonViewer from './JsonViewer.vue'

const props = defineProps<{
  todo: Todo | null
}>()

defineEmits<{
  close: []
}>()

const jsonText = computed(() => {
  const p = props.todo?.payload
  if (p == null) return '(无 payload)'
  try {
    return JSON.stringify(p, null, 2)
  } catch {
    return String(p)
  }
})

const copyHint = ref<'idle' | 'ok' | 'err'>('idle')

async function copyPayload() {
  if (!props.todo) return
  try {
    await navigator.clipboard.writeText(jsonText.value)
    copyHint.value = 'ok'
  } catch {
    copyHint.value = 'err'
  }
  setTimeout(() => (copyHint.value = 'idle'), 1500)
}
</script>

<template>
  <aside class="todo-detail" role="complementary" aria-label="Todo 详情">
    <template v-if="todo">
      <header class="todo-detail-header">
        <div class="todo-detail-title">
          <span class="todo-detail-badge">{{ todo.type.replace('CREATE_', '') }}</span>
          <h3 :title="todo.targetName">{{ todo.targetName }}</h3>
        </div>
        <button @click="$emit('close')" class="close-btn" aria-label="关闭详情" type="button">
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor"
               stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round">
            <path d="M18 6L6 18M6 6l12 12"/>
          </svg>
        </button>
      </header>

      <div class="todo-detail-meta">
        <div class="meta-item">
          <span class="meta-label">ID</span>
          <span class="meta-value">{{ todo.id }}</span>
        </div>
        <div class="meta-item">
          <span class="meta-label">状态</span>
          <span :class="['meta-status', todo.status.toLowerCase()]">
            <span class="status-dot"></span>{{ todo.status }}
          </span>
        </div>
      </div>

      <div v-if="todo.errorMessage" class="todo-detail-error">
        <span class="meta-label">错误</span>
        <span>{{ todo.errorMessage }}</span>
      </div>

      <div class="todo-detail-body">
        <div class="json-toolbar">
          <span class="json-label">payload</span>
          <button @click="copyPayload" :class="['copy-btn', copyHint]" type="button">
            <span v-if="copyHint === 'idle'">复制 JSON</span>
            <span v-else-if="copyHint === 'ok'">已复制</span>
            <span v-else>复制失败</span>
          </button>
        </div>
        <div class="json-frame">
          <JsonViewer :value="jsonText"/>
        </div>
      </div>
    </template>

    <div v-else class="detail-empty">
      <div class="detail-empty-icon">
        <svg width="44" height="44" viewBox="0 0 24 24" fill="none" stroke="currentColor"
             stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round">
          <path d="M21 11.5a8.38 8.38 0 0 1-.9 3.8 8.5 8.5 0 0 1-7.6 4.7 8.38 8.38 0 0 1-3.8-.9L3 21l1.9-5.7a8.38 8.38 0 0 1-.9-3.8 8.5 8.5 0 0 1 4.7-7.6 8.38 8.38 0 0 1 3.8-.9h.5a8.48 8.48 0 0 1 8 8v.5z"/>
        </svg>
      </div>
      <div class="detail-empty-title">查看 Todo 详情</div>
      <div class="detail-empty-hint">
        点击左侧 Todo Board 中的任意一项<br/>这里会展示对应的 <code>payload</code> JSON
      </div>
    </div>
  </aside>
</template>

<style scoped>
.todo-detail {
  display: flex;
  flex-direction: column;
  min-height: 0;
  height: 100vh;
  overflow: hidden;
  background: rgba(255, 255, 255, 0.55);
  backdrop-filter: saturate(180%) blur(14px);
  -webkit-backdrop-filter: saturate(180%) blur(14px);
  border-left: 1px solid rgba(99, 102, 241, 0.12);
  padding: 16px 14px 18px;
  box-sizing: border-box;
}

/* —— 空态 —— */
.detail-empty {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  text-align: center;
  color: #9ca3af;
  padding: 24px;
  animation: fadeIn 0.3s ease;
}

.detail-empty-icon {
  color: #c7d2fe;
  margin-bottom: 16px;
  filter: drop-shadow(0 6px 14px rgba(139, 92, 246, 0.18));
}

.detail-empty-title {
  font-size: 15px;
  font-weight: 600;
  color: #6b7280;
  margin-bottom: 8px;
}

.detail-empty-hint {
  font-size: 12.5px;
  color: #9ca3af;
  line-height: 1.7;
  max-width: 260px;
}

.detail-empty-hint code {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  background: rgba(99, 102, 241, 0.1);
  color: #4f46e5;
  padding: 1px 6px;
  border-radius: 4px;
  font-size: 11.5px;
}

@keyframes fadeIn {
  from { opacity: 0; transform: translateY(6px); }
  to   { opacity: 1; transform: translateY(0); }
}

/* —— 详情头部 —— */
.todo-detail-header {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  padding-bottom: 14px;
  border-bottom: 1px solid rgba(99, 102, 241, 0.12);
  margin-bottom: 14px;
  animation: fadeIn 0.3s ease;
}

.todo-detail-title {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.todo-detail-badge {
  align-self: flex-start;
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 0.6px;
  padding: 2px 9px;
  border-radius: 5px;
  background: rgba(99, 102, 241, 0.12);
  color: #4f46e5;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
}

.todo-detail-title h3 {
  margin: 0;
  font-size: 15px;
  font-weight: 700;
  color: #111827;
  line-height: 1.35;
  word-break: break-word;
  letter-spacing: -0.2px;
}

.close-btn {
  flex: 0 0 30px;
  width: 30px;
  height: 30px;
  border-radius: 8px;
  border: none;
  background: rgba(15, 23, 42, 0.05);
  color: #6b7280;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: background 0.15s, color 0.15s, transform 0.18s;
}

.close-btn:hover {
  background: rgba(15, 23, 42, 0.1);
  color: #111827;
  transform: rotate(90deg);
}

.todo-detail-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 18px;
  margin-bottom: 14px;
}

.meta-item {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
}

.meta-label {
  color: #9ca3af;
  font-weight: 700;
  font-size: 10px;
  letter-spacing: 0.6px;
  text-transform: uppercase;
}

.meta-value {
  color: #4b5563;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 11.5px;
}

.meta-status {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-weight: 700;
  font-size: 10px;
  padding: 2px 10px;
  border-radius: 999px;
  letter-spacing: 0.4px;
}

.status-dot {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: currentColor;
}

.meta-status.pending { background: rgba(148, 163, 184, 0.16); color: #64748b; }
.meta-status.running { background: rgba(59, 130, 246, 0.14); color: #2563eb; }
.meta-status.running .status-dot { animation: dotPulse 1.2s ease-in-out infinite; }
.meta-status.success { background: rgba(16, 185, 129, 0.14); color: #059669; }
.meta-status.failed  { background: rgba(239, 68, 68, 0.14); color: #dc2626; }

@keyframes dotPulse {
  0%, 100% { opacity: 1; transform: scale(1); }
  50%      { opacity: 0.5; transform: scale(1.5); }
}

.todo-detail-error {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 10px 12px;
  margin-bottom: 14px;
  font-size: 12px;
  color: #b91c1c;
  background: rgba(239, 68, 68, 0.08);
  border: 1px solid rgba(239, 68, 68, 0.22);
  border-radius: 10px;
  line-height: 1.5;
  word-break: break-word;
}

.todo-detail-body {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-height: 0;
}

.json-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
}

.json-label {
  font-size: 10px;
  font-weight: 700;
  color: #6b7280;
  letter-spacing: 0.6px;
  text-transform: uppercase;
}

.copy-btn {
  font: inherit;
  font-size: 11px;
  font-weight: 600;
  padding: 4px 12px;
  border-radius: 7px;
  border: 1px solid rgba(99, 102, 241, 0.22);
  background: rgba(99, 102, 241, 0.06);
  color: #4f46e5;
  cursor: pointer;
  transition: all 0.15s;
}

.copy-btn:hover {
  background: rgba(99, 102, 241, 0.14);
  border-color: rgba(99, 102, 241, 0.35);
}

.copy-btn.ok {
  background: rgba(16, 185, 129, 0.12);
  color: #059669;
  border-color: rgba(16, 185, 129, 0.3);
}

.copy-btn.err {
  background: rgba(239, 68, 68, 0.12);
  color: #dc2626;
  border-color: rgba(239, 68, 68, 0.3);
}

.json-frame {
  flex: 1;
  min-height: 0;
  display: flex;
}

@media (prefers-color-scheme: dark) {
  .todo-detail {
    background: rgba(11, 16, 32, 0.5);
    border-left-color: rgba(99, 102, 241, 0.22);
  }
  .todo-detail-title h3 { color: #f3f4f6; }
  .detail-empty-title { color: #cbd5e1; }
}
</style>
