<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import type { Todo } from './types'
import { useScopeAgent } from './composables/useScopeAgent'
import ChatPane from './components/ChatPane.vue'
import TodoBoard from './components/TodoBoard.vue'
import TodoDetailPanel from './components/TodoDetailPanel.vue'
import sampleState from './mocks/sampleState.json'

// 通过 URL `?mock=1` 或 `VITE_MOCK=1` 启用 mock 模式：不连后端、不订阅 SSE，
// 直接灌入 src/mocks/sampleState.json 的样本数据，方便调样式。
const isMock = (() => {
  if (typeof window !== 'undefined' && new URL(window.location.href).searchParams.get('mock') === '1') return true
  return import.meta.env.VITE_MOCK === 'true' || import.meta.env.VITE_MOCK === '1'
})()

const {
  threadId,
  messages,
  todos,
  running,
  streamingId,
  send,
  resumeRun,
  loadMockState,
} = useScopeAgent({ mock: isMock })

if (isMock) {
  loadMockState(sampleState as unknown as { messages: never[]; todos: Todo[] })
}

const selectedTodoId = ref<string | null>(null)
const selectedTodo = computed<Todo | null>(() =>
    selectedTodoId.value
        ? todos.value.find(t => t.id === selectedTodoId.value) ?? null
        : null,
)

function selectTodo(id: string) {
  selectedTodoId.value = selectedTodoId.value === id ? null : id
}

function closeDetail() {
  selectedTodoId.value = null
}

function onKeydown(e: KeyboardEvent) {
  if (e.key === 'Escape' && selectedTodoId.value) {
    selectedTodoId.value = null
  }
}

onMounted(() => window.addEventListener('keydown', onKeydown))
onBeforeUnmount(() => window.removeEventListener('keydown', onKeydown))
</script>

<template>
  <div class="layout">
    <ChatPane
        :thread-id="threadId"
        :messages="messages"
        :running="running"
        :streaming-id="streamingId"
        :is-mock="isMock"
        @send="send"
        @decide="resumeRun"
    />

    <TodoBoard
        :todos="todos"
        :selected-id="selectedTodoId"
        @select="selectTodo"
    />

    <!-- 详情浮层用 position:fixed，不占 grid 列，所以左侧对话框不会被挤窄。
         同时不给 :key，切换 todo 时复用同一个 aside 实例只换内容，避免 leave+enter 抖动 -->
    <TodoDetailPanel
        :todo="selectedTodo"
        @close="closeDetail"
    />
  </div>
</template>

<style scoped>
/* 三栏定宽 + justify-content: center —— 宽屏左右均匀留白；窄屏让 chat 列收缩，再窄就依次砍掉详情、看板。
   chat 用 minmax(0, 1080) 与 ChatPane 内部 .app 的 max-width 对齐，确保列内不再有横向 dead space */
.layout {
  display: grid;
  grid-template-columns: minmax(0, 1080px) 280px 360px;
  gap: 14px;
  height: 100vh;
  justify-content: center;
  padding: 0 12px;
  box-sizing: border-box;
}

@media (max-width: 1440px) {
  .layout {
    grid-template-columns: minmax(0, 1fr) 260px 320px;
    gap: 12px;
  }
}

@media (max-width: 1200px) {
  .layout {
    grid-template-columns: minmax(0, 1fr) 240px 280px;
    padding: 0 8px;
  }
}

@media (max-width: 1024px) {
  .layout {
    grid-template-columns: minmax(0, 1fr) 320px;
    padding: 0;
  }
  .layout :deep(.todo-detail) {
    display: none;
  }
}

@media (max-width: 768px) {
  .layout {
    grid-template-columns: 1fr;
  }
  .layout :deep(.todos),
  .layout :deep(.todo-detail) {
    display: none;
  }
}
</style>

<style>
body {
  margin: 0;
  min-height: 100vh;
  color: #111827;
  background: #f5f7ff;
  background-image: radial-gradient(at 12% 8%, rgba(99, 102, 241, 0.18) 0px, transparent 50%),
  radial-gradient(at 88% 12%, rgba(236, 72, 153, 0.12) 0px, transparent 50%),
  radial-gradient(at 50% 92%, rgba(139, 92, 246, 0.16) 0px, transparent 50%);
  background-attachment: fixed;
}

@media (prefers-color-scheme: dark) {
  body {
    background: #0b1020;
    background-image: radial-gradient(at 12% 8%, rgba(99, 102, 241, 0.25) 0px, transparent 50%),
    radial-gradient(at 88% 12%, rgba(236, 72, 153, 0.18) 0px, transparent 50%),
    radial-gradient(at 50% 92%, rgba(139, 92, 246, 0.22) 0px, transparent 50%);
    color: #e5e7eb;
  }
}
</style>
