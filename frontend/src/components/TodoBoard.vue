<script setup lang="ts">
import type { Todo } from '../types'
import TodoCard from './TodoCard.vue'

defineProps<{
  todos: Todo[]
  selectedId: string | null
}>()

defineEmits<{
  select: [id: string]
}>()
</script>

<template>
  <aside class="todos">
    <header class="todos-header">
      <div class="todos-title-block">
        <h2>Todo Board</h2>
        <span class="todos-subtitle">实时执行进度</span>
      </div>
      <span class="todos-count" :class="{ active: todos.length > 0 }">{{ todos.length }}</span>
    </header>

    <div v-if="todos.length === 0" class="todos-empty">
      <div class="todos-empty-icon">
        <svg width="36" height="36" viewBox="0 0 24 24" fill="none" stroke="currentColor"
             stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round">
          <rect x="3" y="4" width="18" height="18" rx="2.5"/>
          <line x1="3" y1="10" x2="21" y2="10"/>
          <path d="M8 14h.01M8 18h.01M12 14h5M12 18h5"/>
        </svg>
      </div>
      <div class="todos-empty-text">暂无待办任务</div>
      <div class="todos-empty-hint">提交需求后这里会展示执行计划</div>
    </div>

    <ul v-else class="todos-list">
      <TodoCard
          v-for="t in todos"
          :key="t.id"
          :todo="t"
          :active="selectedId === t.id"
          @click="$emit('select', t.id)"
      />
    </ul>
  </aside>
</template>

<style scoped>
.todos {
  display: flex;
  flex-direction: column;
  min-height: 0;
  height: 100vh;
  border-left: 1px solid rgba(99, 102, 241, 0.1);
  padding: 18px 16px 22px;
  overflow: hidden;
  background: rgba(255, 255, 255, 0.35);
  backdrop-filter: saturate(180%) blur(10px);
  -webkit-backdrop-filter: saturate(180%) blur(10px);
  box-sizing: border-box;
}

.todos-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 4px 4px 14px;
  margin-bottom: 12px;
  border-bottom: 1px solid rgba(99, 102, 241, 0.12);
}

.todos-title-block h2 {
  margin: 0;
  font-size: 15px;
  font-weight: 700;
  color: #111827;
  letter-spacing: -0.2px;
  line-height: 1.2;
}

.todos-subtitle {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 11px;
  color: #9ca3af;
  margin-top: 4px;
  letter-spacing: 0.3px;
}

.todos-subtitle::before {
  content: "";
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: #a5b4fc;
}

.todos-count {
  min-width: 28px;
  height: 26px;
  padding: 0 10px;
  border-radius: 999px;
  background: rgba(99, 102, 241, 0.1);
  color: #6b7280;
  font-size: 12px;
  font-weight: 700;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  transition: all 0.25s ease;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
}

.todos-count.active {
  background: linear-gradient(135deg, #6366f1, #8b5cf6 60%, #ec4899);
  color: #fff;
  box-shadow: 0 4px 12px rgba(139, 92, 246, 0.4);
}

.todos-empty {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  text-align: center;
  color: #9ca3af;
  padding: 24px 16px;
}

.todos-empty-icon {
  color: #c7d2fe;
  margin-bottom: 14px;
  filter: drop-shadow(0 4px 10px rgba(139, 92, 246, 0.18));
}

.todos-empty-text {
  font-size: 14px;
  color: #6b7280;
  font-weight: 600;
  margin-bottom: 4px;
}

.todos-empty-hint {
  font-size: 12px;
  color: #9ca3af;
  line-height: 1.6;
  max-width: 220px;
}

.todos-list {
  list-style: none;
  margin: 0;
  padding: 0 4px 4px 0;
  display: flex;
  flex-direction: column;
  gap: 10px;
  overflow-y: auto;
  flex: 1;
}

.todos-list::-webkit-scrollbar { width: 6px; }
.todos-list::-webkit-scrollbar-thumb { background: rgba(99, 102, 241, 0.22); border-radius: 4px; }
.todos-list::-webkit-scrollbar-thumb:hover { background: rgba(99, 102, 241, 0.4); }

@media (prefers-color-scheme: dark) {
  .todos {
    background: rgba(11, 16, 32, 0.45);
    border-left-color: rgba(99, 102, 241, 0.2);
  }
  .todos-header { border-bottom-color: rgba(99, 102, 241, 0.2); }
  .todos-title-block h2 { color: #e5e7eb; }
  .todos-count { background: rgba(99, 102, 241, 0.2); color: #c7d2fe; }
  .todos-empty-text { color: #cbd5e1; }
}
</style>
